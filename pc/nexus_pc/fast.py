"""
Act while you're still talking.

Speech recognisers send partial text as you speak ("open", "open you", "open youtube"…). The moment the words already
make one complete, safe command ("open notepad", "pause", "volume up"), it fires: no waiting for you to finish.
If the name could still grow ("youtube" → "youtube music"), it waits for a short pause first.
The final text then only runs what wasn't already done ("open notepad and type hi" → notepad opened early, then types).
"""
from __future__ import annotations

import re

from .actions import Action
from .grammar import understand, split_commands
from . import catalog

_INSTANT_KINDS = {"open_app", "open_url", "media", "volume", "tab", "window", "folder"}
_SAFE_SYSTEM = {"screenshot", "task_manager"}
_TRAILING_JOIN = re.compile(r"[\s,]+(?:and then|and|then|after that|aur|phir|fir)$", re.I)


class EarlyTrigger:
    def __init__(self) -> None:
        self.fired: list[Action] = []
        self._waiting: Action | None = None

    def reset(self) -> None:
        self.fired.clear()
        self._waiting = None

    @staticmethod
    def _instant(clause: str, finished: bool) -> tuple[Action | None, bool]:
        """(action, needs_pause) if [clause] is one complete, safe command. [finished]: words followed it ("… and")."""
        acts = understand(clause)
        if len(acts) != 1: return None, False
        a = acts[0]
        ok = a.kind in _INSTANT_KINDS or (a.kind == "system" and a.target in _SAFE_SYSTEM)
        if not ok: return None, False
        if a.kind in ("open_app", "open_url"):
            if not catalog.canonical(a.target): return None, False          # unknown name: maybe still being said
            return a, not finished and catalog.is_prefix_of_longer_name(a.target)
        if a.kind == "window" and a.target == "close" and not a.arg and not finished: return None, False  # could be "close tab"
        return a, False

    def on_partial(self, partial: str) -> list[Action]:
        """Call with every partial result. Returns the actions to run right now (usually none or one), in order.

        Clauses already followed by "and/then/aur" are complete and fire at once; only a leading run of safe, instant
        clauses fires, so nothing ever runs ahead of an earlier step ("type hi and open notepad" waits for the end).
        """
        text = partial.strip()
        trailing = _TRAILING_JOIN.search(text)
        if trailing: text = text[:trailing.start()]
        clauses = split_commands(text)
        out: list[Action] = []
        self._waiting = None
        for i, c in enumerate(clauses):
            finished = i < len(clauses) - 1 or bool(trailing)
            a, needs_pause = self._instant(c, finished)
            if a is None: break
            if a in self.fired: continue
            if needs_pause: self._waiting = a; break
            self.fired.append(a); out.append(a)
        return out

    def on_pause(self) -> Action | None:
        """Call when the speaker paused (~250 ms of silence): fires a command that was only waiting for that."""
        a, self._waiting = self._waiting, None
        if a is None or a in self.fired: return None
        self.fired.append(a)
        return a

    def on_final(self, text: str) -> list[Action]:
        """The full sentence: everything still to do, in order (skipping what already ran early)."""
        remaining = list(self.fired)
        out: list[Action] = []
        for a in understand(text):
            if a in remaining: remaining.remove(a); continue
            out.append(a)
        self.reset()
        return out
