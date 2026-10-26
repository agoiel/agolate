"""Элементы интерфейса в стиле Android-версии: карточки, кнопки, плитки, списки, переключатели."""

from __future__ import annotations

from PySide6.QtCore import (Property, QEasingCurve, QEvent, QPoint, QPropertyAnimation, QRect, QRectF, QSize, Qt,
                            QTimer, Signal)
from PySide6.QtGui import QColor, QLinearGradient, QPainter, QPainterPath, QPixmap
from PySide6.QtWidgets import (QAbstractButton, QBoxLayout, QButtonGroup, QFrame, QHBoxLayout, QLabel, QLayout,
                               QLineEdit, QPushButton, QScrollArea, QSizePolicy, QToolButton, QVBoxLayout, QWidget)

from . import theme


class Clickable(QFrame):
    """Рамка, которая ведёт себя как кнопка, но правильно считает высоту содержимого."""

    clicked = Signal()

    def __init__(self, name: str):
        super().__init__()
        self.setObjectName(name)
        self.setCursor(Qt.CursorShape.PointingHandCursor)

    def mousePressEvent(self, e):
        # нажатие нужно «забрать», иначе отпускание кнопки мыши сюда не придёт
        if e.button() == Qt.MouseButton.LeftButton:
            e.accept()
        else:
            super().mousePressEvent(e)

    def mouseReleaseEvent(self, e):
        if e.button() == Qt.MouseButton.LeftButton and self.rect().contains(e.position().toPoint()):
            self.clicked.emit()


def relayout(w: QWidget) -> None:
    """Пересчитать высоту всех родителей — после того как что-то раскрылось или свернулось."""
    p = w
    while p is not None:
        lay = p.layout()
        if lay is not None:
            lay.invalidate()
            lay.activate()
        p.updateGeometry()
        if isinstance(p, ScrollContent):
            p.schedule()
        p = p.parentWidget()


class ScrollContent(QWidget):
    """Содержимое окна прокрутки. Всегда получает полную высоту для своей ширины.

    Сам QScrollArea иногда отдаёт содержимому только минимальную высоту, и тогда при
    раскрытии карточек тексты наезжают друг на друга. Здесь высота пересчитывается
    после каждого изменения раскладки.
    """

    def __init__(self):
        super().__init__()
        self._pending = False

    def refit(self) -> None:
        self._pending = False
        lay = self.layout()
        if lay is None:
            return
        lay.activate()
        h = lay.totalHeightForWidth(self.width()) if lay.hasHeightForWidth() else -1
        h = max(h, lay.totalSizeHint().height(), lay.totalMinimumSize().height())
        if h != self.minimumHeight():
            self.setMinimumHeight(h)

    def schedule(self) -> None:
        if not self._pending:
            self._pending = True
            QTimer.singleShot(0, self.refit)

    def resizeEvent(self, e):
        super().resizeEvent(e)
        if e.size().width() != e.oldSize().width():
            self.schedule()

    def event(self, e):
        if e.type() == QEvent.Type.LayoutRequest:
            self.schedule()
        return super().event(e)


def _repolish(w: QWidget) -> None:
    w.style().unpolish(w)
    w.style().polish(w)


def label(text: str, name: str = "", wrap: bool = True) -> QLabel:
    lb = QLabel(text)
    if name:
        lb.setObjectName(name)
    lb.setWordWrap(wrap)
    return lb


class ElideLabel(QLabel):
    """Метка в одну строку: если не помещается, обрезается многоточием (полный текст — в подсказке)."""

    def __init__(self, text: str = "", name: str = ""):
        super().__init__()
        if name:
            self.setObjectName(name)
        self._full = ""
        self.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Fixed)
        self.setText(text)

    def setText(self, text: str) -> None:  # noqa: N802
        self._full = text
        self._elide()

    def text(self) -> str:
        return self._full

    def _elide(self) -> None:
        shown = self.fontMetrics().elidedText(self._full, Qt.TextElideMode.ElideRight, max(self.width(), 40))
        super().setText(shown)
        self.setToolTip(self._full if shown != self._full else "")

    def sizeHint(self):
        return QSize(self.fontMetrics().horizontalAdvance(self._full) + 4, super().sizeHint().height())

    def minimumSizeHint(self):
        return QSize(40, super().minimumSizeHint().height())

    def resizeEvent(self, e):
        super().resizeEvent(e)
        self._elide()

    def changeEvent(self, e):
        super().changeEvent(e)
        if e.type() in (QEvent.Type.FontChange, QEvent.Type.StyleChange):
            self._elide()
            self.updateGeometry()


