package com.manhwatranslator.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Тайтл — сохранённый контекст одной манхвы: название, свой глоссарий,
 * последние реплики (их видит переводчик) и кэш уже переведённых облаков.
 * Хранится в памяти телефона: files/titles/<id>.json.
 */
class Title(
    val id: String,
    var name: String,
    var glossary: String = "",
    val history: ArrayDeque<Pair<String, String>> = ArrayDeque(),
    val cache: LinkedHashMap<String, String> = LinkedHashMap(),
    var updated: Long = System.currentTimeMillis(),
) {
    fun toJson(): String {
        val h = JSONArray()
        for ((o, t) in history) h.put(JSONArray().put(o).put(t))
        val c = JSONObject()
        for ((k, v) in cache) c.put(k, v)
        return JSONObject()
            .put("id", id).put("name", name).put("glossary", glossary).put("updated", updated)
            .put("history", h).put("cache", c)
            .toString()
    }

    companion object {
        const val MAX_HISTORY = 60
        const val MAX_CACHE = 3000

        fun fromJson(json: String): Title {
            val o = JSONObject(json)
            val t = Title(o.getString("id"), o.optString("name"), o.optString("glossary"), updated = o.optLong("updated"))
            val h = o.optJSONArray("history") ?: JSONArray()
            for (i in 0 until h.length()) {
                val p = h.optJSONArray(i) ?: continue
                t.history.addLast(p.optString(0) to p.optString(1))
            }
            val c = o.optJSONObject("cache") ?: JSONObject()
            for (k in c.keys()) t.cache[k] = c.optString(k)
            return t
        }
    }
}

/** Краткие сведения о тайтле для списка. */
data class TitleInfo(val id: String, val name: String, val lines: Int, val glossary: String, val updated: Long)

object TitleStore {
    fun dir(ctx: Context): File = File(ctx.filesDir, "titles").apply { mkdirs() }

    fun file(ctx: Context, id: String): File = File(dir(ctx), "$id.json")

    fun list(ctx: Context): List<TitleInfo> =
        (dir(ctx).listFiles { f -> f.extension == "json" } ?: emptyArray()).mapNotNull { f ->
            try {
                val t = Title.fromJson(f.readText())
                TitleInfo(t.id, t.name, t.history.size, t.glossary, t.updated)
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.updated }

    fun load(ctx: Context, id: String): Title? = try {
        val f = file(ctx, id)
        if (id.isNotBlank() && f.exists()) Title.fromJson(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun save(ctx: Context, t: Title) {
        while (t.history.size > Title.MAX_HISTORY) t.history.removeFirst()
        while (t.cache.size > Title.MAX_CACHE) t.cache.remove(t.cache.keys.first())
        t.updated = System.currentTimeMillis()
        val f = file(ctx, t.id)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(t.toJson())
        tmp.renameTo(f)
    }

    fun create(ctx: Context, name: String, glossary: String): Title {
        val t = Title(UUID.randomUUID().toString().take(8), name.trim(), glossary)
        save(ctx, t)
        return t
    }

    fun delete(ctx: Context, id: String) {
        file(ctx, id).delete()
    }

    /** Забыть реплики и кэш, оставив название и глоссарий. */
    fun clearContext(ctx: Context, id: String) {
        val t = load(ctx, id) ?: return
        t.history.clear()
        t.cache.clear()
        save(ctx, t)
    }
}
