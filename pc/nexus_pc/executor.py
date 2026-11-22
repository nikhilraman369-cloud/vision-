"""
Runs Actions on Windows.  Executor().run(Action("open_app", "notepad")) → Result(ok=True, msg="opened notepad").

Each step waits until it has really happened (the app's window is in front, the page has a new title, the button
exists) so the next step in a queue lands in the right place. Waits are short and cancellable.
"""
from __future__ import annotations

import ctypes
import json
import os
import re
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from . import catalog, screen, winput, winsys
from .actions import Action


@dataclass
class Result:
    ok: bool
    msg: str = ""

    def __str__(self): return ("ok: " if self.ok else "failed: ") + self.msg


# Need a yes from the user first (a misheard "sleep" must not put the PC to sleep).
NEEDS_CONFIRM = {("system", "shutdown"), ("system", "restart"), ("system", "sleep"), ("system", "empty_bin")}

SEARCH_URLS = {
    "google": "https://www.google.com/search?q={}", "youtube": "https://www.youtube.com/results?search_query={}",
    "youtube music": "https://music.youtube.com/search?q={}", "ytmusic": "https://music.youtube.com/search?q={}",
    "amazon": "https://www.amazon.in/s?k={}", "flipkart": "https://www.flipkart.com/search?q={}",
    "wikipedia": "https://en.wikipedia.org/w/index.php?search={}", "github": "https://github.com/search?q={}",
    "instagram": "https://www.instagram.com/explore/search/keyword/?q={}", "twitter": "https://x.com/search?q={}",
    "chatgpt": "https://chatgpt.com/?q={}", "maps": "https://www.google.com/maps/search/{}",
    "google maps": "https://www.google.com/maps/search/{}", "spotify": "https://open.spotify.com/search/{}",
}

_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"


def resolve_video_id(query: str, music: bool, timeout: float = 5) -> str | None:
    """The best video (or song, for YouTube Music) for [query], via YouTube's own search API. None on any problem."""
    def post(url, body, origin):
        req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "User-Agent": _UA, "Origin": origin})
        with urllib.request.urlopen(req, timeout=timeout) as r: return r.read().decode("utf-8", "replace")
    try:
        if music:
            body = post("https://music.youtube.com/youtubei/v1/search?prettyPrint=false",
                        {"context": {"client": {"clientName": "WEB_REMIX", "clientVersion": "1.20240101.01.00", "hl": "en", "gl": "IN"}},
                         "query": query, "params": "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"}, "https://music.youtube.com")
            m = re.search(r'"videoId":"([A-Za-z0-9_-]{11})"', body)
            if m: return m.group(1)
        body = post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false",
                    {"context": {"client": {"clientName": "WEB", "clientVersion": "2.20240101.00.00", "hl": "en", "gl": "IN"}}, "query": query},
                    "https://www.youtube.com")
        m = re.search(r'"videoRenderer":\{"videoId":"([A-Za-z0-9_-]{11})"', body)
        return m.group(1) if m else None
    except Exception:
        return None


