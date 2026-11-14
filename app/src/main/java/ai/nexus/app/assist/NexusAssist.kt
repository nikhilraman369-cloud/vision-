package ai.nexus.app.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import ai.nexus.app.NexusApp

/**
 * Lets NEXUS be chosen as the phone's "Digital assistant app". Then whatever your phone maps to
 * "assistant" (usually a long-press of the power button or home gesture) starts NEXUS listening.
 * A normal power-button press (screen on/off) is not affected.
 */
class NexusInteractionService : VoiceInteractionService()

class NexusSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NexusSession(this)
}

/** No screen of its own: it just starts NEXUS listening (the "Listening…" pill shows on top of whatever you're doing). */
class NexusSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        runCatching { NexusApp.get(context).quickListen() }
        hide()
    }
}

/**
 * Android requires an assistant to name a speech-recognition service. NEXUS listens through the
 * phone's real recogniser (see VoiceEngine), so this one just declines politely.
 */
class NexusRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) { runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) } }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
