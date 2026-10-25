"""Таблица языков и их коды для разных движков.

Чтобы добавить язык, допишите сюда новую запись.
"""

# Языки оригинала
SOURCE_LANGUAGES: dict[str, dict] = {
    "ko": {
        "name": "Корейский (манхва)",
        "windows": ["ko"],
        "easyocr": ["ko", "en"],
        "google": "ko",
        "deepl": "KO",
        "microsoft": "ko",
        "llm": "Korean",
        "joiner": " ",        # чем склеивать строки внутри облака
        "direction": "ltr",   # порядок облаков в одном ряду
    },
    "ja": {
        "name": "Японский (манга)",
        "windows": ["ja"],
        "easyocr": ["ja", "en"],
        "google": "ja",
        "deepl": "JA",
        "microsoft": "ja",
        "llm": "Japanese",
        "joiner": "",
        "direction": "rtl",
    },
    "zh": {
        "name": "Китайский упрощённый (маньхуа)",
        "windows": ["zh-Hans", "zh-CN"],
        "easyocr": ["ch_sim", "en"],
        "google": "zh-CN",
        "deepl": "ZH",
        "microsoft": "zh-Hans",
        "llm": "Simplified Chinese",
        "joiner": "",
        "direction": "ltr",
    },
    "zh-tw": {
        "name": "Китайский традиционный",
        "windows": ["zh-Hant", "zh-TW"],
        "easyocr": ["ch_tra", "en"],
        "google": "zh-TW",
        "deepl": "ZH",
        "microsoft": "zh-Hant",
        "llm": "Traditional Chinese",
        "joiner": "",
        "direction": "ltr",
    },
    "en": {
        "name": "Английский (сканлейты)",
        "windows": ["en"],
        "easyocr": ["en"],
        "google": "en",
        "deepl": "EN",
        "microsoft": "en",
        "llm": "English",
        "joiner": " ",
        "direction": "ltr",
    },
}

# Языки перевода
TARGET_LANGUAGES: dict[str, dict] = {
    "ru": {"name": "Русский", "google": "ru", "deepl": "RU", "microsoft": "ru", "llm": "Russian"},
    "uk": {"name": "Украинский", "google": "uk", "deepl": "UK", "microsoft": "uk", "llm": "Ukrainian"},
    "en": {"name": "Английский", "google": "en", "deepl": "EN-US", "microsoft": "en", "llm": "English"},
}


import re as _re

_HANGUL = _re.compile(r"[\uAC00-\uD7AF\u1100-\u11FF\u3130-\u318F]")
_KANA = _re.compile(r"[\u3040-\u30FF\u31F0-\u31FF\uFF66-\uFF9F]")
_HAN = _re.compile(r"[\u3400-\u4DBF\u4E00-\u9FFF]")
_LATIN = _re.compile(r"[A-Za-z\u00C0-\u024F]")


def detect(text: str, selected: str) -> str:
    """Язык облака по символам. Только латиница — английский, даже если выбран корейский."""
    h, k = len(_HANGUL.findall(text)), len(_KANA.findall(text))
    c, latin = len(_HAN.findall(text)), len(_LATIN.findall(text))
    if h and h >= k:
        return "ko"
    if k:
        return "ja"
    if c:
        return selected if selected in ("ja", "zh", "zh-tw") else "zh"
    if latin >= 2:
        return "en"
    return selected


def src(code: str) -> dict:
    return SOURCE_LANGUAGES.get(code, SOURCE_LANGUAGES["ko"])


def tgt(code: str) -> dict:
    return TARGET_LANGUAGES.get(code, TARGET_LANGUAGES["ru"])
