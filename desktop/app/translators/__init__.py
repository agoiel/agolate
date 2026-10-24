"""Движки перевода. Все переключаются в настройках."""

from __future__ import annotations

from .base import RateLimit, TranslateError, TranslateRequest, Translator
from .llm import GeminiTranslator, OpenAICompatTranslator
from .machine import DeepLTranslator, GoogleFreeTranslator, MicrosoftTranslator

TRANSLATORS: dict[str, type[Translator]] = {
    cls.name: cls
    for cls in (GoogleFreeTranslator, DeepLTranslator, MicrosoftTranslator,
                GeminiTranslator, OpenAICompatTranslator)
}


def create_translator(name: str, cfg) -> Translator:
    cls = TRANSLATORS.get(name, GoogleFreeTranslator)
    return cls(cfg.translator_settings(cls.name))


__all__ = ["TRANSLATORS", "RateLimit", "TranslateError", "TranslateRequest", "Translator", "create_translator"]
