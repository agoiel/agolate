package com.manhwatranslator.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TranslateException(message: String, val code: Int = 0) : Exception(message)

class TranslateRequest(
    val texts: List<String>,
    val src: SourceLang,
    val tgt: TargetLang,
    val history: List<Pair<String, String>> = emptyList(),
    val glossary: List<GlossaryEntry> = emptyList(),
    /** тексты с подставленными якорями (для обычных переводчиков) */
    val anchored: List<String>? = null,
)

interface Translator {
    val title: String
    val usesLlm: Boolean get() = false
    fun translate(req: TranslateRequest): List<String>
}

// ==================================================================== HTTP

/**
 * Ограничитель частоты: между двумя запросами к сервису перевода проходит
 * не меньше заданной паузы. Запросы идут строго по очереди.
 */
object RateLimit {
    @Volatile var intervalMs: Long = 1000
    private var last = 0L

    @Synchronized
    fun await() {
        val wait = last + intervalMs - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        last = System.currentTimeMillis()
    }
}

internal object Http {
    fun call(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
             body: String? = null, title: String): String {
        try {
            return once(url, method, headers, body, title)
        } catch (e: TranslateException) {
            if (e.code != 429) throw e
            // Сервис попросил притормозить: ждём и пробуем ещё раз
            Thread.sleep(maxOf(3000L, RateLimit.intervalMs * 2))
            return once(url, method, headers, body, title)
        }
    }

