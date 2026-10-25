package com.manhwatranslator.core

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Несколько проходов распознавания для сложных страниц (тот же алгоритм, что в ПК-версии).
 *
 * Распознавание лучше всего читает тёмный текст на светлом ровном фоне, поэтому
 * страница проверяется в нескольких вариантах, а результаты объединяются:
 * 1. оригинал (увеличенный, если текст мелкий);
 * 2. серая версия с растянутым контрастом и инверсией — для светлого текста на тёмном фоне;
 * 3. локальный контраст — фон вычитается, остаются буквы; для пёстрых фонов;
 * 4. (максимальный) инверсия локального контраста;
 * 5. (максимальный) цветовой контраст — для цветного текста на фоне похожей яркости.
 */
object Enhance {
    const val FAST = 1
    const val THOROUGH = 2
    const val MAX = 3

    val modes = listOf(
        Triple(FAST, "Быстрый", "1 проход · для обычных облаков"),
        Triple(THOROUGH, "Тщательный", "3 прохода · тёмные фоны, светлый текст"),
        Triple(MAX, "Максимальный", "5 проходов · пёстрые фоны, цветной текст"),
    )

    /** Во сколько раз увеличить картинку: мелкий текст распознаётся хуже. */
    fun scaleFor(w: Int, h: Int): Float {
        val up = (1200f / w).coerceIn(1f, 2f)
        val cap = sqrt(3_500_000f / (w.toFloat() * h)).coerceAtMost(up)
        return max(cap, min(1f, up))
    }

    fun scaled(src: Bitmap, k: Float): Bitmap =
        if (abs(k - 1f) < 0.01f) src
        else Bitmap.createScaledBitmap(src, (src.width * k).roundToInt(), (src.height * k).roundToInt(), true)

    /** Варианты картинки. Первый — сам оригинал. */
    fun variants(src: Bitmap, mode: Int): List<Bitmap> {
        if (mode <= FAST) return listOf(src)
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h) { i ->
            val c = px[i]
            (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000
        }
        val out = ArrayList<Bitmap>()
        out.add(src)
        out.add(toBitmap(invert(stretch(gray)), w, h))
        val radius = (min(w, h) / 25).coerceIn(12, 48)
        val local = localContrast(gray, w, h, radius)
        out.add(toBitmap(local, w, h))
        if (mode >= MAX) {
            out.add(toBitmap(invert(local.copyOf()), w, h))
            out.add(toBitmap(colorContrast(px, w, h, radius), w, h))
        }
        return out
    }

    // ---------------------------------------------------------------- преобразования

    private fun stretch(g: IntArray): IntArray {
        val hist = IntArray(256)
        for (v in g) hist[v]++
        val lo = percentile(hist, g.size, 0.02)
        val hi = percentile(hist, g.size, 0.98)
        if (hi - lo < 1) return g
        val k = 255f / (hi - lo)
        for (i in g.indices) g[i] = ((g[i] - lo) * k).roundToInt().coerceIn(0, 255)
        return g
    }

    private fun percentile(hist: IntArray, total: Int, q: Double): Int {
        val target = (total * q).toLong()
        var acc = 0L
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= target) return v
        }
        return 255
    }

    private fun invert(g: IntArray): IntArray {
        for (i in g.indices) g[i] = 255 - g[i]
        return g
    }

    /** Яркость минус размытый фон: остаются только резкие перепады (буквы). */
    private fun localContrast(g: IntArray, w: Int, h: Int, r: Int): IntArray {
        val bg = boxBlur(g, w, h, r)
        return IntArray(g.size) { i -> (128 + (g[i] - bg[i]) * 3).coerceIn(0, 255) }
    }

    /** Насколько цвет пикселя отличается от цвета фона вокруг. Буквы — тёмные на белом. */
    private fun colorContrast(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        val rr = boxBlur(IntArray(px.size) { Color.red(px[it]) }, w, h, r)
        val gg = boxBlur(IntArray(px.size) { Color.green(px[it]) }, w, h, r)
        val bb = boxBlur(IntArray(px.size) { Color.blue(px[it]) }, w, h, r)
        return IntArray(px.size) { i ->
            val c = px[i]
            val dr = (Color.red(c) - rr[i]).toFloat()
            val dg = (Color.green(c) - gg[i]).toFloat()
            val db = (Color.blue(c) - bb[i]).toFloat()
            (255 - sqrt(dr * dr + dg * dg + db * db) * 2.5f).roundToInt().coerceIn(0, 255)
        }
    }

    /** Быстрое размытие квадратным окном (скользящие суммы по строкам и столбцам). */
    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            var n = 0
            for (x in 0..min(r, w - 1)) { sum += src[row + x]; n++ }
            for (x in 0 until w) {
                tmp[row + x] = sum / n
                val add = x + r + 1
                val rem = x - r
                if (add < w) { sum += src[row + add]; n++ }
                if (rem >= 0) { sum -= src[row + rem]; n-- }
            }
        }
        val out = IntArray(src.size)
        for (x in 0 until w) {
            var sum = 0
            var n = 0
            for (y in 0..min(r, h - 1)) { sum += tmp[y * w + x]; n++ }
            for (y in 0 until h) {
                out[y * w + x] = sum / n
                val add = y + r + 1
                val rem = y - r
                if (add < h) { sum += tmp[add * w + x]; n++ }
                if (rem >= 0) { sum -= tmp[rem * w + x]; n-- }
            }
        }
        return out
    }

    private fun toBitmap(g: IntArray, w: Int, h: Int): Bitmap {
        val px = IntArray(g.size) { i -> val v = g[i]; Color.rgb(v, v, v) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------- объединение результатов

    private val letterRe = Regex("\\p{L}")

    private fun letters(t: String) = letterRe.findAll(t).count()

    private fun overlap(a: Box, b: Box): Float {
        val w = min(a.x2, b.x2) - max(a.x, b.x)
        val h = min(a.y2, b.y2) - max(a.y, b.y)
        if (w <= 0 || h <= 0) return 0f
        return (w * h) / max(1f, min(a.w * a.h, b.w * b.h))
    }

    /**
     * Объединяет строки из нескольких проходов без дублей. Первый проход — самый надёжный;
     * строка из другого прохода добавляется, если на её месте ничего нет, и заменяет найденное,
     * если в ней заметно больше букв.
     */
    fun merge(passes: List<List<TextLine>>): List<TextLine> {
        val result = ArrayList(passes.firstOrNull() ?: emptyList())
        for (lines in passes.drop(1)) {
            for (cand in lines) {
                val n = letters(cand.text)
                val compact = cand.text.replace(" ", "")
                if (n < 2 || n < 0.5f * compact.length) continue   // мусор с узоров фона
                val hits = result.indices.filter { overlap(result[it].box, cand.box) > 0.4f }
                if (hits.isEmpty()) result.add(cand)
                else if (hits.size == 1 && n > 1.3f * letters(result[hits[0]].text)) result[hits[0]] = cand
            }
        }
        return result
    }
}
