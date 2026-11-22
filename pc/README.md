# NEXUS for PC (Windows)

Lightning-fast control of a Windows PC by voice or text, in English, Hindi/Hinglish and casual speech.

## Run it
```
pip install -r requirements.txt
python -m nexus_pc                      # the white dot
```
- **Tap the dot** (or **Ctrl+Alt+Space** anywhere) and talk. It stops listening on its own when you stop talking; tap again to stop early.
- **Right-click the dot:** type a command · stop everything · quit. **Drag it** anywhere (the spot is remembered).
- The dot never takes the focus, so what you say goes to the app you're using.
- Console: `python -m nexus_pc --dry "youtube kholo aur tum hi ho chalao"` (understand only), `--run "…"` (do it now),
  `--listen` (one voice command), `--wav file.wav` (from a 16 kHz mono WAV).

**Offline speech** needs a Vosk model in `%LOCALAPPDATA%\NEXUS\models\` (or set `NEXUS_VOSK_MODEL`). Tested with
[`vosk-model-en-in-0.5`](https://alphacephei.com/vosk/models) (Indian English, ~1 GB, loads in ~7 s);
`vosk-model-small-en-in-0.4` (36 MB) also works, less accurately. Without a model, tapping the dot opens the type box.

## What it does
| Say | Done by |
|---|---|
| open notepad / chrome / any installed app | App Paths, Start menu shortcuts, Store apps (`Get-StartApps`) |
| open youtube / gmail / github.com | Chrome (the current tab if you're in Chrome, else a new one) |
| play believer · youtube pe tum hi ho chalao | YouTube's search API → the video/song plays directly (YouTube or YouTube Music) |
| search cats on youtube / amazon / google | the site's search page |
| pause · next song · skip 10 seconds | media keys; pause/play check the speakers first, so "pause" never resumes |
| volume 40 · volume badhao · mute | Windows Core Audio |
| brightness 70 | WMI (laptop screens) |
| type … · press ctrl c · select all | SendInput, Unicode (Hindi, emoji) |
| click sign in · click the second video · double click readme | Windows UI Automation: buttons/links/list items by name or position, also when misheard ("signing") |
| scroll down 3 times · go to the top | mouse wheel on the window in front |
| new tab · close tab · go back · refresh | browser shortcuts |
| minimize · maximize · close notepad · switch to chrome · show desktop | window management |
| take a screenshot | `Pictures\Screenshots\NEXUS ….png` |
| shut down · restart · sleep · empty recycle bin | **only after you tap Yes** |
| stop · cancel · ruko · rehne do | drops everything still queued, interrupts waits |

Unknown phrases are matched against what's on screen; an app is opened for one only on an exact name match.

## Design
```
mic ─ Vosk partials ─► EarlyTrigger ─► CommandQueue ─► Executor ─► Windows
            └─ final sentence (best of 6 guesses that parses) ┘
```
- `grammar.py` understanding (pure Python) · `fast.py` act while you talk: a finished, safe command fires before the sentence ends,
  and never ahead of an earlier step · `voice.py` streaming Vosk, mishearing fixes, noise filter ("yeah", "huh" do nothing)
- `cmdqueue.py` one worker thread, in order; a failed open drops the rest of that batch (so nothing is typed into the wrong window)
- `executor.py` each step waits until it really happened (window in front, page loaded, button present) · `winput.py` SendInput ·
  `winsys.py` windows, apps, sound, brightness · `screen.py` UI Automation (one cached call per search) · `dot.py` the white dot

## Tests
- `python -m unittest discover -s tests -t .`: offline (understanding, early trigger, queue, speech clean-up)
- `set NEXUS_LIVE=1` then `python -m unittest tests.test_live -v`: end to end on the PC. Uses a test window of its own
  (`tests/pad_app.py`), a local test page in a new Chrome tab, Calculator, real YouTube playback at 10% volume; puts volume and
  brightness back. Voice tests use Windows' built-in offline voice. Never runs shutdown/restart/sleep/lock/empty bin.

## Known limits
- Hindi words spoken in Hindi are recognised by an English model: common ones are fixed ("colo" → "kholo"); typed Hinglish is fully understood.
- Qt apps mis-place emoji typed via SendInput (Chrome, Office and Notepad are fine).
- Brightness works on built-in laptop screens, not external monitors.