    private fun once(url: String, method: String, headers: Map<String, String>, body: String?, title: String): String {
        RateLimit.await()
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw TranslateException("$title: неверный адрес")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                if (!headers.containsKey("Content-Type")) conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code >= 400) {
                val hint = when (code) {
                    401 -> "неверный API-ключ"
                    403 -> "доступ запрещён — проверьте ключ и регион"
                    429 -> "слишком много запросов или закончился лимит"
                    456 -> "закончился месячный лимит символов"
                    else -> ""
                }
                // HTML-страницы ошибок не показываем — в них нет ничего полезного
                val details = if (text.trimStart().startsWith("<")) "" else text.take(200)
                throw TranslateException("$title: ошибка $code — $hint\n$details".trim(), code)
            }
            return text
        } catch (e: TranslateException) {
            throw e
        } catch (e: Exception) {
            throw TranslateException("$title: нет связи (${e.javaClass.simpleName})")
        } finally {
            conn.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

// ==================================================================== обычные переводчики

/**
 * Бесплатный веб-эндпоинт Google без ключа. Неофициальный — может быть ограничен.
 * Все облака страницы уходят одним запросом (через перевод строки), а не по одному.
 */
class GoogleFreeTranslator : Translator {
    override val title = "Google (без ключа)"

    private fun request(text: String, sl: String, tl: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t" +
            "&sl=${Http.enc(sl)}&tl=${Http.enc(tl)}&q=${Http.enc(text)}"
        val raw = try {
            Http.call(url, title = title)
        } catch (e: TranslateException) {
            if (e.code == 429) {
                throw TranslateException("Google временно ограничил бесплатные запросы с вашего адреса. " +
                    "Чаще всего так бывает при включённом VPN. Отключите VPN, подождите несколько минут " +
                    "или выберите другой переводчик (DeepL, Gemini).", 429)
            }
            throw e
        }
        return try {
            val segs = JSONArray(raw).getJSONArray(0)
            buildString {
                for (i in 0 until segs.length()) {
                    val seg = segs.optJSONArray(i) ?: continue
                    if (!seg.isNull(0)) append(seg.optString(0))
                }
            }
        } catch (e: Exception) {
            throw TranslateException("Google: неожиданный ответ сервера")
        }
    }

    override fun translate(req: TranslateRequest): List<String> {
        val texts = (req.anchored ?: req.texts).map { it.replace('\n', ' ') }
        val sl = req.src.google
        val tl = req.tgt.google
        // Делим на порции, чтобы адрес запроса не получился слишком длинным
        val chunks = ArrayList<MutableList<Int>>()
        var size = 0
        for (i in texts.indices) {
            val len = Http.enc(texts[i]).length + 3
            if (chunks.isEmpty() || size + len > 5000) {
                chunks.add(mutableListOf())
                size = 0
            }
            chunks.last().add(i)
            size += len
        }
        val out = arrayOfNulls<String>(texts.size)
        for (chunk in chunks) {
            val joined = chunk.joinToString("\n") { texts[it] }
            val parts = request(joined, sl, tl).split("\n").map { it.trim() }
            if (parts.size == chunk.size) {
                chunk.forEachIndexed { k, idx -> out[idx] = parts[k] }
            } else {
                // Google склеил строки — переводим эту порцию по одной
                for (idx in chunk) out[idx] = request(texts[idx], sl, tl).trim()
            }
        }
        return out.map { it.orEmpty() }
    }
}

/** DeepL API. План Free — ключ оканчивается на :fx. */
class DeepLTranslator(private val key: String) : Translator {
    override val title = "DeepL"

    override fun translate(req: TranslateRequest): List<String> {
        if (key.isBlank()) throw TranslateException("DeepL: укажите API-ключ в настройках")
        val host = if (key.trim().endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val body = JSONObject()
            .put("text", JSONArray(req.anchored ?: req.texts))
            .put("source_lang", req.src.deepl)
            .put("target_lang", req.tgt.deepl)
        if (req.history.isNotEmpty()) body.put("context", req.history.takeLast(8).joinToString("\n") { it.first })
        val raw = Http.call("https://$host/v2/translate", "POST",
            mapOf("Authorization" to "DeepL-Auth-Key ${key.trim()}"), body.toString(), title)
        val arr = JSONObject(raw).getJSONArray("translations")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("text") }
    }
}

/** Azure AI Translator. Бесплатный уровень F0 — 2 млн символов в месяц. */
class MicrosoftTranslator(private val key: String, private val region: String) : Translator {
    override val title = "Microsoft"

    override fun translate(req: TranslateRequest): List<String> {
        if (key.isBlank()) throw TranslateException("Microsoft: укажите API-ключ в настройках")
        val headers = mutableMapOf("Ocp-Apim-Subscription-Key" to key.trim())
        if (region.isNotBlank() && region != "global") headers["Ocp-Apim-Subscription-Region"] = region.trim()
        val body = JSONArray()
        (req.anchored ?: req.texts).forEach { body.put(JSONObject().put("Text", it)) }
        val url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0" +
            "&from=${Http.enc(req.src.microsoft)}&to=${Http.enc(req.tgt.microsoft)}"
        val arr = JSONArray(Http.call(url, "POST", headers, body.toString(), title))
        return (0 until arr.length()).map {
            arr.getJSONObject(it).getJSONArray("translations").getJSONObject(0).getString("text")
        }
    }
}

// ==================================================================== ИИ-переводчики

object LlmPrompt {
    fun system(req: TranslateRequest): String = """
You are an experienced translator of webtoons, manhwa, manga and manhua.
Translate speech bubbles from ${req.src.llm} into ${req.tgt.llm}.

Rules:
- Write natural, lively spoken ${req.tgt.llm}, the way a good scanlation team would. Keep each character's tone.
- The bubbles are consecutive lines of the same scene, in reading order. Use them and the previous context to resolve who speaks, gender, and formal/informal address.
- The text comes from OCR and may contain recognition errors or broken line wraps. Silently fix obvious errors.
- Always use the glossary translations for names and terms; inflect them according to ${req.tgt.llm} grammar.
- Sound effects: translate briefly and expressively.
- Do not add notes, explanations or quotes. Do not merge or split bubbles.
- Answer ONLY with JSON: {"translations": ["...", "..."]} — exactly ${req.texts.size} strings in the same order.
""".trim()

    fun user(req: TranslateRequest): String = buildString {
        if (req.glossary.isNotEmpty()) {
            appendLine("Glossary:")
            req.glossary.forEach { e -> appendLine("- ${e.source} → ${e.target}" + if (e.note.isNotBlank()) "  (${e.note})" else "") }
            appendLine()
        }
        if (req.history.isNotEmpty()) {
            appendLine("Previous lines (context only, do not translate):")
            req.history.forEach { (o, t) -> appendLine("- $o → $t") }
            appendLine()
        }
        appendLine("Bubbles to translate (${req.texts.size}):")
        append(JSONArray(req.texts).toString(1))
    }

    /** Достаёт список переводов из ответа модели, даже если вокруг JSON есть мусор. */
    fun parse(raw: String, n: Int): List<String> {
        var text = raw.trim().replace(Regex("^```(?:json)?|```$", RegexOption.MULTILINE), "").trim()
        val list: JSONArray? = try {
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text).optJSONArray("translations")
        } catch (e: Exception) {
            val m = Regex("\\{.*\\}|\\[.*\\]", RegexOption.DOT_MATCHES_ALL).find(text)
            text = m?.value ?: ""
            try {
                if (text.startsWith("[")) JSONArray(text) else JSONObject(text).optJSONArray("translations")
            } catch (e2: Exception) {
                null
            }
        }
        if (list == null) throw TranslateException("ИИ вернул ответ не в формате JSON. Попробуйте ещё раз.")
        val out = (0 until list.length()).map { list.optString(it).trim() }.toMutableList()
        while (out.size < n) out.add("")
        return out.take(n)
    }
}

