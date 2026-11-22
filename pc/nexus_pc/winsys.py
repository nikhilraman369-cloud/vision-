"""Windows plumbing: top-level windows, finding and launching apps, volume, brightness, known folders."""
from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import difflib
import json
import os
import re
import shutil
import subprocess
import time
import winreg
from dataclasses import dataclass
from pathlib import Path

import psutil

from . import winput

user32 = ctypes.WinDLL("user32", use_last_error=True)
NO_WINDOW = 0x08000000  # CREATE_NO_WINDOW
DATA_DIR = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "NEXUS"

# ----------------------------------------------------------------------------- windows

WM_CLOSE = 0x0010
SW_MINIMIZE, SW_MAXIMIZE, SW_RESTORE = 6, 3, 9
GW_OWNER = 4
GWL_EXSTYLE = -20
WS_EX_TOOLWINDOW, WS_EX_NOACTIVATE = 0x80, 0x08000000
_SHELL_CLASSES = {"Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd", "Windows.UI.Core.CoreWindow"}

user32.GetWindowLongW.restype = ctypes.c_long
_EnumProc = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)


@dataclass
class Win:
    hwnd: int
    title: str
    cls: str
    exe: str  # lower-case process name without .exe

    def __str__(self): return f"{self.exe}: {self.title!r}"


def _text(hwnd) -> str:
    n = user32.GetWindowTextLengthW(hwnd)
    buf = ctypes.create_unicode_buffer(n + 1)
    user32.GetWindowTextW(hwnd, buf, n + 1)
    return buf.value


def _class(hwnd) -> str:
    buf = ctypes.create_unicode_buffer(256)
    user32.GetClassNameW(hwnd, buf, 256)
    return buf.value


def _exe(hwnd) -> str:
    pid = wt.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    try:
        return psutil.Process(pid.value).name().lower().removesuffix(".exe")
    except Exception:
        return ""


def window(hwnd) -> Win | None:
    if not hwnd: return None
    return Win(int(hwnd), _text(hwnd), _class(hwnd), _exe(hwnd))


_own: set[int] = set()   # NEXUS's own windows (the dot): never treated as the "current app"


def register_own_window(hwnd: int) -> None: _own.add(int(hwnd))


def _is_app_window(hwnd) -> bool:
    if not user32.IsWindowVisible(hwnd) or user32.GetWindow(hwnd, GW_OWNER): return False
    if user32.GetWindowLongW(hwnd, GWL_EXSTYLE) & (WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE): return False
    if int(hwnd) in _own or _class(hwnd) in _SHELL_CLASSES: return False
    return bool(_text(hwnd))


def windows() -> list[Win]:
    """Real app windows, front-most first (Z order)."""
    out: list[Win] = []

    def cb(h, _):
        if _is_app_window(h):
            w = window(h)
            if w and not (w.exe == "applicationframehost" and not w.title): out.append(w)
        return True
    user32.EnumWindows(_EnumProc(cb), 0)
    return out


def foreground() -> Win | None:
    h = user32.GetForegroundWindow()
    if h and int(h) in _own:
        ws = windows()
        return ws[0] if ws else None
    return window(h)


def activate(hwnd: int) -> bool:
    """Bring a window to the front (the ALT tap lets a background process take the foreground)."""
    if user32.IsIconic(hwnd): user32.ShowWindow(hwnd, SW_RESTORE)
    winput._send([winput._key(0x12, False), winput._key(0x12, True)])
    user32.SetForegroundWindow(hwnd)
    for _ in range(20):
        if user32.GetForegroundWindow() == hwnd: return True
        time.sleep(0.02)
    return user32.GetForegroundWindow() == hwnd


def close_window(hwnd: int) -> None: user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)
def show(hwnd: int, how: int) -> None: user32.ShowWindow(hwnd, how)


def rect(hwnd: int) -> tuple[int, int, int, int]:
    r = wt.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(r))
    return r.left, r.top, r.right, r.bottom


# ----------------------------------------------------------------------------- apps

