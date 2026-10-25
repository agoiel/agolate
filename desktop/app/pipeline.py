"""Конвейер: картинка → строки (OCR) → облака → перевод.

Здесь же живёт память сессии:
- кэш уже переведённых облаков (при прокрутке те же облака не переводятся повторно
  и не тратят лимиты);
- последние реплики, которые передаются переводчику как контекст.
"""

from __future__ import annotations

import re
import time
from collections import OrderedDict, deque

import numpy as np
from PIL import Image

from . import languages
from .bubbles import group_lines
from .config import GLOSSARY_DIR, Config
from .glossary import Glossary
from .models import PageResult
from .ocr import OcrBackend, OcrError, OcrLanguageMissing, create_ocr, enhance
from .translators import RateLimit, TranslateError, TranslateRequest, Translator, create_translator


def _norm(text: str) -> str:
    return re.sub(r"\s+", "", text).lower()


class Pipeline:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self._ocr: OcrBackend | None = None
        self._ocr_name = ""
        self._translator: Translator | None = None
        self._translator_key = ""
        self.glossary: Glossary | None = None
        self._glossary_name = None
        self.cache: OrderedDict[str, str] = OrderedDict()
        self.history: deque[tuple[str, str]] = deque(maxlen=50)

    # ------------------------------------------------------------ сессия
    def reset_session(self) -> None:
        """Новый тайтл: забыть контекст и кэш."""
        self.cache.clear()
        self.history.clear()

    def reload(self) -> None:
        """Перечитать настройки (после окна настроек)."""
        self._translator = None
        self._glossary_name = None
        if self.cfg["ocr_engine"] != self._ocr_name:
            self._ocr = None

    # ------------------------------------------------------------ ленивые объекты
    @property
    def ocr(self) -> OcrBackend:
        if self._ocr is None:
            self._ocr = create_ocr(self.cfg["ocr_engine"], self.cfg)
            self._ocr_name = self.cfg["ocr_engine"]
        return self._ocr

    @property
    def translator(self) -> Translator:
        key = self.cfg["translator"]
        if self._translator is None or self._translator_key != key:
            self._translator = create_translator(key, self.cfg)
            self._translator_key = key
        return self._translator

    def _load_glossary(self) -> Glossary | None:
        name = self.cfg.get("glossary") or ""
        if name != self._glossary_name:
            self._glossary_name = name
            self.glossary = None
            path = GLOSSARY_DIR / name
            if name and path.exists():
                try:
                    self.glossary = Glossary.load(path)
                except (OSError, ValueError):
                    self.glossary = None
        return self.glossary

    # ------------------------------------------------------------ основная работа
    def process(self, image: Image.Image, translate: bool = True) -> PageResult:
        cfg = self.cfg
        lang = cfg["source_lang"]
        info = languages.src(lang)
        result = PageResult(image_size=image.size)
        t0 = time.perf_counter()

        try:
            mode = cfg.get("ocr_mode", "thorough")
            lines = enhance.merge([self.ocr.recognize(v, lang) for v in enhance.variants(image, mode)])
        except OcrLanguageMissing as e:
            result.error = str(e)
            result.missing_ocr_lang = e.lang
            return result
        except OcrError as e:
            result.error = str(e)
            return result
        t1 = time.perf_counter()
        result.timings["ocr"] = t1 - t0

        arr = np.asarray(image.convert("RGB"))
        result.bubbles = group_lines(
            lines, arr,
            joiner=info["joiner"], direction=info["direction"],
            gap=float(cfg["bubble_gap"]), row_gap=float(cfg["bubble_row_gap"]),
            border_check=bool(cfg["bubble_border_check"]),
            skip_free_text=bool(cfg["skip_free_text"]),
            skip_cut=bool(cfg["skip_cut_bubbles"]),
        )
        for b in result.bubbles:
            b.lang = languages.detect(b.text, lang)
        result.line_count = len(lines)
        t2 = time.perf_counter()
        result.timings["bubbles"] = t2 - t1
        if not translate:
            return result

        # Кэш: то, что уже переводили, не отправляем повторно
        todo = []
        for b in result.bubbles:
            if not b.translatable:
                continue
            hit = self.cache.get(self._cache_key(b.text))
            if hit is not None:
                b.translation = hit
                result.cache_hits += 1
            else:
                todo.append(b)

        if todo:
            try:
                # облака на разных языках переводятся отдельными запросами
                groups: dict[str, list] = {}
                for b in todo:
                    groups.setdefault(b.lang or lang, []).append(b)
                for code, group in groups.items():
                    self._translate(group, code)
            except TranslateError as e:
                result.error = str(e)
        result.timings["translate"] = time.perf_counter() - t2
        return result

    def _cache_key(self, text: str) -> str:
        c = self.cfg
        src = languages.detect(text, c['source_lang'])
        return f"{src}>{c['target_lang']}|{c['translator']}|{_norm(text)}"

    def _translate(self, bubbles, src_code: str) -> None:
        cfg = self.cfg
        texts = [b.text for b in bubbles]
        glossary = self._load_glossary()
        RateLimit.interval = max(0.0, float(cfg.get("request_interval", 1.0)))
        tr = self.translator

        n_ctx = int(cfg["context_size"])
        req = TranslateRequest(
            texts=texts, src=src_code, tgt=cfg["target_lang"],
            history=list(self.history)[-n_ctx:] if n_ctx > 0 else [],
        )
        if glossary:
            req.glossary = glossary.find(texts)
            if not tr.uses_llm and req.glossary:
                req.anchored = [glossary.apply_anchors(t) for t in texts]

        out = tr.translate(req)
        for b, t in zip(bubbles, out):
            b.translation = t
            if t:
                self.cache[self._cache_key(b.text)] = t
                self.history.append((b.text, t))
        while len(self.cache) > 3000:
            self.cache.popitem(last=False)
