# Roadmap — the four capability levels

Status legend: ✅ in this version · 🟡 partial · ⬜ next

## Level 1 — Personal phone assistant
* ✅ Voice and text input, spoken and text replies
* ✅ Open / switch / return to apps, Home, Back, Recents, notifications, quick settings
* ✅ Tap, long-press, swipe, scroll, type, copy, paste, close dialogs
* ✅ Contacts, calls (number choice, duplicates), end call, SMS
* ✅ Flashlight, volume, brightness, DND, rotation; Wi-Fi/Bluetooth/data/location open the right panel where Android forbids direct toggles
* 🟡 Text selection of arbitrary ranges

## Level 2 — Intelligent conversational assistant
* ✅ English, Hindi (Devanagari), Hinglish; informal/polite phrasing; ASR error fixes
* ✅ Question vs instruction vs mixed request
* ✅ Conversation with personality (Claude), web search for live facts
* ✅ Follow-ups and references ("like it", "message him", "the same video")
* ✅ Long-term memory with inspect / edit / clear / disable; aliases; shortcuts
* ✅ Screen awareness: accessibility tree, screen classification, optional screenshots
* 🟡 OCR: model reads screenshots when enabled; on-device OCR (ML Kit) ⬜
* ⬜ Learning from corrections automatically (store "that's not what I meant" outcomes)

## Level 3 — Universal application controller
* ✅ YouTube (search, results, watch, Shorts, like, save, subscribe, comments)
* ✅ Instagram (feed, Reels, stories, comments, search, profiles, follow, DMs)
* ✅ WhatsApp (click-to-chat, search, draft vs send, read chats)
* ✅ Spotify / YouTube Music play-from-search, media controls, seeking
* ✅ Chrome / web search, links; Gmail drafts; files; notifications; settings pages
* ✅ Generic skill for any other app
* ⬜ Dedicated skills: Gmail (search/reply/forward), Chrome tabs, Files (move/rename), Telegram, Messages

## Level 4 — Autonomous personal agent
* ✅ Multi-step workflows from one sentence, with dependency-aware failure handling
* ✅ Observe → act → verify; uncertain ≠ failed; no blind repeats
* ✅ Stop / pause / continue / correct / repeat; clarifying questions mid-workflow
* ✅ Claude agent loop for unfamiliar screens and open-ended tasks
* ⬜ On-device wake-word engine (e.g. openWakeWord / Porcupine) for low-power always-on "Hey Nexus"
* ⬜ Barge-in while NEXUS is speaking (needs echo-cancelled audio capture)
* ⬜ Recording user demonstrations as reusable workflows

## Android limits worth knowing
* Apps can't toggle Wi-Fi (Android 10+) or Bluetooth (13+) directly; NEXUS opens the panel instead.
* Continuous listening needs the bubble's foreground service; Android shows a mic indicator while listening.
* Some apps (banking, DRM video) hide content from accessibility or block screenshots.
* App interfaces change; skills use labels and roles rather than exact layouts, and fall back to the generic skill and Claude.
