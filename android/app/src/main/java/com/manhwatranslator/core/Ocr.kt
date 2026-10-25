package com.manhwatranslator.core

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class OcrException(message: String) : Exception(message)

/** Результат распознавания: строки и сколько строк нашёл каждый распознаватель (для диагностики). */
class OcrResult(val lines: List<TextLine>, val stats: Map<String, Int>)

/** Распознавание текста через ML Kit (модели приходят из сервисов Google Play). */
class Ocr(private val ctx: Context) {
    private val clients = HashMap<String, TextRecognizer>()

    companion object {
        val scriptNames = mapOf("latin" to "латиница", "korean" to "корейский",
            "japanese" to "японский", "chinese" to "китайский")

        /**
         * Распознаватели для языка. Английский дополнительно читается корейским распознавателем:
         * он тоже понимает латиницу и выручает, если модуль латиницы в сервисах Google Play
         * не скачался или работает с ошибками.
         */
        fun scriptsFor(lang: SourceLang): List<String> =
            if (lang.script == "latin") listOf("latin", "korean") else listOf(lang.script)

        /** Попросить сервисы Google Play скачать модели распознавания заранее. */
        fun requestModules(ctx: Context, lang: SourceLang) {
            val ocr = Ocr(ctx)
            try {
                for (script in scriptsFor(lang)) ocr.install(script)
            } catch (_: Exception) {
            }
        }
    }

    private fun install(script: String) {
        val request = ModuleInstallRequest.newBuilder().addApi(client(script)).build()
        ModuleInstall.getClient(ctx).installModules(request)
    }

    private fun client(script: String): TextRecognizer = clients.getOrPut(script) {
        when (script) {
            "korean" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "japanese" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "chinese" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    /**
     * Распознавание в несколько проходов (см. [Enhance]). Координаты строк — в пикселях
     * исходной картинки. Вызывать из фонового потока.
     */
    fun recognize(bitmap: Bitmap, lang: SourceLang, mode: Int): OcrResult {
        val k = Enhance.scaleFor(bitmap.width, bitmap.height)
        val big = Enhance.scaled(bitmap, k)
        val scripts = scriptsFor(lang)
        val stats = LinkedHashMap<String, Int>()
        val passes = ArrayList<List<TextLine>>()
        var failure: OcrException? = null
        for (v in Enhance.variants(big, mode)) {
            for (script in scripts) {
                val lines = try {
                    recognizeOnce(v, script)
                } catch (e: OcrException) {
                    // запасной распознаватель не должен ломать основной, и наоборот
                    failure = failure ?: e
                    emptyList()
                }
                stats[script] = (stats[script] ?: 0) + lines.size
                passes.add(lines.map { l -> TextLine(Box(l.box.x / k, l.box.y / k, l.box.w / k, l.box.h / k), l.text) })
            }
            if (v !== big) v.recycle()
        }
        if (big !== bitmap) big.recycle()
        val merged = Enhance.merge(passes)
        // если не сработал ни один распознаватель — показываем ошибку, а не «текст не найден»
        if (merged.isEmpty()) failure?.let { throw it }
        return OcrResult(merged, stats)
    }

    private fun recognizeOnce(bitmap: Bitmap, script: String): List<TextLine> {
        val result = try {
            Tasks.await(client(script).process(InputImage.fromBitmap(bitmap, 0)), 60, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is MlKitException && cause.errorCode == MlKitException.UNAVAILABLE) {
                try { install(script) } catch (_: Exception) {}
                throw OcrException("Сервисы Google Play ещё скачивают модель распознавания " +
                    "(${scriptNames[script] ?: script}). Подождите минуту (нужен интернет) и попробуйте снова.")
            }
            throw OcrException("Ошибка распознавания (${scriptNames[script] ?: script}): ${cause?.message ?: e.message}")
        } catch (e: java.util.concurrent.TimeoutException) {
            throw OcrException("Распознавание заняло слишком много времени")
        }
        val out = ArrayList<TextLine>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val r = line.boundingBox ?: continue
                out.add(TextLine(Box(r.left.toFloat(), r.top.toFloat(), r.width().toFloat(), r.height().toFloat()), line.text))
            }
        }
        return out
    }

    fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }
}
