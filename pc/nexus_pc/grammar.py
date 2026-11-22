"""
Understands what you say (English, Hindi/Hinglish, casual speech) and turns it into Actions.

    understand("youtube kholo aur tum hi ho chalao") -> [(open_url youtube), (play 'tum hi ho' arg=youtube)]
    understand("scroll down 3 times, then click the second video") -> [(scroll 'down' n=3), (click arg=video n=2)]

Pure Python, no dependencies: fully testable anywhere.
"""
from __future__ import annotations

import re
from .actions import Action
from . import catalog

# ----------------------------------------------------------------------------- normalisation

_FILLER_LEAD = re.compile(
    r"^(?:(?:hey|hi|ok|okay|yo)\s+nexus|nexus|please|pls|plz|kindly|can you|could you|will you|would you|just|bhai|yaar|yar|bro|"
    r"i want to|i want you to|i wanna|let's|lets|go ahead and|zara|jara|ek kaam karo)\b[\s,]*", re.I)
_FILLER_TAIL = re.compile(r"[\s,]*\b(?:please|pls|plz|for me|now|right now|quickly|jaldi|na|yaar|bhai|abhi|ok|okay)$", re.I)

_WORDS = {  # casual / Hinglish word → plain word (whole words only)
    "vid": "video", "vids": "videos", "vdo": "video", "pic": "picture", "pics": "pictures", "msg": "message", "u": "you", "ur": "your",
    "kr": "kar", "krdo": "kar do", "kardo": "kar do", "kro": "karo", "krna": "karna",
    "pehla": "first", "pehli": "first", "pehle": "first", "pahla": "first", "dusra": "second", "doosra": "second", "dusri": "second",
    "doosri": "second", "dusre": "second", "doosre": "second", "teesra": "third", "teesri": "third", "teesre": "third", "tisra": "third",
    "chautha": "fourth", "chauthi": "fourth", "paanchva": "fifth", "panchva": "fifth", "aakhri": "last", "akhri": "last",
    "agla": "next", "agli": "next", "agle": "next", "pichla": "previous", "pichli": "previous", "pichhla": "previous",
    "neeche": "down", "niche": "down", "upar": "up", "uppar": "up", "awaaz": "volume", "awaz": "volume", "aawaz": "volume",
    "gaana": "song", "gana": "song", "gaane": "songs",
}

# Verb at the END (Hindi order) → English verb at the start.
_TAIL_VERBS = [
    (r"(?:open karo|open kar do|open kar|kholo|khol do|khol de|kholna|khol|chalu karo|chalu kar do|start karo|launch karo)", "open"),
    (r"(?:play karo|play kar do|chalao|chala do|chala de|chala|bajao|baja do|laga do|lagao|sunao|suna do)", "play"),
    (r"(?:search karo|search kar do|search kar|search|dhundo|dhoondo|dhundho|dhundh do|dhoond do|khojo)", "search"),
    (r"(?:type karo|type kar do|type kar|likho|likh do|likh de|likh)", "type"),
    (r"(?:click karo|click kar do|click kar|tap karo|tap kar do|dabao|daba do|press karo)", "click"),
    (r"(?:band karo|band kar do|band kar|close karo|close kar do|close kar|hatao|hata do)", "close"),
    (r"(?:scroll karo|scroll kar do|scroll kar|scroll)", "scroll"),
    (r"(?:minimize karo|minimize kar do|chhota karo)", "minimize"),
    (r"(?:maximize karo|maximize kar do|bada karo|full karo)", "maximize"),
    (r"(?:pause karo|pause kar do|roko|rok do|ruko)", "pause"),
    (r"(?:copy karo|copy kar do|copy kar lo|copy kar)", "copy"),
    (r"(?:paste karo|paste kar do|paste kar)", "paste"),
    (r"(?:save karo|save kar do|save kar lo|save kar)", "save"),
    (r"(?:download karo|download kar do|download kar lo|download kar)", "download"),
    (r"(?:refresh karo|refresh kar do|reload karo)", "refresh"),
    (r"(?:mute karo|mute kar do|chup karo)", "mute"),
    (r"(?:dikhao|dikha do|dikha|show karo)", "show"),
    (r"(?:jao|chalo|pe jao|par jao|mein jao|me jao)", "go to"),
]
_TAIL_VERB_RX = [(re.compile(rf"^(.+?)\s+{p}$", re.I), v) for p, v in _TAIL_VERBS]
_BARE_VERB_RX = [(re.compile(rf"^{p}$", re.I), v) for p, v in _TAIL_VERBS]
# "youtube pe X chalao" / "google par X dhundo": "<site> pe" in front.
_ON_SITE_FRONT = re.compile(r"^(.+?)\s+(?:pe|par|per|mein|me)\s+(.+)$", re.I)


