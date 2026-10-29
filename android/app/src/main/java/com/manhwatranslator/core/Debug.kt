package com.manhwatranslator.core

import android.content.Context
import android.graphics.Color
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Время этапов одного запуска (для режима разработчика). */
class Timings {
    private val start = System.nanoTime()
    private var last = start
    private val list = ArrayList<Pair<String, Long>>()

    /** Закончился этап: время с прошлой отметки. */
    @Synchronized
    fun mark(name: String) {
        val now = System.nanoTime()
        list.add(name to (now - last) / 1_000_000)
        last = now
    }

    @Synchronized
    fun stages(): List<Pair<String, Long>> = ArrayList(list)

    fun totalMs(): Long = (System.nanoTime() - start) / 1_000_000
}

/** Один проход распознавания: вариант картинки × распознаватель. */
class PassInfo(
    val variant: String,
    val script: String,
    val lines: List<TextLine>,
    /** Сколько готовилась картинка этого варианта, мс */
    val prepMs: Long,
    /** Сколько шло распознавание, мс */
    val ocrMs: Long,
    val error: String = "",
)

/** Что добавило объединение проходов. */
class MergeStats {
    var added = 0
    var replaced = 0
}

/** Подробности запуска для режима разработчика. */
class DebugInfo(
    val passes: List<PassInfo> = emptyList(),
    val stages: List<Pair<String, Long>> = emptyList(),
    val totalMs: Long = 0,
    /** Сколько проходов шло одновременно (1 — по очереди) */
    val threads: Int = 1,
    val merge: MergeStats = MergeStats(),
    val lines: Int = 0,
    val bubbles: Int = 0,
    val cacheHits: Int = 0,
    val error: String = "",
) {
    fun report(): String = buildString {
        append(trf("Всего %s мс", totalMs))
        append(" · ")
        append(if (threads > 1) trf("параллельно ×%s", threads) else tr("по очереди"))
        if (stages.isNotEmpty()) {
            append('\n')
            append(stages.joinToString(" · ") { (n, ms) -> "$n $ms" })
        }
        if (passes.isNotEmpty()) {
            append('\n').append(tr("Проходы:"))
            for ((variant, group) in passes.groupBy { it.variant }) {
                append("\n  ").append(Enhance.variantName(variant)).append(": ")
                append(group.joinToString(", ") { p ->
                    val err = if (p.error.isNotEmpty()) " ⚠" else ""
                    "${Ocr.scriptName(p.script)} ${p.lines.size} (${p.ocrMs} ${tr("мс")})$err"
                })
                group.firstOrNull()?.let { if (it.prepMs > 0) append(trf(" · подготовка %s мс", it.prepMs)) }
            }
        }
        append('\n')
        append(trf("Строк %s (проходы добавили %s, заменили %s) · облаков %s", lines, merge.added, merge.replaced, bubbles))
        if (cacheHits > 0) append(trf(" · из кэша %s", cacheHits))
        val errors = passes.filter { it.error.isNotEmpty() }.map { "${Ocr.scriptName(it.script)}: ${it.error}" }.distinct()
        if (errors.isNotEmpty()) append('\n').append(tr("Ошибки проходов:")).append('\n').append(errors.joinToString("\n"))
        if (error.isNotEmpty()) append('\n').append(tr("Ошибка:")).append(' ').append(error)
    }

    companion object {
        /** Цвета рамок для проходов — по порядку вариантов. */
        val passColors = intArrayOf(
            Color.rgb(80, 200, 120), Color.rgb(90, 160, 255), Color.rgb(255, 170, 60),
            Color.rgb(230, 90, 200), Color.rgb(250, 230, 80),
        )
    }
}

/** Журнал запусков в памяти телефона — его можно скопировать и прислать. */
object DevLog {
    private const val MAX_BYTES = 60_000

    private fun file(ctx: Context) = File(ctx.filesDir, "dev_log.txt")

    @Synchronized
    fun append(ctx: Context, text: String) {
        try {
            val f = file(ctx)
            val stamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())
            val old = if (f.exists()) f.readText() else ""
            var all = "$old── $stamp\n$text\n\n"
            if (all.length > MAX_BYTES) all = all.substring(all.length - MAX_BYTES).substringAfter("── ", "").let { "── $it" }
            f.writeText(all)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun read(ctx: Context): String = try { file(ctx).takeIf { it.exists() }?.readText().orEmpty() } catch (_: Exception) { "" }

    @Synchronized
    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) {}
    }
}
