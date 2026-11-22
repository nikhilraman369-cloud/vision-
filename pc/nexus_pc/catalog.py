"""What NEXUS knows by name: websites, desktop apps and their spoken aliases."""

# Websites (opened in Chrome). key = canonical name.
SITES = {
    "youtube": "https://www.youtube.com", "youtube music": "https://music.youtube.com", "google": "https://www.google.com",
    "gmail": "https://mail.google.com", "google drive": "https://drive.google.com", "google docs": "https://docs.google.com",
    "google sheets": "https://sheets.google.com", "google maps": "https://maps.google.com", "google calendar": "https://calendar.google.com",
    "google photos": "https://photos.google.com", "google meet": "https://meet.google.com", "google translate": "https://translate.google.com",
    "whatsapp web": "https://web.whatsapp.com", "instagram": "https://www.instagram.com", "facebook": "https://www.facebook.com",
    "twitter": "https://x.com", "linkedin": "https://www.linkedin.com", "reddit": "https://www.reddit.com", "github": "https://github.com",
    "chatgpt": "https://chatgpt.com", "claude": "https://claude.ai", "gemini": "https://gemini.google.com", "perplexity": "https://www.perplexity.ai",
    "netflix": "https://www.netflix.com", "amazon": "https://www.amazon.in", "flipkart": "https://www.flipkart.com", "hotstar": "https://www.hotstar.com",
    "prime video": "https://www.primevideo.com", "spotify web": "https://open.spotify.com", "wikipedia": "https://www.wikipedia.org",
    "canva": "https://www.canva.com", "notion": "https://www.notion.so", "zomato": "https://www.zomato.com", "swiggy": "https://www.swiggy.com",
    "outlook": "https://outlook.live.com", "telegram web": "https://web.telegram.org", "pinterest": "https://www.pinterest.com",
}

# Desktop apps: canonical → how Windows finds it (Start-menu name, exe, or URI). Resolved again at run time.
APPS = {
    "chrome": "chrome", "edge": "msedge", "firefox": "firefox", "notepad": "notepad", "calculator": "calc", "paint": "mspaint",
    "file explorer": "explorer", "settings": "ms-settings:", "task manager": "taskmgr", "command prompt": "cmd", "terminal": "wt",
    "powershell": "powershell", "word": "winword", "excel": "excel", "powerpoint": "powerpnt", "outlook app": "outlook", "onenote": "onenote",
    "vs code": "code", "spotify": "spotify", "whatsapp": "whatsapp:", "telegram": "telegram", "discord": "discord", "zoom": "zoom",
    "teams": "ms-teams:", "vlc": "vlc", "camera": "microsoft.windows.camera:", "photos": "ms-photos:", "clock": "ms-clock:",
    "snipping tool": "ms-screenclip:", "store": "ms-windows-store:", "control panel": "control", "recycle bin": "shell:RecycleBinFolder",
    "obs": "obs64", "steam": "steam", "premiere pro": "Adobe Premiere Pro", "photoshop": "photoshop", "after effects": "AfterFX",
    "davinci resolve": "Resolve", "capcut": "CapCut", "audacity": "audacity", "sticky notes": "ms-stickynotes:",
}

# Spoken aliases → canonical names (English, Hinglish, short forms, common mishearings).
ALIASES = {
    "yt": "youtube", "you tube": "youtube", "utube": "youtube", "youtub": "youtube",
    "yt music": "youtube music", "you tube music": "youtube music", "ytm": "youtube music", "youtube musik": "youtube music",
    "insta": "instagram", "ig": "instagram", "fb": "facebook", "x": "twitter", "gpt": "chatgpt", "chat gpt": "chatgpt",
    "mail": "gmail", "g mail": "gmail", "drive": "google drive", "docs": "google docs", "sheets": "google sheets", "maps": "google maps",
    "calendar": "google calendar", "meet": "google meet", "translate": "google translate", "wa": "whatsapp", "whats app": "whatsapp",
    "google chrome": "chrome", "browser": "chrome", "microsoft edge": "edge", "notes": "notepad", "note pad": "notepad",
    "calc": "calculator", "calci": "calculator", "explorer": "file explorer", "files": "file explorer", "my computer": "file explorer",
    "this pc": "file explorer", "cmd": "command prompt", "vscode": "vs code", "visual studio code": "vs code", "code editor": "vs code",
    "ms word": "word", "microsoft word": "word", "ms excel": "excel", "microsoft excel": "excel", "ppt": "powerpoint",
    "microsoft teams": "teams", "task manager": "task manager", "screenshot tool": "snipping tool", "microsoft store": "store",
    "play store": "store", "premiere": "premiere pro", "ps": "photoshop", "davinci": "davinci resolve", "netflix": "netflix",
    "primevideo": "prime video", "amazon prime": "prime video",
}

ALL_NAMES = set(SITES) | set(APPS) | set(ALIASES)


def canonical(name: str) -> str | None:
    n = " ".join(name.lower().split())
    n = n.removeprefix("the ").removesuffix(" app").removesuffix(" website").removesuffix(" site").strip()
    if n in SITES or n in APPS:
        return n
    return ALIASES.get(n)


def is_prefix_of_longer_name(name: str) -> bool:
    """'youtube' could still become 'youtube music' — wait a beat before acting on it."""
    n = canonical(name) or name
    return any(other != n and other.startswith(n + " ") for other in ALL_NAMES)
