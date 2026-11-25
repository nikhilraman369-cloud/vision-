package ai.nexus.core.skills

import ai.nexus.core.agent.Outcome
import ai.nexus.core.nlu.Command
import ai.nexus.core.nlu.Fuzzy
import ai.nexus.core.nlu.IntentParser
import ai.nexus.core.screen.ElementFinder
import ai.nexus.core.screen.Packages
import ai.nexus.core.screen.ScreenKind
import java.net.URLEncoder

/**
 * WhatsApp. When the contact has a phone number, the official click-to-chat
 * link opens the right chat with the text prefilled – far more reliable than
 * navigating the UI. Otherwise NEXUS searches chats on screen.
 */
class WhatsAppSkill : AppSkill {
    override val packages = setOf(Packages.WHATSAPP, Packages.WHATSAPP_BUSINESS)

    override suspend fun handle(cmd: Command, env: SkillEnv): Outcome? = when (cmd) {
        is Command.OpenChat -> openChat(cmd.contact, env, null)
        is Command.SendMessage -> send(cmd, env)
        is Command.ReadMessages -> read(cmd, env)
        is Command.Search -> openChat(cmd.query, env, null)
        is Command.Call -> if (cmd.app == "whatsapp") call(cmd, env) else null
        else -> null
    }

    // ------------------------------------------------------------ calls

