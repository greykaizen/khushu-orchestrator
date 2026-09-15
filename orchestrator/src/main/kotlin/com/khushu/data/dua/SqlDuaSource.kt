package com.khushu.data.dua

import com.khushu.data.model.Dua
import com.khushu.data.model.DuaCategory
import com.khushu.data.store.AssetResolver
import com.khushu.data.store.SqlStore

/**
 * SQL-backed dua/dhikr over the `khushu-content` DB / `content` pack. Returns the same
 * [Dua]/[DuaCategory] models the JSON `DuaSource` produced, so the aggregate can flip to
 * it without touching callers. `audioUrl` resolves the local GH mirror (assets kind
 * `dua-audio`) rather than the donor CDN — the mirror is the canonical, licensed,
 * self-hosted copy; the donor url is preserved only in the archive.
 */
class SqlDuaSource(private val content: SqlStore, private val assets: AssetResolver? = null) {

    suspend fun duas(): List<Dua> = content.query(
        "SELECT d.id, d.post_id, p.post_title, d.category, d.subcategory, d.title, d.arabic, " +
            "d.repetition, d.translation, d.transliteration, d.virtue, d.explanation, d.reference, d.audio_asset_id " +
            "FROM dua_items d LEFT JOIN dua_posts p ON p.post_id = d.post_id ORDER BY d.id",
    ).map { r -> mapDua(r) }

    suspend fun dua(id: Int): Dua? = content.query(
        "SELECT d.id, d.post_id, p.post_title, d.category, d.subcategory, d.title, d.arabic, " +
            "d.repetition, d.translation, d.transliteration, d.virtue, d.explanation, d.reference, d.audio_asset_id " +
            "FROM dua_items d LEFT JOIN dua_posts p ON p.post_id = d.post_id WHERE d.id=?", listOf(id),
    ).firstOrNull()?.let { mapDua(it) }

    suspend fun categories(): List<DuaCategory> = content.query(
        "SELECT category, subcategory, post_title, dua_count FROM dua_posts ORDER BY category, post_id",
    ).map { r -> DuaCategory(r.stringAt(0) ?: "", r.stringAt(1) ?: "", r.stringAt(2) ?: "", r.intAt(3) ?: 0) }

    suspend fun bySubcategory(subcategory: String): List<Dua> = content.query(
        "SELECT d.id, d.post_id, p.post_title, d.category, d.subcategory, d.title, d.arabic, " +
            "d.repetition, d.translation, d.transliteration, d.virtue, d.explanation, d.reference, d.audio_asset_id " +
            "FROM dua_items d LEFT JOIN dua_posts p ON p.post_id = d.post_id WHERE d.subcategory=? ORDER BY d.id",
        listOf(subcategory),
    ).map { mapDua(it) }

    /** Subcategory slugs present, with dua counts (for a picker). */
    suspend fun subcategoryCounts(): Map<String, Int> = content.query(
        "SELECT subcategory, COUNT(*) FROM dua_items GROUP BY subcategory ORDER BY subcategory",
    ).associate { it.stringAt(0)!! to (it.intAt(1) ?: 0) }

    private fun mapDua(r: com.khushu.data.store.Row) = Dua(
        id = r.intAt(0) ?: 0, postId = r.intAt(1) ?: 0, postTitle = r.stringAt(2) ?: "",
        category = r.stringAt(3) ?: "", subcategory = r.stringAt(4) ?: "", title = r.stringAt(5) ?: "",
        arabic = r.stringAt(6) ?: "", repetition = r.stringAt(7) ?: "", translation = r.stringAt(8) ?: "",
        transliteration = r.stringAt(9) ?: "", virtue = r.stringAt(10) ?: "", explanation = r.stringAt(11) ?: "",
        audioUrl = r.stringAt(13)?.let { if (assets != null) assets.url("dua-audio", it) else it },
        reference = r.stringAt(12),
    )
}