# How to recognise an app's window: process names and/or title words.
_APP_WINDOWS = {
    "chrome": ("chrome",), "edge": ("msedge",), "firefox": ("firefox",), "notepad": ("notepad",),
    "calculator": ("calculatorapp", "title:calculator"), "paint": ("mspaint", "title:paint"), "file explorer": ("explorer",),
    "settings": ("systemsettings", "title:settings"), "task manager": ("taskmgr",), "command prompt": ("cmd", "title:command prompt"),
    "terminal": ("windowsterminal",), "powershell": ("powershell", "title:powershell"), "word": ("winword",), "excel": ("excel",),
    "powerpoint": ("powerpnt",), "outlook app": ("outlook", "olk"), "vs code": ("code",), "spotify": ("spotify",),
    "whatsapp": ("whatsapp", "title:whatsapp"), "telegram": ("telegram",), "discord": ("discord",), "zoom": ("zoom",),
    "teams": ("ms-teams", "teams"), "vlc": ("vlc",), "camera": ("title:camera",), "photos": ("photos", "title:photos"),
    "clock": ("title:clock",), "store": ("title:microsoft store",), "control panel": ("title:control panel",),
    "obs": ("obs64",), "steam": ("steam",), "premiere pro": ("adobe premiere pro",), "photoshop": ("photoshop",),
    "davinci resolve": ("resolve",), "capcut": ("capcut",), "audacity": ("audacity",), "sticky notes": ("title:sticky notes",),
    "youtube": ("title:youtube",), "youtube music": ("title:youtube music",),
}
BROWSERS = {"chrome", "msedge", "firefox", "brave", "opera"}


def matches_app(w: Win | None, app: str) -> bool:
    if not w: return False
    app = app.lower()
    hints = _APP_WINDOWS.get(app, (Path(app).stem.lower(), f"title:{app}"))
    for h in hints:
        if h.startswith("title:"):
            if h[6:] in w.title.lower(): return True
        elif w.exe == h:
            return True
    return False


def find_windows(app: str) -> list[Win]:
    return [w for w in windows() if matches_app(w, app)]


def _app_path(exe: str) -> str | None:
    name = exe if exe.lower().endswith(".exe") else exe + ".exe"
    for root in (winreg.HKEY_CURRENT_USER, winreg.HKEY_LOCAL_MACHINE):
        try:
            with winreg.OpenKey(root, rf"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\{name}") as k:
                v = winreg.QueryValue(k, None)
                if v and Path(v.strip('"')).exists(): return v.strip('"')
        except OSError:
            pass
    return shutil.which(exe)


def chrome_path() -> str | None:
    p = _app_path("chrome")
    if p: return p
    for base in (os.environ.get("PROGRAMFILES", ""), os.environ.get("PROGRAMFILES(X86)", ""), os.environ.get("LOCALAPPDATA", "")):
        c = Path(base) / "Google/Chrome/Application/chrome.exe"
        if base and c.exists(): return str(c)
    return None


def _start_menu_links() -> dict[str, str]:
    out: dict[str, str] = {}
    for base in (os.environ.get("PROGRAMDATA", ""), os.environ.get("APPDATA", "")):
        d = Path(base) / "Microsoft/Windows/Start Menu/Programs"
        if base and d.exists():
            for lnk in d.rglob("*.lnk"):
                stem = lnk.stem.lower()
                if "uninstall" in stem or "readme" in stem or "help" == stem: continue
                out.setdefault(stem, str(lnk))
    return out


_start_apps: dict[str, str] | None = None


def start_apps(refresh: bool = False) -> dict[str, str]:
    """Every app on the Start menu, Store apps included: name (lower) → AppUserModelID. Cached for a day."""
    global _start_apps
    if _start_apps is not None and not refresh: return _start_apps
    cache = DATA_DIR / "start_apps.json"
    try:
        if not refresh and cache.exists() and time.time() - cache.stat().st_mtime < 86400:
            _start_apps = json.loads(cache.read_text(encoding="utf-8"))
            return _start_apps
    except Exception:
        pass
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-Command",
                            "[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-StartApps | ConvertTo-Json -Compress"],
                           capture_output=True, text=True, encoding="utf-8", timeout=20, creationflags=NO_WINDOW)
        items = json.loads(r.stdout or "[]")
        if isinstance(items, dict): items = [items]
        _start_apps = {i["Name"].lower(): i["AppID"] for i in items if i.get("Name") and i.get("AppID")}
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        cache.write_text(json.dumps(_start_apps), encoding="utf-8")
    except Exception:
        _start_apps = {}
    return _start_apps


def _best(name: str, names) -> str | None:
    name = name.lower()
    names = list(names)
    if name in names: return name
    starts = sorted((n for n in names if n.startswith(name + " ") or n.startswith(name)), key=len)
    if starts: return starts[0]
    words = sorted((n for n in names if re.search(rf"\b{re.escape(name)}\b", n)), key=len)
    if words: return words[0]
    close = difflib.get_close_matches(name, names, n=1, cutoff=0.8)
    return close[0] if close else None