class Executor:
    def __init__(self, cancel: threading.Event | None = None, label_timeout: float = 6.0):
        self.cancel = cancel or threading.Event()
        self.label_timeout = label_timeout

    # ------------------------------------------------------------------ helpers
    def _sleep(self, s: float) -> bool:
        """Cancellable sleep. False if cancelled."""
        return not self.cancel.wait(s)

    def _wait(self, cond, timeout: float, step: float = 0.08) -> bool:
        end = time.time() + timeout
        while time.time() < end:
            if self.cancel.is_set(): return False
            try:
                if cond(): return True
            except Exception:
                pass
            time.sleep(step)
        return False

    def _fg(self): return winsys.foreground()

    def _in_browser(self) -> bool:
        w = self._fg()
        return bool(w and w.exe in winsys.BROWSERS)

    def _front_new_browser(self, known: dict[int, str]) -> bool:
        """After starting the browser: true once it's in front (it may open behind other windows: bring it forward)."""
        if self._in_browser(): return True
        for w in winsys.windows():
            if w.exe in winsys.BROWSERS and (w.hwnd not in known or known[w.hwnd] != w.title):
                winsys.activate(w.hwnd)
                return self._in_browser()
        return False

    def _ensure_browser(self) -> bool:
        if self._in_browser(): return True
        ws = [w for w in winsys.windows() if w.exe in winsys.BROWSERS]
        if ws: return winsys.activate(ws[0].hwnd)
        winsys.open_in_browser("about:blank")
        return self._wait(lambda: self._front_new_browser({}), 10)

    def _goto(self, url: str) -> None:
        """Open [url]: in the current browser tab when you're in a browser (a fresh tab or a site you just opened), else a new tab."""
        before = self._fg()
        if before and before.exe in winsys.BROWSERS:
            winput.press("ctrl+l"); time.sleep(0.06)
            winput.type_text(url); time.sleep(0.03)
            winput.press("enter")
        else:
            known = {w.hwnd: w.title for w in winsys.windows() if w.exe in winsys.BROWSERS}
            winsys.open_in_browser(url)
            self._wait(lambda: self._front_new_browser(known), 10)
        old = before.title if before else ""
        self._wait(lambda: (w := self._fg()) is not None and w.exe in winsys.BROWSERS and w.title != old, 6)

    # ------------------------------------------------------------------ dispatch
    def run(self, a: Action) -> Result:
        fn = getattr(self, "do_" + a.kind, None)
        if fn is None: return Result(False, f"I don't know how to {a.kind} yet")
        try:
            return fn(a)
        except LookupError as e:
            return Result(False, f"couldn't find {e.args[0] if e.args else a.target!r}")
        except Exception as e:  # never crash the queue
            return Result(False, f"{type(e).__name__}: {e}")

    # ------------------------------------------------------------------ apps and sites
    def do_open_app(self, a: Action, exact: bool = False) -> Result:
        name = a.target
        if name in catalog.SITES and name not in catalog.APPS: return self.do_open_url(a)
        if name == "task manager": return self.do_system(Action("system", "task_manager"))
        before = self._fg()
        already = winsys.matches_app(before, name)
        how = winsys.launch(name, catalog.APPS.get(name), exact=exact)
        def ready():   # the app's window has come to the front
            w = self._fg()
            return winsys.matches_app(w, name) and (already or not before or w.hwnd != before.hwnd)
        if not self._wait(ready, 1.0 if already else 10):
            # Some apps open behind or reuse a window: bring the newest one forward.
            ws = winsys.find_windows(name)
            if ws: winsys.activate(ws[0].hwnd)
            elif not already: return Result(True, f"started {name} (window not seen yet)")
        self._sleep(0.25)
        return Result(True, f"opened {name}" + ("" if how.startswith(("uri", "start")) or how.endswith(".exe") else f" ({Path(how).name})"))

    def do_open_url(self, a: Action) -> Result:
        t = a.target
        url = catalog.SITES.get(t) or (t if re.match(r"^[a-z][a-z0-9+.-]*:", t, re.I) else "https://" + t)
        self._goto(url)
        return Result(True, f"opened {url}")

    def do_search(self, a: Action) -> Result:
        where = (a.arg or "google").lower()
        tpl = SEARCH_URLS.get(where, SEARCH_URLS["google"])
        if where == "spotify" and winsys.find_windows("spotify"):
            os.startfile("spotify:search:" + urllib.parse.quote(a.target)); return Result(True, f"searched spotify for {a.target}")
        q = urllib.parse.quote_plus(a.target) if "=" in tpl else urllib.parse.quote(a.target)   # query string vs path
        self._goto(tpl.format(q))
        return Result(True, f"searched {where} for {a.target}")

    def do_play(self, a: Action) -> Result:
        where = a.arg or "ytmusic"
        if where == "spotify":
            os.startfile("spotify:search:" + urllib.parse.quote(a.target))
            return Result(True, f"searched spotify for {a.target} (press play)")
        music = where == "ytmusic"
        vid = resolve_video_id(a.target, music)
        if not vid:
            self._goto(SEARCH_URLS["ytmusic" if music else "youtube"].format(urllib.parse.quote_plus(a.target)))
            return Result(True, f"couldn't look it up, showing results for {a.target}")
        self._goto(("https://music.youtube.com/watch?v=" if music else "https://www.youtube.com/watch?v=") + vid)
        skipped = self._skip_ad()
        return Result(True, f"playing {a.target} on {'YouTube Music' if music else 'YouTube'}" + (" (skipped an ad)" if skipped else ""))

    def _skip_ad(self, timeout: float = 40.0) -> bool:
        """While YouTube shows ads before the song: press its own "Skip" button as soon as it appears (ads can come in
        twos). Returns once the song itself is playing. True if an ad was skipped."""
        fg = self._fg()
        if not fg or fg.exe not in winsys.BROWSERS: return False
        start = time.time()
        end, skipped, saw_ad, pressed_play = start + timeout, False, False, 0.0
        while time.time() < end and not self.cancel.is_set():
            els = [e for e in screen.elements(fg.hwnd) if e.visible and e.name]
            names = {e.name.strip().lower() for e in els}
            btn = next((e for e in els if e.type == screen.T["button"] and e.name.strip().lower() in ("skip", "skip ad", "skip ads")), None)
            if btn:
                self._click_at("click", *btn.center); skipped = saw_ad = True
                if not self._sleep(1.0): break
                continue
            # Chrome sometimes blocks autoplay: the player then shows its Play button ("Play (k)"; "Play" on YouTube Music)
            play = next((e for e in els if e.type == screen.T["button"] and re.match(r"^play(?: \(k\))?$", e.name.strip().lower())), None)
            if play and time.time() - pressed_play > 3 and time.time() - start > 1.5:
                self._click_at("click", *play.center); pressed_play = time.time()
                if not self._sleep(0.8): break
                continue
            if names & {"sponsored", "my ad center", "ad"}:          # an ad that can't be skipped yet
                saw_ad = True
                if not self._sleep(0.5): break
                continue
            if winsys.sound_playing(0.4): break
            if not saw_ad and time.time() - start > 6: break         # no ad and still silent: don't hold up the queue
        return skipped

    # ------------------------------------------------------------------ media and sound
    def do_media(self, a: Action) -> Result:
        t = a.target
        if t in ("pause", "play"):
            playing = winsys.sound_playing()
            if (t == "pause") == playing: winput.press("media_play_pause")
            return Result(True, ("paused" if t == "pause" else "playing") + ("" if (t == "pause") == playing else " (already)"))
        if t == "toggle": winput.press("media_play_pause"); return Result(True, "play/pause")
        if t == "next": winput.press("media_next"); return Result(True, "next")
        if t == "previous": winput.press("media_prev"); return Result(True, "previous")
        if t == "stop": winput.press("media_stop"); return Result(True, "stopped")
        if t in ("forward", "back"):
            if not self._in_browser(): return Result(False, "skipping works in the browser player")
            winput.press("right" if t == "forward" else "left", max(1, round(a.n / 5)))   # YouTube/most players: 5 s per arrow
            return Result(True, f"{t} {a.n}s")
        return Result(False, f"unknown media command {t}")

    def do_volume(self, a: Action) -> Result:
        t = a.target
        if t == "mute": winsys.set_mute(True); return Result(True, "muted")
        if t == "unmute": winsys.set_mute(False); return Result(True, "unmuted")
        if t == "set": winsys.set_mute(False); winsys.set_volume(a.n); return Result(True, f"volume {a.n}")
        v = winsys.get_volume() + (a.n if t == "up" else -a.n)
        if t == "up": winsys.set_mute(False)
        winsys.set_volume(v)
        return Result(True, f"volume {winsys.get_volume()}")

    # ------------------------------------------------------------------ keyboard
    def do_type(self, a: Action) -> Result:
        winput.type_text(a.target)
        return Result(True, f"typed {len(a.target)} characters")

    def do_keys(self, a: Action) -> Result:
        k = a.target
        winput.press(k, a.n)
        return Result(True, f"pressed {k}" + (f" ×{a.n}" if a.n > 1 else ""))

    # ------------------------------------------------------------------ mouse
    def do_scroll(self, a: Action) -> Result:
        t = a.target
        if t in ("top", "bottom"): winput.press("ctrl+home" if t == "top" else "ctrl+end"); return Result(True, f"scrolled to {t}")
        fg = self._fg()
        restore = None
        if fg:   # the wheel goes to the window under the mouse: make sure that's the app in front
            l, tp, r, b = winsys.rect(fg.hwnd)
            x, y = winput.cursor()
            if not (l <= x < r and tp <= y < b):
                restore = (x, y); winput.move((l + r) // 2, (tp + b) // 2)
        notches = 5 * a.n
        if t == "up": winput.wheel(notches)
        elif t == "down": winput.wheel(-notches)
        elif t == "right": winput.wheel(notches, horizontal=True)
        elif t == "left": winput.wheel(-notches, horizontal=True)
        if restore: winput.move(*restore)
        return Result(True, f"scrolled {t}" + (f" ×{a.n}" if a.n > 1 else ""))

    def _click_at(self, kind: str, x: int, y: int) -> None:
        back = winput.cursor()
        winput.click(x, y, "right" if kind == "right_click" else "left", double=kind == "double_click")
        if kind != "right_click":
            time.sleep(0.05); winput.move(*back)

    def do_click(self, a: Action, kind: str = "click") -> Result:
        if a.arg == "cursor":
            x, y = winput.cursor(); self._click_at(kind, x, y); return Result(True, f"{kind.replace('_', ' ')} at the mouse")
        fg = self._fg()
        if not fg: return Result(False, "no window in front")
        found = None
        end = time.time() + (self.label_timeout if a.target else 4)
        while True:   # always look at least once; then retry while the page loads
            els = screen.elements(fg.hwnd)
            found = screen.find_label(els, a.target) if a.target else screen.pick(screen.candidates(els, a.arg), a.n)
            if found or time.time() > end or not self._sleep(0.35): break
        what = a.target or f"{'last' if a.n == -1 else _ordinal(a.n)} {a.arg or 'item'}"
        if not found: return Result(False, f"couldn't see {what!r} on screen")
        pos = screen.bring_into_view(found)
        if pos: self._click_at(kind, *pos)
        elif kind != "click" or not screen.invoke(found): return Result(False, f"{what!r} is not clickable")
        return Result(True, f"clicked {found.name[:60]!r}")

    def do_double_click(self, a: Action) -> Result: return self.do_click(a, "double_click")
    def do_right_click(self, a: Action) -> Result: return self.do_click(a, "right_click")

    # ------------------------------------------------------------------ browser tabs
    _TAB_KEYS = {"new": "ctrl+t", "close": "ctrl+w", "next": "ctrl+tab", "previous": "ctrl+shift+tab", "reopen": "ctrl+shift+t",
                 "refresh": "f5", "back": "alt+left", "forward": "alt+right", "incognito": "ctrl+shift+n", "history": "ctrl+h",
                 "downloads": "ctrl+j", "bookmark": "ctrl+d"}

    def do_tab(self, a: Action) -> Result:
        t = a.target
        if t in ("new", "incognito", "history", "downloads", "reopen") and not self._ensure_browser():
            return Result(False, "couldn't open the browser")
        if t == "go":
            winput.press(f"ctrl+{min(a.n, 9)}" if a.n <= 8 else "ctrl+9"); return Result(True, f"tab {a.n}")
        k = self._TAB_KEYS.get(t)
        if not k: return Result(False, f"unknown tab command {t}")
        winput.press(k, a.n)
        return Result(True, f"tab {t}")

    # ------------------------------------------------------------------ windows
    def do_window(self, a: Action) -> Result:
        t, fg = a.target, self._fg()
        if t == "close":
            if a.arg:
                ws = winsys.find_windows(a.arg)
                if not ws: return Result(False, f"{a.arg} isn't open")
                for w in ws: winsys.close_window(w.hwnd)
                return Result(True, f"closed {a.arg}" + (f" ({len(ws)} windows)" if len(ws) > 1 else ""))
            if not fg: return Result(False, "no window in front")
            winsys.close_window(fg.hwnd); return Result(True, f"closed {fg.title[:50]!r}")
        if t == "switch":
            ws = winsys.find_windows(a.arg)
            if not ws: return self.do_open_app(Action("open_app", a.arg))
            winsys.activate(ws[0].hwnd); return Result(True, f"switched to {a.arg}")
        if t in ("minimize", "maximize", "restore"):
            if not fg: return Result(False, "no window in front")
            winsys.show(fg.hwnd, {"minimize": winsys.SW_MINIMIZE, "maximize": winsys.SW_MAXIMIZE, "restore": winsys.SW_RESTORE}[t])
            return Result(True, f"{t}d {fg.title[:40]!r}")
        keys = {"desktop": "win+d", "snap_left": "win+left", "snap_right": "win+right", "fullscreen": "f11", "task_view": "win+tab"}
        if t in keys: winput.press(keys[t]); return Result(True, t.replace("_", " "))
        return Result(False, f"unknown window command {t}")

    def do_folder(self, a: Action) -> Result:
        return Result(True, f"opened {winsys.open_folder(a.target)}")

    # ------------------------------------------------------------------ system
    def do_system(self, a: Action) -> Result:
        t = a.target
        if t == "lock": ctypes.windll.user32.LockWorkStation(); return Result(True, "locked")
        if t == "sleep": ctypes.windll.powrprof.SetSuspendState(0, 1, 0); return Result(True, "sleeping")
        if t == "shutdown": subprocess.Popen(["shutdown", "/s", "/t", "0"]); return Result(True, "shutting down")
        if t == "restart": subprocess.Popen(["shutdown", "/r", "/t", "0"]); return Result(True, "restarting")
        if t == "screenshot":
            from PIL import ImageGrab
            d = winsys.known_folder("pictures") / "Screenshots"
            d.mkdir(parents=True, exist_ok=True)
            p = d / f"NEXUS {datetime.now():%Y-%m-%d %H%M%S}.png"
            ImageGrab.grab(all_screens=True).save(p)
            return Result(True, f"saved {p}")
        if t == "task_manager": winput.press("ctrl+shift+esc"); return Result(True, "task manager")
        if t == "empty_bin":
            ctypes.windll.shell32.SHEmptyRecycleBinW(None, None, 0x1 | 0x2 | 0x4); return Result(True, "recycle bin emptied")
        if t.startswith("settings:"):
            os.startfile("ms-settings:" + t.split(":", 1)[1]); return Result(True, f"opened {t.split(':', 1)[1]} settings")
        if t in ("brightness", "brightness_up", "brightness_down"):
            lvl = a.n if t == "brightness" else winsys.get_brightness() + (a.n if t.endswith("up") else -a.n)
            winsys.set_brightness(lvl)
            return Result(True, f"brightness {max(0, min(100, lvl))}")
        return Result(False, f"unknown system command {t}")

    def do_dictate(self, a: Action) -> Result:
        winput.press("win+h"); return Result(True, "dictation " + a.target)

    def do_wait(self, a: Action) -> Result:
        return Result(self._sleep(a.n), f"waited {a.n}s")

    def do_screen(self, a: Action) -> Result:
        """Not a known command: a button/link with that name on screen, else an app with that name."""
        old = self.label_timeout
        self.label_timeout = 2.0
        try:
            r = self.do_click(Action("click", a.target))
        finally:
            self.label_timeout = old
        if r.ok: return r
        try:   # only an app with exactly this name: a stray phrase must never launch a "close enough" app
            return self.do_open_app(Action("open_app", a.target), exact=True)
        except LookupError:
            return Result(False, f"I couldn't find {a.target!r} on screen or as an app")


def _ordinal(n: int) -> str:
    return {1: "first", 2: "second", 3: "third"}.get(n, f"{n}th")
