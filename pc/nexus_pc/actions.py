from __future__ import annotations
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Action:
    """One thing to do. `kind` says what, `target`/`arg` say with what, `n` is a count or position."""
    kind: str
    target: str = ""
    arg: str = ""
    n: int = 1

    def __str__(self) -> str:
        parts = [self.kind]
        if self.target: parts.append(repr(self.target))
        if self.arg: parts.append(f"arg={self.arg!r}")
        if self.n != 1: parts.append(f"n={self.n}")
        return "(" + " ".join(parts) + ")"


# Action kinds (the executor implements each):
#   open_app  target=app name                 open_url  target=url
#   search    target=query, arg=engine/site   play      target=query, arg=where (youtube|ytmusic|spotify|"")
#   media     target=play|pause|toggle|next|previous|stop|forward|back, n=seconds for forward/back
#   volume    target=up|down|set|mute|unmute, n=amount/level
#   type      target=text                     keys      target="ctrl+c" style chord, n=repeat
#   scroll    target=up|down|left|right|top|bottom, n=times
#   click     target=label | "" ; arg=video|link|result|… for ordinal clicks; n=ordinal (-1 last); kind variants:
#             double_click, right_click
#   tab       target=new|close|next|previous|reopen|go(n)|refresh|back|forward|incognito|history|downloads|bookmark
#   window    target=close|minimize|maximize|restore|switch(app)|desktop|snap_left|snap_right|fullscreen|task_view
#   system    target=lock|sleep|shutdown|restart|screenshot|settings(page)|task_manager|empty_bin|brightness(n)
#   folder    target=downloads|documents|desktop|pictures|music|videos|<path>
#   dictate   target=start|stop
#   wait      n=seconds
#   screen    target=raw text — not a known shape: matched against what's on screen at run time
