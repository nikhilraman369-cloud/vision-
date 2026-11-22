"""
The white dot: a small always-on-top button. It never takes focus, so keys and clicks go to the app you're using.

    tap            start listening (tap again to stop early). Ctrl+Alt+Space does the same from anywhere.
    drag           move it (the spot is remembered)
    right-click    type a command · stop everything · quit

States: faint = voice still loading · white = ready · pulsing ring = listening · turning arc = working.
"""
from __future__ import annotations

import ctypes
import json
import logging
import math
import sys
import threading
import time

from PyQt6.QtCore import QObject, QPoint, QPointF, QRectF, Qt, QTimer, pyqtSignal
from PyQt6.QtGui import QAction, QColor, QFont, QPainter, QPen, QBrush, QGuiApplication
from PyQt6.QtWidgets import QApplication, QHBoxLayout, QLabel, QLineEdit, QMenu, QPushButton, QVBoxLayout, QWidget

from . import winsys
from .actions import Action
from .cmdqueue import CommandQueue, Event
from .voice import Voice, find_model

SETTINGS = winsys.DATA_DIR / "settings.json"
log = logging.getLogger("nexus.dot")
GWL_EXSTYLE, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TOPMOST = -20, 0x08000000, 0x80, 0x8


def _no_activate(widget: QWidget) -> None:
    hwnd = int(widget.winId())
    u = ctypes.windll.user32
    u.SetWindowLongW(hwnd, GWL_EXSTYLE, u.GetWindowLongW(hwnd, GWL_EXSTYLE) | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TOPMOST)
    winsys.register_own_window(hwnd)


def _load_settings() -> dict:
    try: return json.loads(SETTINGS.read_text(encoding="utf-8"))
    except Exception: return {}


def _save_settings(d: dict) -> None:
    try:
        SETTINGS.parent.mkdir(parents=True, exist_ok=True)
        SETTINGS.write_text(json.dumps(d, indent=1), encoding="utf-8")
    except Exception:
        pass


_FLAGS = (Qt.WindowType.FramelessWindowHint | Qt.WindowType.WindowStaysOnTopHint | Qt.WindowType.Tool
          | Qt.WindowType.WindowDoesNotAcceptFocus)


