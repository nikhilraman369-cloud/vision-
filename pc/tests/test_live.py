"""
End-to-end on a real Windows PC.  set NEXUS_LIVE=1 and run:  python -m unittest tests.test_live -v

Only touches things the test creates (a test window, a local test page in a new browser tab) and puts volume and
brightness back afterwards. Never runs shutdown/restart/sleep/lock/empty bin.
"""
import ctypes
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

LIVE = os.environ.get("NEXUS_LIVE") == "1"
HERE = Path(__file__).parent
user32 = ctypes.windll.user32 if LIVE else None
from nexus_pc.actions import Action as A

if LIVE:
    from nexus_pc import winsys, screen, winput
    from nexus_pc.cmdqueue import CommandQueue
    from nexus_pc.executor import Executor, resolve_video_id


def wait_for(cond, timeout=8.0, step=0.1):
    end = time.time() + timeout
    while time.time() < end:
        try:
            v = cond()
            if v: return v
        except Exception:
            pass
        time.sleep(step)
    return None


class Pad:
    """The test window and what it reports."""
    def __init__(self):
        fd, self.report = tempfile.mkstemp(suffix=".json"); os.close(fd)
        self.proc = subprocess.Popen([sys.executable, str(HERE / "pad_app.py"), self.report])
        self.win = wait_for(lambda: next((w for w in winsys.windows() if w.title == "NEXUS Test Pad"), None), 15)
        assert self.win, "test pad didn't open"
        self.front()

    def front(self):
        winsys.activate(self.win.hwnd)
        assert wait_for(lambda: winsys.foreground().hwnd == self.win.hwnd, 3), "couldn't bring the test pad forward"

    def state(self):
        return json.loads(Path(self.report).read_text(encoding="utf-8"))

    def close(self):
        self.proc.kill(); self.proc.wait(5)
        try: os.remove(self.report)
        except OSError: pass


