package ai.nexus.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import ai.nexus.app.NexusApp
import ai.nexus.app.ui.MainActivity
import ai.nexus.core.agent.AgentState
import ai.nexus.core.voice.HandsFreeState
import ai.nexus.core.voice.HandsFreeStateMachine
import ai.nexus.core.voice.PhoneticWakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Always-on, fully offline "Hey Nexus". Streams the microphone into the free
 * Vosk recogniser, checks every partial result for a fuzzy wake word, then
 * keeps a 6.5 s command window open after each command. Runs as a microphone
 * foreground service so it keeps working over other apps.
 */
class NexusVoiceService : Service(), HandsFreeStateMachine.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: NexusApp
    private lateinit var haptics: HapticEngine
    private lateinit var machine: HandsFreeStateMachine
    @Volatile private var loop: Thread? = null
    @Volatile private var running = false
    @Volatile private var screenOn = true
    private var wakeLock: PowerManager.WakeLock? = null
    private var routedToEarbuds = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = NexusApp.get(this)
        haptics = HapticEngine(this)
        if (!startForegroundCompat()) { stopSelf(); return }
        val engine = PhoneticWakeWordEngine(app.memory.settings.wakeWord)
        machine = HandsFreeStateMachine(engine, listener = this)
        instance = this
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        registerReceiver(screenReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) })
        // Never listen to ourselves: ignore audio while NEXUS is speaking.
        scope.launch { app.voice.state.collect { v -> if (v == VoiceState.SPEAKING) machine.pause() else machine.resume() } }
        // The 6.5 s window restarts when a command has finished, not when it was heard.
        scope.launch { app.nexus.state.collect { s -> if (s == AgentState.IDLE || s == AgentState.WAITING_FOR_USER) machine.extend() } }
        app.handsFreeActivate = { machine.activate() }
        updateWakeLock()
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        running = false
        app.handsFreeActivate = null
        _state.value = HandsFreeState.SLEEP_WAKEWORD
        _on.value = false
        runCatching { unregisterReceiver(screenReceiver) }
        wakeLock?.let { if (it.isHeld) it.release() }
        unrouteEarbuds()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Nexus (hands-free)", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, NexusVoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Say \"Hey Nexus\"")
            .setContentText("Listening offline on your phone. Nothing leaves your device.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .setOngoing(true).build()
        return runCatching {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIF_ID, n)
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------ audio loop

    @SuppressLint("MissingPermission")
    private fun startLoop() {
        if (running) return
        running = true
        _on.value = true
        loop = Thread({
            var rec: Recognizer? = null
            var audio: AudioRecord? = null
            try {
                val path = app.voskModels.modelPath(app.memory.settings.speechModel)
                    ?: throw IllegalStateException("Download the offline speech model in Settings first.")
                val model = loadModel(path)
                rec = Recognizer(model, SAMPLE_RATE.toFloat())
                val useEarbuds = app.memory.settings.useEarbudMic && routeEarbuds()
                val source = if (useEarbuds) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
                val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                audio = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
                audio.startRecording()
                val buf = ShortArray(CHUNK) // 100 ms of audio per read: fast partial results
                var lastPartial = ""
                while (running) {
                    if (!screenOn && !app.memory.settings.listenScreenOff) { Thread.sleep(300); continue }
                    val n = audio.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (machine.state == HandsFreeState.PAUSED) { machine.tick(); continue }
                    if (rec.acceptWaveForm(buf, n)) {
                        val text = JSONObject(rec.result).optString("text").trim()
                        lastPartial = ""
                        if (text.isNotEmpty()) main.post { machine.onFinal(text) }
                    } else {
                        val partial = JSONObject(rec.partialResult).optString("partial").trim()
                        if (partial.isNotEmpty() && partial != lastPartial) { lastPartial = partial; main.post { machine.onPartial(partial) } }
                    }
                    main.post { machine.tick() }
                }
            } catch (e: Exception) {
                main.post { app.notifyHandsFreeError(e.message ?: "Hands-free listening stopped.") ; stopSelf() }
            } finally {
                runCatching { audio?.stop(); audio?.release() }
                runCatching { rec?.close() }
            }
        }, "nexus-voice").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    // ------------------------------------------------------------ state machine callbacks

    override fun onWake() { haptics.tick(); _state.value = HandsFreeState.ACTIVE_COMMAND_WINDOW }

    override fun onSleep() { haptics.tick(); _state.value = HandsFreeState.SLEEP_WAKEWORD }

    override fun onCommand(text: String) {
        ScreenWakeController.prepareFor(this, app, text) { app.ask(text) }
    }

    // ------------------------------------------------------------ earbuds, screen, battery

    /** Route the microphone to connected Bluetooth earbuds (works in your pocket). */
    private fun routeEarbuds(): Boolean {
        val am = getSystemService(AudioManager::class.java) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                    ?: return false
                routedToEarbuds = am.setCommunicationDevice(dev)
            } else {
                if (!am.isBluetoothScoAvailableOffCall) return false
                @Suppress("DEPRECATION") am.startBluetoothSco()
                @Suppress("DEPRECATION") am.isBluetoothScoOn = true
                routedToEarbuds = true
            }
            routedToEarbuds
        }.getOrDefault(false)
    }

    private fun unrouteEarbuds() {
        if (!routedToEarbuds) return
        val am = getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else { @Suppress("DEPRECATION") am.stopBluetoothSco(); @Suppress("DEPRECATION") am.isBluetoothScoOn = false }
        }
        routedToEarbuds = false
    }

    private fun updateWakeLock() {
        val want = app.memory.settings.listenScreenOff
        if (want && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nexus:handsfree")?.apply { setReferenceCounted(false); acquire() }
        } else if (!want) { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            screenOn = i?.action == Intent.ACTION_SCREEN_ON
            if (!screenOn) machine.sleepNow()
        }
    }

    companion object {
        private const val CHANNEL = "nexus_handsfree"
        private const val NOTIF_ID = 11
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1600
        const val ACTION_STOP = "ai.nexus.HANDSFREE_STOP"
        @Volatile var instance: NexusVoiceService? = null
        @Volatile private var cachedModel: Pair<String, Model>? = null

        private val _state = MutableStateFlow(HandsFreeState.SLEEP_WAKEWORD)
        val state: StateFlow<HandsFreeState> = _state
        private val _on = MutableStateFlow(false)
        val on: StateFlow<Boolean> = _on

        /** Loading a model takes a second or two; keep it for the life of the process. */
        @Synchronized fun loadModel(path: String): Model {
            cachedModel?.let { (p, m) -> if (p == path) return m }
            val m = Model(path)
            cachedModel = path to m
            return m
        }

        fun start(c: Context) { runCatching { c.startForegroundService(Intent(c, NexusVoiceService::class.java)) } }
        fun stop(c: Context) = c.stopService(Intent(c, NexusVoiceService::class.java))
        fun refreshSettings() { instance?.updateWakeLock() }
    }
}
