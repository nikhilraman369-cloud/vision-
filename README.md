# NEXUS — a personal AI assistant that runs your Android phone

Talk to your phone the way you'd talk to a person: in English, Hindi or Hinglish.
NEXUS works out what you mean, plans the steps, carries them out on screen,
checks that each one worked, and remembers the context for your next request.

> "Hey Nexus, open Instagram, go to Reels, find the next Reel, like it, save it, open the comments and write nice video."
>
> "YouTube kholo, filmmaking search karo, pehla video chalao aur like karo."
>
> "Open WhatsApp, find Rahul and send him a message saying I'll reach in ten minutes."

## What's here

| Module | What it is |
|---|---|
| [`core/`](core) | The brain. Pure Kotlin, runs and is tested on any JVM: language understanding, workflow engine, screen understanding, app skills, memory, and the Claude integration. |
| [`app/`](app) | The Android app: accessibility service (eyes and hands), voice in/out, floating bubble, chat screen, settings and memory screens. |

### How a request flows

```
voice / text ─▶ TextNormalizer ─▶ CommandSplitter ─▶ IntentParser ─▶ steps
                (Hindi script,      ("…, then …",       (EN / HI /        │
                 wake word,          "aur", "phir",       Hinglish rules)   ▼
                 fillers, ASR        keeps typed text                 Nexus engine
                 fixes)              in one piece)          ┌──────────────┴──────────────┐
                                                            │ for each step:              │
                                                            │  resolve "it"/"him"/aliases │
                                                            │  observe screen → classify  │
                                                            │  app skill → generic skill  │
                                                            │  act → wait for change      │
                                                            │  verify (success/uncertain/ │
                                                            │          failed)            │
                                                            │  unknown screen → Claude    │
                                                            │  agent (tool use loop)      │
                                                            └─────────────────────────────┘
```

* **Fast path, offline.** Everyday commands are understood by built-in rules
  (no network, no latency): opening apps, navigation, scrolling, likes, saves,
  comments, search, results, messages, calls, toggles, volume/brightness, media,
  files, notifications, memory.
* **Smart path, Claude.** Questions and conversation, and any phone task the
  rules can't break down (or a screen the skills don't recognise), go to Claude
  (`claude-opus-5-5`). In agent mode Claude sees the screen as a numbered element
  list (plus an optional screenshot) and acts through tools — `tap`, `type_text`,
  `scroll`, `swipe`, `open_app`, `ask_user`, `done`… — one verified step at a time.
* **Observe → act → verify.** Every action waits for the screen to react
  (event-driven, no fixed sleeps) and re-reads it. Toggles such as Like are
  never tapped twice blindly: if NEXUS can't confirm the new state it reports
  "uncertain" and carries on with the rest of the task.

### Features in this version

* **Understanding**: dozens of phrasings per action; English, Hindi (Devanagari
  is transliterated), Hinglish, polite/informal speech, speech-recognition
  mistakes ("insta gram", "rails" → Reels, "what's app"), multi-step sentences,
  follow-ups ("like it", "message him", "find another one"), corrections
  ("open the second result instead", "that's not what I meant"), "do the same
  thing again", stop / wait / continue.
* **Screens**: classifies YouTube home / results / watch page / Shorts /
  comments, Instagram feed / Reels / stories / comments / search / profile / DMs,
  WhatsApp chat list / chat, Gmail, browsers, dialler, settings, launchers.
  "What's on my screen?", "read this page", "what does this button do?",
  "find the search option", "open the second result".
* **Apps**: YouTube (deep-link search, skips ads, Shorts vs videos, likes,
  Watch later, comments), Instagram (tabs, Reels navigation, like / save / follow,
  comments, search, DMs), WhatsApp (official click-to-chat links with prefilled
  text, chat search, draft vs send, reading chats), plus a **generic skill**
  that works in any app by reading its labels, lists and fields.
* **Phone**: calls (with "which Rahul?" disambiguation and number choice),
  SMS, contacts, flashlight, Wi-Fi/Bluetooth/DND/rotation (directly where Android
  allows, otherwise opens the right panel), volume, brightness, media keys and
  seeking, Spotify / YouTube Music "play X", settings pages, notifications,
  files, links, email drafts.
* **Memory** (inspectable, editable, erasable, can be switched off):
  preferences, contact aliases ("remember that mom means Sunita Sharma"), app
  aliases, shortcuts ("when I say good night, turn off wifi and set brightness
  to 10"), facts, most-used apps, recent conversation. Passwords, OTPs and card
  numbers are refused.
* **Voice**: tap-to-talk, continuous listening, optional wake word, spoken
  replies in English or Hindi, never listens to itself, tap the bubble to
  interrupt.
* **Floating bubble**: draggable; colour shows listening / thinking / working /
  paused; tap = talk, long-press = stop, double-tap = open NEXUS.

## Building

The APK is built by GitHub Actions on every push (**Actions → Build →
`nexus-debug-apk`**). Locally:

```bash
./gradlew :core:test            # brain + simulated-phone tests, no Android SDK needed
./gradlew :app:assembleDebug    # needs the Android SDK (ANDROID_HOME or local.properties)
```

## First run on the phone

1. Install the APK (allow installs from your browser/files app).
2. Open NEXUS and follow the setup checklist:
   **Phone control** (Settings → Accessibility → NEXUS phone control),
   microphone, display over other apps, contacts/calls/SMS, notification access,
   files.
3. Optional: in **Settings**, paste an Anthropic API key to enable conversation
   and the AI agent for unfamiliar apps. Without a key, phone commands still work offline.
4. Tap **Show floating bubble** and talk.

On Android 13+ a sideloaded app's accessibility toggle may be greyed out at
first: open *Settings → Apps → NEXUS → ⋮ → Allow restricted settings*, then
enable it.

## Privacy

* Screen reading, rules, memory and voice routing all run on the phone.
* Cloud AI is optional and can be switched off; screenshots are only sent if
  you enable that separately. The API key is stored in app-private storage.
* The microphone is used only while the bubble/mic is green; the persistent
  notification shows when NEXUS is active and can turn it off.
* Notifications are kept in memory only, never written to disk.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the design and
[docs/ROADMAP.md](docs/ROADMAP.md) for how this maps onto the four capability
levels and what comes next.