def normalise(text: str) -> str:
    t = " " + re.sub(r"\s+", " ", text.strip().lower().replace("’", "'")) + " "
    for a, b in _WORDS.items():
        t = re.sub(rf" {re.escape(a)}(?= )", f" {b}", t)
    t = t.strip(" .!?")
    for _ in range(3):
        t2 = _FILLER_TAIL.sub("", _FILLER_LEAD.sub("", t)).strip(" ,")
        if t2 == t: break
        t = t2
    return t


# ----------------------------------------------------------------------------- splitting several commands

_SPLIT = re.compile(r"\s*(?:\n+|;|\.\s+|,\s*(?:and\s+then|and|then|phir|fir|aur)?\s*|\s(?:and\s+then|after\s+that|afterwards|then|and|phir|fir|aur|uske\s+baad|iske\s+baad|&)\s)\s*", re.I)
_VERBS_START = re.compile(
    r"^(?:open|close|play|pause|resume|stop|search|google|find|look|type|write|press|hit|click|tap|double|right|select|scroll|go|switch|"
    r"minimi[sz]e|maximi[sz]e|restore|mute|unmute|volume|turn|set|increase|decrease|raise|lower|next|previous|skip|forward|rewind|back|"
    r"refresh|reload|new|copy|paste|cut|undo|redo|save|download|lock|shutdown|shut|restart|sleep|take|screenshot|show|zoom|wait|start|"
    r"launch|run|send|reply|delete|remove|rename|create|make|enter|exit|quit|full|snap|move|bookmark|print|read|dictate|focus|"
    r"youtube|chrome|notepad|google)\b", re.I)
_PAYLOAD = re.compile(r"^(?:type|write|likho|dictate|send|reply|search|google|find)\b", re.I)


def split_commands(text: str) -> list[str]:
    """Split "open chrome, go to youtube and play believer" into its commands, keeping typed text together."""
    out: list[str] = []
    pos = 0
    current = ""
    for m in _SPLIT.finditer(text):
        before = text[pos:m.start()]
        after = text[m.end():]
        candidate = (current + before).strip()
        next_clause = normalise(after)
        tail_verb = any(rx.match(_SPLIT.split(after, maxsplit=1)[0].strip()) for rx, _ in _TAIL_VERB_RX)
        in_payload = bool(_PAYLOAD.match(normalise(candidate)))
        hard = m.group(0).strip() in ("", ";") or "\n" in m.group(0) or m.group(0).strip().startswith(".")
        starts_new = bool(_VERBS_START.match(next_clause)) or tail_verb
        if candidate and (starts_new and (not in_payload or _strong_after_payload(next_clause)) or (hard and not in_payload)):
            out.append(candidate)
            current = ""
        else:
            current = candidate + m.group(0)
        pos = m.end()
    last = (current + text[pos:]).strip()
    if last: out.append(last)
    return [c.strip(" ,.") for c in out if c.strip(" ,.")]


