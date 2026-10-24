"""Простой редактор глоссария: таблица «оригинал — перевод — заметка»."""

from __future__ import annotations

import re
from pathlib import Path

from PySide6.QtWidgets import (QDialog, QDialogButtonBox, QHBoxLayout, QHeaderView, QInputDialog,
                               QLabel, QLineEdit, QMessageBox, QPushButton, QTableWidget,
                               QTableWidgetItem, QVBoxLayout)

from ..config import GLOSSARY_DIR
from ..glossary import Glossary, GlossaryEntry


def new_glossary_file(parent, source_lang: str) -> Path | None:
    """Спрашивает название тайтла и создаёт пустой глоссарий."""
    name, ok = QInputDialog.getText(parent, "Новый глоссарий", "Название тайтла:")
    if not ok or not name.strip():
        return None
    slug = re.sub(r"[^\w\-]+", "_", name.strip(), flags=re.UNICODE).strip("_") or "glossary"
    GLOSSARY_DIR.mkdir(parents=True, exist_ok=True)
    path = GLOSSARY_DIR / f"{slug}.json"
    i = 2
    while path.exists():
        path = GLOSSARY_DIR / f"{slug}_{i}.json"
        i += 1
    Glossary(name=name.strip(), source_lang=source_lang).save(path)
    return path


class GlossaryEditor(QDialog):
    def __init__(self, path: Path, parent=None):
        super().__init__(parent)
        self.path = Path(path)
        self.glossary = Glossary.load(self.path)
        self.setWindowTitle(f"Глоссарий — {self.glossary.name}")
        self.resize(720, 520)

        self.name_edit = QLineEdit(self.glossary.name)
        self.table = QTableWidget(0, 3)
        self.table.setHorizontalHeaderLabels(["Оригинал", "Перевод", "Заметка (пол, кто это, как обращаться)"])
        hdr = self.table.horizontalHeader()
        hdr.setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents)
        hdr.setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)
        hdr.setSectionResizeMode(2, QHeaderView.ResizeMode.Stretch)
        for e in self.glossary.entries:
            self._add_row(e.source, e.target, e.note)

        add_btn = QPushButton("+ Добавить")
        del_btn = QPushButton("− Удалить выбранное")
        add_btn.clicked.connect(lambda: self._add_row("", "", "", focus=True))
        del_btn.clicked.connect(self._delete_rows)

        hint = QLabel("Имена, названия, звания, обращения. Заметки помогают ИИ-переводчикам "
                      "(например: «главный герой, мужчина»). Обычные переводчики используют "
                      "только колонку «Перевод».")
        hint.setWordWrap(True)

        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save
                                   | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self._save)
        buttons.rejected.connect(self.reject)

        top = QHBoxLayout()
        top.addWidget(QLabel("Название:"))
        top.addWidget(self.name_edit)
        row = QHBoxLayout()
        row.addWidget(add_btn)
        row.addWidget(del_btn)
        row.addStretch()

        lay = QVBoxLayout(self)
        lay.addLayout(top)
        lay.addWidget(hint)
        lay.addWidget(self.table)
        lay.addLayout(row)
        lay.addWidget(buttons)

    def _add_row(self, src: str, tgt: str, note: str, focus: bool = False) -> None:
        r = self.table.rowCount()
        self.table.insertRow(r)
        for c, v in enumerate((src, tgt, note)):
            self.table.setItem(r, c, QTableWidgetItem(v))
        if focus:
            self.table.setCurrentCell(r, 0)
            self.table.editItem(self.table.item(r, 0))

    def _delete_rows(self) -> None:
        rows = sorted({i.row() for i in self.table.selectedIndexes()}, reverse=True)
        for r in rows:
            self.table.removeRow(r)

    def _save(self) -> None:
        entries = []
        for r in range(self.table.rowCount()):
            vals = [(self.table.item(r, c).text().strip() if self.table.item(r, c) else "")
                    for c in range(3)]
            if vals[0] and vals[1]:
                entries.append(GlossaryEntry(*vals))
        self.glossary.name = self.name_edit.text().strip() or self.glossary.name
        self.glossary.entries = entries
        try:
            self.glossary.save(self.path)
        except OSError as e:
            QMessageBox.warning(self, "Ошибка", f"Не удалось сохранить: {e}")
            return
        self.accept()
