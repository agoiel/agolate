"""Окно настроек."""

from __future__ import annotations

import os
import subprocess
import sys

from PySide6.QtWidgets import (QCheckBox, QComboBox, QDialog, QDialogButtonBox, QDoubleSpinBox,
                               QFormLayout, QGroupBox, QHBoxLayout, QLabel, QLineEdit,
                               QPushButton, QSpinBox, QTabWidget, QVBoxLayout, QWidget)

from .. import languages
from ..config import GLOSSARY_DIR, Config
from ..glossary import list_glossaries
from ..translators import TRANSLATORS
from .glossary_editor import GlossaryEditor, new_glossary_file


def _combo(items: list[tuple[str, str]], current: str) -> QComboBox:
    cb = QComboBox()
    for key, title in items:
        cb.addItem(title, key)
    i = cb.findData(current)
    cb.setCurrentIndex(max(0, i))
    return cb


def _key_edit(value: str) -> QLineEdit:
    e = QLineEdit(value)
    e.setEchoMode(QLineEdit.EchoMode.PasswordEchoOnEdit)
    return e


def _dspin(value: float, lo: float, hi: float, step: float) -> QDoubleSpinBox:
    s = QDoubleSpinBox()
    s.setRange(lo, hi)
    s.setSingleStep(step)
    s.setDecimals(2)
    s.setValue(float(value))
    return s


def _spin(value: int, lo: int, hi: int, step: int = 1) -> QSpinBox:
    s = QSpinBox()
    s.setRange(lo, hi)
    s.setSingleStep(step)
    s.setValue(int(value))
    return s


