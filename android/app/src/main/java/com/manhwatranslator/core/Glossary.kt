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

    fun find(texts: List<String>): List<GlossaryEntry> {
        val joined = texts.joinToString("\n").lowercase()
        return sorted().filter { joined.contains(it.source.lowercase()) }
    }

    fun applyAnchors(text: String): String {
        var out = text
        for (e in sorted()) {
            out = out.replace(Regex(Regex.escape(e.source), RegexOption.IGNORE_CASE), Regex.escapeReplacement(e.target))
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

    /** Имя файла из названия тайтла. */
    fun fileNameFor(ctx: Context, title: String): String {
        val slug = title.trim().replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').ifEmpty { "glossary" }
        var name = "$slug.json"
        var i = 2
        while (File(dir(ctx), name).exists()) name = "${slug}_${i++}.json"
        return name
    }
}
