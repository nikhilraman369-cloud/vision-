package ai.nexus.core.llm

import ai.nexus.core.agent.ActionFeedback
import ai.nexus.core.agent.AgentAction
import ai.nexus.core.agent.AgentSession
import ai.nexus.core.agent.Brain
import ai.nexus.core.device.Direction
import ai.nexus.core.memory.Turn
import ai.nexus.core.screen.ScreenSnapshot
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.WebSearchTool20260209
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * Claude-powered reasoning for NEXUS: open conversation (with web search for
 * live facts like weather) and a phone-operating agent that sees the screen
 * as a numbered element list (plus a screenshot when the user allows it)
 * and acts through tools, one verified step at a time.
 */
class ClaudeBrain(
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val enableWebSearch: Boolean = true,
    /** Server-side refusal fallbacks (beta). Turned off automatically if the API rejects it. */
    @Volatile private var useServerFallbacks: Boolean = true,
) : Brain {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    private fun cachedSystem(text: String) = listOf(
        TextBlockParam.builder().text(text).cacheControl(CacheControlEphemeral.builder().build()).build(),
    )

    private fun MessageCreateParams.Builder.withFallbacks(): MessageCreateParams.Builder = apply {
        if (useServerFallbacks) {
            putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
            putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
    }

    /** Sends the request; retries once without fallbacks if the API rejects that parameter. */
    private suspend fun send(build: () -> MessageCreateParams.Builder): Message = withContext(Dispatchers.IO) {
        try {
            client.messages().create(build().withFallbacks().build())
        } catch (e: BadRequestException) {
            if (useServerFallbacks && (e.message ?: "").contains("fallback", ignoreCase = true)) {
                useServerFallbacks = false
                client.messages().create(build().build())
            } else throw e
        }
    }

    private fun Message.isRefusal() = stopReason().map { it.toString() == "refusal" }.orElse(false)

    private fun Message.text(): String = content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("").trim()

    // ================================================================ chat

    override suspend fun chat(history: List<Turn>, userText: String, context: String): String {
        val messages = ArrayList<MessageParam>()
        // History must alternate and start with the user.
        var expectUser = true
        for (t in history.takeLast(20)) {
            val isUser = t.role == "user"
            if (isUser != expectUser) continue
            messages += if (isUser) MessageParam.ofUser(t.text) else MessageParam.ofAssistant(t.text)
            expectUser = !expectUser
        }
        if (!expectUser) messages.removeAt(messages.lastIndex) // keep alternation: drop a dangling user turn
        messages += MessageParam.ofUser("<context>\n$context\n</context>\n\n$userText")

        var reply = ""
        repeat(3) { // pause_turn: server tools (web search) may pause a long turn
            val params = {
                MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(16000L)
                    .systemOfTextBlockParams(cachedSystem(CHAT_SYSTEM))
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                    .messages(messages)
                    .apply { if (enableWebSearch) addTool(WebSearchTool20260209.builder().maxUses(3L).build()) }
            }
            val resp = send(params)
            if (resp.isRefusal()) return "Sorry, I can't help with that one."
            reply += resp.text()
            if (resp.stopReason().orElse(null) != StopReason.PAUSE_TURN) return reply.ifBlank { "Hmm, I don't have an answer for that." }
            messages += resp.toParam()
        }
        return reply.ifBlank { "Sorry, that took too long. Try asking again." }
    }

    /** Plain completion (no tools) for planners/extractors that want strict JSON or a short text. */
    suspend fun complete(system: String, user: String, maxTokens: Long = 2000): String? {
        val resp = send {
            MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .systemOfTextBlockParams(cachedSystem(system))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage(user)
        }
        if (resp.isRefusal()) return null
        return resp.text().ifBlank { null }
    }

    // ================================================================ screen Q&A

    override suspend fun describeScreen(question: String, screen: ScreenSnapshot, screenshot: ByteArray?, context: String): String {
        val blocks = ArrayList<ContentBlockParam>()
        screenshot?.let { blocks += ContentBlockParam.ofImage(image(it)) }
        blocks += ContentBlockParam.ofText(
            TextBlockParam.builder().text("<context>\n$context\n</context>\n<screen>\n${screen.toPromptText()}</screen>\n\n$question\nAnswer in 1–3 short spoken sentences.").build(),
        )
        val resp = send {
            MessageCreateParams.builder()
                .model(model)
                .maxTokens(4000L)
                .systemOfTextBlockParams(cachedSystem(CHAT_SYSTEM))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessageOfContentBlockParams(blocks)
        }
        if (resp.isRefusal()) return "I can't describe that."
        return resp.text()
    }

    private fun image(bytes: ByteArray): ImageBlockParam {
        val png = bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        return ImageBlockParam.builder().source(
            Base64ImageSource.builder()
                .data(Base64.getEncoder().encodeToString(bytes))
                .mediaType(if (png) Base64ImageSource.MediaType.IMAGE_PNG else Base64ImageSource.MediaType.IMAGE_JPEG)
                .build(),
        ).build()
    }

    // ================================================================ agent

    override fun startAgent(goal: String, context: String, firstScreen: ScreenSnapshot?, screenshot: ByteArray?): AgentSession =
        Session(goal, context, firstScreen, screenshot)

    private inner class Session(
        private val goal: String,
        private val context: String,
        private val firstScreen: ScreenSnapshot?,
        private val firstShot: ByteArray?,
    ) : AgentSession {
        private val messages = ArrayList<MessageParam>()
        private var pendingToolId: String? = null

        override suspend fun next(feedback: ActionFeedback?): AgentAction {
            if (messages.isEmpty()) {
                val blocks = ArrayList<ContentBlockParam>()
                firstShot?.let { blocks += ContentBlockParam.ofImage(image(it)) }
                blocks += ContentBlockParam.ofText(
                    TextBlockParam.builder().text(
                        "<context>\n$context\n</context>\n<screen>\n${firstScreen?.toPromptText() ?: "(screen not readable)"}</screen>\n\nUser's goal: $goal",
                    ).build(),
                )
                messages += MessageParam.ofUserOfContentBlockParams(blocks)
            } else {
                val id = pendingToolId
                val fb = feedback
                val text = buildString {
                    append(fb?.description ?: "ok")
                    append(if (fb?.screenChanged == true) "\nThe screen changed." else "\nThe screen did NOT change.")
                    append("\n<screen>\n").append(fb?.screen?.toPromptText() ?: "(screen not readable)").append("</screen>")
                }
                if (id != null) {
                    val contentBlocks = ArrayList<ToolResultBlockParam.Content.Block>()
                    contentBlocks += ToolResultBlockParam.Content.Block.ofText(text)
                    fb?.screenshot?.let { contentBlocks += ToolResultBlockParam.Content.Block.ofImage(image(it)) }
                    messages += MessageParam.ofUserOfContentBlockParams(
                        listOf(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(id).contentOfBlocks(contentBlocks).build())),
                    )
                } else {
                    messages += MessageParam.ofUser(text)
                }
            }

            val resp = send {
                MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(16000L)
                    .systemOfTextBlockParams(cachedSystem(AGENT_SYSTEM))
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                    .tools(AGENT_TOOLS.map { com.anthropic.models.messages.ToolUnion.ofTool(it) })
                    .toolChoice(ToolChoiceAuto.builder().disableParallelToolUse(true).build())
                    .messages(messages)
            }
            // Keep the assistant turn verbatim (including thinking blocks) for the next request.
            messages += resp.toParam()
            if (resp.isRefusal()) { pendingToolId = null; return AgentAction.Fail("I can't help with that task.") }

            val toolUse = resp.content().firstNotNullOfOrNull { it.toolUse().orElse(null) }
            if (toolUse == null) {
                pendingToolId = null
                val t = resp.text()
                return if (t.isBlank()) AgentAction.Fail("The AI didn't choose an action.") else AgentAction.Done(t)
            }
            pendingToolId = toolUse.id()
            @Suppress("UNCHECKED_CAST")
            val input = runCatching { toolUse._input().convert(Map::class.java) as Map<String, Any?> }.getOrDefault(emptyMap())
            return parseAction(toolUse.name(), input)
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        private fun Map<String, Any?>.int(k: String): Int? = (this[k] as? Number)?.toInt() ?: (this[k] as? String)?.toIntOrNull()
        private fun Map<String, Any?>.str(k: String): String? = this[k]?.toString()?.takeIf { it.isNotBlank() }
        private fun dir(s: String?): Direction = when (s?.lowercase()) { "up" -> Direction.UP; "left" -> Direction.LEFT; "right" -> Direction.RIGHT; else -> Direction.DOWN }

        /** Validates tool input; malformed input becomes a Fail the loop can report. */
        fun parseAction(name: String, input: Map<String, Any?>): AgentAction = when (name) {
            "tap" -> input.int("element_id")?.let { AgentAction.Tap(it) } ?: AgentAction.Fail("tap without element_id")
            "tap_at" -> { val x = input.int("x"); val y = input.int("y"); if (x != null && y != null) AgentAction.TapAt(x, y) else AgentAction.Fail("tap_at needs x and y") }
            "long_press" -> input.int("element_id")?.let { AgentAction.LongPress(it) } ?: AgentAction.Fail("long_press without element_id")
            "type_text" -> input.str("text")?.let { AgentAction.Type(input.int("element_id"), it, input["submit"] == true) } ?: AgentAction.Fail("type_text without text")
            "scroll" -> AgentAction.Scroll(input.int("element_id"), dir(input.str("direction")))
            "swipe" -> AgentAction.Swipe(dir(input.str("direction")))
            "press_back" -> AgentAction.Back
            "press_home" -> AgentAction.Home
            "open_app" -> input.str("name")?.let { AgentAction.OpenApp(it) } ?: AgentAction.Fail("open_app without name")
            "open_url" -> input.str("url")?.let { AgentAction.OpenUrl(it) } ?: AgentAction.Fail("open_url without url")
            "wait" -> AgentAction.Wait((input.int("ms") ?: 800).toLong())
            "say" -> AgentAction.Say(input.str("text") ?: "")
            "ask_user" -> AgentAction.AskUser(input.str("question") ?: "Could you clarify?")
            "done" -> AgentAction.Done(input.str("summary") ?: "")
            "fail" -> AgentAction.Fail(input.str("reason") ?: "I couldn't finish that.")
            else -> AgentAction.Fail("Unknown tool $name")
        }

        private fun prop(type: String, desc: String, enum: List<String>? = null): JsonValue =
            JsonValue.from(buildMap { put("type", type); put("description", desc); enum?.let { put("enum", it) } })

        private fun tool(name: String, desc: String, props: Map<String, JsonValue> = emptyMap(), required: List<String> = emptyList()): Tool =
            Tool.builder().name(name).description(desc).inputSchema(
                Tool.InputSchema.builder()
                    .properties(Tool.InputSchema.Properties.builder().apply { props.forEach { (k, v) -> putAdditionalProperty(k, v) } }.build())
                    .required(required)
                    .build(),
            ).build()

        private val DIRECTIONS = listOf("up", "down", "left", "right")

        val AGENT_TOOLS: List<Tool> = listOf(
            tool("tap", "Tap a screen element by its [id] from the screen list.", mapOf("element_id" to prop("integer", "Element id shown in [brackets].")), listOf("element_id")),
            tool("tap_at", "Tap screen coordinates (use only when the element is not in the list, e.g. seen in the screenshot).", mapOf("x" to prop("integer", "x pixel"), "y" to prop("integer", "y pixel")), listOf("x", "y")),
            tool("long_press", "Long-press an element.", mapOf("element_id" to prop("integer", "Element id.")), listOf("element_id")),
            tool(
                "type_text", "Type text into a field (replaces its content). Set submit=true to press the keyboard's enter/search key afterwards.",
                mapOf("text" to prop("string", "Text to type."), "element_id" to prop("integer", "Field id; omit for the focused field."), "submit" to prop("boolean", "Press enter/search after typing.")),
                listOf("text"),
            ),
            tool("scroll", "Scroll a list to reveal more content. direction=down reveals content below.", mapOf("direction" to prop("string", "Content direction.", DIRECTIONS), "element_id" to prop("integer", "Scrollable container id; omit for the main list.")), listOf("direction")),
            tool("swipe", "Swipe the whole screen with a finger. direction=up moves to the next Reel/Short.", mapOf("direction" to prop("string", "Finger direction.", DIRECTIONS)), listOf("direction")),
            tool("press_back", "Press Android Back."),
            tool("press_home", "Go to the home screen."),
            tool("open_app", "Launch an installed app by name.", mapOf("name" to prop("string", "App name, e.g. YouTube.")), listOf("name")),
            tool("open_url", "Open a URL or deep link.", mapOf("url" to prop("string", "https:// URL or app link.")), listOf("url")),
            tool("wait", "Wait for something to load.", mapOf("ms" to prop("integer", "Milliseconds, 200-4000.")), listOf("ms")),
            tool("say", "Tell the user something now, e.g. information they asked you to find, while continuing the task.", mapOf("text" to prop("string", "Short spoken sentence(s).")), listOf("text")),
            tool("ask_user", "Ask the user a question and stop until they answer (ambiguity, or before anything irreversible they didn't ask for).", mapOf("question" to prop("string", "The question.")), listOf("question")),
            tool("done", "The goal is complete and verified on screen.", mapOf("summary" to prop("string", "One short spoken sentence for the user, including any answer they asked for.")), listOf("summary")),
            tool("fail", "The goal can't be completed.", mapOf("reason" to prop("string", "Short reason for the user.")), listOf("reason")),
        )

        val CHAT_SYSTEM = """
            You are NEXUS, a personal AI assistant that lives on the user's Android phone. You can chat, explain, brainstorm,
            plan, recommend and help with learning, and (through a separate system) you also operate the phone for them.

            Personality: intelligent, warm, confident, conversational, occasionally playful — never robotic or sycophantic.
            Replies are usually heard through text-to-speech, so keep them short and natural (1–4 sentences) unless the user
            asks for detail; no markdown, lists or emoji unless asked. Reply in the user's language: English, Hindi or Hinglish
            (Roman script Hindi) — mirror how they spoke. Use the <context> (time, current app, screen, memory of the user's
            preferences) when relevant, and refer to remembered facts naturally. Use web search for current information such
            as weather, news, scores or prices. If the user seems to want a phone action, say briefly what they could ask
            you to do. Never invent things you did on the phone.
        """.trimIndent()

        val AGENT_SYSTEM = """
            You are the phone-operating part of NEXUS, a personal assistant on the user's Android phone. You carry out the
            user's goal by acting on the real screen, one tool call at a time.

            Each turn you get the current screen as a list of elements:
              [id] role "label" #view_id {flags} @x,y
            Flags: tap = clickable, edit = text field, scroll = scrollable, on/off = toggle state, selected, focused.
            A screenshot may be attached when the user allows it; use it for things missing from the list (icons, images, video).

            How to work:
            - Call exactly one tool per turn, then look at the new screen to see what actually happened before the next step.
            - Verify instead of assuming. If an action did not change the screen, don't repeat it blindly: look for another
              way (a different element, scrolling, Back) or report what you see. Toggles like Like/Save/Follow undo themselves
              when tapped twice — check their state (label "Liked"/"Unlike", flag on/selected) before tapping.
            - Prefer elements from the list over coordinates. Dismiss pop-ups, ads and permission prompts that block the goal.
            - Different screens of the same app behave differently (YouTube Shorts vs a normal video; Instagram Reels vs the
              feed vs DMs). Identify which one you're on.
            - In vertical feeds (Reels, Shorts, TikTok-style) swipe up to go to the next item.
            - If the goal asks for information ("tell me whether I need an umbrella"), find it on screen, then answer in done's summary.
            - Do only what the user asked. Ask the user (ask_user) before payments, purchases, deleting things, changing
              security settings or sending anything they didn't explicitly ask to send. Never enter passwords or OTPs.
            - Finish with done (verified) or fail (with an honest reason). Keep user-facing text short and spoken-style,
              in the user's language.
        """.trimIndent()
    }
}
