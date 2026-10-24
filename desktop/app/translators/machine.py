"""Обычные машинные переводчики: Google (без ключа), DeepL, Microsoft."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

from .. import languages
from .base import TranslateError, TranslateRequest, Translator


class GoogleFreeTranslator(Translator):
    """Бесплатный веб-эндпоинт Google Translate.

    Ключ не нужен, удобно для начала. Это неофициальный способ: Google может
    ограничить его без предупреждения — тогда переключитесь на DeepL или Microsoft.
    """

    name = "google_free"
    title = "Google (без ключа)"
    URL = "https://translate.googleapis.com/translate_a/single"

    def _one(self, text: str, sl: str, tl: str) -> str:
        r = self._get(self.URL, params={"client": "gtx", "sl": sl, "tl": tl, "dt": "t", "q": text})
        try:
            data = r.json()
            return "".join(seg[0] for seg in data[0] if seg and seg[0])
        except (ValueError, TypeError, IndexError) as e:
            raise TranslateError("Google: неожиданный ответ сервера.") from e

    def translate(self, req: TranslateRequest) -> list[str]:
        texts = req.anchored or req.texts
        sl, tl = languages.src(req.src)["google"], languages.tgt(req.tgt)["google"]
        with ThreadPoolExecutor(max_workers=4) as pool:
            return list(pool.map(lambda t: self._one(t, sl, tl), texts))


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