def caption(text: str) -> QLabel:
    return label(text, "caption")


def pill(text: str, kind: str = "primary", icon_name: str | None = None, tooltip: str = "") -> QPushButton:
    """Кнопка-таблетка. kind: primary | hero | heroOutline | outline | tonal | link."""
    b = QPushButton(text)
    b.setProperty("kind", kind)
    b.setCursor(Qt.CursorShape.PointingHandCursor)
    if icon_name:
        c = theme.palette()
        color = {"primary": c.on_primary, "hero": theme.INK, "heroOutline": theme.CREAM}.get(kind, c.primary)
        b.setIcon(theme.icon(icon_name, color, 18))
        b.setIconSize(QSize(18, 18))
    if tooltip:
        b.setToolTip(tooltip)
    return b


def set_pill(b: QPushButton, text: str, kind: str, icon_name: str | None = None) -> None:
    b.setText(text)
    b.setProperty("kind", kind)
    if icon_name:
        c = theme.palette()
        color = {"primary": c.on_primary, "hero": theme.INK, "heroOutline": theme.CREAM}.get(kind, c.primary)
        b.setIcon(theme.icon(icon_name, color, 18))
    _repolish(b)


# ---------------------------------------------------------------- карточка

class Card(QFrame):
    """Карточка со скруглёнными углами: заголовок с иконкой, подпись и содержимое."""

    def __init__(self, title: str, subtitle: str = "", icon_name: str = "", collapsible: bool = False,
                 collapsed: bool = False):
        super().__init__()
        self.setObjectName("card")
        self.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Minimum)
        outer = QVBoxLayout(self)
        outer.setContentsMargins(16, 14, 16, 16)
        outer.setSpacing(10)

        head = QWidget()
        hl = QHBoxLayout(head)
        hl.setContentsMargins(0, 0, 0, 0)
        hl.setSpacing(12)
        if icon_name:
            badge = QLabel()
            badge.setObjectName("badge")
            badge.setFixedSize(36, 36)
            badge.setAlignment(Qt.AlignmentFlag.AlignCenter)
            badge.setPixmap(theme.pixmap(icon_name, theme.palette().primary, 20))
            hl.addWidget(badge, 0, Qt.AlignmentFlag.AlignTop)
        col = QVBoxLayout()
        col.setSpacing(0)
        col.addWidget(ElideLabel(title, "cardTitle"))
        if subtitle:
            col.addWidget(ElideLabel(subtitle, "cardSubtitle"))
        hl.addLayout(col, 1)

        self.body = QWidget()
        self.lay = QVBoxLayout(self.body)
        self.lay.setContentsMargins(0, 4, 0, 0)
        self.lay.setSpacing(10)

        if collapsible:
            self.chevron = QLabel()
            self.chevron.setFixedSize(22, 22)
            hl.addWidget(self.chevron, 0, Qt.AlignmentFlag.AlignVCenter)
            btn = Clickable("collapseHeader")
            bl = QVBoxLayout(btn)
            bl.setContentsMargins(0, 0, 0, 0)
            bl.addWidget(head)
            btn.clicked.connect(self.toggle)
            outer.addWidget(btn)
            self._open = not collapsed
            self._render_collapse()
        else:
            outer.addWidget(head)
        outer.addWidget(self.body)

    def add(self, w: QWidget | QLayout, stretch: int = 0) -> None:
        if isinstance(w, QLayout):
            self.lay.addLayout(w, stretch)
        else:
            self.lay.addWidget(w, stretch)

    def toggle(self) -> None:
        self._open = not self._open
        self._render_collapse()

    def _render_collapse(self) -> None:
        self.body.setVisible(self._open)
        relayout(self.body)
        pm = theme.pixmap("chevron", theme.palette().primary, 22)
        if self._open:
            pm = pm.transformed(__import__("PySide6.QtGui", fromlist=["QTransform"]).QTransform().rotate(180))
        self.chevron.setPixmap(pm)


# ---------------------------------------------------------------- плитка