@unittest.skipUnless(LIVE, "set NEXUS_LIVE=1 to run on this PC")
class Live(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ex = Executor()
        cls.pad = Pad()

    @classmethod
    def tearDownClass(cls):
        cls.pad.close()

    def setUp(self):
        self.pad.front()

    def run_ok(self, a, ex=None):
        r = (ex or self.ex).run(a)
        self.assertTrue(r.ok, f"{a}: {r}")
        return r

    def into_notes(self):
        """Put the keyboard in the text box (by its name, like "click notes"), and empty it."""
        self.run_ok(A("click", "notes")); self.run_ok(A("keys", "ctrl+a")); self.run_ok(A("keys", "delete"))
        self.assertTrue(wait_for(lambda: self.pad.state()["text"] == "", 2))

    # ---------------------------------------------------------------- keyboard
    def test_typing_unicode_and_hotkeys(self):
        self.into_notes()
        # (emoji are checked in Chrome: Qt's text box itself mis-places surrogate pairs)
        self.run_ok(A("type", "Hello from NEXUS — नमस्ते\nline two"))
        self.assertTrue(wait_for(lambda: self.pad.state()["text"] == "Hello from NEXUS — नमस्ते\nline two", 3), self.pad.state()["text"])
        self.run_ok(A("keys", "ctrl+a")); self.run_ok(A("type", "replaced"))
        self.assertTrue(wait_for(lambda: self.pad.state()["text"] == "replaced", 3))
        self.run_ok(A("keys", "backspace", n=3))
        self.assertTrue(wait_for(lambda: self.pad.state()["text"] == "repla", 3))

    # ---------------------------------------------------------------- UI Automation
    def test_click_a_button_by_name(self):
        r = self.run_ok(A("click", "subscribe"))
        self.assertTrue(wait_for(lambda: "Subscribe" in self.pad.state()["clicks"], 3), r)

    def test_click_survives_a_mishearing(self):
        self.run_ok(A("click", "signing"))       # Vosk often hears "sign in" as "signing"
        self.assertTrue(wait_for(lambda: "Sign in" in self.pad.state()["clicks"], 3))

    def test_double_click_and_ordinal(self):
        self.run_ok(A("double_click", "cherry"))
        self.assertTrue(wait_for(lambda: "Cherry" in self.pad.state()["double"], 3))
        self.run_ok(A("click", "", "", 2))       # "click the second one"
        self.assertTrue(wait_for(lambda: self.pad.state()["selected"] == "Banana", 3), self.pad.state())
        self.run_ok(A("click", "", "", -1))
        self.assertTrue(wait_for(lambda: self.pad.state()["selected"] == "Cherry", 3))

    def test_missing_label_fails_cleanly_and_fast(self):
        ex = Executor(label_timeout=1.0)
        t = time.time()
        r = ex.run(A("click", "launch the rockets"))
        self.assertFalse(r.ok); self.assertLess(time.time() - t, 3)

    def test_screen_fallback_finds_a_button(self):
        self.run_ok(A("screen", "cancel"))
        self.assertTrue(wait_for(lambda: "Cancel" in self.pad.state()["clicks"], 3))

    # ---------------------------------------------------------------- windows
    def test_minimize_maximize_restore_switch(self):
        h = self.pad.win.hwnd
        self.run_ok(A("window", "maximize")); self.assertTrue(wait_for(lambda: user32.IsZoomed(h), 2))
        self.run_ok(A("window", "restore")); self.assertTrue(wait_for(lambda: not user32.IsZoomed(h), 2))
        self.run_ok(A("window", "minimize")); self.assertTrue(wait_for(lambda: user32.IsIconic(h), 2))
        self.run_ok(A("window", "switch", "nexus test pad"))
        self.assertTrue(wait_for(lambda: winsys.foreground().hwnd == h and not user32.IsIconic(h), 3))

    # ---------------------------------------------------------------- real apps (ones that keep no documents)
    def test_open_and_close_calculator(self):
        if winsys.find_windows("calculator"): self.skipTest("calculator already open")
        r = self.run_ok(A("open_app", "calculator"))
        self.assertTrue(winsys.matches_app(winsys.foreground(), "calculator"), (r, winsys.foreground()))
        self.run_ok(A("window", "close", "calculator"))
        self.assertTrue(wait_for(lambda: not winsys.find_windows("calculator"), 5))

    def test_open_a_folder(self):
        before = {w.hwnd for w in winsys.windows() if w.exe == "explorer"}
        self.run_ok(A("folder", "downloads"))
        new = wait_for(lambda: [w for w in winsys.windows() if w.exe == "explorer" and w.hwnd not in before], 8)
        self.assertTrue(new, "no new Explorer window")
        for w in new: winsys.close_window(w.hwnd)        # only the window this test opened

    # ---------------------------------------------------------------- sound and screen
    def test_volume(self):
        before, muted = winsys.get_volume(), winsys.is_muted()
        try:
            self.run_ok(A("volume", "set", n=37)); self.assertEqual(37, winsys.get_volume())
            self.run_ok(A("volume", "up", n=10)); self.assertEqual(47, winsys.get_volume())
            self.run_ok(A("volume", "down", n=20)); self.assertEqual(27, winsys.get_volume())
            self.run_ok(A("volume", "mute")); self.assertTrue(winsys.is_muted())
            self.run_ok(A("volume", "unmute")); self.assertFalse(winsys.is_muted())
        finally:
            winsys.set_volume(before); winsys.set_mute(muted)

    def test_brightness(self):
        try:
            before = winsys.get_brightness()
        except Exception as e:
            self.skipTest(f"no software brightness on this display: {e}")
        try:
            target = 30 if before != 30 else 40
            self.run_ok(A("system", "brightness", n=target)); self.assertEqual(target, winsys.get_brightness())
            self.run_ok(A("system", "brightness_up", n=10)); self.assertEqual(target + 10, winsys.get_brightness())
        finally:
            winsys.set_brightness(before)

    def test_screenshot(self):
        r = self.run_ok(A("system", "screenshot"))
        p = Path(r.msg.removeprefix("saved "))
        try:
            self.assertTrue(p.exists() and p.stat().st_size > 10_000, r.msg)
        finally:
            p.unlink(missing_ok=True)

    # ---------------------------------------------------------------- the queue, for real
    def test_queue_runs_a_sentence_in_order(self):
        q = CommandQueue(Executor()).start()
        try:
            self.into_notes()
            q.submit_text("type Milk, eggs and bread and press enter, type Done then click subscribe")
            self.assertTrue(q.wait_idle(15))
            self.assertTrue(wait_for(lambda: self.pad.state()["text"] == "Milk, eggs and bread\nDone", 3), self.pad.state()["text"])
            self.assertIn("Subscribe", self.pad.state()["clicks"])
            self.assertTrue(all(j.result.ok for j in q.history), [str(j.result) for j in q.history])
        finally:
            q.stop()

    def test_queue_cancel_stops_before_typing(self):
        q = CommandQueue(Executor()).start()
        try:
            self.into_notes(); self.run_ok(A("type", "keep"))
            q.submit_text("wait 4 seconds then type NOPE")
            time.sleep(0.3)
            t = time.time(); q.submit_text("stop")
            self.assertTrue(q.wait_idle(2)); self.assertLess(time.time() - t, 1.0)
            time.sleep(0.3)
            self.assertEqual("keep", self.pad.state()["text"])
        finally:
            q.stop()

    def test_risky_command_is_refused_without_a_yes(self):
        q = CommandQueue(Executor(), confirm=None).start()
        try:
            q.submit_text("shut down the pc")
            self.assertTrue(q.wait_idle(3))
            self.assertIn("needs your OK", q.history[-1].result.msg)
        finally:
            q.stop()


@unittest.skipUnless(LIVE, "set NEXUS_LIVE=1 to run on this PC")
class LiveBrowser(unittest.TestCase):
    """A local test page in Chrome (a new tab that is closed at the end)."""

    def test_page_by_name_by_position_scroll_and_tabs(self):
        ex = Executor()
        url = (HERE / "page.html").resolve().as_uri()
        r = ex.run(A("open_url", url)); self.assertTrue(r.ok, r)
        w = wait_for(lambda: (f := winsys.foreground()) and f.exe in winsys.BROWSERS and "NEXUS test page" in f.title and f, 10)
        self.assertTrue(w, f"page didn't open: {winsys.foreground()}")
        try:
            r = ex.run(A("click", "get started")); self.assertTrue(r.ok, r)
            self.assertTrue(wait_for(lambda: "clicked: get started" in winsys.foreground().title, 4), winsys.foreground().title)
            r = ex.run(A("click", "message")); self.assertTrue(r.ok, r)
            ex.run(A("type", "Hi 👋 नमस्ते"))
            self.assertTrue(wait_for(lambda: "text: Hi 👋 नमस्ते" in winsys.foreground().title, 3), winsys.foreground().title)
            r = ex.run(A("click", "", "result", 2)); self.assertTrue(r.ok, r)      # "click the second result"
            self.assertTrue(wait_for(lambda: "went to #two" in winsys.foreground().title, 4), winsys.foreground().title)
            ex.run(A("tab", "refresh")); time.sleep(1.0)
            r = ex.run(A("scroll", "down", n=2)); self.assertTrue(r.ok, r)
            self.assertTrue(wait_for(lambda: "scrolled" in winsys.foreground().title or "went" in winsys.foreground().title, 3))
            r = ex.run(A("tab", "new")); self.assertTrue(r.ok, r)
            self.assertTrue(wait_for(lambda: not self.on_page(), 3))
            ex.run(A("tab", "close"))
            self.assertTrue(wait_for(self.on_page, 3), winsys.foreground().title)
        finally:
            if self.on_page(): winput.press("ctrl+w")

    @staticmethod
    def on_page() -> bool:
        f = winsys.foreground()
        return bool(f and f.exe in winsys.BROWSERS and
                    any(m in f.title for m in ("NEXUS test page", "scrolled", "went to", "clicked:", "text:")))

    def test_youtube_plays_and_pauses(self):
        ex = Executor()
        vol, muted = winsys.get_volume(), winsys.is_muted()
        winsys.set_volume(10); winsys.set_mute(False)
        try:
            r = ex.run(A("play", "believer imagine dragons", "youtube")); self.assertTrue(r.ok, r)
            self.assertTrue(wait_for(lambda: "YouTube" in winsys.foreground().title, 10), winsys.foreground().title)
            self.assertTrue(wait_for(lambda: winsys.sound_playing(), 15), "no sound: the video didn't start")
            r = ex.run(A("media", "pause")); self.assertTrue(r.ok, r)
            self.assertTrue(wait_for(lambda: not winsys.sound_playing(0.8), 5), "still playing after pause")
            r = ex.run(A("media", "pause")); self.assertIn("already", r.msg)     # pausing twice doesn't resume it
        finally:
            f = winsys.foreground()
            if f and f.exe in winsys.BROWSERS and "YouTube" in f.title: winput.press("ctrl+w")
            winsys.set_volume(vol); winsys.set_mute(muted)

    def test_youtube_lookup(self):
        vid = resolve_video_id("believer imagine dragons", music=False)
        self.assertRegex(vid or "", r"^[A-Za-z0-9_-]{11}$")
        self.assertRegex(resolve_video_id("tum hi ho", music=True) or "", r"^[A-Za-z0-9_-]{11}$")


def windows_of(pid: int) -> list[int]:
    out = []
    P = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)

    def cb(h, _):
        p = ctypes.c_ulong()
        user32.GetWindowThreadProcessId(ctypes.c_void_p(h), ctypes.byref(p))
        if p.value == pid and user32.IsWindowVisible(ctypes.c_void_p(h)): out.append(h)
        return True
    user32.EnumWindows(P(cb), 0)
    return out