    /**
     * WhatsApp call. Saved contact → WhatsApp's own call entry (no screen tapping at all).
     * Group, or no entry → open the chat and press its call button. Confirm prompts are accepted;
     * success only when the call screen is really showing.
     */
    private suspend fun call(cmd: Command.Call, env: SkillEnv): Outcome {
        val name = (if (cmd.contact == IntentParser.SAME_CONTACT) env.ctx.currentContactName else cmd.contact) ?: env.ctx.currentContactName
            ?: return Outcome.Asked("Who should I call on WhatsApp?")
        val kind = if (cmd.video) "video" else "voice"
        val isGroup = Regex("\\bgroup\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)
        if (!isGroup) {
            when (val lookup = env.contacts(name)) {
                is ContactLookup.Ambiguous -> return Outcome.Asked("I found ${lookup.options.size} contacts called $name: " + lookup.options.joinToString { it.name } + ". Which one?")
                is ContactLookup.Found -> {
                    val c = lookup.contact
                    env.ctx.currentContact = c; env.ctx.currentContactName = c.name
                    val r = runCatching { env.system.whatsappCall(c.id, c.numbers.firstOrNull(), cmd.video) }.getOrNull()
                    if (r is ai.nexus.core.device.SysResult.Done) {
                        acceptCallPrompt(env, cmd.video)
                        return if (onCallScreen(env, 6000)) Outcome.Success("Calling ${c.name} on WhatsApp ($kind).")
                        else Outcome.Uncertain("I started a WhatsApp $kind call to ${c.name}, but I can't see the call screen yet.")
                    }
                    // No WhatsApp entry for this contact (or WhatsApp refused): use their chat's call button.
                    val opened = openChat(c.name, env, null)
                    if (opened !is Outcome.Success) return opened
                    return pressCall(env, c.name, cmd.video)
                }
                ContactLookup.NotFound -> {}
            }
        }
        // Groups and names WhatsApp knows but the phone doesn't: find the chat, press its call button.
        val opened = searchChat(name, env)
        if (opened !is Outcome.Success) return opened
        return pressCall(env, env.ctx.currentContactName ?: name, cmd.video)
    }

    private val VOICE_LABELS = listOf("Voice call", "Audio call", "Call")
    private val VIDEO_LABELS = listOf("Video call")

    private suspend fun pressCall(env: SkillEnv, who: String, video: Boolean): Outcome {
        val ops = env.ops
        val s = ops.waitFor(3000) { ops.kind(it) == ScreenKind.WA_CHAT } ?: return Outcome.Failed("The chat with $who didn't open.")
        val f = ElementFinder(s)
        val btn = (if (video) f.byViewId("menuitem_video_call", "video_call_btn") else f.byViewId("menuitem_voip_call", "menuitem_call", "voice_call_btn"))
            ?: f.byLabel(*(if (video) VIDEO_LABELS else VOICE_LABELS).toTypedArray(), requireClickable = true, minScore = 0.9)
            ?: return Outcome.Failed("I couldn't find the ${if (video) "video" else "voice"} call button in $who's chat.")
        ops.tap(btn, s) ?: return Outcome.Failed("The call button didn't respond.")
        acceptCallPrompt(env, video)
        return if (onCallScreen(env, 6000)) Outcome.Success("Calling $who on WhatsApp (${if (video) "video" else "voice"}).")
        else Outcome.Uncertain("I pressed call in $who's chat, but I can't see the call screen yet.")
    }

    /** WhatsApp sometimes asks "Start voice call?" or, for groups, "Call group?": say yes. Never taps Cancel. */
    private suspend fun acceptCallPrompt(env: SkillEnv, video: Boolean) {
        val ops = env.ops
        val yes = listOf("Call", "Start call", "Voice call", "Video call", "Call group", "Start", "Call now", "Ring")
        val s = ops.waitFor(2500) { snap -> snap.packageName in packages && (isCallScreen(snap) || promptButton(snap, yes) != null) } ?: return
        if (isCallScreen(s)) return
        promptButton(s, yes)?.let { ops.tap(it, s) }
    }

    /** The confirm button of a dialog or bottom sheet: never the chat's own call icons in the top bar, never "Cancel". */
    private fun promptButton(s: ai.nexus.core.screen.ScreenSnapshot, yes: List<String>) =
        s.nodes.filter { it.clickable && !it.editable && it.shortViewId?.startsWith("menuitem") != true && it.bounds.bottom > s.screenHeight * 0.12 }
            .filter { n -> yes.any { y -> n.label.trim().equals(y, true) } }
            .maxByOrNull { it.bounds.top }

    private fun isCallScreen(s: ai.nexus.core.screen.ScreenSnapshot): Boolean = s.packageName in packages && s.nodes.any { n ->
        val l = n.label.lowercase()
        l == "end call" || l == "leave call" || l.startsWith("calling") || l.startsWith("ringing") || l == "connecting" || l.contains("end-to-end encrypted") && s.nodes.any { it.label.equals("Mute", true) } ||
            n.shortViewId in setOf("end_call_btn", "footer_end_call_btn", "call_status", "end_call_button")
    }

    private suspend fun onCallScreen(env: SkillEnv, ms: Long): Boolean = env.ops.waitFor(ms) { isCallScreen(it) } != null

    private fun pkg(env: SkillEnv) = env.ctx.currentPackage?.takeIf { it in packages } ?: Packages.WHATSAPP

    private suspend fun send(cmd: Command.SendMessage, env: SkillEnv): Outcome {
        val ops = env.ops
        val name = when (cmd.contact) {
            null, IntentParser.SAME_CONTACT -> env.ctx.currentContactName
            else -> cmd.contact
        }
        val text = cmd.text ?: return Outcome.Asked("What should I say${name?.let { " to $it" } ?: ""}?")
        val s = ops.current()
        // No name given and a chat is open: it's for this chat, whoever it is.
        val inRightChat = s != null && ops.kind(s) == ScreenKind.WA_CHAT &&
            (cmd.contact == null || name == null || chatTitle(s)?.let { Fuzzy.similarity(it, name) > 0.7 || it.contains(name, true) } == true)
        if (inRightChat && cmd.contact == null) chatTitle(s!!)?.let { env.ctx.currentContactName = it }

        if (!inRightChat) {
            name ?: return Outcome.Asked("Who should I send it to?")
            val r = openChat(name, env, text)
            if (r !is Outcome.Success) return r
        }
        val now = ops.current() ?: return Outcome.Failed("I can't see the screen.")
        val field = ElementFinder(now).byViewId("entry", "conversation_entry") ?: ElementFinder(now).firstEditable()
            ?: return Outcome.NeedsAgent("I couldn't find the message box.")
        if (field.text?.contains(text.take(20)) != true) {
            val typed = ops.typeText(text, field)
            if (typed is Outcome.Failed) return typed
        }
        if (!cmd.send) return Outcome.Success("Typed it — I haven't sent it.")
        return ops.submit(text, listOf("Send"), listOf("send", "send_container"))
    }

    private fun chatTitle(s: ai.nexus.core.screen.ScreenSnapshot): String? =
        s.nodes.firstOrNull { it.shortViewId == "conversation_contact_name" }?.label

    /** Open a chat: click-to-chat link if we know the number, else search on screen. */
    private suspend fun openChat(nameIn: String?, env: SkillEnv, prefill: String?): Outcome {
        val ops = env.ops
        val name = (if (nameIn == IntentParser.SAME_CONTACT) env.ctx.currentContactName else nameIn)
            ?: return Outcome.Asked("Whose chat should I open?")

        when (val lookup = env.contacts(name)) {
            is ContactLookup.Ambiguous -> return Outcome.Asked(
                "I found ${lookup.options.size} contacts called $name: " + lookup.options.joinToString { it.name } + ". Which one?",
            )
            is ContactLookup.Found -> {
                val number = lookup.contact.numbers.firstOrNull()?.filter { it.isDigit() || it == '+' }?.removePrefix("+")
                if (number != null && number.length >= 8) {
                    val full = if (number.length == 10) "91$number" else number
                    val url = "https://wa.me/$full" + (prefill?.let { "?text=" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: "")
                    if (env.device.openUri(url, pkg(env))) {
                        val chat = ops.waitFor(6000) { it.packageName in packages && ops.kind(it) == ScreenKind.WA_CHAT }
                        if (chat != null) {
                            env.ctx.currentContact = lookup.contact; env.ctx.currentContactName = lookup.contact.name
                            env.ctx.currentChatApp = pkg(env)
                            return Outcome.Success()
                        }
                    }
                }
                return searchChat(lookup.contact.name, env)
            }
            ContactLookup.NotFound -> return searchChat(name, env)
        }
    }

    private suspend fun searchChat(name: String, env: SkillEnv): Outcome {
        val ops = env.ops
        var s = ops.current() ?: return Outcome.Failed("I can't see the screen.")
        if (s.packageName !in packages) {
            env.device.launchApp(pkg(env))
            s = ops.waitFor(5000) { it.packageName in packages } ?: return Outcome.Failed("WhatsApp didn't open.")
        }
        // Back out of an open chat to the list.
        if (ops.kind(s) == ScreenKind.WA_CHAT) {
            env.device.global(ai.nexus.core.device.GlobalAction.BACK)
            s = ops.awaitChange(s) ?: ops.screen() ?: return Outcome.Failed("I can't see the screen.")
        }
        val row = findRow(s, name)
        if (row == null) {
            val search = ElementFinder(s).byViewId("menuitem_search", "my_search_bar", "search_bar")
                ?: ElementFinder(s).byLabel("Search", "Ask Meta AI or Search", requireClickable = true, minScore = 0.8)
                ?: return Outcome.NeedsAgent("I couldn't find WhatsApp search.")
            ops.tap(search, s) ?: return Outcome.Failed("Search didn't open.")
            val typed = ops.typeText(name)
            if (typed is Outcome.Failed) return typed
            s = ops.waitFor(3000) { findRow(it, name) != null } ?: return Outcome.Failed("I couldn't find a chat with $name on WhatsApp.")
        }
        val target = findRow(s, name) ?: return Outcome.Failed("I couldn't find $name.")
        ops.tap(target, s) ?: return Outcome.Failed("The chat didn't open.")
        val ok = ops.waitFor(3000) { ops.kind(it) == ScreenKind.WA_CHAT }
            ?: return Outcome.Uncertain("I tapped $name, but couldn't confirm the chat opened.")
        env.ctx.currentContactName = chatTitle(ok) ?: name
        env.ctx.currentChatApp = pkg(env)
        return Outcome.Success()
    }

    private fun findRow(s: ai.nexus.core.screen.ScreenSnapshot, name: String) = s.nodes
        .filter { !it.editable && !it.text.isNullOrBlank() && (it.shortViewId == "conversations_row_contact_name" || it.shortViewId == "contact_row_name" || it.shortViewId == null || it.shortViewId?.contains("name") == true) }
        .map { it to Fuzzy.similarity(it.text!!, name).let { sim -> if (it.text.contains(name, true)) maxOf(sim, 0.9) else sim } }
        .filter { it.second >= 0.75 }
        .maxByOrNull { it.second }?.first

    private suspend fun read(cmd: Command.ReadMessages, env: SkillEnv): Outcome {
        if (cmd.contact != null) {
            val r = openChat(cmd.contact, env, null)
            if (r !is Outcome.Success) return r
        }
        val s = env.ops.current() ?: return Outcome.Failed("I can't see the screen.")
        if (env.ops.kind(s) != ScreenKind.WA_CHAT) {
            val rows = s.nodes.filter { it.shortViewId == "conversations_row_contact_name" }.take(5).map { it.label }
            val previews = s.nodes.filter { it.shortViewId == "single_msg_tv" || it.shortViewId == "conversations_row_message" }.take(5).map { it.label }
            if (rows.isEmpty()) return Outcome.NeedsAgent("I couldn't read the chat list.")
            val text = rows.zip(previews + List(maxOf(0, rows.size - previews.size)) { "" }).joinToString(". ") { (n, p) -> if (p.isBlank()) n else "$n: $p" }
            return Outcome.Success("Your latest chats: $text")
        }
        val msgs = s.nodes.filter { it.shortViewId == "message_text" || it.shortViewId == "caption" }.map { it.label }.takeLast(5)
        if (msgs.isEmpty()) return Outcome.NeedsAgent("I couldn't read the messages here.")
        return Outcome.Success("The latest messages${env.ctx.currentContactName?.let { " with $it" } ?: ""}: " + msgs.joinToString(" … "))
    }
}