class Tile(QToolButton):
    """Плитка быстрого действия: иконка в кружке и подпись."""

    def __init__(self, text: str, icon_name: str, tooltip: str = ""):
        super().__init__()
        self.setObjectName("tile")
        self.setText(text)
        c = theme.palette()
        pm = QPixmap(44, 44)
        pm.fill(Qt.GlobalColor.transparent)
        p = QPainter(pm)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setBrush(QColor(c.selected))
        p.setPen(Qt.PenStyle.NoPen)
        p.drawEllipse(0, 0, 44, 44)
        p.drawPixmap(11, 11, theme.pixmap(icon_name, c.primary, 22))
        p.end()
        self.setIcon(pm)
        self.setIconSize(QSize(44, 44))
        self.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextUnderIcon)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.setMinimumHeight(96)
        if tooltip:
            self.setToolTip(tooltip)


# ---------------------------------------------------------------- раскрывающийся список

class _Choice(QFrame):
    clicked = Signal()

    def __init__(self, title: str, subtitle: str):
        super().__init__()
        self.setObjectName("choice")
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(12, 8, 10, 8)
        col = QVBoxLayout()
        col.setSpacing(1)
        t = label(title)
        t.setStyleSheet("font-weight: 600;")
        col.addWidget(t)
        if subtitle:
            col.addWidget(caption(subtitle))
        lay.addLayout(col, 1)
        self.check = QLabel()
        self.check.setFixedSize(22, 22)
        lay.addWidget(self.check)

    def set_checked(self, on: bool) -> None:
        self.setProperty("checked", "true" if on else "false")
        c = theme.palette()
        self.check.setPixmap(theme.pixmap("check", c.primary, 20) if on else QPixmap())
        _repolish(self)

    def mousePressEvent(self, e):
        if e.button() == Qt.MouseButton.LeftButton:
            e.accept()

    def mouseReleaseEvent(self, e):
        if e.button() == Qt.MouseButton.LeftButton and self.rect().contains(e.position().toPoint()):
            self.clicked.emit()


class _DropPopup(QFrame):
    """Выпадающий список — отдельное всплывающее окно под полем, как у обычных списков Windows.

    Окно не зависит от раскладки главного окна, поэтому ничего не сдвигает и не сжимает.
    """

    closed = Signal()

    def __init__(self, owner: QWidget, content: QWidget):
        super().__init__(owner, Qt.WindowType.Popup | Qt.WindowType.FramelessWindowHint
                         | Qt.WindowType.NoDropShadowWindowHint)
        self.setObjectName("dropPopup")
        self.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(4, 6, 4, 6)
        self.area = QScrollArea()
        self.area.setObjectName("dropPopupArea")
        self.area.setWidgetResizable(True)
        self.area.setFrameShape(QFrame.Shape.NoFrame)
        self.area.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.area.setWidget(content)
        content.setObjectName("dropPopupContent")
        lay.addWidget(self.area)

    def hideEvent(self, e):
        super().hideEvent(e)
        self.closed.emit()

    def keyPressEvent(self, e):
        if e.key() == Qt.Key.Key_Escape:
            self.hide()
        else:
            super().keyPressEvent(e)