class Bubble(QWidget):
    """Text next to the dot: what was heard, what was done, or a Yes/No question."""
    answered = pyqtSignal(bool)

    def __init__(self):
        super().__init__(None, _FLAGS)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        lay = QVBoxLayout(self); lay.setContentsMargins(12, 8, 12, 8); lay.setSpacing(6)
        self.label = QLabel(); self.label.setWordWrap(True); self.label.setMaximumWidth(320)
        self.label.setStyleSheet("color: white;"); self.label.setFont(QFont("Segoe UI", 10))
        lay.addWidget(self.label)
        row = QHBoxLayout(); row.setSpacing(8)
        self.yes, self.no = QPushButton("Yes"), QPushButton("No")
        for b, ok in ((self.yes, True), (self.no, False)):
            b.setCursor(Qt.CursorShape.PointingHandCursor); b.setFocusPolicy(Qt.FocusPolicy.NoFocus)
            b.setStyleSheet("QPushButton{background:#ffffff;color:#111;border:none;border-radius:8px;padding:4px 14px;font:600 9pt 'Segoe UI'}"
                            "QPushButton:hover{background:#e6e6e6}")
            b.clicked.connect(lambda _=False, ok=ok: self._answer(ok))
            row.addWidget(b)
        row.addStretch(1)
        self.buttons = QWidget(); self.buttons.setLayout(row); lay.addWidget(self.buttons); self.buttons.hide()
        self._hide = QTimer(self, singleShot=True, timeout=self.hide)

    def paintEvent(self, e):
        p = QPainter(self); p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setBrush(QColor(20, 20, 22, 225)); p.setPen(Qt.PenStyle.NoPen)
        p.drawRoundedRect(QRectF(self.rect()), 12, 12)

    def say(self, text: str, anchor: QWidget, seconds: float = 3.0, ask: bool = False):
        self.label.setText(text)
        self.buttons.setVisible(ask)
        self.adjustSize()
        g = anchor.frameGeometry()
        scr = (anchor.screen() or QGuiApplication.primaryScreen()).availableGeometry()
        x = g.left() - self.width() - 4 if g.center().x() > scr.center().x() else g.right() + 4
        y = min(max(g.center().y() - self.height() // 2, scr.top()), scr.bottom() - self.height())
        self.move(x, y)
        if not self.isVisible():
            self.show(); _no_activate(self)
        self.raise_()
        if ask: self._hide.stop()
        else: self._hide.start(int(seconds * 1000))

    def _answer(self, ok: bool):
        self.buttons.hide(); self.hide(); self.answered.emit(ok)


class CommandBox(QWidget):
    """Type a command (this one does take focus while you type)."""
    submitted = pyqtSignal(str)

    def __init__(self):
        super().__init__(None, Qt.WindowType.FramelessWindowHint | Qt.WindowType.WindowStaysOnTopHint | Qt.WindowType.Tool)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        lay = QVBoxLayout(self); lay.setContentsMargins(0, 0, 0, 0)
        self.edit = QLineEdit(); self.edit.setPlaceholderText("Tell NEXUS…  (Enter to run, Esc to close)")
        self.edit.setMinimumWidth(380)
        self.edit.setStyleSheet("QLineEdit{background:#141416;color:white;border:1px solid #3a3a3e;border-radius:12px;padding:9px 12px;font:10.5pt 'Segoe UI'}")
        lay.addWidget(self.edit)
        self.edit.returnPressed.connect(self._go)
        self.prev_hwnd = 0

    def keyPressEvent(self, e):
        if e.key() == Qt.Key.Key_Escape: self._close()
        else: super().keyPressEvent(e)

    def open_near(self, anchor: QWidget):
        fg = winsys.foreground()
        self.prev_hwnd = fg.hwnd if fg else 0
        self.adjustSize()
        g = anchor.frameGeometry()
        scr = (anchor.screen() or QGuiApplication.primaryScreen()).availableGeometry()
        x = g.left() - self.width() - 6 if g.center().x() > scr.center().x() else g.right() + 6
        self.move(x, g.center().y() - self.height() // 2)
        self.show(); self.raise_(); self.activateWindow(); self.edit.setFocus()
        ctypes.windll.user32.SetForegroundWindow(int(self.winId()))

    def _close(self):
        self.hide(); self.edit.clear()
        if self.prev_hwnd: winsys.activate(self.prev_hwnd)

    def _go(self):
        t = self.edit.text().strip()
        self._close()
        if t: QTimer.singleShot(120, lambda: self.submitted.emit(t))   # let the old window get the focus back first


class Dot(QWidget):
    R = 11        # dot radius (px)
    SIZE = 60

    clicked = pyqtSignal()
    menu_requested = pyqtSignal(QPoint)

    def __init__(self):
        super().__init__(None, _FLAGS)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        self.setFixedSize(self.SIZE, self.SIZE)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.state = "loading"
        self._t0 = time.time()
        self._press: QPoint | None = None
        self._dragged = False
        self._anim = QTimer(self, interval=33, timeout=self.update)
        s = _load_settings()
        scr = QGuiApplication.primaryScreen().availableGeometry()
        x, y = s.get("dot_x", scr.right() - self.SIZE - 24), s.get("dot_y", scr.center().y())
        if not any(sc.availableGeometry().contains(QPoint(x + self.SIZE // 2, y + self.SIZE // 2)) for sc in QGuiApplication.screens()):
            x, y = scr.right() - self.SIZE - 24, scr.center().y()
        self.move(x, y)

    def showEvent(self, e):
        super().showEvent(e); _no_activate(self)

    def set_state(self, s: str):
        if s == self.state: return
        self.state = s; self._t0 = time.time()
        self._anim.start() if s in ("listening", "working", "loading") else self._anim.stop()
        self.update()
        log.info("state %s", s)

    def paintEvent(self, e):
        p = QPainter(self); p.setRenderHint(QPainter.RenderHint.Antialiasing)
        c = QPointF(self.SIZE / 2, self.SIZE / 2)
        t = time.time() - self._t0
        if self.state == "listening":       # soft ring breathing out from the dot
            for k in range(2):
                ph = ((t * 0.9) + k * 0.5) % 1.0
                rad = self.R + 3 + ph * 15
                p.setPen(QPen(QColor(255, 255, 255, int(190 * (1 - ph))), 2)); p.setBrush(Qt.BrushStyle.NoBrush)
                p.drawEllipse(c, rad, rad)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QColor(0, 0, 0, 60)); p.drawEllipse(c + QPointF(0, 1.5), self.R + 1.5, self.R + 1.5)   # shadow: visible on white
        alpha = 255
        if self.state == "loading": alpha = int(120 + 60 * math.sin(t * 3))
        p.setBrush(QColor(255, 255, 255, alpha)); p.setPen(QPen(QColor(0, 0, 0, 40), 1))
        p.drawEllipse(c, self.R, self.R)
        if self.state == "working":
            pen = QPen(QColor(255, 255, 255, 230), 2.2); pen.setCapStyle(Qt.PenCapStyle.RoundCap)
            p.setPen(pen); p.setBrush(Qt.BrushStyle.NoBrush)
            r = self.R + 5
            p.drawArc(QRectF(c.x() - r, c.y() - r, 2 * r, 2 * r), int(-t * 360 * 16) % (360 * 16), 100 * 16)
        if self.state == "error":
            p.setPen(QPen(QColor(255, 90, 90, 220), 2)); p.setBrush(Qt.BrushStyle.NoBrush)
            p.drawEllipse(c, self.R + 4, self.R + 4)

    def mousePressEvent(self, e):
        if e.button() == Qt.MouseButton.LeftButton:
            self._press = e.globalPosition().toPoint() - self.pos(); self._dragged = False
        elif e.button() == Qt.MouseButton.RightButton:
            self.menu_requested.emit(e.globalPosition().toPoint())

    def mouseMoveEvent(self, e):
        if self._press is None: return
        np_ = e.globalPosition().toPoint() - self._press
        if self._dragged or (np_ - self.pos()).manhattanLength() > 4:
            self._dragged = True; self.move(np_)

    def mouseReleaseEvent(self, e):
        if e.button() != Qt.MouseButton.LeftButton or self._press is None: return
        self._press = None
        if self._dragged:
            s = _load_settings(); s["dot_x"], s["dot_y"] = self.x(), self.y(); _save_settings(s)
        else:
            self.clicked.emit()


class Controller(QObject):
    """Wires the dot to voice and the command queue (their callbacks come from other threads → signals)."""
    sig_text = pyqtSignal(str, bool)
    sig_event = pyqtSignal(object)
    sig_state = pyqtSignal(str)
    sig_ask = pyqtSignal(object)
    sig_toggle = pyqtSignal()
    sig_status = pyqtSignal(str)

    def __init__(self, app: QApplication, hotkey: str | None = "ctrl+alt+space"):
        super().__init__()
        self.app = app
        self.dot, self.bubble, self.box = Dot(), Bubble(), CommandBox()
        self._answer: tuple[threading.Event, list] | None = None
        self.queue = CommandQueue(on_event=self.sig_event.emit, confirm=self._confirm).start()
        self.voice: Voice | None = None
        self._listening: threading.Event | None = None
        self._listen_thread: threading.Thread | None = None
        self._last_error = ""

        self.sig_text.connect(self._on_text)
        self.sig_event.connect(self._on_event)
        self.sig_state.connect(self._set_state)
        self.sig_ask.connect(self._ask)
        self.sig_toggle.connect(self.toggle)
        self.sig_status.connect(lambda s: self.bubble.say(s, self.dot, 4))
        self.dot.clicked.connect(self.toggle)
        self.dot.menu_requested.connect(self._menu)
        self.box.submitted.connect(self.run_text)
        self.bubble.answered.connect(self._answered)
        self.dot.show()
        threading.Thread(target=self._load_voice, daemon=True, name="nexus-voice-load").start()
        threading.Thread(target=winsys.start_apps, daemon=True, name="nexus-apps").start()   # warm the app list
        if hotkey:
            try:
                import keyboard
                keyboard.add_hotkey(hotkey, self.sig_toggle.emit, suppress=False)
            except Exception as e:
                log.warning("hotkey unavailable: %s", e)

    # ------------------------------------------------------------------ voice
    def _load_voice(self):
        if not find_model():
            self._last_error = "No speech model yet: right-click → type, or add a Vosk model."
            self.sig_state.emit("idle"); self.sig_status.emit(self._last_error); return
        try:
            self.voice = Voice(self.queue, on_text=self.sig_text.emit).load()
            self.sig_state.emit("idle")
        except Exception as e:
            self._last_error = f"Voice failed to load: {e}"
            self.sig_state.emit("idle"); self.sig_status.emit(self._last_error)

    def toggle(self):
        if self._listening is not None:
            self._listening.set(); return
        if self.voice is None:
            if self.dot.state == "loading": self.bubble.say("Voice is still loading…", self.dot, 2); return
            self.box.open_near(self.dot); return
        stop = threading.Event()
        self._listening = stop
        self.dot.set_state("listening")
        self.bubble.say("Listening…", self.dot, 6)

        def run():
            try:
                self.voice.listen(stop)
            except Exception as e:
                self.sig_status.emit(f"Microphone problem: {e}")
            finally:
                self._listening = None
                self.sig_state.emit("working" if self.queue.busy() else "idle")
        self._listen_thread = threading.Thread(target=run, daemon=True, name="nexus-listen")
        self._listen_thread.start()

    def run_text(self, text: str):
        self.queue.submit_text(text)

    # ------------------------------------------------------------------ UI updates
    def _set_state(self, s: str):
        if s == "idle" and self._listening is not None: s = "listening"
        self.dot.set_state(s)

    def _on_text(self, text: str, final: bool):
        self.bubble.say(("“%s”" if final else "%s…") % text, self.dot, 3)

    def _on_event(self, e: Event):
        j = e.job
        if j and j.result: log.info("%s %s → %s", e.kind, j.action, j.result)
        elif e.kind != "queued": log.info("%s %s", e.kind, j.action if j else e.text)
        if e.kind == "start":
            if self._listening is None: self.dot.set_state("working")
        elif e.kind in ("done", "failed") and j and j.result:
            mark = "✓" if j.result.ok else "✗"
            self.bubble.say(f"{mark} {j.result.msg}", self.dot, 3 if j.result.ok else 5)
            if not j.result.ok and self._listening is None: self.dot.set_state("error"); QTimer.singleShot(900, lambda: self._set_state("working" if self.queue.busy() else "idle"))
        elif e.kind == "idle":
            if self.dot.state != "error": self._set_state("idle")
        elif e.kind == "cancelled" and j is None:
            self.bubble.say("Stopped.", self.dot, 2); self._set_state("idle")

    # ------------------------------------------------------------------ confirmation (called on the queue's thread)
    def _confirm(self, a: Action) -> bool:
        done, box = threading.Event(), [False]
        self._answer = (done, box)
        self.sig_ask.emit(a)
        done.wait(20)
        self._answer = None
        return box[0]

    def _ask(self, a: Action):
        what = {"shutdown": "Shut down the PC", "restart": "Restart the PC", "sleep": "Put the PC to sleep",
                "empty_bin": "Empty the recycle bin (can't be undone)"}.get(a.target, a.target)
        self.bubble.say(f"{what}?", self.dot, ask=True)

    def _answered(self, ok: bool):
        if self._answer:
            done, box = self._answer; box[0] = ok; done.set()

    # ------------------------------------------------------------------ menu
    def _menu(self, pos: QPoint):
        m = QMenu()
        m.setStyleSheet("QMenu{background:#141416;color:white;border:1px solid #333;border-radius:8px;padding:4px}"
                        "QMenu::item{padding:6px 18px;border-radius:6px}QMenu::item:selected{background:#2a2a2e}")
        a_type = QAction("Type a command…", m); a_type.triggered.connect(lambda: self.box.open_near(self.dot))
        a_stop = QAction("Stop everything", m); a_stop.triggered.connect(self.queue.cancel)
        a_quit = QAction("Quit NEXUS", m); a_quit.triggered.connect(self.quit)
        for a in (a_type, a_stop): m.addAction(a)
        m.addSeparator(); m.addAction(a_quit)
        m.exec(pos)

    def quit(self):
        if self._listening: self._listening.set()
        self.queue.stop()
        self.app.quit()


def main(argv=None) -> int:
    winsys.DATA_DIR.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(filename=str(winsys.DATA_DIR / "nexus.log"), level=logging.INFO, encoding="utf-8",
                        format="%(asctime)s %(name)s %(message)s")
    app = QApplication(sys.argv if argv is None else argv)
    app.setQuitOnLastWindowClosed(False)
    ctl = Controller(app)
    app._nexus = ctl  # keep alive
    return app.exec()