class GeminiTranslator(private val key: String, private val model: String) : Translator {
    override val title = "Gemini"
    override val usesLlm = true

    override fun translate(req: TranslateRequest): List<String> {
        if (key.isBlank()) throw TranslateException("Gemini: укажите API-ключ (aistudio.google.com)")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", LlmPrompt.system(req)))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", LlmPrompt.user(req))))))
            .put("generationConfig", JSONObject().put("temperature", 0.3).put("responseMimeType", "application/json"))
        val m = model.ifBlank { "gemini-2.5-flash-lite" }.trim()
        val raw = Http.call("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent", "POST",
            mapOf("x-goog-api-key" to key.trim()), body.toString(), title)
        val text = try {
            val parts = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
        } catch (e: Exception) {
            throw TranslateException("Gemini: пустой ответ")
        }
        return LlmPrompt.parse(text, req.texts.size)
    }
}

/** OpenAI, OpenRouter, DeepSeek и любые совместимые серверы. */
class OpenAICompatTranslator(private val baseUrl: String, private val key: String, private val model: String) : Translator {
    override val title = "OpenAI-совместимый"
    override val usesLlm = true

    override fun translate(req: TranslateRequest): List<String> {
        if (baseUrl.isBlank() || model.isBlank()) throw TranslateException("Укажите адрес сервера и модель в настройках")
        val headers = if (key.isNotBlank()) mapOf("Authorization" to "Bearer ${key.trim()}") else emptyMap()
        val body = JSONObject()
            .put("model", model.trim())
            .put("temperature", 0.3)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", LlmPrompt.system(req)))
                .put(JSONObject().put("role", "user").put("content", LlmPrompt.user(req))))
        val raw = Http.call("${baseUrl.trim().trimEnd('/')}/chat/completions", "POST", headers, body.toString(), title)
        val text = try {
            JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } catch (e: Exception) {
            throw TranslateException("Сервер вернул неожиданный ответ")
        }
        return LlmPrompt.parse(text, req.texts.size)
    }
}

// ==================================================================== общий помощник «вся страница одним запросом»

/**
 * Переводит облака порциями: тексты склеиваются через перевод строки, ответ делится обратно.
 * Если сервис склеил строки и их число не совпало, порция переводится по одной.
 */
internal fun translateJoined(texts: List<String>, maxChars: Int, one: (String) -> String): List<String> {
    val clean = texts.map { it.replace('\n', ' ') }
    val chunks = ArrayList<MutableList<Int>>()
    var size = 0
    for (i in clean.indices) {
        val len = clean[i].length + 1
        if (chunks.isEmpty() || size + len > maxChars) {
            chunks.add(mutableListOf())
            size = 0
        }
        chunks.last().add(i)
        size += len
    }
    val out = arrayOfNulls<String>(clean.size)
    for (chunk in chunks) {
        val parts = one(chunk.joinToString("\n") { clean[it] }).split("\n").map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size == chunk.size) chunk.forEachIndexed { k, idx -> out[idx] = parts[k] }
        else for (idx in chunk) out[idx] = one(clean[idx]).trim()
    }
    return out.map { it.orEmpty() }
}

private fun simpleCode(google: String) = when (google) {
    "zh-CN", "zh-TW" -> "zh"
    else -> google
}

// ==================================================================== Яндекс

/**
 * Яндекс Переводчик.
 * Без ключа — неофициальный бесплатный способ (как у мобильного приложения Яндекса).
 * С ключом — официальный Yandex Cloud Translate (нужны API-ключ и ID каталога).
 */
class YandexTranslator(private val key: String, private val folderId: String) : Translator {
    override val title = "Яндекс"
    private val ucid = java.util.UUID.randomUUID().toString().replace("-", "")

    override fun translate(req: TranslateRequest): List<String> {
        val texts = req.anchored ?: req.texts
        val sl = simpleCode(req.src.google)
        val tl = simpleCode(req.tgt.google)
        return if (key.isNotBlank()) cloud(texts, sl, tl) else free(texts, sl, tl)
    }

