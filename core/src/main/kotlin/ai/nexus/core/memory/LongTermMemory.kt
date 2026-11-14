package ai.nexus.core.memory

import ai.nexus.core.nlu.Fuzzy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

enum class MemoryKind {
    FACT, PREFERENCE, CONTACT_ALIAS, APP_ALIAS, SHORTCUT, CORRECTION,
    /** A phrase the phone brain worked out once, cached as action lines so it's instant next time. */
    LEARNED,
    /** Short summary of a past conversation, for continuity. */
    EPISODE,
    /** A mishearing the user corrected: key = heard, value = meant. */
    MISHEARING,
    /** A task taught with "watch me", stored as action lines. */
    ROUTINE,
    NOTE,
}

@Serializable
data class MemoryItem(
    val id: String = UUID.randomUUID().toString(),
    val kind: MemoryKind,
    /** e.g. "mom" for an alias, "language" for a preference; null for free facts. */
    val key: String? = null,
    val value: String,
    val createdMs: Long = System.currentTimeMillis(),
    val updatedMs: Long = createdMs,
    val uses: Int = 0,
    /** 1 (trivia) … 5 (core fact). */
    val importance: Int = 3,
    val pinned: Boolean = false,
    val confidence: Double = 1.0,
    /** "told" | "learned" | "notification" */
    val source: String = "told",
    val lastUsedMs: Long = 0,
)

@Serializable
data class UserSettings(
    val name: String? = null,
    val language: String = "auto",          // auto | en | hi | hinglish
    val responseStyle: String = "friendly", // friendly | brief | detailed
    val voice: String? = null,
    val speechRate: Float = 1.0f,
    val wakeWordEnabled: Boolean = false,
    val wakeWord: String = "hey nexus",
    val continuousListening: Boolean = false,
    val speakResponses: Boolean = true,
    val confirmBeforeSending: Boolean = false,
    val useCloudAi: Boolean = true,
    /** Use the free on-device model for commands the rules don't understand. */
    val usePhoneBrain: Boolean = true,
    /** Short tick/vibration instead of speaking "Done" after every action. */
    val quietAcks: Boolean = true,
    /** After a background task (sending, sharing), jump back to the app you were in. */
    val returnAfterTasks: Boolean = true,
    /** Always-on "Hey Nexus" with the free offline speech engine. */
    val handsFree: Boolean = false,
    /** Keep listening for "Hey Nexus" while the screen is off (uses more battery). */
    val listenScreenOff: Boolean = false,
    /** Use Bluetooth earbuds' microphone when connected. */
    val useEarbudMic: Boolean = true,
    /** Offline speech model: "en-in" (Indian English) or "hi" (Hindi). */
    val speechModel: String = "en-in",
    /** How NEXUS addresses you. */
    val addressAs: String = "Boss",
    /** Speak a short "On it, Boss." / "Done." instead of only vibrating. */
    val spokenAcks: Boolean = true,
    /** When a step can't be done, stop and ask to be taught. */
    val askWhenStuck: Boolean = true,
    /** "ask" | "always" | "off": saving facts you mention in passing. */
    val autoCapture: String = "ask",
    /** Seconds the conversation stays open after a reply. */
    val followUpSeconds: Int = 6,
    /** Unused since 2.5 (the volume-button shortcut was removed). */
    val volumeKeys: Boolean = true,
    val morningBrief: String = "07:30",
    val eveningReview: String = "21:30",
    val briefsEnabled: Boolean = true,
    val quietStart: String = "23:00",
    val quietEnd: String = "07:00",
    /** Voice output: "neural" (downloaded voice pack) or "system". */
    val voiceEngine: String = "neural",
    val voicePack: String = "piper-en_GB-alan-medium",
    val voicePitch: Float = 0.95f,
    val voiceEffects: Boolean = true,
    val earcons: Boolean = true,
    val wifiOnlyDownloads: Boolean = true,
    /** LLM planner: "local" (phone brain) | "claude" (API key) | "off". */
    val llmProvider: String = "local",
    val bubbleSide: String = "right",
    val bubbleY: Float = 0.35f,
    val bubbleOpacity: Float = 0.85f,
    val bubbleSize: Int = 52,
    val bubbleHideFullscreen: Boolean = true,
    val notificationWatching: Boolean = true,
    val voiceMuted: Boolean = false,
    /** Use the online speech engine (more accurate, especially Hinglish and names). */
    val accurateListening: Boolean = true,
    val sendScreenshotsToCloud: Boolean = false,
)

@Serializable
data class MemoryData(
    val enabled: Boolean = true,
    val items: List<MemoryItem> = emptyList(),
    val settings: UserSettings = UserSettings(),
    /** package → launch count, for "commonly used applications". */
    val appUsage: Map<String, Int> = emptyMap(),
    /** recent conversation turns kept across sessions (only when memory is enabled). */
    val recentTurns: List<Turn> = emptyList(),
    /** "package|intent" → view id that worked last time (learned UI selectors). */
    val selectors: Map<String, String> = emptyMap(),
    /** package → typical time (ms) for the app to open, learned from use. */
    val loadMs: Map<String, Int> = emptyMap(),
)

