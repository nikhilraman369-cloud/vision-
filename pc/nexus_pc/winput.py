"""Keyboard and mouse for Windows through SendInput (one batched call per chord / text: fast, and Unicode-safe)."""
from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import time

user32 = ctypes.WinDLL("user32", use_last_error=True)

INPUT_MOUSE, INPUT_KEYBOARD = 0, 1
KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP, KEYEVENTF_UNICODE = 0x1, 0x2, 0x4
MOUSEEVENTF_MOVE, MOUSEEVENTF_ABSOLUTE, MOUSEEVENTF_VIRTUALDESK = 0x1, 0x8000, 0x4000
MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP = 0x2, 0x4
MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP = 0x8, 0x10
MOUSEEVENTF_WHEEL, MOUSEEVENTF_HWHEEL = 0x800, 0x1000
WHEEL_DELTA = 120

ULONG_PTR = ctypes.c_size_t


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wt.LONG), ("dy", wt.LONG), ("mouseData", wt.DWORD), ("dwFlags", wt.DWORD), ("time", wt.DWORD),
                ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wt.WORD), ("wScan", wt.WORD), ("dwFlags", wt.DWORD), ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wt.DWORD), ("wParamL", wt.WORD), ("wParamH", wt.WORD)]


class _U(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wt.DWORD), ("u", _U)]


user32.SendInput.argtypes = (wt.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
user32.SendInput.restype = wt.UINT

VK = {
    "backspace": 0x08, "tab": 0x09, "enter": 0x0D, "return": 0x0D, "shift": 0x10, "ctrl": 0x11, "control": 0x11, "alt": 0x12,
    "pause": 0x13, "capslock": 0x14, "esc": 0x1B, "escape": 0x1B, "space": 0x20, "pageup": 0x21, "pagedown": 0x22, "end": 0x23,
    "home": 0x24, "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28, "printscreen": 0x2C, "insert": 0x2D, "delete": 0x2E,
    "del": 0x2E, "win": 0x5B, "windows": 0x5B, "menu": 0x5D, "apps": 0x5D,
    "plus": 0xBB, "equals": 0xBB, "minus": 0xBD, "comma": 0xBC, "period": 0xBE, "dot": 0xBE, "slash": 0xBF, "semicolon": 0xBA,
    "volume_mute": 0xAD, "volume_down": 0xAE, "volume_up": 0xAF, "media_next": 0xB0, "media_prev": 0xB1, "media_stop": 0xB2,
    "media_play_pause": 0xB3, "browser_back": 0xA6, "browser_forward": 0xA7,
}
VK.update({f"f{i}": 0x6F + i for i in range(1, 25)})
VK.update({chr(c): c for c in range(ord("0"), ord("9") + 1)})
VK.update({chr(c).lower(): c for c in range(ord("A"), ord("Z") + 1)})
VK.update({"page up": 0x21, "page down": 0x22, "arrow up": 0x26, "arrow down": 0x28, "arrow left": 0x25, "arrow right": 0x27})
_EXTENDED = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x5B, 0x5D, 0x6F}
_ALIAS = {"windows key": "win", "window": "win", "cmd": "win", "command": "win", "option": "alt", "control": "ctrl", "del": "delete",
          "+": "plus", "-": "minus", "=": "equals", ",": "comma", ".": "period", "/": "slash", ";": "semicolon"}


def _key(vk: int, up: bool) -> INPUT:
    flags = (KEYEVENTF_KEYUP if up else 0) | (KEYEVENTF_EXTENDEDKEY if vk in _EXTENDED else 0)
    i = INPUT(type=INPUT_KEYBOARD)
    i.ki = KEYBDINPUT(vk, 0, flags, 0, 0)
    return i


def _send(inputs: list[INPUT]) -> int:
    if not inputs: return 0
    arr = (INPUT * len(inputs))(*inputs)
    return user32.SendInput(len(inputs), arr, ctypes.sizeof(INPUT))


def parse_chord(chord: str) -> list[int]:
    """"ctrl+shift+t" → [VK_CONTROL, VK_SHIFT, 'T']. Raises ValueError for an unknown key."""
    chord = chord.strip().lower()
    if chord in VK: return [VK[chord]]
    parts = [p for p in chord.replace(" + ", "+").split("+")] if "+" in chord.strip("+") else chord.split()
    out = []
    for p in parts:
        p = _ALIAS.get(p.strip(), p.strip())
        if p not in VK: raise ValueError(f"unknown key {p!r}")
        out.append(VK[p])
    return out


def press(chord: str, times: int = 1) -> None:
    vks = parse_chord(chord)
    seq: list[INPUT] = []
    for _ in range(max(1, times)):
        seq += [_key(v, False) for v in vks] + [_key(v, True) for v in reversed(vks)]
    _send(seq)


def type_text(text: str) -> None:
    """Types any text (Hindi, emoji…) as Unicode keystrokes. Newlines become Enter."""
    seq: list[INPUT] = []
    for ch in text.replace("\r\n", "\n"):
        if ch == "\n":
            seq += [_key(0x0D, False), _key(0x0D, True)]
            continue
        for unit in _utf16(ch):
            for up in (False, True):
                i = INPUT(type=INPUT_KEYBOARD)
                i.ki = KEYBDINPUT(0, unit, KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if up else 0), 0, 0)
                seq.append(i)
    for k in range(0, len(seq), 200):          # chunks: very long text stays responsive
        _send(seq[k:k + 200])
        if len(seq) > 200: time.sleep(0.005)


def _utf16(ch: str) -> list[int]:
    b = ch.encode("utf-16-le")
    return [int.from_bytes(b[i:i + 2], "little") for i in range(0, len(b), 2)]


# ----------------------------------------------------------------------------- mouse

def cursor() -> tuple[int, int]:
    p = wt.POINT()
    user32.GetCursorPos(ctypes.byref(p))
    return p.x, p.y


def move(x: int, y: int) -> None:
    user32.SetCursorPos(int(x), int(y))


def _mouse(flags: int, data: int = 0) -> INPUT:
    i = INPUT(type=INPUT_MOUSE)
    i.mi = MOUSEINPUT(0, 0, ctypes.c_uint32(data).value, flags, 0, 0)
    return i


def click(x: int | None = None, y: int | None = None, button: str = "left", double: bool = False) -> None:
    if x is not None: move(x, y)
    down, up = (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP) if button == "right" else (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP)
    seq = [_mouse(down), _mouse(up)]
    _send(seq)
    if double:
        time.sleep(0.03)
        _send([_mouse(down), _mouse(up)])


def wheel(notches: int, horizontal: bool = False) -> None:
    """Positive = up (or right when horizontal)."""
    flag = MOUSEEVENTF_HWHEEL if horizontal else MOUSEEVENTF_WHEEL
    step = 1 if notches > 0 else -1
    for _ in range(abs(notches)):
        _send([_mouse(flag, step * WHEEL_DELTA)])
        time.sleep(0.012)