def _strong_after_payload(clause: str) -> bool:
    # inside "type …", only a clear new action ends the text: "type hello and press enter"
    return bool(re.match(r"^(?:press|hit|open|close|go|switch|send it|scroll|click|tap|save|search|play|pause|minimi[sz]e|maximi[sz]e)\b", clause))


# ----------------------------------------------------------------------------- numbers

_NUM = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
        "eleven": 11, "twelve": 12, "fifteen": 15, "twenty": 20, "thirty": 30, "forty": 40, "fifty": 50, "hundred": 100,
        "ek": 1, "do": 2, "teen": 3, "char": 4, "chaar": 4, "paanch": 5, "panch": 5, "chhe": 6, "saat": 7, "aath": 8, "nau": 9, "das": 10,
        "once": 1, "twice": 2, "thrice": 3, "a": 1, "an": 1}
_ORD = {"first": 1, "second": 2, "third": 3, "fourth": 4, "fifth": 5, "sixth": 6, "seventh": 7, "eighth": 8, "ninth": 9, "tenth": 10,
        "last": -1, "top": 1, "latest": 1}
_NUM_W = r"(\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|hundred|ek|do|teen|char|chaar|paanch|panch|chhe|saat|aath|nau|das)"


def num(s: str | None) -> int | None:
    if not s: return None
    s = s.strip().lower()
    return int(s) if s.isdigit() else _NUM.get(s)


def ordinal(s: str | None) -> int | None:
    if not s: return None
    s = s.strip().lower()
    m = re.match(r"^(\d+)(?:st|nd|rd|th)$", s)
    return int(m.group(1)) if m else _ORD.get(s)


# ----------------------------------------------------------------------------- the rules

def _rx(p: str) -> re.Pattern: return re.compile(p, re.I)

_KIND = r"(video|videos|result|results|link|links|song|songs|tab|item|option|email|mail|file|folder|image|picture|photo|short|shorts|chat|message|post|one)"
_ORDW = r"(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|last|top|latest|\d+(?:st|nd|rd|th))"

_HOTKEYS = {
    "select all": "ctrl+a", "copy": "ctrl+c", "copy this": "ctrl+c", "copy it": "ctrl+c", "cut": "ctrl+x", "paste": "ctrl+v", "paste it": "ctrl+v",
    "paste here": "ctrl+v", "undo": "ctrl+z", "undo that": "ctrl+z", "redo": "ctrl+y", "save": "ctrl+s", "save it": "ctrl+s",
    "save this": "ctrl+s", "save as": "ctrl+shift+s", "print": "ctrl+p", "print this": "ctrl+p", "find": "ctrl+f", "new file": "ctrl+n",
    "new window": "ctrl+n", "open file": "ctrl+o", "zoom in": "ctrl+plus", "zoom out": "ctrl+minus", "reset zoom": "ctrl+0",
    "bold": "ctrl+b", "italic": "ctrl+i", "underline": "ctrl+u", "enter": "enter", "press enter": "enter", "hit enter": "enter",
    "escape": "esc", "press escape": "esc", "delete": "delete", "backspace": "backspace", "tab": "tab", "space": "space",
    "alt tab": "alt+tab", "switch window": "alt+tab", "rename": "f2", "properties": "alt+enter",
}


def understand(text: str) -> list[Action]:
    """Everything you said → the actions, in order."""
    actions: list[Action] = []
    ctx = {"app": None}
    for clause in split_commands(text):
        actions.extend(parse_clause(clause, ctx))
    return actions


def parse_clause(raw: str, ctx: dict | None = None) -> list[Action]:
    ctx = ctx if ctx is not None else {"app": None}
    c = normalise(raw)
    if not c: return []
    # Hindi order: "youtube kholo" → "open youtube"; "tum hi ho chalao" → "play tum hi ho"
    c = _to_english_order(c)

    for rule in _RULES:
        r = rule(c, ctx)
        if r is not None:
            for a in r:
                if a.kind in ("open_app", "open_url"): ctx["app"] = a.target
            return r
    # Anything else: something on screen to find and act on at run time.
    return [Action("screen", c)]


