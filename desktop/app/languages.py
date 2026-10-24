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


def src(code: str) -> dict:
    return SOURCE_LANGUAGES.get(code, SOURCE_LANGUAGES["ko"])


def tgt(code: str) -> dict:
    return TARGET_LANGUAGES.get(code, TARGET_LANGUAGES["ru"])