    private fun free(texts: List<String>, sl: String, tl: String): List<String> {
        val body = buildString {
            for (t in texts) append("text=").append(Http.enc(t.replace('\n', ' '))).append('&')
            append("lang=").append(Http.enc("$sl-$tl"))
        }
        val raw = Http.call(
            "https://translate.yandex.net/api/v1/tr.json/translate?ucid=$ucid&srv=android&format=text",
            "POST", mapOf("Content-Type" to "application/x-www-form-urlencoded"), body, title)
        val arr = try {
            JSONObject(raw).getJSONArray("text")
        } catch (e: Exception) {
            throw TranslateException("Яндекс: неожиданный ответ. Бесплатный способ мог перестать работать — " +
                "укажите ключ Yandex Cloud или выберите другой переводчик.")
        }
        return texts.indices.map { arr.optString(it).trim() }
    }

    private fun cloud(texts: List<String>, sl: String, tl: String): List<String> {
        if (folderId.isBlank()) throw TranslateException("Яндекс: для ключа Yandex Cloud укажите ID каталога")
        val body = JSONObject()
            .put("folderId", folderId.trim())
            .put("sourceLanguageCode", sl)
            .put("targetLanguageCode", tl)
            .put("texts", JSONArray(texts))
        val raw = Http.call("https://translate.api.cloud.yandex.net/translate/v2/translate", "POST",
            mapOf("Authorization" to "Api-Key ${key.trim()}"), body.toString(), title)
        val arr = JSONObject(raw).getJSONArray("translations")
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("text") }
    }
}

// ==================================================================== DuckDuckGo

/** Переводчик DuckDuckGo (внутри — Microsoft Bing). Неофициальный, без ключа. */
class DuckDuckGoTranslator : Translator {
    override val title = "DuckDuckGo"

    private fun token(): String {
        val page = Http.call("https://duckduckgo.com/?q=translate&ia=web",
            headers = mapOf("User-Agent" to UA), title = title)
        return Regex("vqd=[\"']?([\\d-]+)").find(page)?.groupValues?.get(1)
            ?: throw TranslateException("DuckDuckGo: не удалось начать сессию перевода. Попробуйте позже.")
    }

    override fun translate(req: TranslateRequest): List<String> {
        val vqd = token()
        val from = req.src.microsoft
        val to = req.tgt.microsoft
        return translateJoined(req.anchored ?: req.texts, 1500) { text ->
            val url = "https://duckduckgo.com/translation.js?vqd=${Http.enc(vqd)}&query=translate" +
                "&from=${Http.enc(from)}&to=${Http.enc(to)}"
            val raw = Http.call(url, "POST", mapOf("Content-Type" to "text/plain", "User-Agent" to UA), text, title)
            try {
                JSONObject(raw).getString("translated")
            } catch (e: Exception) {
                throw TranslateException("DuckDuckGo: неожиданный ответ сервера")
            }
        }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}

// ==================================================================== Lingva

/**
 * Lingva — открытый посредник к Google Переводчику. Помогает, когда Google ограничивает
 * запросы напрямую (например, при VPN). Адрес сервера можно поменять в настройках.
 */
class LingvaTranslator(private val baseUrl: String) : Translator {
    override val title = "Lingva"

    private fun code(google: String) = when (google) {
        "zh-CN" -> "zh"
        "zh-TW" -> "zh_HANT"
        else -> google
    }

    override fun translate(req: TranslateRequest): List<String> {
        val base = baseUrl.trim().trimEnd('/').ifEmpty { "https://lingva.ml" }
        val sl = code(req.src.google)
        val tl = code(req.tgt.google)
        return translateJoined(req.anchored ?: req.texts, 1500) { text ->
            val q = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")
            val raw = Http.call("$base/api/v1/$sl/$tl/$q", title = title)
            val o = try { JSONObject(raw) } catch (e: Exception) {
                throw TranslateException("Lingva: сервер ответил не JSON. Попробуйте другой адрес сервера.")
            }
            if (o.has("error")) throw TranslateException("Lingva: ${o.optString("error")}")
            o.optString("translation")
        }
    }
}

// ==================================================================== Papago

/** Papago (Naver Cloud) — сильнее всех в корейском. Нужны Client ID и Client Secret. */
class PapagoTranslator(private val id: String, private val secret: String) : Translator {
    override val title = "Papago"

