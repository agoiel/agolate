package com.manhwatranslator.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TranslateException(message: String) : Exception(message)

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

internal object Http {
    fun call(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
             body: String? = null, title: String): String {
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
                throw TranslateException("$title: ошибка $code $hint\n${text.take(300)}".trim())
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

/** Бесплатный веб-эндпоинт Google без ключа. Неофициальный — может быть ограничен. */
class GoogleFreeTranslator : Translator {
    override val title = "Google (без ключа)"
    private val pool = Executors.newFixedThreadPool(4)

    private fun one(text: String, sl: String, tl: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t" +
            "&sl=${Http.enc(sl)}&tl=${Http.enc(tl)}&q=${Http.enc(text)}"
        val raw = Http.call(url, title = title)
        return try {
            val segs = JSONArray(raw).getJSONArray(0)
            buildString {
                for (i in 0 until segs.length()) {
                    val s = segs.optJSONArray(i) ?: continue
                    if (!s.isNull(0)) append(s.optString(0))
                }
            }
        } catch (e: Exception) {
            throw TranslateException("Google: неожиданный ответ сервера")
        }
    }

    override fun translate(req: TranslateRequest): List<String> {
        val texts = req.anchored ?: req.texts
        val futures = texts.map { t -> pool.submit(Callable { one(t, req.src.google, req.tgt.google) }) }
        return futures.map {
            try {
                it.get(60, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.ExecutionException) {
                throw (e.cause as? TranslateException) ?: TranslateException("Google: ошибка")
            }
        }
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

object Translators {
    /** ключ → название для экрана настроек */
    val all = listOf(
        "google_free" to "Google (без ключа)",
        "deepl" to "DeepL",
        "microsoft" to "Microsoft",
        "gemini" to "Gemini (ИИ)",
        "openai" to "OpenAI-совместимый (ИИ)",
    )

    fun create(s: Settings): Translator = when (s.translator) {
        "deepl" -> DeepLTranslator(s.deeplKey)
        "microsoft" -> MicrosoftTranslator(s.microsoftKey, s.microsoftRegion)
        "gemini" -> GeminiTranslator(s.geminiKey, s.geminiModel)
        "openai" -> OpenAICompatTranslator(s.openaiUrl, s.openaiKey, s.openaiModel)
        else -> GoogleFreeTranslator()
    }
}
