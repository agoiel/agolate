"""Снимки главного окна без экрана — для проверки интерфейса в GitHub Actions.

    QT_QPA_PLATFORM=offscreen python tools/ui_preview.py out/
"""

import os
import sys
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from PySide6.QtWidgets import QApplication  # noqa: E402

from app.ui import theme  # noqa: E402
from app.ui.main_window import MainWindow, create_app  # noqa: E402
from app.ui.widgets import Card, Dropdown  # noqa: E402

out = Path(sys.argv[1] if len(sys.argv) > 1 else "out")
out.mkdir(parents=True, exist_ok=True)
app, cfg = create_app()


def settle():
    for _ in range(6):
        app.processEvents()


def shot(win, name, w, h):
    win.resize(w, h)
    settle()
    win.grab().save(str(out / f"{name}.png"))
    print("saved", name, w, h)


for mode in ("light", "dark"):
    theme.apply(app, mode)
    win = MainWindow(cfg)
    win.show()
    settle()
    if mode == "light":
        shot(win, "light_wide", 1100, 1500)
        shot(win, "light_medium", 760, 1700)
        shot(win, "light_narrow", 380, 2600)
        # раскрытые списки и свёрнутые карточки
        for dd in win.findChildren(Dropdown):
            if dd.title.text() in ("Google", "Яндекс") or "Google" in dd.title.text():
                dd.toggle()
        for card in win.findChildren(Card):
            if hasattr(card, "chevron"):
                card.toggle()
        shot(win, "light_expanded", 1100, 2600)
    else:
        shot(win, "dark_wide", 1100, 1500)
    win.close()
    settle()
print("done")