class Dropdown(QFrame):
    """Поле выбора: по нажатию под ним выпадает список с описанием каждого варианта."""

    changed = Signal(str)

    def __init__(self, caption_text: str, items: list[tuple[str, str, str, str]], current: str):
        """items: (ключ, название, описание, группа)."""
        super().__init__()
        self.setObjectName("dropdown")
        self.items = items
        self.current = current
        self.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Fixed)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(2, 2, 2, 2)
        lay.setSpacing(0)

        self.header = Clickable("dropHeader")
        hl = QHBoxLayout(self.header)
        hl.setContentsMargins(12, 8, 10, 8)
        col = QVBoxLayout()
        col.setSpacing(1)
        col.addWidget(ElideLabel(caption_text.upper(), "fieldLabel"))
        self.title = ElideLabel("")
        self.title.setStyleSheet("font-size: 11pt; font-weight: 600;")
        self.subtitle = ElideLabel("", "caption")
        col.addWidget(self.title)
        col.addWidget(self.subtitle)
        hl.addLayout(col, 1)
        self.chev = QLabel()
        self.chev.setFixedSize(22, 22)
        hl.addWidget(self.chev)
        self.header.clicked.connect(self.toggle)
        lay.addWidget(self.header)

        self.list = QWidget()
        ll = QVBoxLayout(self.list)
        ll.setContentsMargins(4, 0, 6, 0)
        ll.setSpacing(6)
        self.choices: dict[str, _Choice] = {}
        group = None
        for key, title, sub, grp in items:
            if grp and grp != group:
                group = grp
                ll.addWidget(label(grp, "sectionLabel"))
            ch = _Choice(title, sub)
            ch.clicked.connect(lambda k=key: self._choose(k))
            self.choices[key] = ch
            ll.addWidget(ch)
        ll.addStretch(1)
        # список получает свою полную высоту, а не минимально возможную
        self.list.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Minimum)
        self.popup = _DropPopup(self, self.list)
        self.popup.closed.connect(self._on_closed)
        self._render()

    def is_open(self) -> bool:
        return self.popup.isVisible()

    def toggle(self) -> None:
        if self.popup.isVisible():
            self.popup.hide()
        else:
            self.open()

    def open(self) -> None:
        w = max(self.width(), 300)
        need = self.list.layout().totalHeightForWidth(w - 26) if self.list.layout().hasHeightForWidth() else -1
        need = max(need, self.list.sizeHint().height()) + 14
        top_left = self.mapToGlobal(QPoint(0, self.height() + 4))
        screen = self.screen().availableGeometry()
        below = screen.bottom() - top_left.y() - 8
        above = self.mapToGlobal(QPoint(0, 0)).y() - screen.top() - 12
        if below >= min(need, 260) or below >= above:
            h = min(need, below)
            pos = top_left
        else:
            h = min(need, above)
            pos = self.mapToGlobal(QPoint(0, -h - 4))
        self.popup.setFixedSize(w, max(h, 120))
        self.popup.move(pos)
        self.setProperty("open", "true")
        _repolish(self)
        self._render_chevron()
        self.popup.show()
        ch = self.choices.get(self.current)
        if ch is not None:
            self.popup.area.ensureWidgetVisible(ch, 0, 12)

    def _on_closed(self) -> None:
        self.setProperty("open", "false")
        _repolish(self)
        self._render_chevron()

    def select(self, key: str) -> None:
        self.current = key
        self._render()

    def _choose(self, key: str) -> None:
        self.current = key
        self.popup.hide()
        self._render()
        self.changed.emit(key)

    def _render(self) -> None:
        item = next((i for i in self.items if i[0] == self.current), self.items[0])
        self.title.setText(item[1])
        self.subtitle.setText(item[2])
        self.subtitle.setVisible(bool(item[2]))
        self.setToolTip(item[2])
        for k, ch in self.choices.items():
            ch.set_checked(k == item[0])
        self._render_chevron()

    def _render_chevron(self) -> None:
        from PySide6.QtGui import QTransform
        pm = theme.pixmap("chevron", theme.palette().primary, 22)
        if self.popup.isVisible():
            pm = pm.transformed(QTransform().rotate(180))
        self.chev.setPixmap(pm)


# ---------------------------------------------------------------- поле ввода с подписью

class Field(QWidget):
    edited = Signal(str)

    def __init__(self, caption_text: str, value: str = "", placeholder: str = "", secret: bool = False):
        super().__init__()
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(4)
        if caption_text:
            lay.addWidget(label(caption_text.upper(), "fieldLabel"))
        self.edit = QLineEdit(value)
        self.edit.setPlaceholderText(placeholder)
        if secret:
            self.edit.setEchoMode(QLineEdit.EchoMode.PasswordEchoOnEdit)
        self.edit.editingFinished.connect(lambda: self.edited.emit(self.edit.text().strip()))
        lay.addWidget(self.edit)


# ---------------------------------------------------------------- число с кнопками − и +

