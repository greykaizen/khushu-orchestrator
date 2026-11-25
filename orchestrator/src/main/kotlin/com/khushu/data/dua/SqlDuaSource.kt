package com.khushu.data.dua

import com.khushu.data.markup.ContentBlockParser
import com.khushu.data.model.Dua
import com.khushu.data.model.DuaArticle
import com.khushu.data.model.DuaArticleCategory
import com.khushu.data.model.DuaArticleInfo
import com.khushu.data.model.DuaBlocks
import com.khushu.data.model.DuaCategory
import com.khushu.data.store.AssetResolver
import com.khushu.data.store.Row
import com.khushu.data.store.SqlStore

/**
 * SQL-backed dua/dhikr over the `khushu-content` DB / `content` pack. Returns the same
 * [Dua]/[DuaCategory] models the JSON `DuaSource` produced, so the aggregate can flip to
 * it without touching callers. `audioUrl` resolves the local GH mirror (assets kind
 * `dua-audio`) rather than the donor CDN — the mirror is the canonical, licensed,
 * self-hosted copy; the donor url is preserved only in the archive.
 */
class SqlDuaSource(private val content: SqlStore, private val assets: AssetResolver? = null) {

    @Volatile private var hasBlocksColumn: Boolean? = null

    /**
     * Pre-v2026.11 packs have no `blocks_json`; probe once and fall back to the
     * plain fields. Never fail the whole read because a pack is older than the
     * app's renderer.
     */
    private suspend fun hasBlocks(): Boolean {
        hasBlocksColumn?.let { return it }
        val exists = runCatching { content.query("SELECT blocks_json FROM dua_items LIMIT 1") }.isSuccess
        hasBlocksColumn = exists
        return exists
    }

    private fun projection(withBlocks: Boolean): String =
        "d.id, d.post_id, p.post_title, d.category, d.subcategory, d.title, d.arabic, " +
            "d.repetition, d.translation, d.transliteration, d.virtue, d.explanation, d.reference, d.audio_asset_id" +
            if (withBlocks) ", d.blocks_json" else ", NULL"

    private fun selectSql(withBlocks: Boolean, tail: String): String =
        "SELECT " + projection(withBlocks) +
            " FROM dua_items d LEFT JOIN dua_posts p ON p.post_id = d.post_id " + tail

    suspend fun duas(): List<Dua> {
        val withBlocks = hasBlocks()
        return content.query(selectSql(withBlocks, "ORDER BY d.id")).map { mapDua(it, withBlocks) }
    }

    suspend fun dua(id: Int): Dua? {
        val withBlocks = hasBlocks()
        return content.query(selectSql(withBlocks, "WHERE d.id=?"), listOf(id))
            .firstOrNull()?.let { mapDua(it, withBlocks) }
    }

    suspend fun categories(): List<DuaCategory> = content.query(
        "SELECT category, subcategory, post_title, dua_count FROM dua_posts ORDER BY category, post_id",
    ).map { r -> DuaCategory(r.stringAt(0) ?: "", r.stringAt(1) ?: "", r.stringAt(2) ?: "", r.intAt(3) ?: 0) }

    suspend fun bySubcategory(subcategory: String): List<Dua> {
        val withBlocks = hasBlocks()
        return content.query(
            selectSql(withBlocks, "WHERE d.subcategory=? ORDER BY d.id"),
            listOf(subcategory),
        ).map { mapDua(it, withBlocks) }
    }

    /**
     * Full-text search across every dua — title, Arabic, translation,
     * transliteration, virtue, explanation, reference — through the pack's
     * `dua_fts` index, joined back to the rows it indexes.
     *
     * The user's words become prefix terms ("wear clothes" matches "After
     * Wearing Clothes"); punctuation is dropped rather than passed through,
     * because FTS5 treats it as syntax and one stray quote is a query error.
     * Best matches first (bm25), stable for equal scores.
     */
    suspend fun search(query: String, limit: Int = 40): List<Dua> {
        val match = ftsQuery(query) ?: return emptyList()
        val withBlocks = hasBlocks()
        return content.query(
            "SELECT " + projection(withBlocks) +
                " FROM dua_fts f JOIN dua_items d ON d.id = f.rowid " +
                "LEFT JOIN dua_posts p ON p.post_id = d.post_id " +
                "WHERE dua_fts MATCH ? ORDER BY rank, d.id LIMIT ?",
            listOf(match, limit),
        ).map { r -> mapDua(r, withBlocks) }
    }

