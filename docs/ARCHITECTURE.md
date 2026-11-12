# NEXUS architecture

## Layers

```
app (Android)                         core (pure Kotlin, unit-tested)
─────────────                         ───────────────────────────────
MainActivity / Settings / Memory ┐    nlu/      TextNormalizer, Devanagari, CommandSplitter,
OverlayService (bubble, mic FGS) ├──▶            IntentParser → Command, BuiltInApps, Fuzzy
VoiceEngine (STT/TTS, wake word) ┘    agent/    Nexus (engine), Ops (observe/act/verify),
                                                AppDirectory, Outcome/Events, Brain, OfflineChat
NexusAccessibilityService ─▶ AndroidDevice ─▶  device/DeviceController
AndroidSystemActions ───────────────────────▶  device/SystemActions
NexusNotificationListener                      screen/   UiNode, ScreenSnapshot, ElementFinder,
                                                         ScreenClassifier
                                               skills/   YouTube, Instagram, WhatsApp, Generic
                                               memory/   LongTermMemory (JSON, user-controlled),
                                                         WorkingContext (short-term)
                                               llm/      ClaudeBrain (chat, screen Q&A, agent)
```

`core` never touches Android APIs; it talks to the phone only through
`DeviceController` and `SystemActions`. That is what makes the whole agent
testable with a simulated phone (`core/src/test/.../fake/FakePhone.kt`), which
drives scripted Instagram/YouTube screens end-to-end.

## Understanding a request

1. **Normalise** – Devanagari → Hinglish, strip "hey Nexus"/"please"/"can you…
   for me", fix common recognition errors, keep the user's casing for text that
   will be typed.
2. **Split** – into clauses on `,` / `then` / `and` / `phir` / `aur` …, but only
   where a new action starts, and never inside text being dictated
   ("saying I'll come and call you later" stays whole).
3. **Parse** – ordered rule families (navigation, scrolling, next/previous,
   screen questions, reactions, comments, messaging, calls, toggles, levels,
   settings, notifications, files, media, results, sections, search, typing,
   taps, memory, links, apps). Anything unmatched becomes `Chat` (a question)
   or `Agentic` (a phone task for Claude). Requests mixing a phone action with
   reasoning ("…and tell me whether I need an umbrella") go to the agent whole.

## Executing

For each step the engine:

* resolves references from `WorkingContext` (current contact, chat app,
  previous app) and `LongTermMemory` (aliases, preferences);
* asks first if a contact is ambiguous, and resumes the remaining steps after
  the answer;
* routes to the skill for the target app (messaging) or the app on screen,
  falling back to `GenericSkill`;
* treats outcomes as `Success`, `Uncertain` (done but unconfirmed: reported,
  never blindly repeated, workflow continues), `Failed` (navigation failures
  stop dependent steps; others don't), `NeedsAgent` (hand the step to Claude)
  or `Asked` (wait for the user, then continue).

Control words (`stop`, `wait`, `continue`, `that's not what I meant`) are
handled immediately, even mid-task; a new instruction replaces the running
task; "continue" after a stop resumes the remaining steps.

## Speed

* Rule-based understanding is instant and offline.
* `awaitChange` is driven by accessibility events with a 120 ms quiet-period
  debounce, so the next step starts as soon as the screen settles.
* Screen fingerprints avoid re-analysing an unchanged screen.
* Deep links (YouTube search, WhatsApp click-to-chat, media play-from-search)
  skip whole navigation sequences.
* Claude is used only when needed; chat runs at low effort, the agent at
  medium; system prompts and tools are prompt-cached.

## Claude integration

`ClaudeBrain` uses the official Anthropic Java SDK with `claude-opus-5-5`,
adaptive thinking, and server-side refusal fallbacks (beta, switched off
automatically if the API rejects it). Chat has the web-search tool for live
facts. The agent is a manual tool-use loop (one action per turn, parallel tool
use disabled) so that NEXUS can verify each action on the device before the
model decides the next one; assistant turns are kept verbatim so thinking
context carries across steps.
