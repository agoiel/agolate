"""Обычные машинные переводчики: Google (без ключа), DeepL, Microsoft."""

from __future__ import annotations

from urllib.parse import quote

from .. import languages
from .base import TranslateError, TranslateRequest, Translator


class GoogleFreeTranslator(Translator):
    """Бесплатный веб-эндпоинт Google Translate.

    Ключ не нужен, удобно для начала. Это неофициальный способ: Google может
    ограничить его без предупреждения (особенно при VPN) — тогда переключитесь
    на DeepL или Microsoft. Все облака страницы уходят одним запросом.
    """

    name = "google_free"
    title = "Google (без ключа)"
    URL = "https://translate.googleapis.com/translate_a/single"

    def _one(self, text: str, sl: str, tl: str) -> str:
        try:
            r = self._get(self.URL, params={"client": "gtx", "sl": sl, "tl": tl, "dt": "t", "q": text})
        except TranslateError as e:
            if e.code == 429:
                raise TranslateError(
                    "Google временно ограничил бесплатные запросы с вашего адреса. Чаще всего так бывает "
                    "при включённом VPN. Отключите VPN, подождите несколько минут или выберите другой "
                    "переводчик (DeepL, Gemini).", 429) from e
            raise
        try:
            data = r.json()
            return "".join(seg[0] for seg in data[0] if seg and seg[0])
        except (ValueError, TypeError, IndexError) as e:
            raise TranslateError("Google: неожиданный ответ сервера.") from e

    def translate(self, req: TranslateRequest) -> list[str]:
        texts = [t.replace("\n", " ") for t in (req.anchored or req.texts)]
        sl, tl = languages.src(req.src)["google"], languages.tgt(req.tgt)["google"]
        # делим на порции, чтобы адрес запроса не был слишком длинным
        chunks: list[list[int]] = []
        size = 0
        for i, t in enumerate(texts):
            n = len(quote(t)) + 3
            if not chunks or size + n > 5000:
                chunks.append([])
                size = 0
            chunks[-1].append(i)
            size += n
        out = [""] * len(texts)
        for chunk in chunks:
            parts = [p.strip() for p in self._one("\n".join(texts[i] for i in chunk), sl, tl).split("\n")]
            if len(parts) == len(chunk):
                for i, p in zip(chunk, parts):
                    out[i] = p
            else:
                # Google склеил строки — переводим эту порцию по одной
                for i in chunk:
                    out[i] = self._one(texts[i], sl, tl).strip()
        return out


class DeepLTranslator(Translator):
    """DeepL API. Бесплатный план — 500 000 символов в месяц (ключ оканчивается на :fx)."""

    name = "deepl"
    title = "DeepL"

    def translate(self, req: TranslateRequest) -> list[str]:
        key = self.settings.get("api_key", "").strip()
        if not key:
            raise TranslateError("DeepL: укажите API-ключ в настройках.")
        host = "api-free.deepl.com" if key.endswith(":fx") else "api.deepl.com"
        body = {
            "text": req.anchored or req.texts,
            "source_lang": languages.src(req.src)["deepl"],
            "target_lang": languages.tgt(req.tgt)["deepl"],
        }
        # Контекст DeepL не переводит и не тарифицирует — передаём прошлые реплики
        if req.history:
            body["context"] = "\n".join(o for o, _ in req.history[-8:])
        r = self._post(f"https://{host}/v2/translate", json=body,
                       headers={"Authorization": f"DeepL-Auth-Key {key}"})
        return [t["text"] for t in r.json().get("translations", [])]


class MicrosoftTranslator(Translator):
    """Azure AI Translator. Бесплатный уровень F0 — 2 млн символов в месяц."""

    name = "microsoft"
    title = "Microsoft"
    URL = "https://api.cognitive.microsofttranslator.com/translate"

    def translate(self, req: TranslateRequest) -> list[str]:
        key = self.settings.get("api_key", "").strip()
        if not key:
            raise TranslateError("Microsoft: укажите API-ключ в настройках.")
        headers = {"Ocp-Apim-Subscription-Key": key, "Content-Type": "application/json"}
        region = self.settings.get("region", "").strip()
        if region and region != "global":
            headers["Ocp-Apim-Subscription-Region"] = region
        params = {"api-version": "3.0",
                  "from": languages.src(req.src)["microsoft"],
                  "to": languages.tgt(req.tgt)["microsoft"]}
        r = self._post(self.URL, params=params, headers=headers,
                       json=[{"Text": t} for t in (req.anchored or req.texts)])
        return [item["translations"][0]["text"] for item in r.json()]
