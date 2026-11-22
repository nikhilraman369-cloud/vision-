"""
Finds things on screen by name through Windows UI Automation (works in Chrome, Edge, Office, File Explorer, Store apps…).

    find_label(hwnd, "sign in")        → best matching button/link/menu item
    find_ordinal(hwnd, "video", 2)     → the second video on the page (reading order)

One cross-process call (FindAllBuildCache) fetches every element with its name, type and position, so a whole web
page is searched in well under a second.
"""
from __future__ import annotations

import difflib
import re
from dataclasses import dataclass

import uiautomation as auto
from uiautomation import uiautomation as _uia

# property and control-type ids from the UI Automation spec
P_RECT, P_TYPE, P_LTYPE, P_NAME, P_ENABLED, P_AID, P_OFFSCREEN = 30001, 30003, 30004, 30005, 30010, 30011, 30022
T = dict(button=50000, checkbox=50002, combobox=50003, edit=50004, hyperlink=50005, image=50006, listitem=50007, list=50008,
         menuitem=50011, radio=50013, tab=50018, tabitem=50019, text=50020, treeitem=50024, custom=50025, group=50026,
         dataitem=50029, document=50030, splitbutton=50031, window=50032, pane=50033, header=50034, headeritem=50035, titlebar=50037)
_CLICKABLE = {T[k] for k in ("button", "checkbox", "combobox", "edit", "hyperlink", "listitem", "menuitem", "radio", "tabitem",
                             "treeitem", "dataitem", "splitbutton", "headeritem")}
_CONTAINERS = {T[k] for k in ("pane", "group", "custom", "document", "window", "list", "tab", "titlebar")}


@dataclass
class El:
    name: str
    type: int
    ltype: str
    aid: str
    rect: tuple[int, int, int, int]
    offscreen: bool
    elem: object  # IUIAutomationElement

    @property
    def center(self): l, t, r, b = self.rect; return (l + r) // 2, (t + b) // 2

    @property
    def visible(self): l, t, r, b = self.rect; return not self.offscreen and r - l > 2 and b - t > 2


def _client():
    c = _uia._AutomationClient.instance()
    return c.IUIAutomation


def elements(hwnd: int) -> list[El]:
    """Everything inside the window, with names and positions (one call)."""
    ua = _client()
    cr = ua.CreateCacheRequest()
    for p in (P_RECT, P_TYPE, P_LTYPE, P_NAME, P_ENABLED, P_AID, P_OFFSCREEN): cr.AddProperty(p)
    root = ua.ElementFromHandle(hwnd)
    arr = root.FindAllBuildCache(4, ua.CreateTrueCondition(), cr)   # 4 = TreeScope_Descendants
    out: list[El] = []
    for i in range(arr.Length if arr else 0):
        e = arr.GetElement(i)
        try:
            r = e.CachedBoundingRectangle
            out.append(El((e.CachedName or "").strip(), e.CachedControlType, (e.CachedLocalizedControlType or "").lower(),
                          e.CachedAutomationId or "", (r.left, r.top, r.right, r.bottom), bool(e.CachedIsOffscreen), e))
        except Exception:
            continue
    return out


# ----------------------------------------------------------------------------- by name

def _norm(s: str) -> str:
    return " ".join(re.sub(r"[^\w\s]", " ", s.lower()).split())


def score(label: str, name: str) -> float:
    """How well an on-screen name matches what was said (0 = not at all)."""
    a, b = _norm(label), _norm(name)
    if not a or not b: return 0
    if a == b: return 100
    if b.startswith(a + " "): return 90 - min(len(b) - len(a), 40) / 4
    aw, bw = a.split(), b.split()
    if all(w in bw for w in aw): return 78 - min(len(bw) - len(aw), 20) / 2
    if len(b) >= 3 and re.search(rf"\b{re.escape(b)}\b", a): return 65        # "the sign in thing" vs "sign in"
    r = difflib.SequenceMatcher(None, a, b[:len(a) + 6]).ratio()           # mishearing: "signing" vs "sign in"
    return r * 72 if r >= 0.78 else 0


def find_label(els: list[El], label: str) -> El | None:
    best, best_s = None, 0.0
    for e in els:
        if not e.name or e.type == T["titlebar"]: continue
        s = score(label, e.name)
        if s <= 0: continue
        if e.type in _CLICKABLE: s += 10
        elif e.type in _CONTAINERS: s -= 25
        if not e.visible: s -= 30
        if s > best_s: best, best_s = e, s
    return best if best_s >= 45 else None


# ----------------------------------------------------------------------------- by position ("the second video")

def _reading_order(els: list[El]) -> list[El]:
    seen, out = set(), []
    for e in sorted(els, key=lambda e: (e.rect[1] // 40, e.rect[0])):
        key = (e.rect[0] // 8, e.rect[1] // 8)
        if key in seen: continue
        seen.add(key); out.append(e)
    return out


def candidates(els: list[El], kind: str) -> list[El]:
    vis = [e for e in els if e.visible and e.rect[3] - e.rect[1] >= 8]
    links = [e for e in vis if e.type == T["hyperlink"] and e.name]
    if kind in ("video", "short"):
        c = [e for e in links if e.aid in ("video-title", "video-title-link")]
        if kind == "short": c = [e for e in links if "short" in e.name.lower()] or c
        if not c: c = [e for e in links if re.search(r"\bviews?\b|\d+:\d\d|\bminutes?\b", e.name.lower()) and e.rect[2] - e.rect[0] > 120]
        return _reading_order(c)
    if kind == "result":
        heads = [e for e in vis if e.ltype == "heading" and e.name]
        walker = _client().ControlViewWalker
        c = []
        for h in heads:
            try:
                p = walker.GetParentElement(h.elem)
                if p and p.CurrentControlType == T["hyperlink"]: c.append(h)
            except Exception:
                pass
        return _reading_order(c or [e for e in links if len(e.name) > 20 and e.rect[2] - e.rect[0] > 200])
    if kind == "tab": return _reading_order([e for e in vis if e.type == T["tabitem"]])
    if kind == "picture": return _reading_order([e for e in vis if e.type == T["image"] and e.rect[2] - e.rect[0] > 60])
    if kind == "link": return _reading_order(links)
    if kind in ("email", "message", "chat"):
        return _reading_order([e for e in vis if e.type in (T["dataitem"], T["listitem"]) and e.name]) or _reading_order(links)
    items = [e for e in vis if e.type in (T["listitem"], T["dataitem"], T["treeitem"]) and e.name]
    return _reading_order(items) or _reading_order([e for e in links if len(e.name) > 3])


def pick(cands: list[El], n: int) -> El | None:
    if not cands: return None
    if n == -1: return cands[-1]
    return cands[n - 1] if 0 < n <= len(cands) else None


def bring_into_view(e: El) -> tuple[int, int] | None:
    """Scroll an element into view if needed; its centre on screen, or None."""
    if e.visible: return e.center
    try:
        c = auto.Control.CreateControlFromElement(e.elem)
        p = c.GetScrollItemPattern() if hasattr(c, "GetScrollItemPattern") else None
        if p: p.ScrollIntoView()
        r = c.BoundingRectangle
        if r.width() > 2 and r.height() > 2: return (r.left + r.right) // 2, (r.top + r.bottom) // 2
    except Exception:
        pass
    return None


def invoke(e: El) -> bool:
    try:
        c = auto.Control.CreateControlFromElement(e.elem)
        p = c.GetInvokePattern()
        if p: p.Invoke(); return True
    except Exception:
        pass
    return False
