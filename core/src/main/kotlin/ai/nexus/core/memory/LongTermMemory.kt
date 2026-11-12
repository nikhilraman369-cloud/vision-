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
