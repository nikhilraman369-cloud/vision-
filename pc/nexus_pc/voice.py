"""
Offline, streaming speech (Vosk) → act while you talk.

Partial words go to EarlyTrigger as you speak, so "open notepad" fires before you finish the sentence; each final
sentence queues whatever is left. Everything runs on an audio clock, so a WAV file behaves exactly like the mic
(that's how it's tested).

    v = Voice(queue); v.load()            # ~10-30 s for the large model, once
    v.listen(stop_event)                  # one session: ends after you stop talking
"""
from __future__ import annotations

import json
import os
import queue as _queue
import re
import threading
import wave
from pathlib import Path
from typing import Callable, Iterable

from . import catalog
from .cmdqueue import CommandQueue, is_cancel
from .fast import EarlyTrigger
from .grammar import _VERBS_START, understand
from .winsys import DATA_DIR

RATE = 16000
CHUNK_S = 0.05

_UNITS = {"zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9,
          "ten": 10, "eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15, "sixteen": 16,
          "seventeen": 17, "eighteen": 18, "nineteen": 19}
_TENS = {"twenty": 20, "thirty": 30, "forty": 40, "fifty": 50, "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90}
_NUMWORD = r"(?:(?:%s)(?:[\s-](?:%s))?|(?:%s)|hundred|one hundred)" % ("|".join(_TENS), "|".join(list(_UNITS)[1:10]), "|".join(_UNITS))
_BEFORE = re.compile(rf"\b(volume|sound|brightness|to|at|tab|number|wait|by)\s+({_NUMWORD})\b")
_AFTER = re.compile(rf"\b({_NUMWORD})\s+(percent|seconds?|secs?|minutes?|mins?)\b")

# Things the recogniser commonly hears instead of what was said.
SPEECH_FIXES = {"you tube": "youtube", "note pad": "notepad", "g mail": "gmail", "what's app": "whatsapp", "whats app": "whatsapp",
                "colo": "kholo", "cholo": "kholo", "kolo": "kholo", "chalo do": "chala do", "vs court": "vs code",
                "screen shot": "screenshot", "google crome": "google chrome", "crome": "chrome", "un mute": "unmute"}


# A whole utterance that is (almost always) a mishearing of a one-word command.
WHOLE_FIXES = {"cancer": "cancel", "council": "cancel", "can sell": "cancel", "pass": "pause", "paws": "pause", "pose": "pause",
               "pours": "pause", "boss": "pause", "paas": "pause", "neck": "next", "nets": "next", "mute it": "mute",
               "news": "mute", "skip it": "skip", "stop it": "stop"}
_LEAD_NOISE = re.compile(r"^(?:(?:an|and|a|uh|um|umm|ah|oh|huh|hmm|the|to|so|you|okay so)\s+)+", re.I)


def _val(words: str) -> int:
    w = words.replace("-", " ").split()
    if w[-1] == "hundred": return 100
    n = 0
    for x in w: n += _TENS.get(x, 0) + _UNITS.get(x, 0)
    return n


def clean(text: str) -> str:
    """Recogniser text → what the grammar expects: fixes common mishearings, 'volume forty' → 'volume 40'."""
    t = f" {text.lower().strip()} "
    for a, b in SPEECH_FIXES.items(): t = t.replace(f" {a} ", f" {b} ")
    t = _BEFORE.sub(lambda m: f"{m.group(1)} {_val(m.group(2))}", t)
    t = _AFTER.sub(lambda m: f"{_val(m.group(1))} {m.group(2)}", t)
    t = " ".join(t.split())
    t = re.sub(r"^(?:said|such|search|sat|sit)\s+(volume|brightness)\b", r"set \1", t)
    # "click" heard as quit/quick/kick before a position: "quit the second video" → "click the second video"
    t = re.sub(r"\b(?:quit|quick|kick|lick|clique|clip)\s+(?:on\s+)?(the\s+)?(?=(?:first|second|third|fourth|fifth|last|next|top)\b)",
               lambda m: "click " + (m.group(1) or ""), t)
    rest = _LEAD_NOISE.sub("", t)
    if rest != t and _VERBS_START.match(rest): t = rest          # "an open notepad" → "open notepad"
    return WHOLE_FIXES.get(t, t)


_NOISE_WORDS = {"yeah", "yes", "yep", "no", "nope", "ok", "okay", "huh", "hmm", "hm", "mm", "mhm", "the", "a", "an", "and", "uh",
                "um", "umm", "ah", "oh", "hey", "hi", "hello", "so", "but", "i", "it", "is", "that", "this", "what", "haan", "han",
                "achha", "acha", "ha", "right", "well", "you", "to", "of", "in"}


def is_noise(text: str) -> bool:
    """Background talk / a cough / "yeah": nothing to do (never let it click or open things)."""
    words = text.lower().split()
    return not words or all(w in _NOISE_WORDS for w in words)


def _fit(text: str) -> int:
    """2 = every part is a known command, 1 = understood but names an unknown app, 0 = not a command."""
    if is_cancel(text): return 2
    acts = understand(text) if text else []
    if not acts or any(a.kind == "screen" for a in acts): return 0
    if any(a.kind == "open_app" and not catalog.canonical(a.target) for a in acts): return 1
    return 2


def choose(alternatives: list[str]) -> str:
    """Of the recogniser's best guesses (best first), the first that fits NEXUS's commands best; else the best guess."""
    cleaned = [clean(a) for a in alternatives if a and a.strip()]
    if not cleaned: return ""
    fits = [_fit(c) for c in cleaned]
    best = max(fits)
    return cleaned[fits.index(best)] if best else cleaned[0]


def _final_text(result_json: str) -> str:
    r = json.loads(result_json)
    if "alternatives" in r: return choose([a.get("text", "") for a in r["alternatives"]])
    return clean(r.get("text", ""))


def find_model() -> Path | None:
    env = os.environ.get("NEXUS_VOSK_MODEL")
    if env and Path(env).exists(): return Path(env)
    d = DATA_DIR / "models"
    if not d.exists(): return None
    models = [p for p in d.iterdir() if p.is_dir() and p.name.startswith("vosk-model")]
    # prefer Indian English, then the biggest
    models.sort(key=lambda p: ("en-in" in p.name, "small" not in p.name), reverse=True)
    return models[0] if models else None


class Voice:
    def __init__(self, q: CommandQueue | None, on_text: Callable[[str, bool], None] | None = None,
                 model_path: str | Path | None = None, pause_s: float = 0.3, follow_up_s: float = 2.5,
                 first_word_timeout_s: float = 6.0, max_s: float = 30.0):
        self.q = q
        self.on_text = on_text or (lambda text, final: None)
        self.model_path = Path(model_path) if model_path else find_model()
        self.pause_s, self.follow_up_s, self.first_word_timeout_s, self.max_s = pause_s, follow_up_s, first_word_timeout_s, max_s
        self.model = None
        self.trigger = EarlyTrigger()
        self.log: list[tuple[float, str, object]] = []   # (audio time, what, detail): for tests and the status bubble
        self._ready = threading.Event()

    # ------------------------------------------------------------------ model
    def load(self) -> "Voice":
        if self.model is None:
            if not self.model_path or not Path(self.model_path).exists():
                raise FileNotFoundError(f"no Vosk model; put one in {DATA_DIR / 'models'} or set NEXUS_VOSK_MODEL")
            import vosk
            vosk.SetLogLevel(-1)
            self.model = vosk.Model(str(self.model_path))
        self._ready.set()
        return self

    @property
    def ready(self) -> bool: return self._ready.is_set()

    # ------------------------------------------------------------------ sessions
    def listen(self, stop: threading.Event | None = None, device=None) -> list[str]:
        """One session from the microphone. Returns the final sentences heard."""
        import sounddevice as sd
        stop = stop or threading.Event()
        buf: _queue.Queue[bytes] = _queue.Queue()

        def cb(data, frames, t, status): buf.put(bytes(data))

        def chunks():
            while not stop.is_set():
                try: yield buf.get(timeout=0.5)
                except _queue.Empty: continue
        with sd.RawInputStream(samplerate=RATE, blocksize=int(RATE * CHUNK_S), dtype="int16", channels=1, callback=cb, device=device):
            return self.process(chunks(), stop)

    def listen_file(self, path: str | Path, tail_silence_s: float = 3.0) -> list[str]:
        """Same as listen(), from a 16 kHz mono 16-bit WAV (then silence, like someone who stopped talking)."""
        def chunks():
            with wave.open(str(path), "rb") as w:
                if w.getframerate() != RATE or w.getnchannels() != 1 or w.getsampwidth() != 2:
                    raise ValueError("need 16 kHz mono 16-bit WAV")
                n = int(RATE * CHUNK_S)
                while True:
                    d = w.readframes(n)
                    if not d: break
                    yield d
            for _ in range(int(tail_silence_s / CHUNK_S)): yield b"\0\0" * int(RATE * CHUNK_S)
        return self.process(chunks())

    def process(self, chunks: Iterable[bytes], stop: threading.Event | None = None) -> list[str]:
        import vosk
        self.load()
        rec = vosk.KaldiRecognizer(self.model, RATE)
        rec.SetMaxAlternatives(6)
        self.trigger.reset()
        self.log = []
        finals: list[str] = []
        t = 0.0
        last_partial, last_change, paused, heard, last_final = "", 0.0, False, False, None
        for data in chunks:
            if stop is not None and stop.is_set(): break
            t += len(data) / 2 / RATE
            if rec.AcceptWaveform(data):
                text = _final_text(rec.Result())
                if text:
                    finals.append(text); self._final(t, text); heard = True; last_final = t
                last_partial, paused = "", True
            else:
                p = clean(json.loads(rec.PartialResult()).get("partial", ""))
                if p != last_partial:
                    last_partial, last_change, paused = p, t, False
                    if p:
                        heard = True
                        self.on_text(p, False)
                        self._early(t, p, self.trigger.on_partial(p), "partial")
                elif p and not paused and t - last_change >= self.pause_s:
                    paused = True
                    self._early(t, p, self.trigger.on_pause(), "pause")
            if not heard and t > self.first_word_timeout_s: break
            if last_final is not None and not last_partial and t - last_final > self.follow_up_s: break
            if t > self.max_s: break
        text = _final_text(rec.FinalResult())
        if text:
            finals.append(text); self._final(t, text)
        self.log.append((t, "end", None))
        return finals

    # ------------------------------------------------------------------ hand-off to the queue
    def _early(self, t: float, partial: str, actions, why: str) -> None:
        if actions is None: return
        if not isinstance(actions, list): actions = [actions]
        if not actions: return
        self.log.append((t, "early", (partial, actions, why)))
        if self.q: self.q.submit(actions, f"early: {partial}")

    def _final(self, t: float, text: str) -> None:
        if is_noise(text):
            self.log.append((t, "noise", text)); return
        self.on_text(text, True)
        if is_cancel(text):
            self.trigger.reset()
            self.log.append((t, "cancel", text))
            if self.q: self.q.cancel()
            return
        rest = self.trigger.on_final(text)
        self.log.append((t, "final", (text, rest)))
        if self.q and rest: self.q.submit(rest, text)