def _to_english_order(c: str) -> str:
    for rx, verb in _BARE_VERB_RX:          # "copy karo" → "copy", "roko" → "pause"
        if rx.match(c): return verb
    for rx, verb in _TAIL_VERB_RX:
        m = rx.match(c)
        if not m: continue
        obj = re.sub(r"\s+(?:pe|par|per|ko|ka|ki|ke)$", "", m.group(1).strip())
        # "<site> pe <thing> search/play" → "search/play <thing> on <site>"
        on = _ON_SITE_FRONT.match(obj)
        if on and verb in ("search", "play", "type") and (catalog.canonical(on.group(1)) or on.group(1) in ("google",)):
            return f"{verb} {on.group(2)} on {on.group(1)}"
        if verb == "go to" and catalog.canonical(obj): return f"open {obj}"
        return f"{verb} {obj}"
    return c


_COUNT_ANY = re.compile(rf"\b(?:{_NUM_W}\s*(?:times?|baar|bar|dafa)|(twice|thrice))\b", re.I)


def _counted(c: str, ctx) -> list[Action] | None:
    """"scroll down 3 times", "teen baar neeche scroll", "next song twice" — the count can be anywhere."""
    m = _COUNT_ANY.search(c)
    if not m: return None
    n = num(m.group(1)) or num(m.group(2))
    if not n: return None
    inner = (c[:m.start()] + " " + c[m.end():]).strip()
    inner = re.sub(r"\s+", " ", inner)
    if not inner: return None
    acts = parse_clause(inner, ctx)
    if len(acts) == 1 and acts[0].kind in ("scroll", "keys") or (len(acts) == 1 and acts[0].kind == "tab" and acts[0].target in ("back", "forward", "next", "previous", "close", "new")):
        a = acts[0]
        return [Action(a.kind, a.target, a.arg, n)]
    if any(a.kind in ("screen", "type", "search", "play", "open_app", "open_url") for a in acts): return None
    return acts * min(n, 30)


def _open(c: str, ctx) -> list[Action] | None:
    m = _rx(r"^(?:open|launch|start|run|go to|goto|visit|show|show me|take me to|switch to|bring up|load|open up)\s+(?:the\s+|my\s+)?(.+?)$").match(c)
    if not m: return None
    name = m.group(1).strip()
    # "open youtube and search X" was split already; here only the thing to open.
    if re.match(r"^(?:new tab|a new tab|incognito|private window|downloads|history|bookmarks|settings for .+)$", name): return None
    if name.endswith(" folder") or name in ("downloads folder", "documents", "desktop", "pictures", "music folder", "videos folder"):
        return [Action("folder", name.removesuffix(" folder").strip())]
    cn = catalog.canonical(name)
    if cn in catalog.SITES and not (cn in catalog.APPS): return [Action("open_url", cn)]
    if cn: return [Action("open_app", cn)]
    if re.match(r"^[\w-]+(\.[\w-]+)+(/\S*)?$", name): return [Action("open_url", name)]
    ordm = _rx(rf"^{_ORDW}\s+{_KIND}$").match(name)
    if ordm: return [Action("click", "", _kind(ordm.group(2)), ordinal(ordm.group(1)) or 1)]
    # Unknown name: an installed app we'll look up at run time, or something on screen.
    return [Action("open_app", name)]


def _kind(k: str) -> str:
    k = k.lower().rstrip("s") if k.lower() not in ("shorts",) else "short"
    return {"mail": "email", "image": "picture", "photo": "picture", "one": "", "result": "result", "item": ""}.get(k, k)