class SettingsDialog(QDialog):
    def __init__(self, cfg: Config, parent=None):
        super().__init__(parent)
        self.cfg = cfg
        self.setWindowTitle("Настройки")
        self.resize(560, 560)
        tabs = QTabWidget()
        tabs.addTab(self._tab_main(), "Основное")
        tabs.addTab(self._tab_ocr(), "Распознавание")
        tabs.addTab(self._tab_translate(), "Перевод")
        tabs.addTab(self._tab_keys(), "Клавиши")
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save
                                   | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self._save)
        buttons.rejected.connect(self.reject)
        lay = QVBoxLayout(self)
        lay.addWidget(tabs)
        lay.addWidget(buttons)

    # ------------------------------------------------------------ вкладки
    def _tab_main(self) -> QWidget:
        c = self.cfg
        w = QWidget()
        f = QFormLayout(w)
        self.src = _combo([(k, v["name"]) for k, v in languages.SOURCE_LANGUAGES.items()], c["source_lang"])
        self.tgt = _combo([(k, v["name"]) for k, v in languages.TARGET_LANGUAGES.items()], c["target_lang"])
        self.display = _combo([("overlay", "Поверх облаков"), ("list", "Окно со списком"),
                               ("both", "И то, и другое")], c["display_mode"])
        self.font_size = _spin(c["overlay_max_font_size"], 8, 48)
        self.opacity = _dspin(c["overlay_opacity"], 0.3, 1.0, 0.05)
        self.auto_ms = _spin(c["auto_interval_ms"], 300, 5000, 100)
        f.addRow("Язык оригинала:", self.src)
        f.addRow("Переводить на:", self.tgt)
        f.addRow("Показ перевода:", self.display)
        f.addRow("Макс. размер шрифта:", self.font_size)
        f.addRow("Непрозрачность плашек:", self.opacity)
        f.addRow("Режим «Авто»: проверка, мс:", self.auto_ms)
        return w

    def _tab_ocr(self) -> QWidget:
        c = self.cfg
        w = QWidget()
        f = QFormLayout(w)
        self.ocr = _combo([("windows", "Windows OCR (быстрый, встроенный)"),
                           ("easyocr", "EasyOCR (тяжёлый, нужен pip install easyocr)")], c["ocr_engine"])
        self.upscale = _dspin(c["ocr_upscale"], 1.0, 4.0, 0.25)
        self.min_conf = _dspin(c["ocr_min_conf"], 0.0, 1.0, 0.05)
        self.gpu = QCheckBox("Использовать видеокарту (EasyOCR)")
        self.gpu.setChecked(bool(c["easyocr_gpu"]))
        self.gap = _dspin(c["bubble_gap"], 0.2, 4.0, 0.1)
        self.row_gap = _dspin(c["bubble_row_gap"], 0.2, 5.0, 0.1)
        self.border = QCheckBox("Разделять облака по контуру")
        self.border.setChecked(bool(c["bubble_border_check"]))
        self.skip_free = QCheckBox("Не переводить текст поверх рисунка (звуки)")
        self.skip_free.setChecked(bool(c["skip_free_text"]))
        self.skip_cut = QCheckBox("Не переводить текст, обрезанный краем области")
        self.skip_cut.setChecked(bool(c["skip_cut_bubbles"]))
        f.addRow("Движок OCR:", self.ocr)
        f.addRow("Увеличение перед OCR:", self.upscale)
        f.addRow("Мин. уверенность (EasyOCR):", self.min_conf)
        f.addRow("", self.gpu)
        f.addRow(QLabel("<b>Сборка облаков</b>"))
        f.addRow("Зазор между строками:", self.gap)
        f.addRow("Зазор между словами:", self.row_gap)
        f.addRow("", self.border)
        f.addRow("", self.skip_free)
        f.addRow("", self.skip_cut)
        hint = QLabel("Если строки одного облака распадаются на несколько — увеличьте «зазор между "
                      "строками». Если соседние облака склеиваются — уменьшите.")
        hint.setWordWrap(True)
        f.addRow(hint)
        return w

    def _tab_translate(self) -> QWidget:
        c = self.cfg
        w = QWidget()
        lay = QVBoxLayout(w)
        f = QFormLayout()
        self.translator = _combo([(k, cls.title) for k, cls in TRANSLATORS.items()], c["translator"])
        self.ctx = _spin(c["context_size"], 0, 40)
        self.interval = _dspin(c.get("request_interval", 1.0), 0.0, 10.0, 0.5)
        self.interval.setSuffix(" с")
        self.glossary = QComboBox()
        self._fill_glossaries(c.get("glossary", ""))
        g_edit = QPushButton("Изменить…")
        g_new = QPushButton("Новый…")
        g_dir = QPushButton("Папка")
        g_edit.clicked.connect(self._edit_glossary)
        g_new.clicked.connect(self._new_glossary)
        g_dir.clicked.connect(lambda: _open_folder(GLOSSARY_DIR))
        grow = QHBoxLayout()
        grow.addWidget(self.glossary, 1)
        grow.addWidget(g_edit)
        grow.addWidget(g_new)
        grow.addWidget(g_dir)
        f.addRow("Переводчик:", self.translator)
        f.addRow("Реплик контекста:", self.ctx)
        f.addRow("Пауза между запросами:", self.interval)
        f.addRow("Глоссарий тайтла:", grow)
        lay.addLayout(f)

        t = c["translators"]
        self.deepl_key = _key_edit(t["deepl"]["api_key"])
        self.ms_key = _key_edit(t["microsoft"]["api_key"])
        self.ms_region = QLineEdit(t["microsoft"]["region"])
        self.gem_key = _key_edit(t["gemini"]["api_key"])
        self.gem_model = QLineEdit(t["gemini"]["model"])
        self.oa_url = QLineEdit(t["openai"]["base_url"])
        self.oa_key = _key_edit(t["openai"]["api_key"])
        self.oa_model = QLineEdit(t["openai"]["model"])

        for title, rows in (
            ("DeepL (бесплатно 500 тыс. символов/мес.)", [("Ключ:", self.deepl_key)]),
            ("Microsoft (бесплатно 2 млн символов/мес.)", [("Ключ:", self.ms_key), ("Регион:", self.ms_region)]),
            ("Gemini (ИИ, есть бесплатный лимит)", [("Ключ:", self.gem_key), ("Модель:", self.gem_model)]),
            ("OpenAI-совместимый (OpenAI, OpenRouter, Ollama, LM Studio)",
             [("Адрес:", self.oa_url), ("Ключ:", self.oa_key), ("Модель:", self.oa_model)]),
        ):
            box = QGroupBox(title)
            bf = QFormLayout(box)
            for label, widget in rows:
                bf.addRow(label, widget)
            lay.addWidget(box)
        lay.addStretch()
        return w

    def _tab_keys(self) -> QWidget:
        h = self.cfg["hotkeys"]
        w = QWidget()
        f = QFormLayout(w)
        self.hk = {k: QLineEdit(h.get(k, "")) for k in ("select", "translate", "toggle", "auto")}
        f.addRow("Выделить область:", self.hk["select"])
        f.addRow("Перевести:", self.hk["translate"])
        f.addRow("Показать/скрыть перевод:", self.hk["toggle"])
        f.addRow("Вкл/выкл «Авто»:", self.hk["auto"])
        hint = QLabel("Формат: &lt;alt&gt;+q, &lt;ctrl&gt;+&lt;shift&gt;+t, &lt;f8&gt;. "
                      "Оставьте пустым, чтобы отключить.")
        hint.setWordWrap(True)
        f.addRow(hint)
        return w

    # ------------------------------------------------------------ глоссарии
    def _fill_glossaries(self, current: str) -> None:
        self.glossary.clear()
        self.glossary.addItem("— без глоссария —", "")
        for p in list_glossaries(GLOSSARY_DIR):
            self.glossary.addItem(p.stem, p.name)
        self.glossary.setCurrentIndex(max(0, self.glossary.findData(current)))

    def _edit_glossary(self) -> None:
        name = self.glossary.currentData()
        if not name:
            self._new_glossary()
            return
        GlossaryEditor(GLOSSARY_DIR / name, self).exec()

    def _new_glossary(self) -> None:
        path = new_glossary_file(self, self.src.currentData())
        if path:
            self._fill_glossaries(path.name)
            GlossaryEditor(path, self).exec()

    # ------------------------------------------------------------ сохранение
    def _save(self) -> None:
        c = self.cfg
        c["source_lang"] = self.src.currentData()
        c["target_lang"] = self.tgt.currentData()
        c["display_mode"] = self.display.currentData()
        c["overlay_max_font_size"] = self.font_size.value()
        c["overlay_opacity"] = self.opacity.value()
        c["auto_interval_ms"] = self.auto_ms.value()

        c["ocr_engine"] = self.ocr.currentData()
        c["ocr_upscale"] = self.upscale.value()
        c["ocr_min_conf"] = self.min_conf.value()
        c["easyocr_gpu"] = self.gpu.isChecked()
        c["bubble_gap"] = self.gap.value()
        c["bubble_row_gap"] = self.row_gap.value()
        c["bubble_border_check"] = self.border.isChecked()
        c["skip_free_text"] = self.skip_free.isChecked()
        c["skip_cut_bubbles"] = self.skip_cut.isChecked()

        c["translator"] = self.translator.currentData()
        c["context_size"] = self.ctx.value()
        c["request_interval"] = self.interval.value()
        c["glossary"] = self.glossary.currentData() or ""
        t = c["translators"]
        t["deepl"]["api_key"] = self.deepl_key.text().strip()
        t["microsoft"]["api_key"] = self.ms_key.text().strip()
        t["microsoft"]["region"] = self.ms_region.text().strip() or "global"
        t["gemini"]["api_key"] = self.gem_key.text().strip()
        t["gemini"]["model"] = self.gem_model.text().strip() or "gemini-2.5-flash-lite"
        t["openai"]["base_url"] = self.oa_url.text().strip()
        t["openai"]["api_key"] = self.oa_key.text().strip()
        t["openai"]["model"] = self.oa_model.text().strip()

        c["hotkeys"] = {k: e.text().strip() for k, e in self.hk.items()}
        c.save()
        self.accept()


def _open_folder(path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    if sys.platform == "win32":
        os.startfile(path)  # noqa: S606
    else:
        subprocess.Popen(["xdg-open", str(path)])
