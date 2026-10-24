package com.manhwatranslator.core

import android.graphics.Bitmap
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

/** Распознавание текста через ML Kit (модели приходят из сервисов Google Play). */
class Ocr {
    private val clients = HashMap<String, TextRecognizer>()

    private fun client(script: String): TextRecognizer = clients.getOrPut(script) {
        when (script) {
            "korean" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "japanese" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "chinese" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    /** Вызывать из фонового потока. */
    fun recognize(bitmap: Bitmap, lang: SourceLang): List<TextLine> {
        val result = try {
            Tasks.await(client(lang.script).process(InputImage.fromBitmap(bitmap, 0)), 60, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is MlKitException && cause.errorCode == MlKitException.UNAVAILABLE) {
                throw OcrException("Сервисы Google Play ещё скачивают модель распознавания для языка «${lang.name}». " +
                    "Подождите минуту (нужен интернет) и попробуйте снова.")
            }
            throw OcrException("Ошибка распознавания: ${cause?.message ?: e.message}")
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