def _play(c: str, ctx) -> list[Action] | None:
    m = _rx(rf"^(?:play|start|watch|listen to|put on)\s+(?:the\s+)?{_ORDW}\s+{_KIND}$").match(c)
    if m: return [Action("click", "", _kind(m.group(2)), ordinal(m.group(1)) or 1)]
    m = _rx(r"^(?:play|listen to|put on|start playing|watch)\s+(.+?)(?:\s+(?:on|in|from|using)\s+(youtube music|yt music|youtube|spotify|ytmusic))?$").match(c)
    if not m: return None
    q = m.group(1).strip()
    where = (m.group(2) or "").lower()
    where = {"yt music": "ytmusic", "youtube music": "ytmusic"}.get(where, where)
    if q in ("", "it", "this", "music", "the music", "song", "the song", "video", "the video"): return [Action("media", "play")]
    if q in ("next", "next song", "the next song", "next video"): return [Action("media", "next")]
    if catalog.canonical(q) and not where: return [Action("open_app" if catalog.canonical(q) in catalog.APPS else "open_url", catalog.canonical(q))]
    if not where:
        if ctx.get("app") in ("youtube music", "spotify") : where = "ytmusic" if ctx["app"] == "youtube music" else "spotify"
        elif ctx.get("app") == "youtube" or re.search(r"\b(video|trailer|vlog|podcast|tutorial|movie|highlights|review)\b", q): where = "youtube"
        else: where = "ytmusic"
    return [Action("play", q, where)]


def _search(c: str, ctx) -> list[Action] | None:
    m = _rx(r"^(?:search for|search karo|search|google for|google|look up|look for|find)\s+(.+?)(?:\s+(?:on|in|using)\s+(youtube music|youtube|google|amazon|flipkart|wikipedia|github|instagram|twitter|chatgpt|maps|google maps|spotify))?$").match(c)
    if not m: return None
    q, where = m.group(1).strip(), (m.group(2) or "").lower()
    if c.startswith("find ") and not where and ctx.get("app") not in ("youtube", "google", "chrome"):
        return [Action("keys", "ctrl+f"), Action("type", q)]
    if not where: where = ctx.get("app") if ctx.get("app") in ("youtube", "youtube music", "amazon", "flipkart", "github", "wikipedia") else "google"
    return [Action("search", q, where)]


def _media(c: str, ctx) -> list[Action] | None:
    table = [
        (r"^(?:pause|pause it|pause (?:the )?(?:video|song|music)|stop (?:the )?(?:video|song|music)|stop playing|music (?:pause|band))$", "pause"),
        (r"^(?:resume|resume it|continue|continue playing|unpause|play again)$", "play"),
        (r"^(?:next|next song|next track|skip|skip song|skip this|skip this song|next video|play next|next one)$", "next"),
        (r"^(?:previous|previous song|previous track|last song|previous video|go back a song|play previous)$", "previous"),
    ]
    for p, k in table:
        if _rx(p).match(c): return [Action("media", k)]
    m = _rx(r"^(?:skip|forward|fast forward|go forward|jump)(?: by)?\s+(\d+)\s*(seconds?|secs?|s|minutes?|mins?)$").match(c)
    if m: return [Action("media", "forward", n=int(m.group(1)) * (60 if m.group(2).startswith("m") else 1))]
    m = _rx(r"^(?:rewind|go back|back)(?: by)?\s+(\d+)\s*(seconds?|secs?|s|minutes?|mins?)$").match(c)
    if m: return [Action("media", "back", n=int(m.group(1)) * (60 if m.group(2).startswith("m") else 1))]
    return None


