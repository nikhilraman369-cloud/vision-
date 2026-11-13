# NEXUS v2 — Decisions

Choices made without asking, as the build prompt asked. Each one can be changed in a setting where noted.

| # | Decision | Why | Setting |
|---|----------|-----|---------|
| 1 | JSON files encrypted with AES-256-GCM (Android Keystore) instead of Room/SQLCipher | Data is small; no extra native libraries; easy export/import | Panel → Data & privacy |
| 2 | sherpa-onnx is fetched by CI from its GitHub releases, with a stub fallback | It isn't on Maven; the build must never break because of it | Release notes show "Voice runtime" |
| 3 | Default voice: Piper `en_GB-alan-medium`. Alternatives: Northern English male, Kokoro George/Lewis | British male, offline, open licence. No real actor is imitated or cloned | Panel → Voice |
| 4 | The local intent router runs first. The LLM sits behind a provider interface (`local`/`claude`/`off`) and returns strict JSON | Speed and privacy first; only the minimum text is sent | Settings → AI brain |
| 5 | Notification extraction is rule-based on the phone, with an optional LLM refinement | Works offline and fast; no notification text leaves the phone by default | Settings → AI brain |
| 6 | Auto-reply only for senders you set to "auto". Everyone else needs your approval | Trust ladder from the spec | Panel → Notification rules |
| 7 | OTP, bank and payment notifications and password fields are never read, recorded or stored | Hard safety rule | Not changeable |
| 8 | Always confirm: new contacts, unknown numbers, deletes, installs and uninstalls, account or security settings | Hard safety rule | Not changeable |
| 9 | Reminder offsets 1 day / 3 h / 1 h / 15 min; quiet hours 23:00–07:00 | Spec defaults | Panel → Notification rules / General |
| 10 | Follow-up window 8 s after each reply | Spec | Panel → Voice |
| 11 | Vosk (offline) is the default speech-to-text; Google online is optional for tap-to-talk accuracy | Privacy and offline first | Settings → Voice |
| 12 | The microphone is not started at boot; a tap-to-start notification is shown instead | Android 14 blocks microphone foreground services started from the background | — |
| 13 | The earbud media button is not taken over | It would break play/pause for music | — |
| 14 | Location triggers are skipped in v2 | Needs background location; low value against its privacy cost | — |
| 15 | The user is addressed as "Boss" | Spec | Panel → General |
| 16 | Raw notification text is kept 7 days, then wiped; the diagnostics log keeps the last 600 entries | Spec / storage | — |
| 17 | Removed always-on "Hey Nexus"; listening is tap / volume up + down, with a 6 s follow-up window | User preference; the always-on listener caused flicker and cut-off speech | Settings → Listening |
| 18 | Volume combo passes the first key through and restores the volume, instead of swallowing volume keys | Swallowing would break camera shutter and in-call volume | Settings → Listening |
| 19 | Smart brain (Qwen 2.5 1.5B) recommended for answering; Fast (0.5B) is still offered | 0.5B is too weak for real answers; 8 GB RAM handles 1.5B | Settings → Brain |
| 20 | Streaming answers via MediaPipe's async API looked up by reflection, falling back to one-shot generation | Speech starts after the first sentence; reflection means an API difference can't break the build | — |
| 21 | Scheduled messages run without a second "Send?" confirmation; behind a PIN lock, a notification asks you to unlock first | You confirmed when you scheduled it; NEXUS can't operate apps behind a secure lock | — |
| 22 | Trailing "… in 10 minutes" is treated as message text, not a delay, for messages | "Tell Rahul I'll be there in 10 minutes" must send now | Say "after 10 minutes, message …" to delay |