class Stepper(QWidget):
    changed = Signal(float)

    def __init__(self, title: str, subtitle: str, value: float, step: float, lo: float, hi: float, fmt):
        super().__init__()
        self.value, self.step, self.lo, self.hi, self.fmt = value, step, lo, hi, fmt
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(8)
        col = QVBoxLayout()
        col.setSpacing(0)
        col.addWidget(label(title))
        if subtitle:
            col.addWidget(caption(subtitle))
        lay.addLayout(col, 1)
        minus = pill("−", "round")
        plus = pill("+", "round")
        self.out = QLabel()
        self.out.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.out.setMinimumWidth(56)
        self.out.setStyleSheet("font-weight: 600;")
        minus.clicked.connect(lambda: self._change(-step))
        plus.clicked.connect(lambda: self._change(step))
        lay.addWidget(minus)
        lay.addWidget(self.out)
        lay.addWidget(plus)
        self._render()

    def _change(self, d: float) -> None:
        self.value = round(min(self.hi, max(self.lo, self.value + d)), 2)
        self._render()
        self.changed.emit(self.value)

    def _render(self) -> None:
        self.out.setText(self.fmt(self.value))


# ---------------------------------------------------------------- переключатель

class Toggle(QAbstractButton):
    """Переключатель в стиле Android с плавным движением."""

    def __init__(self, checked: bool = False):
        super().__init__()
        self.setCheckable(True)
        self.setChecked(checked)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setFixedSize(46, 26)
        self._pos = 1.0 if checked else 0.0
        self._anim = QPropertyAnimation(self, b"pos", self)
        self._anim.setDuration(140)
        self._anim.setEasingCurve(QEasingCurve.Type.OutCubic)
        self.toggled.connect(self._animate)

    def _animate(self, on: bool) -> None:
        self._anim.stop()
        self._anim.setStartValue(self._pos)
        self._anim.setEndValue(1.0 if on else 0.0)
        self._anim.start()

    def _get_pos(self) -> float:
        return self._pos

    def _set_pos(self, v: float) -> None:
        self._pos = v
        self.update()

    pos = Property(float, _get_pos, _set_pos)

    def paintEvent(self, _):
        c = theme.palette()
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        track = QColor(c.primary) if self.isChecked() else QColor(c.border)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(track)
        p.drawRoundedRect(QRectF(0, 3, 46, 20), 10, 10)
        x = 3 + self._pos * 20
        p.setBrush(QColor(c.on_primary if self.isChecked() else c.surface))
        p.setPen(QColor(0, 0, 0, 30))
        p.drawEllipse(QRectF(x, 3, 20, 20))
        p.end()


class SwitchRow(QWidget):
    toggled = Signal(bool)

    def __init__(self, title: str, subtitle: str, checked: bool):
        super().__init__()
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 2, 0, 2)
        col = QVBoxLayout()
        col.setSpacing(0)
        col.addWidget(label(title))
        if subtitle:
            col.addWidget(caption(subtitle))
        lay.addLayout(col, 1)
        self.switch = Toggle(checked)
        self.switch.toggled.connect(self.toggled)
        lay.addWidget(self.switch)

    def mousePressEvent(self, e):
        if e.button() == Qt.MouseButton.LeftButton:
            e.accept()

    def mouseReleaseEvent(self, e):
        if e.button() == Qt.MouseButton.LeftButton and self.rect().contains(e.position().toPoint()):
            self.switch.toggle()


# ---------------------------------------------------------------- переключатель режимов в шапке

class Segmented(QFrame):
    changed = Signal(str)

    def __init__(self, options: list[tuple[str, str]], current: str):
        super().__init__()
        self.setObjectName("segmented")
        lay = QHBoxLayout(self)
        lay.setContentsMargins(4, 4, 4, 4)
        lay.setSpacing(2)
        self.group = QButtonGroup(self)
        self.buttons: dict[str, QPushButton] = {}
        for key, text in options:
            b = QPushButton(text)
            b.setProperty("kind", "segment")
            b.setCheckable(True)
            b.setCursor(Qt.CursorShape.PointingHandCursor)
            b.setChecked(key == current)
            b.clicked.connect(lambda _=False, k=key: self.changed.emit(k))
            self.group.addButton(b)
            self.buttons[key] = b
            lay.addWidget(b, 1)

    def select(self, key: str) -> None:
        if key in self.buttons:
            self.buttons[key].setChecked(True)


# ---------------------------------------------------------------- метка состояния

