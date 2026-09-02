package com.khushu.data.dua

import com.khushu.data.model.Dua
import com.khushu.data.model.DuaArticle
import com.khushu.data.model.DuaArticleCategory
import com.khushu.data.model.DuaArticleInfo
import com.khushu.data.model.DuaCategory
import com.khushu.data.transport.ContentFetcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Dua & dhikr content over `assets/dua_dhikr/` — the lifewithallah corpus
 * (491 duas, 186 reading articles). Structured fields are typed; article
 * bodies are RAW HTML passthrough (the host's markup stack renders them —
 * the same contract as chapter-info and tafsir segments).
 */
class DuaSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "assets/dua_dhikr",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var duasCache: List<Dua>? = null
    private var categoriesCache: List<DuaCategory>? = null
    private var articleIndexCache: List<DuaArticleCategory>? = null

    /** All 491 duas (cached). Filter client-side, or use [bySubcategory]. */
    suspend fun duas(): List<Dua> = duasCache ?: run {
        val arr = json.parseToJsonElement(fetcher.fetch("$baseDir/dua_data.json").decodeToString()).jsonArray
        val out = arr.map { el ->
            val o = el.jsonObject
            Dua(
                id = o["id"]!!.jsonPrimitive.int,
                postId = o["post_id"]!!.jsonPrimitive.int,
                postTitle = o["post_title"]?.jsonPrimitive?.content ?: "",
                category = o["category"]?.jsonPrimitive?.content ?: "",
                subcategory = o["subcategory"]?.jsonPrimitive?.content ?: "",
                title = o["title"]?.jsonPrimitive?.content ?: "",
                arabic = o["arabic"]?.jsonPrimitive?.content ?: "",
                repetition = o["repetition"]?.jsonPrimitive?.content ?: "",
                translation = o["translation"]?.jsonPrimitive?.content ?: "",
                transliteration = o["transliteration"]?.jsonPrimitive?.content ?: "",
                virtue = o["virtue"]?.jsonPrimitive?.content ?: "",
                explanation = o["explanation"]?.jsonPrimitive?.content ?: "",
                audioUrl = o["audio_url"]?.let { if (it.jsonPrimitive.isString) it.jsonPrimitive.content else null },
                reference = o["reference"]?.let { if (it.jsonPrimitive.isString) it.jsonPrimitive.content else null },
            )
        }
        duasCache = out
        out
    }

    suspend fun dua(id: Int): Dua? = duas().firstOrNull { it.id == id }

    /** Subcategory groups derived from the corpus (30: 12 main + 18 other). */
    suspend fun categories(): List<DuaCategory> = categoriesCache ?: run {
        val by = mutableMapOf<Pair<String, String>, Pair<String, Int>>()
        for (d in duas()) {
            val key = d.category to d.subcategory
            val cur = by[key]
            by[key] = (cur?.first ?: d.postTitle) to (cur?.second ?: 0) + 1
        }
        val out = by.map { (k, v) ->
            DuaCategory(category = k.first, subcategory = k.second, postTitle = v.first, count = v.second)
        }.sortedWith(compareBy({ it.category }, { it.subcategory }))
        categoriesCache = out
        out
    }

    suspend fun bySubcategory(subcategory: String): List<Dua> =
        duas().filter { it.subcategory == subcategory }

    // ── reading articles ────────────────────────────────────────────────────

    /** Article index: 12 categories → 186 entries (incl. related-articles). */
    suspend fun articleCategories(): List<DuaArticleCategory> = articleIndexCache ?: run {
        val root = json.parseToJsonElement(fetcher.fetch("$baseDir/articles_index.json").decodeToString()).jsonObject
        val out = root["categories"]!!.jsonArray.map { c ->
            val co = c.jsonObject
            DuaArticleCategory(
                id = co["id"]!!.jsonPrimitive.int,
                name = co["name"]!!.jsonPrimitive.content,
                slug = co["slug"]!!.jsonPrimitive.content,
                count = co["count"]!!.jsonPrimitive.int,
                articles = (co["articles"]?.jsonArray ?: emptyList()).map { a ->
                    val ao = a.jsonObject
                    DuaArticleInfo(
                        id = ao["id"]!!.jsonPrimitive.int,
                        title = ao["title"]!!.jsonPrimitive.content,
                        slug = ao["slug"]!!.jsonPrimitive.content,
                        link = ao["link"]?.jsonPrimitive?.content,
                        filePath = ao["file_path"]!!.jsonPrimitive.content,
                    )
                },
            )
        }
        articleIndexCache = out
        out
    }

    /**
     * One article by its `file_path` from [DuaArticleInfo] — RAW HTML body
     * (standard tags only: p/b/span/div/h2/blockquote/…; no custom tags).
     */
    suspend fun article(filePath: String): DuaArticle? {
        if (!filePath.startsWith("articles/") && !filePath.startsWith("related-articles/")) return null
        val root = json.parseToJsonElement(
            fetcher.fetch("$baseDir/$filePath").decodeToString(),
        ).jsonObject
        return DuaArticle(
            id = root["id"]!!.jsonPrimitive.int,
            title = root["title"]!!.jsonPrimitive.content,
            slug = root["slug"]!!.jsonPrimitive.content,
            link = root["link"]?.jsonPrimitive?.content,
            contentHtml = root["content"]!!.jsonPrimitive.content,
        )
    }

    /** Flat article list across all categories. */
    suspend fun articles(): List<DuaArticleInfo> = articleCategories().flatMap { it.articles }

    // ── local audio mirrors ─────────────────────────────────────────────────

    /**
     * Repo-relative path of the local opus mirror (`dua_{id}.opus`) when it
     * ships in the bundle; null for the 3 entries without local audio.
     * Mirrors are byte-identical donor audio (no re-encode; Opus decodes on
     * Android 12+ via Media3/ExoPlayer).
     */
    suspend fun localAudioPath(id: Int): String? {
        if (dua(id) == null) return null
        return runCatching {
            fetcher.fetch("$baseDir/dua_$id.opus")
            "$baseDir/dua_$id.opus"
        }.getOrNull()
    }

    /** Opus bytes of the local mirror, or null. */
    suspend fun audio(id: Int): ByteArray? = localAudioPath(id)?.let { fetcher.fetch(it) }
}
