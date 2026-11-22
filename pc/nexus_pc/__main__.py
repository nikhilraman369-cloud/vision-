"""
    python -m nexus_pc                       the white dot (tap to talk, right-click to type)
    python -m nexus_pc --dry  "text"         only show what NEXUS understood (nothing runs)
    python -m nexus_pc --run  "text"         run it now, from the console
    python -m nexus_pc --listen              talk once into the microphone and run it
    python -m nexus_pc --wav file.wav [--dry]   the same from a 16 kHz mono WAV
"""
import argparse
import sys
import time


def _print_events(e):
    if e.kind in ("done", "failed") and e.job:
        print(f"  {'✓' if e.job.result.ok else '✗'} {e.job.action}  {e.job.result.msg}  ({e.job.ended - e.job.started:.2f}s)" if e.job.started
              else f"  ✗ {e.job.action}  {e.job.result.msg}")
    elif e.kind == "cancelled" and e.job is None:
        print("  stopped:", e.text)


def main() -> int:
    try: sys.stdout.reconfigure(encoding="utf-8")
    except Exception: pass
    ap = argparse.ArgumentParser(prog="nexus_pc", description="NEXUS for PC")
    ap.add_argument("text", nargs="*")
    ap.add_argument("--dry", action="store_true", help="only show what was understood")
    ap.add_argument("--run", action="store_true", help="run the text now")
    ap.add_argument("--listen", action="store_true", help="one voice command from the microphone")
    ap.add_argument("--wav", help="one voice command from a WAV file")
    ap.add_argument("--yes", action="store_true", help="allow shutdown/restart/sleep/empty bin without asking")
    a = ap.parse_args()
    text = " ".join(a.text)

    if not (a.run or a.listen or a.wav or a.dry or text):
        from .dot import main as dot_main
        return dot_main()

    from .grammar import understand
    if a.dry and not (a.listen or a.wav):
        for i, act in enumerate(understand(text or input("Say something: ")), 1): print(f"{i}. {act}")
        return 0

    from .cmdqueue import CommandQueue
    confirm = (lambda act: True) if a.yes else (lambda act: input(f"  {act.target}? [y/N] ").strip().lower() == "y")
    q = None if a.dry else CommandQueue(on_event=_print_events, confirm=confirm).start()
    if a.listen or a.wav:
        from .voice import Voice
        t = time.time()
        v = Voice(q, on_text=lambda s, final: print(("heard: " if final else "  … ") + s) if final else None).load()
        print(f"voice ready ({time.time() - t:.1f}s). " + ("Speak now." if a.listen else ""))
        v.listen_file(a.wav) if a.wav else v.listen()
        for when, what, detail in v.log:
            if what in ("early", "final", "cancel"): print(f"  {when:5.2f}s {what}: {detail}")
    else:
        q.submit_text(text)
    if q:
        q.wait_idle(120); q.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
