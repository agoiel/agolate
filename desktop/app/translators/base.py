from __future__ import annotations

from dataclasses import dataclass, field

import requests

from ..glossary import GlossaryEntry

TIMEOUT = 30


class TranslateError(RuntimeError):
    """Ошибка перевода с понятным текстом."""


@dataclass
class TranslateRequest:
    texts: list[str]                      # облака текущей страницы по порядку
    src: str                              # код языка оригинала (ko, ja, ...)
    tgt: str                              # код языка перевода (ru, ...)
    history: list[tuple[str, str]] = field(default_factory=list)   # прошлые реплики (оригинал, перевод)
    glossary: list[GlossaryEntry] = field(default_factory=list)     # термины, найденные на странице
    anchored: list[str] | None = None     # тексты с подставленными якорями (для обычных переводчиков)


class Translator:
    name = "base"
    title = "Базовый"
    uses_llm = False

    def __init__(self, settings: dict):
        self.settings = settings or {}
        self.http = requests.Session()

    def translate(self, req: TranslateRequest) -> list[str]:
        raise NotImplementedError

    # ---------------------------------------------------------------- утилиты
    def _post(self, url: str, **kw) -> requests.Response:
        try:
            r = self.http.post(url, timeout=TIMEOUT, **kw)
        except requests.RequestException as e:
            raise TranslateError(f"{self.title}: нет связи ({e.__class__.__name__}).") from e
        self._check(r)
        return r

    def _get(self, url: str, **kw) -> requests.Response:
        try:
            r = self.http.get(url, timeout=TIMEOUT, **kw)
        except requests.RequestException as e:
            raise TranslateError(f"{self.title}: нет связи ({e.__class__.__name__}).") from e
        self._check(r)
        return r

    def _check(self, r: requests.Response) -> None:
        if r.status_code < 400:
            return
        hint = {
            401: "неверный API-ключ",
            403: "доступ запрещён — проверьте ключ и регион",
            429: "слишком много запросов или закончился лимит",
            456: "закончился месячный лимит символов",
        }.get(r.status_code, "")
        body = r.text[:300].replace("\n", " ")
        raise TranslateError(f"{self.title}: ошибка {r.status_code} {hint}\n{body}".strip())
