# NEXUS v2 — Progress

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