def _volume(c: str, ctx) -> list[Action] | None:
    if _rx(r"^(?:mute|mute it|mute the (?:sound|volume|pc|computer|audio)|silence|sound off|volume off)$").match(c): return [Action("volume", "mute")]
    if _rx(r"^(?:unmute|unmute it|sound on|volume on)$").match(c): return [Action("volume", "unmute")]
    m = _rx(r"^(?:set\s+)?(?:the\s+)?(?:volume|sound)\s+(?:to\s+|at\s+)?(\d{1,3})(?:\s*%| percent)?$").match(c) or _rx(r"^(?:volume|sound)\s+(full|max|maximum|zero|half)$").match(c)
    if m:
        v = m.group(1); lvl = {"full": 100, "max": 100, "maximum": 100, "zero": 0, "half": 50}.get(v, None) or int(v)
        return [Action("volume", "set", n=min(lvl, 100))]
    if _rx(r"^(?:volume up|increase (?:the )?volume|turn (?:it|the volume) up|louder|raise (?:the )?volume|volume increase|volume badhao|volume zyada|volume more)$").match(c): return [Action("volume", "up", n=10)]
    if _rx(r"^(?:volume down|decrease (?:the )?volume|turn (?:it|the volume) down|quieter|lower (?:the )?volume|volume decrease|volume kam|volume less)$").match(c): return [Action("volume", "down", n=10)]
    return None


def _scroll(c: str, ctx) -> list[Action] | None:
    if _rx(r"^(?:scroll to (?:the )?(?:top|beginning|start)|go to (?:the )?top|top of (?:the )?page|sabse up(?: jao)?|go up all the way)$").match(c): return [Action("scroll", "top")]
    if _rx(r"^(?:scroll to (?:the )?(?:bottom|end)|go to (?:the )?bottom|end of (?:the )?page|sabse down(?: jao)?|go down all the way)$").match(c): return [Action("scroll", "bottom")]
    m = _rx(r"^(?:scroll|swipe)(?:\s+(up|down|left|right))?(?:\s+(?:a little|a bit|thoda|more|again))?$").match(c) \
        or _rx(r"^(?:(?:thoda|thodi|aur|or|zara|bit)\s+)*(up|down)(?:\s+(?:scroll|jao|karo|go|more))?$").match(c) \
        or _rx(r"^(?:go|move)\s+(up|down)(?:\s+(?:a little|a bit|more))?$").match(c) \
        or _rx(r"^(page up|page down)$").match(c)
    if m:
        d = (m.group(1) or "down").lower()
        if d.startswith("page"): return [Action("keys", "pageup" if d.endswith("up") else "pagedown")]
        return [Action("scroll", d)]
    m = _rx(r"^(?:scroll|swipe)(?:\s+(up|down))?\s+(?:a lot|lots|bahut|zyada|far)$").match(c)
    if m: return [Action("scroll", (m.group(1) or "down"), n=5)]
    return None


def _type(c: str, ctx) -> list[Action] | None:
    m = _rx(r"^(?:type|write|dictate|enter text)\s*:?\s+(.+)$").match(c)
    if not m: return None
    text = m.group(1)
    # keep the user's original casing/punctuation: take it from the raw text later if needed
    send = False
    m2 = _rx(r"^(.+?)\s+and\s+(?:press\s+enter|hit\s+enter|send it|send)$").match(text)
    if m2: text, send = m2.group(1), True
    return [Action("type", text)] + ([Action("keys", "enter")] if send else [])


def _keys(c: str, ctx) -> list[Action] | None:
    if c in _HOTKEYS: return [Action("keys", _HOTKEYS[c])]
    m = _rx(r"^(?:press|hit|use)\s+(.+)$").match(c)
    if m:
        k = m.group(1).strip().replace(" plus ", "+").replace(" + ", "+")
        k = re.sub(r"\b(control)\b", "ctrl", k)
        k = re.sub(r"\s+", "+", k) if re.fullmatch(r"(?:(?:ctrl|alt|shift|win|windows)\s+)+\w+", k) else k
        return [Action("keys", _HOTKEYS.get(k, k))]
    return None


