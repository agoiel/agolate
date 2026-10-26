package com.manhwatranslator.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GlossaryEntry(val source: String, val target: String, val note: String = "")

/**
 * Глоссарий тайтла. Формат JSON совпадает с ПК-версией, файлы можно переносить.
 *
 * Обычные переводчики получают текст, где термин уже заменён на перевод («якорь»),
 * ИИ-переводчики получают список терминов с заметками.
 */
class Glossary(var name: String, var sourceLang: String = "", var entries: List<GlossaryEntry> = emptyList()) {

    private fun sorted() = entries.filter { it.source.isNotBlank() && it.target.isNotBlank() }
        .sortedByDescending { it.source.length }

    /**
     * Термин ищется только в начале слова: «인형» (кукла) не совпадёт с «형».
     * После термина могут идти частицы и окончания (선배님, 선배가, 길드에서).
     */
    private fun pattern(e: GlossaryEntry): Regex {
        // английские термины — целым словом: «Gate» не должен совпасть с «Gatekeeper»
        val latinEnd = e.source.lastOrNull()?.let { it in 'a'..'z' || it in 'A'..'Z' } == true
        val end = if (latinEnd) "(?![\\p{L}\\p{N}])" else ""
        return Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(e.source) + end, RegexOption.IGNORE_CASE)
    }

    fun find(texts: List<String>): List<GlossaryEntry> {
        val joined = texts.joinToString("\n")
        return sorted().filter { pattern(it).containsMatchIn(joined) }
    }

    /** Глагольные окончания: «각성했다» — «пробудился», а не «пробуждение했다». */
    private val verbEnding = "(?![하했한할해합함됐된될되돼])"

    fun applyAnchors(text: String): String {
        var out = text
        for (e in sorted()) {
            val p = Regex(pattern(e).pattern + verbEnding, RegexOption.IGNORE_CASE)
            out = p.replace(out, Regex.escapeReplacement(e.target))
        }
        return out
    }

    fun toJson(): String {
        val arr = JSONArray()
        for (e in entries) {
            if (e.source.isBlank()) continue
            arr.put(JSONObject().put("source", e.source).put("target", e.target).put("note", e.note))
        }
        return JSONObject().put("name", name).put("source_lang", sourceLang).put("entries", arr).toString(2)
    }

    /** Текстовый вид для редактора: «оригинал = перевод | заметка» на строку. */
    fun toText(): String = entries.joinToString("\n") { e ->
        if (e.note.isBlank()) "${e.source} = ${e.target}" else "${e.source} = ${e.target} | ${e.note}"
    }

    companion object {
        fun fromJson(json: String, fallbackName: String): Glossary {
            val o = JSONObject(json)
            val arr = o.optJSONArray("entries") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val s = e.optString("source")
                if (s.isBlank()) null else GlossaryEntry(s, e.optString("target"), e.optString("note"))
            }
            return Glossary(o.optString("name", fallbackName), o.optString("source_lang"), list)
        }

        fun parseText(text: String): List<GlossaryEntry> = text.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) return@mapNotNull null
            val src = t.substringBefore("=").trim()
            val rest = t.substringAfter("=")
            val tgt = rest.substringBefore("|").trim()
            val note = if (rest.contains("|")) rest.substringAfter("|").trim() else ""
            if (src.isEmpty() || tgt.isEmpty()) null else GlossaryEntry(src, tgt, note)
        }
    }
}

/** Хранилище глоссариев: files/glossaries/<имя>.json */
object GlossaryStore {
    fun dir(ctx: Context): File = File(ctx.filesDir, "glossaries").apply { mkdirs() }

    fun list(ctx: Context): List<String> =
        dir(ctx).listFiles { f -> f.extension == "json" }?.map { it.name }?.sorted() ?: emptyList()

    fun load(ctx: Context, file: String): Glossary? = try {
        val f = File(dir(ctx), file)
        if (f.exists()) Glossary.fromJson(f.readText(), f.nameWithoutExtension) else null
    } catch (e: Exception) {
        null
    }

    fun save(ctx: Context, file: String, g: Glossary) {
        File(dir(ctx), file).writeText(g.toJson())
    }

    fun delete(ctx: Context, file: String) {
        File(dir(ctx), file).delete()
    }

    /**
     * Глоссарии, которые идут вместе с приложением (assets/glossaries).
     * Ставятся один раз при первом запуске; если пользователь удалит — не возвращаются.
     */
    fun installBundled(ctx: Context, settings: Settings) {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val done = HashSet(prefs.getStringSet("bundled_installed", emptySet()) ?: emptySet())
        // первая версия ставила только корейский
        if (prefs.getBoolean("bundled_glossaries_v1", false)) done.add("universal_ko.json")
        try {
            for (name in ctx.assets.list("glossaries") ?: emptyArray()) {
                if (name in done) continue
                val target = File(dir(ctx), name)
                if (!target.exists()) {
                    ctx.assets.open("glossaries/$name").use { inp -> target.outputStream().use { inp.copyTo(it) } }
                }
                done.add(name)
            }
            if (settings.glossary.isBlank()) universalFor(ctx, settings.sourceLang)?.let { settings.glossary = it }
        } catch (_: Exception) {
        }
        prefs.edit().putStringSet("bundled_installed", done).apply()
    }

    /** Встроенный универсальный глоссарий для языка оригинала, если он есть. */
    fun universalFor(ctx: Context, lang: String): String? {
        val name = "universal_${lang.substringBefore('-')}.json"
        return if (File(dir(ctx), name).exists()) name else null
    }

    /** При смене языка оригинала меняем универсальный глоссарий на подходящий (свои не трогаем). */
    fun switchUniversal(ctx: Context, settings: Settings) {
        val cur = settings.glossary
        if (cur.isNotBlank() && !cur.startsWith("universal_")) return
        settings.glossary = universalFor(ctx, settings.sourceLang) ?: ""
    }

    /** Имя файла из названия тайтла. */
    fun fileNameFor(ctx: Context, title: String): String {
        val slug = title.trim().replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').ifEmpty { "glossary" }
        var name = "$slug.json"
        var i = 2
        while (File(dir(ctx), name).exists()) name = "${slug}_${i++}.json"
        return name
    }
}
