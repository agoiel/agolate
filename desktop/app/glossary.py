"""Глоссарий тайтла: имена, термины, обращения.

Формат файла (JSON, тот же будет и в Android-версии):
{
  "name": "Название тайтла",
  "source_lang": "ko",
  "entries": [
    {"source": "강진우", "target": "Кан Джину", "note": "главный герой, мужчина"},
    {"source": "헌터", "target": "охотник", "note": ""}
  ]
}

Как используется:
- обычные переводчики (Google/DeepL/Microsoft): перед отправкой термин в тексте заменяется
  сразу на русский вариант («якорь»), и переводчик оставляет его как есть;
- ИИ-переводчики (Gemini/OpenAI): найденные на странице термины с заметками
  передаются в подсказку, и модель сама склоняет их по падежам.
"""

from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass, field
from pathlib import Path


@dataclass
class GlossaryEntry:
    source: str
    target: str
    note: str = ""


@dataclass
class Glossary:
    name: str = ""
    source_lang: str = ""
    entries: list[GlossaryEntry] = field(default_factory=list)
    path: Path | None = None

    # ------------------------------------------------------------ файлы
    @classmethod
    def load(cls, path: Path) -> "Glossary":
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        entries = [GlossaryEntry(e.get("source", ""), e.get("target", ""), e.get("note", ""))
                   for e in data.get("entries", []) if e.get("source")]
        return cls(name=data.get("name", Path(path).stem),
                   source_lang=data.get("source_lang", ""), entries=entries, path=Path(path))

    def save(self, path: Path | None = None) -> None:
        path = Path(path or self.path)
        data = {"name": self.name, "source_lang": self.source_lang,
                "entries": [asdict(e) for e in self.entries if e.source.strip()]}
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        self.path = path

    # ------------------------------------------------------------ применение
    def _sorted(self) -> list[GlossaryEntry]:
        # длинные термины первыми, чтобы «Кан Джину» не разбился на «Кан»
        return sorted((e for e in self.entries if e.source and e.target),
                      key=lambda e: len(e.source), reverse=True)

    @staticmethod
    def _pattern(e: GlossaryEntry) -> re.Pattern:
        """Термин ищется только в начале слова: «인형» (кукла) не совпадёт с «형».
        После термина могут идти частицы и окончания (선배님, 선배가, 길드에서)."""
        return re.compile(r"(?<![^\W_])" + re.escape(e.source), re.IGNORECASE)

    def find(self, texts: list[str]) -> list[GlossaryEntry]:
        """Термины, которые встречаются в этих текстах."""
        joined = "\n".join(texts)
        return [e for e in self._sorted() if self._pattern(e).search(joined)]

    def apply_anchors(self, text: str) -> str:
        """Заменяет термины на их перевод прямо в исходном тексте."""
        out = text
        for e in self._sorted():
            # глагольные окончания не трогаем: «각성했다» — «пробудился», а не «пробуждение했다»
            p = re.compile(self._pattern(e).pattern + r"(?![하했한할해합함됐된될되돼])", re.IGNORECASE)
            out = p.sub(lambda _m, t=e.target: t, out)
        return out


def list_glossaries(folder: Path) -> list[Path]:
    folder.mkdir(parents=True, exist_ok=True)
    return sorted(folder.glob("*.json"))