    /** "wear clothes" -> "wear* clothes*"; null when nothing searchable is left. */
    internal fun ftsQuery(raw: String): String? {
        val terms = raw
            .map { ch -> if (ch.isLetterOrDigit()) ch else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { "$it*" }
    }

    // ── reading articles (raw HTML passthrough) ──────────────────────────

    /**
     * Article index from the content pack: categories with their member articles.
     * Membership is kept (rather than distinct articles) because the donor index
     * intentionally lists some readings under two categories.
     */
    suspend fun articleCategories(): List<DuaArticleCategory> {
        val rows = content.query(
            // Unique labels: the row abstraction is keyed by column label, so a
            // second bare `id`/`slug` would overwrite the category's value.
            "SELECT c.id AS cat_id,c.name AS cat_name,c.slug AS cat_slug,c.count AS cat_count," +
                "a.id AS article_id,a.title AS article_title,a.slug AS article_slug," +
                "a.link AS article_link,a.body_path AS article_path " +
                "FROM article_categories c " +
                "LEFT JOIN article_members m ON m.category_id=c.id " +
                "LEFT JOIN article_items a ON a.id=m.article_id AND a.has_body=1 " +
                // member rowids preserve donor order (category ids are not ordered,
                // and `c.rowid` aliases the INTEGER PRIMARY KEY id).
                "ORDER BY (SELECT MIN(m2.rowid) FROM article_members m2 " +
                "WHERE m2.category_id=c.id),m.rowid",
        )
        return rows.groupBy { it.longAt(0)?.toInt() ?: 0 }.map { (_, group) ->
            val head = group.first()
            DuaArticleCategory(
                id = head.longAt(0)?.toInt() ?: 0,
                name = head.stringAt(1).orEmpty(),
                slug = head.stringAt(2).orEmpty(),
                count = head.longAt(3)?.toInt() ?: 0,
                articles = group.mapNotNull { r ->
                    val id = r.longAt(4)?.toInt() ?: return@mapNotNull null
                    val path = r.stringAt(8).orEmpty()
                    if (path.isBlank()) return@mapNotNull null
                    DuaArticleInfo(
                        id = id,
                        title = r.stringAt(5).orEmpty(),
                        slug = r.stringAt(6).orEmpty(),
                        link = r.stringAt(7),
                        filePath = path,
                    )
                },
            )
        }
    }

    /** Flat article list across all categories (membership order). */
    suspend fun articles(): List<DuaArticleInfo> =
        articleCategories().flatMap { it.articles }

    /** One article by its `body_path`; body is RAW HTML passthrough. */
    suspend fun article(filePath: String): DuaArticle? {
        if (!filePath.startsWith("articles/") && !filePath.startsWith("related-articles/")) return null
        return content.query(
            "SELECT id,title,slug,link,content_html FROM article_items " +
                "WHERE body_path=? AND has_body=1 LIMIT 1",
            listOf(filePath),
        ).firstOrNull()?.let { r ->
            DuaArticle(
                id = r.longAt(0)?.toInt() ?: 0,
                title = r.stringAt(1).orEmpty(),
                slug = r.stringAt(2).orEmpty(),
                link = r.stringAt(3),
                contentHtml = r.stringAt(4).orEmpty(),
            )
        }
    }

    /** Subcategory slugs present, with dua counts (for a picker). */
    suspend fun subcategoryCounts(): Map<String, Int> = content.query(
        "SELECT subcategory, COUNT(*) FROM dua_items GROUP BY subcategory ORDER BY subcategory",
    ).associate { it.stringAt(0)!! to (it.intAt(1) ?: 0) }

    private fun mapDua(r: Row, withBlocks: Boolean): Dua = Dua(
        id = r.intAt(0) ?: 0, postId = r.intAt(1) ?: 0, postTitle = r.stringAt(2) ?: "",
        category = r.stringAt(3) ?: "", subcategory = r.stringAt(4) ?: "", title = r.stringAt(5) ?: "",
        arabic = r.stringAt(6) ?: "", repetition = r.stringAt(7) ?: "", translation = r.stringAt(8) ?: "",
        transliteration = r.stringAt(9) ?: "", virtue = r.stringAt(10) ?: "", explanation = r.stringAt(11) ?: "",
        audioUrl = r.stringAt(13)?.let { if (assets != null) assets.url("dua-audio", it) else it },
        reference = r.stringAt(12),
        blocks = if (withBlocks) {
            r.stringAt(14)?.let(ContentBlockParser::parseDuaBlocks) ?: DuaBlocks()
        } else {
            DuaBlocks()
        },
    )
}
