"""Движки распознавания текста (OCR)."""

from __future__ import annotations

from . import enhance
from .base import OcrBackend, OcrError, OcrLanguageMissing


def create_ocr(name: str, cfg) -> OcrBackend:
    if name == "easyocr":
        from .easyocr_backend import EasyOcrBackend
        return EasyOcrBackend(cfg)
    from .windows_ocr import WindowsOcrBackend
    return WindowsOcrBackend(cfg)


__all__ = ["enhance", "OcrBackend", "OcrError", "OcrLanguageMissing", "create_ocr"]
