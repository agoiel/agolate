package com.manhwatranslator.core

import android.content.Context
import android.graphics.Bitmap

/**
 * Конвейер: картинка → строки (OCR) → облака → перевод.
 * Хранит кэш переводов и последние реплики для контекста.
 */
class Pipeline(private val ctx: Context) {
    private val ocr = Ocr()
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 3000
    }
    private val history = ArrayDeque<Pair<String, String>>()

    @Synchronized
    fun reset() {
        cache.clear()
        history.clear()
    }

    /** Вызывать из фонового потока. */
    @Synchronized
    fun process(bitmap: Bitmap, s: Settings, translate: Boolean = true): PageResult {
        val src = Languages.src(s.sourceLang)
        val tgt = Languages.tgt(s.targetLang)
        val t0 = System.currentTimeMillis()

        val lines = try {
            ocr.recognize(bitmap, src, s.ocrMode)
        } catch (e: OcrException) {
            return PageResult(error = e.message ?: "Ошибка распознавания")
        }
        val img = PixelImage.fromBitmap(bitmap)
        val bubbles = Bubbles.group(lines, img, Bubbles.Params(
            joiner = src.joiner, rtl = src.rtl, gap = s.bubbleGap,
            skipFreeText = s.skipFreeText, skipCut = s.skipCut,
        ))
        val t1 = System.currentTimeMillis()
        if (!translate) return PageResult(bubbles, ocrMs = t1 - t0)

        var hits = 0
        val todo = ArrayList<Bubble>()
        for (b in bubbles) {
            if (!b.translatable) continue
            val hit = cache[key(s, b.text)]
            if (hit != null) { b.translation = hit; hits++ } else todo.add(b)
        }

        var error = ""
        if (todo.isNotEmpty()) {
            try {
                translateBubbles(todo, s, src, tgt)
            } catch (e: TranslateException) {
                error = e.message ?: "Ошибка перевода"
            } catch (e: Exception) {
                error = "Ошибка перевода: ${e.message}"
            }
        }
        return PageResult(bubbles, error, hits, t1 - t0, System.currentTimeMillis() - t1)
    }

    private fun key(s: Settings, text: String) =
        "${s.sourceLang}>${s.targetLang}|${s.translator}|${text.replace(Regex("\\s+"), "").lowercase()}"

    private fun translateBubbles(bubbles: List<Bubble>, s: Settings, src: SourceLang, tgt: TargetLang) {
        val texts = bubbles.map { it.text }
        RateLimit.intervalMs = (s.requestIntervalSec.coerceIn(0f, 30f) * 1000).toLong()
        val tr = Translators.create(s)
        val glossary = s.glossary.takeIf { it.isNotBlank() }?.let { GlossaryStore.load(ctx, it) }
        val found = glossary?.find(texts) ?: emptyList()
        val anchored = if (glossary != null && !tr.usesLlm && found.isNotEmpty()) texts.map { glossary.applyAnchors(it) } else null
        val n = s.contextSize
        val req = TranslateRequest(
            texts = texts, src = src, tgt = tgt,
            history = if (n > 0) history.toList().takeLast(n) else emptyList(),
            glossary = found, anchored = anchored,
        )
        val out = tr.translate(req)
        bubbles.forEachIndexed { i, b ->
            val t = out.getOrNull(i).orEmpty()
            b.translation = t
            if (t.isNotBlank()) {
                cache[key(s, b.text)] = t
                history.addLast(b.text to t)
                while (history.size > 50) history.removeFirst()
            }
        }
    }

    fun close() = ocr.close()
}