@unittest.skipUnless(LIVE, "set NEXUS_LIVE=1 to run on this PC")
class LiveDot(unittest.TestCase):
    """The real white dot (python -m nexus_pc), driven with the real mouse and keyboard."""

    @classmethod
    def setUpClass(cls):
        cls.log = winsys.DATA_DIR / "nexus.log"
        cls.log_start = cls.log.stat().st_size if cls.log.exists() else 0
        cls.pad = Pad()
        cls.proc = subprocess.Popen([sys.executable, "-m", "nexus_pc"], cwd=str(HERE.parent))
        cls.dot = wait_for(lambda: next((h for h in windows_of(cls.proc.pid)
                                         if (lambda r: r[2] - r[0] < 120)(winsys.rect(h))), None), 20)
        assert cls.dot, "the dot didn't appear"
        wait_for(lambda: "state idle" in cls.new_log(), 40)       # voice model loaded

    @classmethod
    def tearDownClass(cls):
        cls.proc.kill(); cls.proc.wait(5)
        cls.pad.close()

    @classmethod
    def new_log(cls) -> str:
        with open(cls.log, "rb") as f:
            f.seek(cls.log_start); return f.read().decode("utf-8", "replace")

    def centre(self):
        l, t, r, b = winsys.rect(self.dot); return (l + r) // 2, (t + b) // 2

    def test_1_always_on_top_and_never_takes_focus(self):
        ex = user32.GetWindowLongW(ctypes.c_void_p(self.dot), -20) & 0xFFFFFFFF
        self.assertTrue(ex & 0x08000000, "WS_EX_NOACTIVATE"); self.assertTrue(ex & 0x8, "WS_EX_TOPMOST")
        self.assertNotIn(self.dot, [w.hwnd for w in winsys.windows()])   # not on the taskbar / alt-tab
        self.assertIn("state idle", self.new_log(), "voice didn't finish loading")

    def test_2_tap_listens_and_tap_again_stops_without_stealing_focus(self):
        self.pad.front()
        mark = len(self.new_log())
        winput.click(*self.centre()); time.sleep(0.3)
        self.assertEqual(self.pad.win.hwnd, winsys.foreground().hwnd, "the dot took the focus")
        self.assertTrue(wait_for(lambda: "state listening" in self.new_log()[mark:], 3), self.new_log()[mark:])
        time.sleep(1.0)                                            # the microphone is really open now
        winput.click(*self.centre())
        self.assertTrue(wait_for(lambda: "state idle" in self.new_log()[mark:].split("state listening", 1)[-1], 8), self.new_log()[mark:])
        self.assertEqual(self.pad.win.hwnd, winsys.foreground().hwnd)

    def test_3_type_a_command_from_the_menu_runs_in_the_app_you_were_in(self):
        self.pad.front()
        Executor().run(A("click", "notes")); winput.press("ctrl+a"); winput.press("delete")
        before = set(windows_of(self.proc.pid))
        winput.click(*self.centre(), button="right")
        menu = wait_for(lambda: next(iter(set(windows_of(self.proc.pid)) - before), None), 3)
        self.assertTrue(menu, "no menu")
        item = wait_for(lambda: screen.find_label(screen.elements(menu), "type a command"), 3)
        self.assertTrue(item, [e.name for e in screen.elements(menu)])
        winput.click(*item.center)
        def box_focused():
            h = user32.GetForegroundWindow()
            return h in windows_of(self.proc.pid) and h != menu and winsys.rect(h)[2] - winsys.rect(h)[0] > 300
        self.assertTrue(wait_for(box_focused, 3), "command box didn't get focus")
        winput.type_text("type Hello from the dot"); winput.press("enter")
        self.assertTrue(wait_for(lambda: self.pad.state()["text"] == "Hello from the dot", 6), self.pad.state())
        self.assertEqual(self.pad.win.hwnd, winsys.foreground().hwnd)