def _click(c: str, ctx) -> list[Action] | None:
    kind = "click"
    m = _rx(r"^(double click|double tap|right click)\s+(?:on\s+)?(.*)$").match(c)
    if m:
        kind = "double_click" if m.group(1).startswith("double") else "right_click"
        c = "click " + (m.group(2) or "")
    m = _rx(rf"^(?:click|tap|select|choose|press|open|hit)\s+(?:on\s+)?(?:the\s+)?{_ORDW}\s+{_KIND}(?:\s+(?:on the page|here|in the list))?$").match(c) \
        or _rx(rf"^(?:the\s+)?{_ORDW}\s+{_KIND}(?:\s+(?:open|click|play))?$").match(c)
    if m: return [Action(kind, "", _kind(m.group(2)), ordinal(m.group(1)) or 1)]
    m = _rx(rf"^(?:click|tap|select|open)\s+(?:on\s+)?(?:the\s+)?{_KIND}\s+(?:number\s+)?(\d+)$").match(c)
    if m: return [Action(kind, "", _kind(m.group(1)), int(m.group(2)))]
    m = _rx(r"^(?:click|tap|press|hit|select|choose|check|uncheck|toggle)\s+(?:on\s+)?(?:the\s+)?(.+?)(?:\s+(?:button|link|icon|tab|option|menu|checkbox))?$").match(c)
    if m:
        label = m.group(1).strip()
        if label in ("", "here", "it", "this"): return [Action(kind, "", "cursor")]
        return [Action(kind, label)]
    if kind != "click": return [Action(kind, "", "cursor")]
    return None


def _tabs(c: str, ctx) -> list[Action] | None:
    t = [
        (r"^(?:open\s+)?(?:a\s+)?new tab$|^new tab (?:kholo|open)$", ("tab", "new")),
        (r"^close (?:this |the |current )?tab$|^tab (?:band|close)(?: karo)?$", ("tab", "close")),
        (r"^(?:next tab|go to (?:the )?next tab|switch to (?:the )?next tab)$", ("tab", "next")),
        (r"^(?:previous tab|last tab|go to (?:the )?previous tab)$", ("tab", "previous")),
        (r"^(?:reopen|restore|bring back)(?: the)? (?:closed |last )?tab$|^reopen closed tab$", ("tab", "reopen")),
        (r"^(?:refresh|reload)(?: the| this)?(?: page| tab)?$", ("tab", "refresh")),
        (r"^(?:go back|back|previous page|go to (?:the )?previous page|wapas|peeche|piche)$", ("tab", "back")),
        (r"^(?:go forward|forward|next page)$", ("tab", "forward")),
        (r"^(?:open\s+)?(?:an?\s+)?(?:incognito|private)(?: window| tab)?$", ("tab", "incognito")),
        (r"^(?:open\s+|show\s+)?(?:my\s+|the\s+)?(?:browser\s+)?history$", ("tab", "history")),
        (r"^(?:open\s+|show\s+)?(?:my\s+|the\s+)?downloads(?: page)?$", ("tab", "downloads")),
        (r"^(?:bookmark|bookmark this|bookmark (?:this |the )?page|save this page)$", ("tab", "bookmark")),
    ]
    for p, (k, tg) in t:
        if _rx(p).match(c): return [Action(k, tg)]
    m = _rx(rf"^(?:go to|switch to|open)\s+(?:the\s+)?(?:tab\s+(\d+)|{_ORDW}\s+tab)$").match(c)
    if m: return [Action("tab", "go", n=int(m.group(1)) if m.group(1) else (ordinal(m.group(2)) or 1))]
    return None