    override fun translate(req: TranslateRequest): List<String> {
        if (id.isBlank() || secret.isBlank()) throw TranslateException("Papago: укажите Client ID и Client Secret")
        val headers = mapOf(
            "X-NCP-APIGW-API-KEY-ID" to id.trim(),
            "X-NCP-APIGW-API-KEY" to secret.trim(),
        )
        return translateJoined(req.anchored ?: req.texts, 4500) { text ->
            val body = JSONObject().put("source", req.src.google).put("target", req.tgt.google).put("text", text)
            val raw = Http.call("https://papago.apigw.ntruss.com/nmt/v1/translation", "POST", headers, body.toString(), title)
            try {
                JSONObject(raw).getJSONObject("message").getJSONObject("result").getString("translatedText")
            } catch (e: Exception) {
                throw TranslateException("Papago: неожиданный ответ сервера")
            }
        }
    }
}

// ==================================================================== Claude

/** Claude (Anthropic API). Учитывает контекст и глоссарий, как Gemini. */
class ClaudeTranslator(private val key: String, private val model: String) : Translator {
    override val title = "Claude"
    override val usesLlm = true

    override fun translate(req: TranslateRequest): List<String> {
        if (key.isBlank()) throw TranslateException("Claude: укажите API-ключ (platform.claude.com)")
        val body = JSONObject()
            .put("model", model.ifBlank { "claude-haiku-5-5" }.trim())
            .put("max_tokens", 4096)
            .put("temperature", 0.3)
            .put("system", LlmPrompt.system(req))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", LlmPrompt.user(req))))
        val raw = Http.call("https://api.anthropic.com/v1/messages", "POST",
            mapOf("x-api-key" to key.trim(), "anthropic-version" to "2023-06-01"), body.toString(), title)
        val text = try {
            val content = JSONObject(raw).getJSONArray("content")
            (0 until content.length()).map { content.getJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        } catch (e: Exception) {
            throw TranslateException("Claude: неожиданный ответ сервера")
        }
        return LlmPrompt.parse(text, req.texts.size)
    }
}

/** Описание переводчика для экрана настроек. */
data class TranslatorInfo(val key: String, val title: String, val subtitle: String, val group: String)

object Translators {
    val all = listOf(
        TranslatorInfo("google_free", "Google", "Без ключа · среднее качество", "Бесплатно, без ключа"),
        TranslatorInfo("yandex", "Яндекс", "Без ключа · хорошо знает русский", "Бесплатно, без ключа"),
        TranslatorInfo("duckduckgo", "DuckDuckGo", "Без ключа · переводит через Bing", "Бесплатно, без ключа"),
        TranslatorInfo("lingva", "Lingva", "Без ключа · Google через посредника, выручает при VPN", "Бесплатно, без ключа"),
        TranslatorInfo("deepl", "DeepL", "Ключ · 500 тыс. символов в месяц бесплатно", "С ключом"),
        TranslatorInfo("microsoft", "Microsoft", "Ключ · 2 млн символов в месяц бесплатно", "С ключом"),
        TranslatorInfo("papago", "Papago", "Ключ Naver Cloud · лучший для корейского", "С ключом"),
        TranslatorInfo("gemini", "Gemini", "ИИ · есть бесплатный лимит", "ИИ — понимают контекст"),
        TranslatorInfo("claude", "Claude", "ИИ · естественный живой перевод", "ИИ — понимают контекст"),
        TranslatorInfo("openai", "OpenAI-совместимый", "ИИ · OpenAI, OpenRouter, DeepSeek и другие", "ИИ — понимают контекст"),
    )

    fun info(key: String): TranslatorInfo = all.firstOrNull { it.key == key } ?: all[0]

    fun create(s: Settings): Translator = when (s.translator) {
        "yandex" -> YandexTranslator(s.yandexKey, s.yandexFolder)
        "duckduckgo" -> DuckDuckGoTranslator()
        "lingva" -> LingvaTranslator(s.lingvaUrl)
        "deepl" -> DeepLTranslator(s.deeplKey)
        "microsoft" -> MicrosoftTranslator(s.microsoftKey, s.microsoftRegion)
        "papago" -> PapagoTranslator(s.papagoId, s.papagoSecret)
        "gemini" -> GeminiTranslator(s.geminiKey, s.geminiModel)
        "claude" -> ClaudeTranslator(s.claudeKey, s.claudeModel)
        "openai" -> OpenAICompatTranslator(s.openaiUrl, s.openaiKey, s.openaiModel)
        else -> GoogleFreeTranslator()
    }
}