class StatusChip(QFrame):
    def __init__(self):
        super().__init__()
        self.setObjectName("chip")
        lay = QHBoxLayout(self)
        lay.setContentsMargins(10, 4, 12, 4)
        lay.setSpacing(8)
        self.dot = QLabel()
        self.dot.setFixedSize(8, 8)
        self.text = QLabel()
        self.text.setStyleSheet(f"color: {theme.CREAM}; background: transparent; font-weight: 600; font-size: 9pt;")
        lay.addWidget(self.dot)
        lay.addWidget(self.text)
        self.setSizePolicy(QSizePolicy.Policy.Maximum, QSizePolicy.Policy.Fixed)

    def set(self, active: bool, text: str) -> None:
        color = "#9BE7C4" if active else "rgba(243,227,204,0.55)"
        self.dot.setStyleSheet(f"background: {color}; border-radius: 4px;")
        self.text.setText(text)


# ---------------------------------------------------------------- шапка с градиентом

class Hero(QWidget):
    """Тёмная шапка с градиентом, скруглённым низом и полупрозрачным облачком."""

    def paintEvent(self, _):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        r = QRectF(self.rect())
        path = QPainterPath()
        rad = 26.0
        path.moveTo(r.left(), r.top())
        path.lineTo(r.right(), r.top())
        path.lineTo(r.right(), r.bottom() - rad)
        path.quadTo(r.right(), r.bottom(), r.right() - rad, r.bottom())
        path.lineTo(r.left() + rad, r.bottom())
        path.quadTo(r.left(), r.bottom(), r.left(), r.bottom() - rad)
        path.closeSubpath()
        g = QLinearGradient(r.topLeft(), r.bottomRight())
        g.setColorAt(0, QColor(theme.INK))
        g.setColorAt(1, QColor(theme.HERO_END))
        p.fillPath(path, g)
        p.setClipPath(path)
        w, h = r.width(), r.height()
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QColor(255, 255, 255, 14))
        p.drawEllipse(QRectF(w * 0.80, -h * 0.25, w * 0.42, w * 0.42))
        p.setBrush(QColor(255, 255, 255, 9))
        p.drawEllipse(QRectF(w * 0.88, h * 0.55, w * 0.30, w * 0.30))
        # облачко с репликой
        bw = min(170.0, w * 0.22)
        bh = bw * 0.62
        left, top = w - bw - 30, 22.0
        pen_color = QColor(255, 255, 255, 46)
        from PySide6.QtGui import QPen
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.setPen(QPen(pen_color, 2))
        p.drawEllipse(QRectF(left, top, bw, bh))
        tail = QPainterPath()
        tail.moveTo(left + bw * 0.28, top + bh * 0.92)
        tail.lineTo(left + bw * 0.18, top + bh * 1.22)
        tail.lineTo(left + bw * 0.42, top + bh * 0.98)
        p.drawPath(tail)
        p.setPen(QPen(QColor(255, 255, 255, 40), 3, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
        cx = left + bw / 2
        for dy, k in ((0.38, 0.24), (0.54, 0.30), (0.70, 0.16)):
            p.drawLine(QPoint(int(cx - bw * k), int(top + bh * dy)), QPoint(int(cx + bw * k), int(top + bh * dy)))
        p.end()


def round_pixmap(path: str, size: int) -> QPixmap:
    """Картинка в круге (логотип в шапке)."""
    src = QPixmap(path)
    out = QPixmap(size * 2, size * 2)
    out.fill(Qt.GlobalColor.transparent)
    if src.isNull():
        return out
    p = QPainter(out)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform)
    clip = QPainterPath()
    clip.addEllipse(QRectF(0, 0, size * 2, size * 2))
    p.setClipPath(clip)
    p.drawPixmap(QRect(0, 0, size * 2, size * 2), src)
    p.setClipping(False)
    from PySide6.QtGui import QPen
    p.setPen(QPen(QColor(theme.CREAM), 4))
    p.setBrush(Qt.BrushStyle.NoBrush)
    p.drawEllipse(QRectF(2, 2, size * 2 - 4, size * 2 - 4))
    p.end()
    out.setDevicePixelRatio(2.0)
    return out


# ---------------------------------------------------------------- раскладки

class FlowLayout(QLayout):
    """Элементы идут в ряд и переносятся на новую строку, если не помещаются (кнопки в шапке)."""

    def __init__(self, spacing: int = 8):
        super().__init__()
        self._items = []
        self._sp = spacing
        self.setContentsMargins(0, 0, 0, 0)

    def addItem(self, item):
        self._items.append(item)

    def count(self):
        return len(self._items)

    def itemAt(self, i):
        return self._items[i] if 0 <= i < len(self._items) else None

    def takeAt(self, i):
        return self._items.pop(i) if 0 <= i < len(self._items) else None

    def expandingDirections(self):
        return Qt.Orientation(0)

    def hasHeightForWidth(self):
        return True

    def heightForWidth(self, w):
        return self._do(QRect(0, 0, w, 0), True)

    def setGeometry(self, rect):
        super().setGeometry(rect)
        self._do(rect, False)

    def sizeHint(self):
        return self.minimumSize()

    def minimumSize(self):
        s = QSize()
        for it in self._items:
            s = s.expandedTo(it.minimumSize())
        return s

    def _do(self, rect: QRect, test: bool) -> int:
        x, y, line_h = rect.x(), rect.y(), 0
        visible = [it for it in self._items if not (it.widget() and it.widget().isHidden())]
        for it in visible:
            hint = it.sizeHint()
            nx = x + hint.width() + self._sp
            if nx - self._sp > rect.right() + 1 and line_h > 0:
                x = rect.x()
                y += line_h + self._sp
                nx = x + hint.width() + self._sp
                line_h = 0
            if not test:
                it.setGeometry(QRect(QPoint(x, y), hint))
            x = nx
            line_h = max(line_h, hint.height())
        return y + line_h - rect.y()


class Columns(QWidget):
    """Карточки в одну или две колонки — в зависимости от ширины окна.

    В две колонки карточки раскладываются так, чтобы колонки были примерно одной высоты.
    Каждая колонка — отдельный виджет: когда карточка раскрывается, колонка и окно сразу
    пересчитывают высоту.
    """

    def __init__(self, widgets: list[QWidget], breakpoint: int = 820):
        super().__init__()
        self.widgets = widgets
        self.breakpoint = breakpoint
        self.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Minimum)
        self.lay = QHBoxLayout(self)
        self.lay.setContentsMargins(0, 0, 0, 0)
        self.lay.setSpacing(14)
        self.cols: list[QWidget] = []
        for _ in range(2):
            col = QWidget()
            col.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Minimum)
            cl = QVBoxLayout(col)
            cl.setContentsMargins(0, 0, 0, 0)
            cl.setSpacing(14)
            cl.setAlignment(Qt.AlignmentFlag.AlignTop)
            self.cols.append(col)
            self.lay.addWidget(col, 1, Qt.AlignmentFlag.AlignTop)
        self._n = 0
        self._arrange(1)

    def _arrange(self, n: int) -> None:
        if n == self._n:
            return
        self._n = n
        for col in self.cols:
            lay = col.layout()
            while lay.count():
                lay.takeAt(0)
        heights = [0] * n
        for w in self.widgets:
            i = heights.index(min(heights))
            self.cols[i].layout().addWidget(w)
            heights[i] += w.sizeHint().height() + 14
        self.cols[1].setVisible(n > 1)
        relayout(self)

    def resizeEvent(self, e):
        n = 2 if e.size().width() >= self.breakpoint else 1
        super().resizeEvent(e)
        if n != self._n:
            QTimer.singleShot(0, lambda: self._arrange(n))