def _window(c: str, ctx) -> list[Action] | None:
    t = [
        (r"^(?:close (?:this|the|current)? ?(?:window|app|program)?|close it|exit|quit(?: this)?(?: app)?|band karo)$", "close"),
        (r"^(?:minimi[sz]e|minimi[sz]e (?:this|it|the window|window))$", "minimize"),
        (r"^(?:maximi[sz]e|maximi[sz]e (?:this|it|the window|window)|make (?:it|this) (?:big|bigger|full))$", "maximize"),
        (r"^(?:restore|restore (?:the )?window)$", "restore"),
        (r"^(?:show (?:the )?desktop|go to (?:the )?desktop|minimi[sz]e (?:all|everything)|desktop dikhao)$", "desktop"),
        (r"^(?:snap (?:it |this |window )?(?:to (?:the )?)?left|move (?:it |this |window )?(?:to (?:the )?)?left)$", "snap_left"),
        (r"^(?:snap (?:it |this |window )?(?:to (?:the )?)?right|move (?:it |this |window )?(?:to (?:the )?)?right)$", "snap_right"),
        (r"^(?:full ?screen|go full ?screen|enter full ?screen|exit full ?screen)$", "fullscreen"),
        (r"^(?:task view|show (?:all )?windows|show all apps)$", "task_view"),
    ]
    for p, tg in t:
        if _rx(p).match(c): return [Action("window", tg)]
    m = _rx(r"^(?:close|quit|exit|kill)\s+(.+?)$").match(c)
    if m and catalog.canonical(m.group(1)): return [Action("window", "close", catalog.canonical(m.group(1)))]
    m = _rx(r"^(?:switch to|go to|bring up|focus)\s+(.+?)$").match(c)
    if m and catalog.canonical(m.group(1)) in catalog.APPS: return [Action("window", "switch", catalog.canonical(m.group(1)))]
    return None


def _system(c: str, ctx) -> list[Action] | None:
    t = [
        (r"^(?:lock|lock (?:the |my )?(?:pc|computer|screen|laptop))$", ("system", "lock")),
        (r"^(?:sleep|go to sleep|put (?:the )?(?:pc|computer|laptop) to sleep)$", ("system", "sleep")),
        (r"^(?:shut ?down|shut down (?:the )?(?:pc|computer|laptop)|turn off (?:the )?(?:pc|computer|laptop)|power off)$", ("system", "shutdown")),
        (r"^(?:restart|reboot|restart (?:the )?(?:pc|computer|laptop))$", ("system", "restart")),
        (r"^(?:take (?:a )?screenshot|screenshot|capture (?:the )?screen|screenshot lo|screenshot le lo)$", ("system", "screenshot")),
        (r"^(?:open\s+)?task manager$", ("system", "task_manager")),
        (r"^(?:empty|clear) (?:the )?recycle bin$", ("system", "empty_bin")),
        (r"^(?:open\s+)?(?:wifi|wi-fi|wi fi) settings$", ("system", "settings:network")),
        (r"^(?:open\s+)?bluetooth settings$", ("system", "settings:bluetooth")),
        (r"^(?:open\s+)?(?:display|screen) settings$", ("system", "settings:display")),
        (r"^(?:open\s+)?sound settings$", ("system", "settings:sound")),
    ]
    for p, (k, tg) in t:
        if _rx(p).match(c): return [Action(k, tg)]
    m = _rx(r"^(?:set\s+)?(?:the\s+)?brightness\s+(?:to\s+)?(\d{1,3})(?:\s*%| percent)?$").match(c)
    if m: return [Action("system", "brightness", n=min(int(m.group(1)), 100))]
    if _rx(r"^(?:brightness up|increase (?:the )?brightness|brighter)$").match(c): return [Action("system", "brightness_up", n=10)]
    if _rx(r"^(?:brightness down|decrease (?:the )?brightness|dimmer|dim the screen)$").match(c): return [Action("system", "brightness_down", n=10)]
    m = _rx(r"^(?:start|begin)\s+dictation$|^dictation (?:on|start)$").match(c)
    if m: return [Action("dictate", "start")]
    if _rx(r"^(?:stop|end)\s+dictation$|^dictation (?:off|stop)$").match(c): return [Action("dictate", "stop")]
    m = _rx(r"^(?:wait|ruko)(?:\s+(\d+)\s*(?:seconds?|secs?|s))?$").match(c)
    if m: return [Action("wait", n=int(m.group(1) or 2))]
    m = _rx(r"^(?:download)(?: (?:this|it|the file|that))?$").match(c)
    if m: return [Action("click", "download")]
    return None


_RULES = [_counted, _tabs, _media, _volume, _scroll, _system, _window, _search, _play, _type, _keys, _click, _open]
