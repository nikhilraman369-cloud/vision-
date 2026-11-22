"""Try it:  python -m nexus_pc "youtube kholo aur tum hi ho chalao"   → shows what NEXUS understood (nothing is executed yet)."""
import sys
from .grammar import understand

if __name__ == "__main__":
    text = " ".join(sys.argv[1:]) or input("Say something: ")
    for i, a in enumerate(understand(text), 1):
        print(f"{i}. {a}")
