"""Настройки программы. Хранятся в config.json рядом с программой."""

from __future__ import annotations

import copy
import json
import sys
from pathlib import Path

# В собранном exe настройки и глоссарии лежат рядом с agolate.exe
if getattr(sys, "frozen", False):
    ROOT = Path(sys.executable).resolve().parent
else:
    ROOT = Path(__file__).resolve().parent.parent
CONFIG_PATH = ROOT / "config.json"
GLOSSARY_DIR = ROOT / "glossaries"

DEFAULTS: dict = {
    "source_lang": "ko",
    "target_lang": "ru",

    # --- Распознавание ---
    "ocr_engine": "windows",          # windows | easyocr
    "ocr_upscale": 1.5,               # увеличение картинки перед OCR (мелкий текст читается лучше)
    "ocr_min_conf": 0.3,              # строки с меньшей уверенностью отбрасываются (только EasyOCR)
    "easyocr_gpu": False,

    # --- Сборка облаков ---
    "bubble_gap": 1.1,                # макс. зазор между строками одного облака (в высотах строки)
    "bubble_row_gap": 1.5,            # макс. зазор между словами в одной строке (в высотах строки)
    "bubble_border_check": True,      # не склеивать строки, если между ними контур облака
    "skip_free_text": False,          # пропускать текст поверх рисунка (звуки и т.п.)
    "skip_cut_bubbles": True,         # пропускать текст, обрезанный краем области

    # --- Перевод ---
    "translator": "google_free",      # google_free | deepl | microsoft | gemini | openai
    "context_size": 12,               # сколько прошлых реплик передавать как контекст
    "glossary": "",                   # имя файла глоссария в папке glossaries (пусто — без глоссария)
    "translators": {
        "deepl": {"api_key": ""},
        "microsoft": {"api_key": "", "region": "global"},
        "gemini": {"api_key": "", "model": "gemini-2.5-flash-lite"},
        "openai": {
            # Подходит для OpenAI, OpenRouter, DeepSeek и локальных моделей (Ollama, LM Studio)
            "base_url": "http://localhost:11434/v1",
            "api_key": "",
            "model": "qwen2.5:7b",
        },
    },

    # --- Отображение ---
    "display_mode": "overlay",        # overlay — поверх облаков | list — окно со списком
    "overlay_font": "Segoe UI",
    "overlay_max_font_size": 20,
    "overlay_opacity": 0.95,
    "auto_interval_ms": 700,          # как часто проверять область в режиме «Авто»

    # --- Горячие клавиши (формат pynput) ---
    "hotkeys": {
        "select": "<alt>+q",
        "translate": "<alt>+w",
        "toggle": "<alt>+e",
        "auto": "<alt>+r",
    },

    # Последняя выделенная область (заполняется автоматически)
    "last_region": None,
}


def _merge(base: dict, override: dict) -> dict:
    out = copy.deepcopy(base)
    for k, v in (override or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


class Config(dict):
    """Словарь настроек с загрузкой/сохранением."""

    def __init__(self, path: Path = CONFIG_PATH):
        super().__init__()
        self.path = path
        self.load()

    def load(self) -> None:
        data = {}
        if self.path.exists():
            try:
                data = json.loads(self.path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                data = {}
        self.clear()
        self.update(_merge(DEFAULTS, data))

    def save(self) -> None:
        self.path.write_text(json.dumps(self, ensure_ascii=False, indent=2), encoding="utf-8")

    def translator_settings(self, name: str) -> dict:
        return self["translators"].get(name, {})