@Serializable
data class Turn(val role: String, val text: String, val atMs: Long = System.currentTimeMillis())

interface MemoryStorage {
    fun read(): String?
    fun write(data: String)
}

class FileMemoryStorage(private val file: File) : MemoryStorage {
    override fun read(): String? = if (file.exists()) file.readText() else null
    override fun write(data: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(data)
        if (!tmp.renameTo(file)) { file.writeText(data); tmp.delete() }
    }
}

class InMemoryStorage(var content: String? = null) : MemoryStorage {
    override fun read() = content
    override fun write(data: String) { content = data }
}

/**
 * Durable, user-controlled memory: preferences, aliases ("mom" → "Sunita
 * Sharma"), shortcuts, facts and corrections. Everything is inspectable,
 * editable and erasable; when disabled nothing new is stored.
 *
 * Sensitive content (message bodies, OTPs, passwords) is never stored here.
 */
class LongTermMemory(private val storage: MemoryStorage) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val lock = Any()

    @Volatile
    var data: MemoryData = load()
        private set

    private fun load(): MemoryData = try {
        storage.read()?.let { json.decodeFromString(MemoryData.serializer(), it) } ?: MemoryData()
    } catch (e: Exception) {
        MemoryData()
    }

    private fun save() = storage.write(json.encodeToString(MemoryData.serializer(), data))

    private fun mutate(block: (MemoryData) -> MemoryData) = synchronized(lock) { data = block(data); save() }

    val enabled: Boolean get() = data.enabled
    val settings: UserSettings get() = data.settings
    val items: List<MemoryItem> get() = data.items

    fun setEnabled(on: Boolean) = mutate { it.copy(enabled = on) }
    fun updateSettings(f: (UserSettings) -> UserSettings) = mutate { it.copy(settings = f(it.settings)) }

    fun remember(kind: MemoryKind, key: String?, value: String): MemoryItem? {
        if (!enabled || looksSensitive(value)) return null
        var result: MemoryItem? = null
        mutate { d ->
            val normKey = key?.let { Fuzzy.normalize(it) }
            val existing = d.items.firstOrNull { it.kind == kind && normKey != null && it.key?.let(Fuzzy::normalize) == normKey }
            val list = if (existing != null) {
                val upd = existing.copy(value = value, updatedMs = System.currentTimeMillis())
                result = upd
                d.items.map { if (it.id == existing.id) upd else it }
            } else {
                val item = MemoryItem(kind = kind, key = key, value = value)
                result = item
                d.items + item
            }
            d.copy(items = list)
        }
        return result
    }

    fun update(id: String, value: String) = mutate { d ->
        d.copy(items = d.items.map { if (it.id == id) it.copy(value = value, updatedMs = System.currentTimeMillis()) else it })
    }

    fun forget(id: String) = mutate { d -> d.copy(items = d.items.filterNot { it.id == id }) }

    fun setPinned(id: String, pinned: Boolean) = mutate { d -> d.copy(items = d.items.map { if (it.id == id) it.copy(pinned = pinned) else it }) }

    fun add(item: MemoryItem): MemoryItem? {
        if (!enabled || looksSensitive(item.value)) return null
        mutate { d -> d.copy(items = d.items + item) }
        return item
    }

    /**
     * Memories relevant to [query], best first, within [budgetChars]: word
     * overlap, importance, pinned, and recency. Marks them as used.
     */
    fun relevant(query: String, budgetChars: Int = 1500, now: Long = System.currentTimeMillis()): List<MemoryItem> {
        if (!enabled) return emptyList()
        val q = Fuzzy.normalize(query).split(' ').filter { it.length > 2 }.toSet()
        val scored = data.items.filter { it.kind != MemoryKind.LEARNED && it.kind != MemoryKind.MISHEARING }.map { m ->
            val words = Fuzzy.normalize("${m.key ?: ""} ${m.value}").split(' ').toSet()
            val overlap = q.count { it in words }.toDouble()
            val ageDays = (now - maxOf(m.updatedMs, m.lastUsedMs)) / 86_400_000.0
            m to (overlap * 3 + m.importance + (if (m.pinned) 4 else 0) - minOf(ageDays / 30.0, 3.0))
        }.filter { it.second > 2.5 || it.first.pinned }.sortedByDescending { it.second }
        val out = ArrayList<MemoryItem>(); var used = 0
        for ((m, _) in scored) { val len = (m.key?.length ?: 0) + m.value.length + 4; if (used + len > budgetChars) break; out += m; used += len }
        if (out.isNotEmpty()) mutate { d -> d.copy(items = d.items.map { i -> if (out.any { it.id == i.id }) i.copy(lastUsedMs = now) else i }) }
        lastUsed = out
        return out
    }

    /** What was used in the last answer (shown in the Memory screen). */
    @Volatile var lastUsed: List<MemoryItem> = emptyList()
        private set

    /** Merge exact or near-duplicate memories; pinned ones are always kept. */
    fun mergeDuplicates(): Int {
        val keep = ArrayList<MemoryItem>(); var merged = 0
        for (m in data.items.sortedByDescending { it.pinned }) {
            val dup = keep.firstOrNull { k -> k.kind == m.kind && Fuzzy.normalize("${k.key} ${k.value}").let { a -> Fuzzy.similarity(a, Fuzzy.normalize("${m.key} ${m.value}")) > 0.92 } }
            if (dup != null && !m.pinned) merged++ else keep += m
        }
        if (merged > 0) mutate { d -> d.copy(items = keep) }
        return merged
    }

    fun exportJson(): String = json.encodeToString(MemoryData.serializer(), data)
    fun importJson(text: String) = mutate { json.decodeFromString(MemoryData.serializer(), text) }

    /** Forget items matching free text ("forget that I like pizza"). Returns how many were removed. */
    fun forgetMatching(text: String): Int {
        val q = Fuzzy.normalize(text)
        val doomed = data.items.filter { item ->
            val hay = Fuzzy.normalize("${item.key ?: ""} ${item.value}")
            hay.contains(q) || q.contains(hay) || Fuzzy.similarity(q, hay) > 0.7
        }
        if (doomed.isNotEmpty()) mutate { d -> d.copy(items = d.items - doomed.toSet()) }
        return doomed.size
    }

    fun clearAll() = mutate { MemoryData(enabled = it.enabled, settings = it.settings) }

    fun lookup(kind: MemoryKind, key: String): String? {
        val k = Fuzzy.normalize(key)
        return data.items.firstOrNull { it.kind == kind && it.key?.let(Fuzzy::normalize) == k }?.value
    }

    fun contactAlias(name: String): String? = lookup(MemoryKind.CONTACT_ALIAS, name)
    fun appAlias(name: String): String? = lookup(MemoryKind.APP_ALIAS, name)
    fun shortcut(phrase: String): String? = lookup(MemoryKind.SHORTCUT, phrase)
    fun preference(key: String): String? = lookup(MemoryKind.PREFERENCE, key)

    fun recordAppLaunch(pkg: String) {
        if (!enabled) return
        mutate { d -> d.copy(appUsage = d.appUsage + (pkg to ((d.appUsage[pkg] ?: 0) + 1))) }
    }

    fun selector(pkg: String?, intent: String): String? = pkg?.let { data.selectors["$it|${Fuzzy.normalize(intent)}"] }

    fun rememberSelector(pkg: String?, intent: String, viewId: String?) {
        if (pkg == null || viewId.isNullOrBlank() || !enabled) return
        val k = "$pkg|${Fuzzy.normalize(intent)}"
        if (data.selectors[k] == viewId) return
        mutate { d -> d.copy(selectors = (d.selectors + (k to viewId)).entries.toList().takeLast(400).associate { it.key to it.value }) }
    }

    /** Rolling average of how long [pkg] takes to open, used to size wait windows. */
    fun recordLoadTime(pkg: String, ms: Long) {
        if (!enabled) return
        val prev = data.loadMs[pkg]
        val avg = if (prev == null) ms.toInt() else ((prev * 3 + ms) / 4).toInt()
        mutate { d -> d.copy(loadMs = d.loadMs + (pkg to avg)) }
    }

    /** How long to wait for [pkg] before giving up: 2× its usual time, between 4 and 10 s. */
    fun patience(pkg: String?, base: Long = 6000): Long = (pkg?.let { data.loadMs[it] }?.let { it * 2L } ?: base).coerceIn(5000, 15_000)

    fun topApps(n: Int = 8): List<String> = data.appUsage.entries.sortedByDescending { it.value }.take(n).map { it.key }

    fun recordTurn(role: String, text: String, keep: Int = 40) {
        if (!enabled || looksSensitive(text)) return
        mutate { d -> d.copy(recentTurns = (d.recentTurns + Turn(role, text.take(2000))).takeLast(keep)) }
    }

    /** Compact memory summary for the language model's context. */
    fun toPromptText(maxItems: Int = 60): String {
        if (!enabled) return "(memory disabled by the user)"
        val s = settings
        val sb = StringBuilder()
        s.name?.let { sb.append("User's name: ").append(it).append('\n') }
        sb.append("Preferred language: ").append(s.language).append("; response style: ").append(s.responseStyle).append('\n')
        val byKind = data.items.takeLast(maxItems).groupBy { it.kind }
        for ((kind, list) in byKind) {
            sb.append(kind.name.lowercase().replace('_', ' ')).append(":\n")
            for (i in list) sb.append(" - ").append(i.key?.let { "$it → " } ?: "").append(i.value).append('\n')
        }
        return sb.toString()
    }

    companion object {
        private val sensitive = Regex(
            "\\b(?:otp|one[- ]time password|password|passcode|pin|cvv|card number|bank account|aadhaar|pan number|ssn)\\b|\\b\\d{12,19}\\b",
            RegexOption.IGNORE_CASE,
        )
        fun looksSensitive(s: String) = sensitive.containsMatchIn(s)
    }
}