@unittest.skipUnless(LIVE, "set NEXUS_LIVE=1 to run on this PC")
class LiveVoice(unittest.TestCase):
    """Speech from Windows' own offline voice → Vosk → actions (nothing is executed)."""
    PHRASES = {
        "open notepad and type hello world": [A("open_app", "notepad"), A("type", "hello world")],
        "set volume to forty": [A("volume", "set", n=40)],
        "scroll down three times then click the second video": [A("scroll", "down", n=3), A("click", "", "video", 2)],
        "open youtube and play believer": [A("open_url", "youtube"), A("play", "believer", "youtube")],
        "next song": [A("media", "next")],
        "take a screenshot": [A("system", "screenshot")],
    }

    @classmethod
    def setUpClass(cls):
        from nexus_pc.voice import Voice, find_model
        if not find_model(): raise unittest.SkipTest("no Vosk model installed")
        cls.dir = Path(tempfile.mkdtemp())
        script = ("Add-Type -AssemblyName System.Speech;"
                  "$f=New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,[System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,[System.Speech.AudioFormat.AudioChannel]::Mono);")
        for i, p in enumerate(cls.PHRASES):
            script += f"$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;$s.SetOutputToWaveFile('{cls.dir / f'{i}.wav'}',$f);$s.Speak('{p}');$s.Dispose();"
        subprocess.run(["powershell", "-NoProfile", "-Command", script], check=True, timeout=60)
        cls.voice = Voice(None).load()

    def test_spoken_commands(self):
        for i, (said, expected) in enumerate(self.PHRASES.items()):
            with self.subTest(said=said):
                self.voice.listen_file(self.dir / f"{i}.wav")
                early = [a for _, what, d in self.voice.log if what == "early" for a in d[1]]
                rest = [a for _, what, d in self.voice.log if what == "final" for a in d[1]]
                self.assertEqual([str(a) for a in expected], [str(a) for a in early + rest], self.voice.log)

    def test_acts_before_the_sentence_ends(self):
        i = list(self.PHRASES).index("open notepad and type hello world")
        self.voice.listen_file(self.dir / f"{i}.wav")
        early = [(t, d[1]) for t, what, d in self.voice.log if what == "early"]
        final_t = next(t for t, what, _ in self.voice.log if what == "final")
        self.assertTrue(early, self.voice.log)
        self.assertEqual([A("open_app", "notepad")], early[0][1])
        self.assertLess(early[0][0], final_t - 0.5)    # opened notepad well before the sentence was over


if __name__ == "__main__":
    unittest.main()
