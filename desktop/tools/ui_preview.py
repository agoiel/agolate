"""Снимки главного окна без экрана — для проверки интерфейса в GitHub Actions.

    QT_QPA_PLATFORM=offscreen python tools/ui_preview.py out/
"""

import os
import sys
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")  # на Windows-сборке задаётся windows
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


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


from PySide6.QtCore import QPoint, Qt  # noqa: E402
from PySide6.QtTest import QTest  # noqa: E402

from app.ui.widgets import Clickable  # noqa: E402


def report(tag, dd):
    """Сколько места нужно заголовку и сколько он получил — если меньше, текст наезжает."""
    h = dd.header
    print(f"[{tag}] header need={h.minimumSizeHint().height()}/{h.sizeHint().height()} got={h.height()} "
          f"title y={dd.title.y()} h={dd.title.height()} sub y={dd.subtitle.y()} h={dd.subtitle.height()} "
          f"list visible={dd.list.isVisible()} need={dd.list.sizeHint().height()} got={dd.list.height()} "
          f"dropdown got={dd.height()}")


def click(w):
    QTest.mouseClick(w, Qt.MouseButton.LeftButton, Qt.KeyboardModifier.NoModifier,
                     QPoint(w.width() // 2, min(20, w.height() // 2)))
    settle()


# 1) как у пользователя: окно открыто, кликаем мышкой по спискам и карточкам
theme.apply(app, "dark")
win = MainWindow(cfg)
win.resize(1000, 1100)
win.show()
settle()
heads = [w for w in win.findChildren(Clickable) if w.objectName() == "dropHeader"]
translator = next(d for d in win.findChildren(Dropdown) if "deepl" in d.choices)
report("before", translator)
click(translator.header)
report("opened", translator)
win.grab().save(str(out / "click_translator_open.png"))
print("translator list visible:", translator.list.isVisible(), "height:", translator.list.height())
# выбрать другой переводчик кликом по пункту
choice = translator.choices["deepl"]
click(choice)
win.grab().save(str(out / "click_translator_chosen.png"))
print("chosen:", translator.current, "list visible after choose:", translator.list.isVisible())
cards = [c for c in win.findChildren(Card) if hasattr(c, "chevron")]
collapse_heads = [w for w in cards[0].findChildren(Clickable) if w.objectName() == "collapseHeader"]
click(collapse_heads[0])
print("tuning open:", cards[0].body.isVisible(), "height:", cards[0].body.height())
win.scroll.verticalScrollBar().setValue(cards[0].mapTo(win.scroll.widget(), QPoint(0, 0)).y() - 20)
settle()
win.grab().save(str(out / "click_tuning_open.png"))
win.close()
settle()

if os.environ.get("PREVIEW_ONLY_CLICKS"):
    print("done")
    sys.exit(0)

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