def launch(app: str, how: str | None, exact: bool = False) -> str:
    """Starts an app by its catalog value or spoken name. Returns how it was started; raises LookupError if not found.
    [exact]: only an app with exactly that name (no fuzzy guess)."""
    if exact and not how:
        n = app.lower().strip()
        links, apps = _start_menu_links(), start_apps()
        if n in links: os.startfile(links[n]); return links[n]
        if n in apps:
            subprocess.Popen(["explorer", f"shell:AppsFolder\\{apps[n]}"], creationflags=NO_WINDOW); return f"start app {n}"
        raise LookupError(app)
    how = how or app
    if re.match(r"^[a-z][\w.+-]*:", how) and not re.match(r"^[a-z]:\\", how, re.I):   # URI: ms-settings:, whatsapp:, shell:…
        if how.startswith("shell:"):
            subprocess.Popen(["explorer", how], creationflags=NO_WINDOW)
        else:
            os.startfile(how)
        return f"uri {how}"
    p = _app_path(how)
    if p:
        subprocess.Popen([p], cwd=str(Path(p).parent), creationflags=0)
        return p
    for key in (how, app):
        links = _start_menu_links()
        b = _best(key, links)
        if b:
            os.startfile(links[b]); return links[b]
        apps = start_apps()
        b = _best(key, apps)
        if b:
            subprocess.Popen(["explorer", f"shell:AppsFolder\\{apps[b]}"], creationflags=NO_WINDOW)
            return f"start app {b}"
    raise LookupError(app)


def open_in_browser(url: str) -> None:
    c = chrome_path()
    if c: subprocess.Popen([c, url])
    else: os.startfile(url)


# ----------------------------------------------------------------------------- folders

_FOLDERS = {"downloads": "shell:Downloads", "download": "shell:Downloads", "documents": "shell:Personal", "document": "shell:Personal",
            "desktop": "shell:Desktop", "pictures": "shell:My Pictures", "photos": "shell:My Pictures", "music": "shell:My Music",
            "videos": "shell:My Video", "video": "shell:My Video", "home": "shell:Profile", "recent": "shell:Recent",
            "this pc": "shell:MyComputerFolder", "recycle bin": "shell:RecycleBinFolder", "screenshots": "shell:Screenshots"}


def open_folder(name: str) -> str:
    n = name.lower().strip()
    if n in _FOLDERS:
        subprocess.Popen(["explorer", _FOLDERS[n]]); return _FOLDERS[n]
    p = Path(os.path.expandvars(os.path.expanduser(name)))
    if p.exists():
        os.startfile(str(p)); return str(p)
    raise LookupError(name)


def known_folder(name: str) -> Path:
    """Resolve a shell: folder to a path (via the shell, so moved folders such as OneDrive Pictures are right)."""
    import win32com.client
    sh = win32com.client.Dispatch("Shell.Application")
    ns = {"pictures": 0x27, "documents": 0x05, "desktop": 0x10, "music": 0x0D, "videos": 0x0E}[name]
    return Path(sh.Namespace(ns).Self.Path)


# ----------------------------------------------------------------------------- sound

def _speakers():
    from pycaw.pycaw import AudioUtilities
    return AudioUtilities.GetSpeakers()


def get_volume() -> int: return round(_speakers().EndpointVolume.GetMasterVolumeLevelScalar() * 100)
def set_volume(level: int) -> None: _speakers().EndpointVolume.SetMasterVolumeLevelScalar(max(0, min(100, level)) / 100, None)
def is_muted() -> bool: return bool(_speakers().EndpointVolume.GetMute())
def set_mute(on: bool) -> None: _speakers().EndpointVolume.SetMute(1 if on else 0, None)


def sound_playing(sample_s: float = 0.35) -> bool:
    """Is anything audible coming out of the speakers right now? (Peak meter of the default output.)"""
    import comtypes
    from pycaw.pycaw import IAudioMeterInformation
    dev = _speakers()
    meter = dev._dev.Activate(IAudioMeterInformation._iid_, comtypes.CLSCTX_ALL, None).QueryInterface(IAudioMeterInformation)
    end = time.time() + sample_s
    while time.time() < end:
        if meter.GetPeakValue() > 0.0005: return True
        time.sleep(0.03)
    return False


# ----------------------------------------------------------------------------- brightness

def _ps(cmd: str, timeout: float = 15) -> str:
    r = subprocess.run(["powershell", "-NoProfile", "-Command", cmd], capture_output=True, text=True, timeout=timeout, creationflags=NO_WINDOW)
    if r.returncode != 0: raise RuntimeError(r.stderr.strip() or "powershell failed")
    return r.stdout.strip()


def get_brightness() -> int:
    out = _ps("(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightness -ErrorAction Stop | Select-Object -First 1).CurrentBrightness")
    return int(out.split()[0])


def set_brightness(level: int) -> None:
    level = max(0, min(100, int(level)))
    _ps("Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods -ErrorAction Stop | "
        f"Invoke-CimMethod -MethodName WmiSetBrightness -Arguments @{{Timeout=0; Brightness={level}}} | Out-Null")
