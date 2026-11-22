# NEXUS for PC (Windows)

Lightning-fast control of a Windows PC by voice or text, in English, Hindi/Hinglish and casual speech.

## Status
- **Done, tested here:** the understanding engine (`nexus_pc/grammar.py`) and the act-while-you-talk trigger (`nexus_pc/fast.py`).
  `python -m unittest discover -s tests -t .` · try it: `python -m nexus_pc "open chrome, go to youtube and play believer"`
- **Next, built and tested on the PC itself:** the Windows executor (apps, Chrome, YouTube/YouTube Music, keyboard, mouse,
  any button by name via Windows UI Automation, volume, brightness, windows, tabs), streaming offline speech (Vosk), the
  command queue (many commands at once), and a minimal always-on-top white dot to tap.

## Design
`speech (partials) → EarlyTrigger → Action → Executor (Windows)`; the final sentence runs the rest in order.
Unknown phrases become a `screen` action: matched against the names of buttons/links on screen at run time.
