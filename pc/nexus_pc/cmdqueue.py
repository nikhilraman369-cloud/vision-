"""
The command queue: say or type many commands at once and they run one after another, in order, on one worker thread.

    q = CommandQueue(Executor()); q.start()
    q.submit_text("open chrome, go to youtube and play believer")
    q.cancel()    # "stop" / "cancel" / "ruko": drops everything still waiting and interrupts the current wait

Each submit is a batch. If a step that later steps depend on fails (the app didn't open), the rest of that batch is
dropped instead of typing into the wrong window. Risky steps (shutdown, restart, sleep, empty recycle bin) ask first.
"""
from __future__ import annotations

import itertools
import queue
import re
import threading
import time
from dataclasses import dataclass
from typing import Callable

from .actions import Action
from .executor import NEEDS_CONFIRM, Executor, Result
from .grammar import understand, normalise

_CANCEL = re.compile(r"^(?:stop|cancel|cancel (?:that|it|all|everything)|stop (?:it|that|all|everything)|never ?mind|abort|"
                     r"ruko|ruk jao|rehne do|rahne do|chhodo|chodo|bas|band karo sab|sab band karo|stop nexus)$", re.I)
# A failure here means the following steps would land in the wrong place.
_GATES = {"open_app", "open_url", "folder"}
_GATE_WINDOW = {"switch"}


@dataclass
class Job:
    action: Action
    batch: int
    source: str = ""
    id: int = 0
    gen: int = 0
    result: Result | None = None
    started: float = 0.0
    ended: float = 0.0


@dataclass
class Event:
    kind: str           # queued | start | done | failed | skipped | cancelled | idle
    job: Job | None = None
    text: str = ""


def restore_case(raw: str, actions: list[Action]) -> list[Action]:
    """Typed text keeps what you actually wrote ("Hello World", not "hello world") when it can be found in the raw text."""
    out = []
    for a in actions:
        if a.kind == "type" and a.target:
            i = raw.lower().find(a.target)
            if i >= 0: a = Action(a.kind, raw[i:i + len(a.target)], a.arg, a.n)
        out.append(a)
    return out


def is_cancel(text: str) -> bool:
    return bool(_CANCEL.match(normalise(text) or text.strip().lower()))


class CommandQueue:
    def __init__(self, executor: Executor | None = None, on_event: Callable[[Event], None] | None = None,
                 confirm: Callable[[Action], bool] | None = None):
        self.cancel_event = threading.Event()
        self.executor = executor or Executor(self.cancel_event)
        self.executor.cancel = self.cancel_event
        self.on_event = on_event or (lambda e: None)
        self.confirm = confirm          # None → risky steps are refused
        self._q: queue.Queue[Job | None] = queue.Queue()
        self._ids = itertools.count(1)
        self._batches = itertools.count(1)
        self._dead_batches: set[int] = set()
        self._generation = 0
        self._busy = threading.Event()
        self._idle = threading.Event(); self._idle.set()
        self._thread: threading.Thread | None = None
        self.current: Job | None = None
        self.history: list[Job] = []

    # ------------------------------------------------------------------ public
    def start(self) -> "CommandQueue":
        if not self._thread:
            self._thread = threading.Thread(target=self._loop, name="nexus-queue", daemon=True)
            self._thread.start()
        return self

    def stop(self) -> None:
        self.cancel(quiet=True)
        self._q.put(None)
        if self._thread: self._thread.join(5)
        self._thread = None
        self.cancel_event.clear()      # the executor may be used again directly

    def submit(self, actions: list[Action], source: str = "") -> int:
        """Queue actions as one batch. Returns the batch id."""
        b = next(self._batches)
        if actions: self._idle.clear()
        for a in actions:
            j = Job(a, b, source, next(self._ids), self._generation)
            self._q.put(j)
            self._emit(Event("queued", j))
        return b

    def submit_text(self, text: str) -> int | None:
        """Understand and queue what was said/typed. "stop"/"cancel" clears the queue instead."""
        if is_cancel(text):
            self.cancel(); return None
        return self.submit(restore_case(text, understand(text)), text)

    def cancel(self, quiet: bool = False) -> int:
        """Drop everything waiting and interrupt the current step's waits. Returns how many were dropped."""
        self._generation += 1
        self.cancel_event.set()
        dropped = 0
        try:
            while True:
                j = self._q.get_nowait()
                if j is None: self._q.put(None); break
                dropped += 1
                self._emit(Event("cancelled", j))
        except queue.Empty:
            pass
        if not self.current: self._idle.set()
        if not quiet: self._emit(Event("cancelled", None, f"cancelled {dropped} waiting"))
        return dropped

    def pending(self) -> int: return self._q.qsize()
    def busy(self) -> bool: return not self._idle.is_set()

    def wait_idle(self, timeout: float | None = None) -> bool:
        return self._idle.wait(timeout)

    # ------------------------------------------------------------------ worker
    def _emit(self, e: Event) -> None:
        try:
            self.on_event(e)
        except Exception:
            pass

    def _loop(self) -> None:
        import comtypes
        comtypes.CoInitialize()          # volume (Core Audio) and UI Automation are COM
        try:
            while True:
                j = self._q.get()
                if j is None: break
                self._run(j)
                if self._q.empty() and self.current is None:
                    self._idle.set(); self._emit(Event("idle"))
        finally:
            comtypes.CoUninitialize()

    def _run(self, j: Job) -> None:
        if j.gen != self._generation or j.batch in self._dead_batches:
            self._emit(Event("skipped" if j.batch in self._dead_batches else "cancelled", j)); return
        self.cancel_event.clear()
        a = j.action
        if (a.kind, a.target) in NEEDS_CONFIRM:
            ok = False
            if self.confirm:
                try: ok = bool(self.confirm(a))
                except Exception: ok = False
            if not ok:
                j.result = Result(False, f"not done: {a.target.replace('_', ' ')} needs your OK")
                self.history.append(j); self._emit(Event("failed", j)); return
        self.current = j
        j.started = time.time()
        self._emit(Event("start", j))
        j.result = self.executor.run(a)
        j.ended = time.time()
        self.current = None
        self.history.append(j)
        self.history = self.history[-200:]
        if j.result.ok:
            self._emit(Event("done", j))
        else:
            self._emit(Event("failed", j))
            if a.kind in _GATES or (a.kind == "window" and a.target in _GATE_WINDOW):
                self._dead_batches.add(j.batch)
