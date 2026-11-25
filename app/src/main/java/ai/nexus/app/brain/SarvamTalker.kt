package ai.nexus.app.brain

import android.content.Context
import ai.nexus.app.hearing.SpeechKeys
import ai.nexus.app.voice.Persona
import ai.nexus.core.agent.Talker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Conversation through Sarvam's chat API (sarvam-105b-conversations: built for voice assistants, low latency,
 * no reasoning delay). Streams the reply and hands over each sentence as soon as it is complete.
 */
class SarvamTalker(
    private val context: Context,
    private val addressAs: () -> String,
    private val userName: () -> String?,
) : Talker {
    companion object {
        const val URL_CHAT = "https://api.sarvam.ai/v1/chat/completions"
        const val MODEL = "sarvam-105b-conversations"

        fun takeSentences(buf: String) = ai.nexus.core.voice.SpeechText.takeSentences(buf)
        fun speakable(s: String) = ai.nexus.core.voice.SpeechText.speakable(s)
    }

    override suspend fun talk(history: List<Pair<String, String>>, userText: String, context: String, onSentence: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val key = SpeechKeys.get(this@SarvamTalker.context, SpeechKeys.SARVAM) ?: error("no Sarvam key (Settings → Listening)")
        val style = Persona.style(this@SarvamTalker.context)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", Persona.systemPrompt(style, addressAs(), userName()) + "\n\nWhat you know right now:\n" + context.take(3000)))
        for ((role, text) in history) {
            if (text.isBlank()) continue
            messages.put(JSONObject().put("role", if (role == "assistant") "assistant" else "user").put("content", text.take(1500)))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))
        val body = JSONObject()
            .put("model", MODEL).put("messages", messages).put("stream", true)
            .put("max_tokens", 400).put("temperature", if (style.key == "cute") 0.8 else 0.6)

        val conn = (URL(URL_CHAT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 6000; readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("api-subscription-key", key)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("Sarvam ${conn.responseCode}: ${err.take(160)}")
            }
            val full = StringBuilder()
            var pending = ""
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    ensureActive()
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = runCatching {
                        JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content", "")
                    }.getOrNull().orEmpty()
                    if (delta.isEmpty()) continue
                    full.append(delta)
                    val (done, rest) = takeSentences(pending + delta)
                    pending = rest
                    done.map(::speakable).filter { it.isNotBlank() }.forEach(onSentence)
                }
            }
            speakable(pending).takeIf { it.isNotBlank() }?.let(onSentence)
            speakable(full.toString())
        } finally { conn.disconnect() }
    }
}
