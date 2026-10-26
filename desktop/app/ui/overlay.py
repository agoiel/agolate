"""Прозрачное окно поверх выделенной области: рисует перевод прямо на облаках.

Окно не перехватывает мышь (клики проходят сквозь него) и, если Windows позволяет,
скрыто от снимков экрана — поэтому не мешает следующему распознаванию.
"""

from __future__ import annotations

from PySide6.QtCore import QRect, QRectF, Qt
from PySide6.QtGui import QColor, QFont, QFontMetrics, QPainter, QPainterPath, QPen
from PySide6.QtWidgets import QWidget

from ..capture import Region, exclude_from_capture, find_screen
from ..models import PageResult

_FLAGS = int(Qt.TextFlag.TextWordWrap.value) | int(Qt.AlignmentFlag.AlignCenter.value)


class OverlayWindow(QWidget):
    def __init__(self, cfg):
        super().__init__(None, Qt.WindowType.FramelessWindowHint | Qt.WindowType.WindowStaysOnTopHint
                         | Qt.WindowType.Tool | Qt.WindowType.WindowTransparentForInput)
        self.cfg = cfg
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        self.result: PageResult | None = None
        self.k = 1.0
        self.excluded = False
        self.user_hidden = False
        self.show_frame = False

    # ------------------------------------------------------------ управление
    def place(self, region: Region) -> None:
        geo = find_screen(region.screen).geometry()
        self.setGeometry(geo.x() + region.x, geo.y() + region.y, region.w, region.h)

    def set_result(self, result: PageResult, k: float) -> None:
        self.result, self.k = result, k
        if not self.user_hidden:
            self.show()
        self.update()

    def clear(self) -> None:
        self.result = None
        self.update()

    def showEvent(self, e):
        super().showEvent(e)
        if not self.excluded:
            self.excluded = exclude_from_capture(self)

    # ------------------------------------------------------------ рисование
    def paintEvent(self, _):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setRenderHint(QPainter.RenderHint.TextAntialiasing)
        if self.show_frame:
            # рамка зоны перевода (кнопка «Показать зону»)
            r = self.rect().adjusted(1, 1, -2, -2)
            p.fillRect(r, QColor(172, 134, 83, 40))
            p.setPen(QPen(QColor(172, 134, 83), 3, Qt.PenStyle.DashLine))
            p.drawRect(r)
            label = f"Зона перевода · {self.width()}×{self.height()}"
            f = QFont("Segoe UI")
            f.setPixelSize(13)
            f.setWeight(QFont.Weight.DemiBold)
            p.setFont(f)
            tw = QFontMetrics(f).horizontalAdvance(label)
            pill = QRectF(10, 10, tw + 24, 28)
            path = QPainterPath()
            path.addRoundedRect(pill, 14, 14)
            p.fillPath(path, QColor(61, 43, 40))
            p.setPen(QColor(243, 227, 204))
            p.drawText(pill, int(Qt.AlignmentFlag.AlignCenter.value), label)
        if not self.result:
            p.end()
            return

        paint_plates(p, self.result.bubbles, self.k, self.rect(), self.cfg)
        p.end()


def _fit(text: str, rect: QRect, family: str, max_size: int, min_size: int = 8):
    width = max(20, rect.width() - 6)
    max_size = max(max_size, min_size)
    f, br = QFont(family), QRect()
    for size in range(max_size, min_size - 1, -1):
        f = QFont(family)
        f.setPixelSize(size)
        f.setWeight(QFont.Weight.DemiBold)
        br = QFontMetrics(f).boundingRect(QRect(0, 0, width, 10000), _FLAGS, text)
        if br.height() + 4 <= rect.height():
            return f, br.height() + 4
    return f, br.height() + 4


def paint_plates(p: QPainter, bubbles, k: float, bounds: QRect, cfg) -> None:
    """Рисует плашки с переводом. k — во сколько раз пиксели картинки крупнее пикселей окна."""
    family = cfg.get("overlay_font", "Segoe UI")
    max_size = int(cfg.get("overlay_max_font_size", 20))
    opacity = float(cfg.get("overlay_opacity", 0.95))

    for b in bubbles:
        if not b.translation:
            continue
        box = b.box.scaled(1 / k)
        line_h = max(8.0, (sum(l.box.h for l in b.lines) / len(b.lines)) / k)
        screen = b.kind == "screen"
        pad = 2.0 if screen else max(4.0, 0.35 * line_h)
        # В манхве русский текст длиннее корейского — даём блоку стать шире;
        # обычный текст на экране закрываем точно по месту
        w = max(box.w + 2 * pad, 24) if screen else max(box.w + 2 * pad, box.w * 1.3, 60)
        h = box.h + 2 * pad
        rect = QRect(round(box.cx - w / 2), round(box.cy - h / 2), round(w), round(h))
        clipped = rect.intersected(bounds.adjusted(1, 1, -1, -1))
        if not clipped.isEmpty():
            rect = clipped

        size_cap = min(max_size, max(9, int(line_h * 0.9))) if screen else max_size
        font, need_h = _fit(b.translation, rect, family, size_cap)
        if need_h > rect.height():
            grow = need_h - rect.height() + 4
            rect.adjust(0, -grow // 2, 0, grow - grow // 2)

        r, g, bl = b.bg_color
        if screen:
            bg = QColor(r, g, bl, 250)
            lum = 0.299 * r + 0.587 * g + 0.114 * bl
            fg = QColor(15, 15, 15) if lum > 140 else QColor(250, 250, 250)
        elif b.kind == "free":
            bg = QColor(20, 20, 20, int(220 * opacity))
            fg = QColor(255, 255, 255)
        else:
            bg = QColor(r, g, bl, int(255 * opacity))
            lum = 0.299 * r + 0.587 * g + 0.114 * bl
            fg = QColor(15, 15, 15) if lum > 140 else QColor(250, 250, 250)

        path = QPainterPath()
        path.addRoundedRect(QRectF(rect), 3 if screen else 8, 3 if screen else 8)
        p.fillPath(path, bg)
        p.setFont(font)
        p.setPen(fg)
        p.drawText(rect.adjusted(3, 2, -3, -2), _FLAGS, b.translation)
