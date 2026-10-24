"""Базовые структуры данных: прямоугольники, строки текста, облака."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class Box:
    """Прямоугольник в пикселях картинки (x, y — левый верхний угол)."""

    x: float
    y: float
    w: float
    h: float

    @property
    def x2(self) -> float:
        return self.x + self.w

    @property
    def y2(self) -> float:
        return self.y + self.h

    @property
    def cx(self) -> float:
        return self.x + self.w / 2

    @property
    def cy(self) -> float:
        return self.y + self.h / 2

    def union(self, other: "Box") -> "Box":
        x, y = min(self.x, other.x), min(self.y, other.y)
        x2, y2 = max(self.x2, other.x2), max(self.y2, other.y2)
        return Box(x, y, x2 - x, y2 - y)

    def scaled(self, k: float) -> "Box":
        return Box(self.x * k, self.y * k, self.w * k, self.h * k)

    def to_dict(self) -> dict:
        return {"x": round(self.x, 1), "y": round(self.y, 1), "w": round(self.w, 1), "h": round(self.h, 1)}


@dataclass
class TextLine:
    """Одна строка текста, найденная OCR."""

    box: Box
    text: str
    conf: float = 1.0


@dataclass
class Bubble:
    """Облако (блок текста), собранное из нескольких строк."""

    box: Box
    lines: list[TextLine]
    text: str = ""
    # bubble — однотонный светлый фон (обычное облако)
    # box    — однотонный тёмный/цветной фон (плашка рассказчика, системное окно)
    # free   — пёстрый фон (звуки, надписи поверх рисунка)
    kind: str = "bubble"
    bg_color: tuple[int, int, int] = (255, 255, 255)
    translation: str = ""
    # Причина, по которой облако не переводится (пусто — переводится)
    skip_reason: str = ""

    @property
    def translatable(self) -> bool:
        return not self.skip_reason and bool(self.text.strip())


@dataclass
class PageResult:
    """Результат обработки одного снимка."""

    bubbles: list[Bubble] = field(default_factory=list)
    image_size: tuple[int, int] = (0, 0)
    timings: dict[str, float] = field(default_factory=dict)
    error: str = ""
    cache_hits: int = 0
    # Код языка, для которого в Windows не хватает OCR (тогда предлагаем докачать)
    missing_ocr_lang: str = ""
