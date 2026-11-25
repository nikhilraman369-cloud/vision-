# NEXUS v2 — Progress

## 2.10.0 (versionCode 22): Hearing Test (step 1 of the v3 plan)
- Panel → 🎧 Hearing Test: 30 real phrases from the task list (English + Hinglish). Each is recorded once (16 kHz, noise suppression, earbud mic optional, stops ~1.2 s after you finish) and the same recording is sent to Sarvam (saarika), Deepgram (nova-3 multi), Whisper large-v3-turbo on Groq, and the phone's own recogniser (en-IN, hi-IN; Android 13+).
- Each result is scored on words heard (Hinglish spelling and Devanagari normalised) and on whether NEXUS would run the same command; plus speed. Copy/Share report.
- Keys are entered in the app and stored only on the phone.
- v3 plan: lean app with only categories A–C, built on the winning engine; intents/deep links first, screen taps only where unavoidable; full-screen persistent reminders; memory; British butler-style voice; online conversation.

## 2.9.0 (versionCode 21): playback that doesn't depend on the screen + app screen recorder
- **YouTube and YouTube Music play directly:** NEXUS looks the video/song up (YouTube's own search API, JSON; the search page as fallback) and opens it in the app with a watch link, which starts playing from any screen, even while something else is playing. YouTube Music looks up songs (not music videos). "Play X" with no app uses YouTube Music when it's installed.
- **Teach NEXUS an app (Panel → 🗺):** records the structure of every different screen you open (ids, buttons, lists, positions; typed text is never saved), with a "Learning screens · N saved" bar. Share file saves it to Downloads/NEXUS and opens the share sheet. These recordings are how each daily app gets taught from its real layout.
- Untested on a real phone; the lookup couldn't be tested from the build machine (YouTube blocked there). It falls back to the old ways on any problem.

## 2.8.0 (versionCode 20): understands what you mean on screen
- **Screen grammar:** generic rules for how people point at things and repeat actions, in English, Hindi and Hinglish:
  positions ("second video", "sixth contact", "group at the top", "sabse upar wala chat", "second last", "number 4", "5th wala", "teesri chat"),
  counts ("scroll 3 times", "teen baar scroll karo", "go back twice", "2 baar back jao", "next 5 reels", "3 reels aage"),
  Hindi word order ("whatsapp kholo", "settings pe click karo", "rahul ko bolo …", "spotify pe X chalao", "google pe X dhundo", "neeche jao", "thoda upar"),
  casual speech ("plz open 2nd vid", "torch on kr", "like kr do", "type kar hello", "insta kholke reels dikha").
- **Checked with 372 phrasings** in three sets (the second and third written separately to test generalisation; first-try scores 90% and 59%, all fixed by general rules) plus simulated-screen tests: the top chat is opened (WhatsApp's search bar is skipped), "sixth contact"/"second last"/"5th wala" pick the right row, counts repeat the right number of times.
- **Never "I don't understand":** anything that isn't a question is treated as something to do. NEXUS matches it against what's on screen (even inside a long sentence); if nothing matches, it lists what it can see and asks which one to tap.
- Tapping an app name that isn't on screen opens the app. Lists: "second last" works, and ordinals beyond the visible rows scroll and keep counting.

## 2.7.1 (versionCode 19): YouTube Music actually plays the song
- YT Music's "play from search" shortcut only opens its results page. NEXUS now looks the song up and opens it directly in YouTube Music (a watch link, which starts playing), and checks that music is really playing. If that fails it falls back to the old shortcut and then to tapping the result, which now also matches the result by its text when the list can't be read as rows.
- Needs internet for the lookup. Untested against the live YouTube page (could not reach it from the build machine), so it fails safe: any problem falls back to the old ways.

## 2.7.0 (versionCode 18): patience, same-name contacts, Instagram songs, animated orb
- **Patience:** a screen step that can't find its target (app still loading) now waits for the screen to settle and retries twice before giving up or asking for help. Per-app patience raised to 5–15 s.
- **Same-name contacts:** if the name you said matches a contact exactly (even two with the same name), NEXUS just uses the first one instead of asking.
- **Songs on Instagram:** "play Kesariya on insta" → search → Audio tab → the song → its first reel.
- **Animated orb:** new bubble drawn live: breathing (idle), green ripples (listening), fast pulse (hearing you), amber spinning arcs (thinking), turning dashed ring (working), blue sound-wave ring (speaking), orange pulse (waiting for you), red shake (stuck), red dashed ring (teaching). It slides fully onto the screen while active and tucks back when idle.

## 2.6.0 (versionCode 17): power-button assistant
- NEXUS can be picked as the phone's **Digital assistant app** (Settings → Listening → "Use NEXUS as my phone's assistant"). The phone's assistant gesture (usually long-press power / home gesture, depends on the phone) then starts NEXUS listening, even from the lock screen. Short power presses are not affected.
- When NEXUS is the default assistant Android points the default speech recogniser at NEXUS's own stub, so NEXUS now explicitly listens through the phone's real recogniser (Google's if present) in that case.
- Untested on a real device. Known caveat: Vivo/iQOO may route the power long-press to its own assistant setting; apps that use the system default recogniser will not work while NEXUS is the default assistant.

## 2.5.0 (versionCode 16): volume buttons are never touched
- The volume-button shortcut is **removed completely** (it kept interfering with normal volume on this phone, in three different designs). NEXUS no longer asks Android for key events at all.
- New ways to start listening: the floating N, the mic button, the notification's Talk button, and a **quick-settings tile** ("NEXUS": swipe down, pencil, drag it up).

## 2.4.1 (versionCode 14): the neural voice finally plays
- **Root cause found:** the audio buffer was sized equal to the voice's sample rate; Android requires a multiple of 4 bytes. Piper voices (22,050 Hz: Lessac, Alan, Ryan, Joe, Amy…) failed to create the audio player, and the error was swallowed, so it looked like "the voice produced no sound". Kokoro (24,000 Hz) was unaffected. Verified the voice packs and sherpa-onnx 1.13.8 themselves work (generated audio from the same Lessac pack and config on a PC; the app's libraries are byte-identical to the official ones).
- Player errors are now reported instead of swallowed; empty results are no longer cached; speed is clamped to a valid range.

## 2.4.0 (versionCode 13): Teach ecosystem
- **Teach screen** (home → 🎓 Teach, also Panel and the bubble's 🎓 chip): name the task in your own words, tap Start, do it once, tap Done. The name is the trigger phrase, so Done saves with no extra questions.
- **Recording bar** (red, on screen): task name, live step count, Done ✓ and ✕. Also an ongoing notification with Done / Cancel if the bubble isn't shown. If a recording has no name (e.g. "watch me"), Done asks for one.
- **Library:** every learned task with the phrases that trigger it, a plain-words step list, success rate and last use. Run, Rename, Phrases, Steps, Re-teach, Turn off, Undo last teach, Delete.
- **Screen recordings (video files) are not supported:** a video can't be turned into reliable taps offline. Live recording is the supported way; the Teach screen says so.

## 2.3.0 (versionCode 10): voice sequence + volume-key fix
- **Volume bar:** the volume-up + down shortcut no longer touches the volume at all (the first key is held back ~0.2 s; if the second joins, it's the shortcut; otherwise the press is replayed as a normal volume change). Left alone during calls and in camera apps.
- **Speaking can no longer fail silently:** if the downloaded voice makes no sound, NEXUS uses the phone's voice for that sentence; the phone voice picks a language it really has, has a stuck-flag fix and a 5 s watchdog; any problem is shown in the chat and in Panel → Voice → "Test voice" (which also warns when a Bluetooth device is taking the sound).
- **Spoken sequence:** "On it, Boss." → task → result ("Opened YouTube.", "Done, Boss.", or for chains "All done, Boss."). Quiet-confirmations mode removed; speech forced on once for this update.
- **Stuck:** "Boss, I'm stuck at … Tell me what to do": whatever you say next is taken as the instruction (or "teach me" / "skip" / "cancel").
- **Listening:** the follow-up window now opens only after NEXUS has finished both talking and working (10 s when it's waiting for your answer).

## 2.2.0 (versionCode 9): speed + open-ended commands
- **Brain streams:** answers are spoken sentence by sentence as the on-device model writes them (MediaPipe async API, found at runtime with a fallback). Generation stops after 3 sentences. The prompt is shorter (faster to read), and a soft "thinking" tone plays instantly.
- **Keep going:** "keep scrolling up/down", "scroll down slowly" (gentle reading-speed swipes), "faster", "slower", "pause", "continue", "stop". It stops by itself at the end of a page. On Reels/Shorts it moves to the next video every few seconds instead.
- **Repeat:** "scroll the reel after 15 seconds", "next reel every 10 seconds", "every 30 seconds …".
- **Later:** "after 10 minutes turn off wifi", "call mom in 5 minutes", "10 minute baad …". Delays of 10 minutes or more use an alarm, so they survive the app closing.
- **Schedule:** "at 9 pm message Rahul saying …", "schedule a message to Rahul tomorrow at 9 saying …", "kal subah 9 baje Rahul ko message karo ki …"; "what have I scheduled", "cancel the scheduled message to Rahul". Runs at the time without asking again. Behind a PIN lock, a notification asks you to unlock, then it sends.
- A "⟳ Scrolling down · tap to stop" pill shows on screen while anything keeps running.

## 2.1.0 (versionCode 8): fixes from the first phone test
- **Brain answers questions offline:** the phone brain (Qwen 2.5, Smart 1.5B recommended) now chats and answers, using memory and recent conversation. No more "connect API". Without the brain, NEXUS says how to download it (with live progress).
- **Listening:** the always-on "Hey Nexus", continuous listening and wake-word settings are removed. Tap the N (or press volume up + down together) to listen. After each reply NEXUS listens 6 s for a follow-up, then sleeps. The recogniser restarts silently inside a window, so the bubble no longer flickers. Longer silence allowance, so you aren't cut off mid-sentence.
- **Voice no longer cuts off:** the audio track drains fully before release; NEXUS doesn't hear itself (the old barge-in listener is gone).
- **Volume up + down = listen**, via the accessibility service (screen on or lock screen). A "Listening…" pill shows on any screen; tap it to cancel.
- **22 voices:** Kokoro studio pack (George, Lewis, Emma, Isabella, Adam, Michael, Bella, Sarah, Nicole, Sky) plus Piper packs (Alan, Northern male, Semaine ×4, Alba, Jenny, Ryan, Joe, Lessac, Amy). One download per pack; switching between voices in a pack is instant.
- **Cleaner UI:** new home header (title no longer squeezed), three equal buttons, a settings gear, and a much shorter Settings (You, Brain, Voice, Listening, Safety).

Version 2.0.0 (versionCode 7). Built on 0.6 (upgraded, not rebuilt).

## Done
- **Phase 0 – Reliability:** crash log (`crash.txt`, Panel → Diagnostics); diagnostics log of every step (last 600); predictive screen cache; observe→act→verify with waits; stuck detection that asks "Teach me / Type instruction / Skip / Cancel" instead of failing silently.
- **Phase 1 – Voice & understanding:** spoken acknowledgements ("On it, Boss."); conversation mode (8 s follow-up window, double-tap the bubble, "that's all" ends it); barge-in ("stop", "wait", "ruko" while speaking); vocabulary post-correction using your contacts and app names, plus a log of mishearings it has learned; "Did you mean…?" with a closest-command catalogue.
- **Phase 2 – Butler voice:** offline neural TTS (sherpa-onnx), packs Piper *Alan* (GB), Piper *Northern English male*, Kokoro *George* and *Lewis*; sentence streaming, ducking, pitch, speed, optional cinematic effects, earcons. Falls back to the system British male voice.
- **Phase 3 – Memory:** encrypted on the phone (AES-256-GCM, Android Keystore); importance, pin, confidence, source, last used; relevance retrieval with a budget; "Shall I remember this, Boss?" auto-capture; "what do you know about…"; Memory screen with search, filters, pin, merge duplicates, export/import, "used in the last answer".
- **Phase 4 – Notification intelligence:** whitelisted apps and keywords; OTP, bank and payment notifications are never read or stored; task and deadline extraction; reminders 1 day / 3 h / 1 h / 15 min, respecting quiet hours and modes; draft outline; RemoteInput replies with approval and a per-sender trust ladder (observe/suggest/ask/auto); dedupe; raw text wiped after 7 days.
- **Phase 5 – Self-learning:** teach mode records robust selectors (id → text/description → fuzzy → class + position); parameterized skills ("send {message} to {name}"); success scoring, versions, rollback, re-teach; corrections ("No, I meant Rahul Sharma", "always use WhatsApp for him"); weekly self-review in Panel → Skills.
- **Phase 6 – Life:** modes (Study, Gym, Writing, College, Sleep, plus custom) with a distraction guard and schedules; morning, evening and weekly briefs; capture inbox; tasks; expenses; CA Inter subjects with spaced-repetition flashcards and "quiz me"; habit streaks; "what should I do now".
- **Phase 7 – Control panel:** Home, Tasks, Memory, Skills, Modes, Notification rules, Voice, Bubble, Study & habits, Diagnostics, Permissions (11 checks with Fix), Data & privacy (export, import, erase). Edge-docked bubble with an expand card, long-press menu and the stuck card.

## Partial
- The neural voice depends on CI fetching sherpa-onnx. If that fails, the release notes say "system voice only" and NEXUS uses the system British voice.
- The LLM layer is optional (`local` phone brain, `claude` with your key, or `off`). Notification refinement through an LLM runs only with `claude`.
- App code is type-checked locally against stubs; device behaviour (Vivo/iQOO quirks) is verified only on a real phone.

## Known gaps
- The microphone cannot start by itself at boot (Android 14 rule). A "NEXUS is ready" notification starts it with one tap.
- Location-based triggers are not implemented.
- The earbud button is not hijacked (it would break music controls).

## First step next session
Install the build, run the acceptance tests in the report, then send Panel → Diagnostics → "Copy report" for anything that misbehaved.

## 2.11.0 — Sarvam is NEXUS's ears
- Hearing Test winner: Sarvam (saarika:v2.5). Tap the bubble → records via earbud mic until you stop talking (0.8 s) → Sarvam → command.
- Settings → Listening: Hearing engine (Sarvam / phone), Sarvam key field, earbud-mic switch.
- If Sarvam fails (no net / bad key) NEXUS says so and falls back to the phone recogniser for 60 s.

## 2.12.0 — Category A complete (fixed app-control tasks)
Spec: `core/src/test/resources/category_a_corpus.txt` (217 phrases, English + Hinglish + Devanagari as Sarvam writes it). End-to-end: `CategoryATest` (16 scenarios on a simulated phone).
- WhatsApp calls: "whatsapp call rahul", "video call mom", "rahul ko whatsapp pe call karo", "video call family group".
  Saved contact → WhatsApp's own call entry in Contacts (no tapping). Group / no entry → open chat → call button → accept "Start call?" (never Cancel). Success only when the call screen shows.
- WhatsApp messages: name/text split using real contact names ("message rahul hi kaise ho"), groups ("college group mein bhejo …"), drafts ("… but don't send").
- Ask ChatGPT / Claude / Gemini: open app → new chat (unless already talking to it) → type → send. Whole prompt kept, even with "and".
- Google search → Chrome. Timers, alarms ("7 baje" = next 7 o'clock; subah/shaam/raat; saade/sava/paune), reminders (Hindi "yaad dilana"), torch, volume, camera/selfie.
- Devanagari English words fixed (कॉल→call, टाइमर→timer, चैट जीपीटी→chatgpt…).
- Self-check screen (Panel → ✅ Self-check): checks every dependency, Try button per task, Copy results.

## 2.12.1 — Category A in everyday English
`category_a_english.txt`: 152 natural English phrasings, all pass. New: relayed messages ("Ask Rahul if he's coming" → "Are you coming tomorrow?", "Let Mom know…", "Tell Rahul he should bring his laptop" → "you should bring your laptop"), "WhatsApp Priya and tell her …" kept as one message, "Give Mom a video call", "Put on some lo-fi", "Watch the latest video from MKBHD", "Use/Get/Have ChatGPT to …", "Hey ChatGPT, …", "Start a 5 minute timer", "an hour and a half", "alarm in 30 minutes", "Set a reminder for 5 pm to …", "Make it louder", "Flashlight" (toggle), "Start recording a video".

## 2.13.0 — Category C: voice and conversation (online, Sarvam)
- Voice: Sarvam Bulbul v4-flash, streamed (/text-to-speech/stream, raw 24 kHz PCM, plays as bytes arrive, next sentence prefetched). Two styles: **Strong** (Simran, Sanchita, Amelia, Sophia, Zarina) and **Cute** (Ritu, Ishita, Neha, Aparna, Shalini). If a v4 voice is refused → the style's v3 voice. If Sarvam fails → downloaded voice → phone voice; retry online after 60 s.
- Conversation: Sarvam `sarvam-105b-conversations` (voice-agent model, no reasoning delay), streamed; each sentence is spoken the moment it's complete. Personality follows the style. Spoken-style rules (short, no markdown). Last 12 turns as history. Failure → other brains, never silence.
- Settings → Voice & conversation: Strong/Cute, voice picker, ▶ Hear it, online voice / online conversation switches.
- API shapes taken from the official `sarvamai` SDK 1.1.22 (docs site unreachable from the build sandbox); not yet tried against the live API.
- Tests: `TalkTest` (streaming order, history, commands never go to chat, failure fallback, speakable text).