class ResponsiveRow(QWidget):
    """Два поля в ряд со стрелкой между ними, а в узком месте — друг под другом."""

    def __init__(self, left: QWidget, middle: QWidget, right: QWidget, breakpoint: int = 420):
        super().__init__()
        self.middle = middle
        self.breakpoint = breakpoint
        self.box = QBoxLayout(QBoxLayout.Direction.LeftToRight, self)
        self.box.setContentsMargins(0, 0, 0, 0)
        self.box.setSpacing(8)
        self.box.addWidget(left, 1)
        self.box.addWidget(middle, 0, Qt.AlignmentFlag.AlignBottom)
        self.box.addWidget(right, 1)

    def resizeEvent(self, e):
        super().resizeEvent(e)
        narrow = e.size().width() < self.breakpoint
        direction = QBoxLayout.Direction.TopToBottom if narrow else QBoxLayout.Direction.LeftToRight
        if self.box.direction() != direction:
            # после смены ряд ↔ столбик высота меняется — сообщить карточке и окну
            QTimer.singleShot(0, lambda: self._apply(direction, narrow))

    def _apply(self, direction, narrow: bool) -> None:
        if self.box.direction() == direction:
            return
        self.box.setDirection(direction)
        self.middle.setVisible(not narrow)
        relayout(self)
