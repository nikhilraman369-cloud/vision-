"""A throwaway window for the live tests: a text box, buttons and a list. It reports what happened to a JSON file.

    python tests/pad_app.py <report.json>
"""
import json
import sys

from PyQt6.QtWidgets import QApplication, QListWidget, QPushButton, QTextEdit, QVBoxLayout, QHBoxLayout, QWidget

report_path = sys.argv[1]
state = {"text": "", "clicks": [], "double": [], "selected": ""}


def save():
    with open(report_path, "w", encoding="utf-8") as f: json.dump(state, f)


app = QApplication(sys.argv[:1])
w = QWidget(); w.setWindowTitle("NEXUS Test Pad"); w.resize(520, 420); w.move(200, 150)
lay = QVBoxLayout(w)
edit = QTextEdit(); edit.setAccessibleName("Notes"); lay.addWidget(edit)
edit.textChanged.connect(lambda: (state.__setitem__("text", edit.toPlainText()), save()))
row = QHBoxLayout(); lay.addLayout(row)
for name in ("Sign in", "Cancel", "Subscribe"):
    b = QPushButton(name); row.addWidget(b)
    b.clicked.connect(lambda _=False, n=name: (state["clicks"].append(n), save()))
lst = QListWidget(); lst.addItems(["Apple", "Banana", "Cherry"]); lay.addWidget(lst)
lst.itemDoubleClicked.connect(lambda it: (state["double"].append(it.text()), save()))
lst.currentTextChanged.connect(lambda t: (state.__setitem__("selected", t), save()))
save()
w.show(); edit.setFocus()
sys.exit(app.exec())
