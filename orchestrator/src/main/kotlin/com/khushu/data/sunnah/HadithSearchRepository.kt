package com.khushu.data.sunnah

import com.khushu.data.markup.ContentMarkup
import com.khushu.data.markup.ContentBlockParser
import com.khushu.data.model.SearchResultRow
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Per-language FTS5 search over hadith content.
 *
 * The index lives in a SIDE database (never touching the distribution `.db`
 * files). Rebuild is fingerprint-based: fingerprint = max-hadith-id + row
 * count per corpus for the language — stale corpora trigger a rebuild,
 * up-to-date indexes are reused.
 *
 * Ported from SunnahApp's SearchRepository pattern, modernized:
 * real FTS5 virtual table (the old plain-table version crashed on MATCH),
 * typed blocks_json parsing instead of regex.
 */
class HadithSearchRepository(
    private val corporaRoot: File,
    /** Side index DB path (created if missing). */
    private val indexDbPath: File? = null,
) : AutoCloseable {

    private val indexDb: File get() = indexDbPath ?: File(corporaRoot, "search_index.db")
    private var connection: Connection? = null

    companion object {
        private val LANG_RE = Regex("^[a-z]{2,4}(-[A-Za-z0-9]+)?$")
        private fun table(lang: String): String {
            require(LANG_RE.matches(lang)) { "unsupported lang code: $lang" }
            return "hadith_fts_" + lang.replace(Regex("[^a-zA-Z0-9]"), "_")
        }
    }

    /**
     * Build or refresh the FTS index for [lang] across all installed corpora.
     * No-ops (returning the existing count) when the fingerprint is fresh
     * unless [rebuildIfStale] is false-forced... i.e. rebuild happens when
     * [force] or when the stored fingerprint differs.
     */
    fun buildIndex(lang: String, force: Boolean = false): IndexBuildSummary {
        val t = table(lang)
        val c = conn()
        c.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE IF NOT EXISTS index_meta (lang TEXT PRIMARY KEY, fingerprint TEXT, indexed_count INTEGER)")
        }
        val fp = fingerprint(lang)
        val existing = if (!force) {
            c.prepareStatement("SELECT fingerprint, indexed_count FROM index_meta WHERE lang=?").use { ps ->
                ps.setString(1, lang)
                val rs = ps.executeQuery()
                if (rs.next()) rs.getString(1) to rs.getInt(2) else null
            }
        } else null
        if (existing != null && existing.first == fp && tableExists(t)) {
            return IndexBuildSummary(lang, existing.second, rebuilt = false)
        }

        c.createStatement().use { st ->
            st.executeUpdate("DROP TABLE IF EXISTS $t")
            st.executeUpdate(
                """CREATE VIRTUAL TABLE $t USING fts5(
                   hadith_id UNINDEXED, collection_id UNINDEXED, book_id UNINDEXED, text,
                   tokenize='unicode61 remove_diacritics 2')""",
            )
        }

        var total = 0
        for (dbFile in corpusFiles()) {
            val collId = dbFile.nameWithoutExtension
            val src = DriverManager.getConnection(jdbcUri(dbFile.absolutePath))
            try {
                src.prepareStatement(
                    """SELECT hc.hadith_id, hc.blocks_json, h.book_id
                       FROM hadith_contents hc JOIN hadiths h ON h.id = hc.hadith_id
                       WHERE hc.lang = ?""",
                ).use { ps ->
                    ps.setString(1, lang)
                    val rs = ps.executeQuery()
                    c.prepareStatement("INSERT INTO $t (hadith_id, collection_id, book_id, text) VALUES (?,?,?,?)")
                        .use { insert ->
                            while (rs.next()) {
                                insert.setString(1, rs.getString(1))
                                insert.setString(2, collId)
                                insert.setString(3, rs.getString(3))
                                insert.setString(4, plainText(rs.getString(2)))
                                insert.addBatch()
                                if (++total % 2000 == 0) insert.executeBatch()
                            }
                            insert.executeBatch()
                        }
                }
            } finally {
                runCatching { src.close() }
            }
        }

        c.prepareStatement("INSERT OR REPLACE INTO index_meta (lang, fingerprint, indexed_count) VALUES (?,?,?)").use { ps ->
            ps.setString(1, lang); ps.setString(2, fp); ps.setInt(3, total); ps.executeUpdate()
        }
        return IndexBuildSummary(lang, total, rebuilt = true)
    }

    /**
     * Full-text search. Query words are ANDed with prefix matching so Arabic
     * and English prefix queries both work.
     */
    fun search(query: String, lang: String, limit: Int = 20, offset: Int = 0): List<SearchResultRow> {
        val t = table(lang)
        val c = conn()
        if (!tableExists(t)) return emptyList()
        val match = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ") { "\"${it.replace("\"", "")}\"*" }
        if (match.isBlank()) return emptyList()
        return c.prepareStatement(
            """SELECT hadith_id, snippet($t, 3, '⟨', '⟩', '…', 16)
               FROM $t WHERE $t MATCH ? ORDER BY rank LIMIT ? OFFSET ?""",
        ).use { ps ->
            ps.setString(1, match)
            ps.setInt(2, limit)
            ps.setInt(3, offset)
            val rs = ps.executeQuery()
            buildList { while (rs.next()) add(SearchResultRow(rs.getString(1), rs.getString(2))) }
        }
    }

    override fun close() {
        connection?.let { runCatching { it.close() } }
        connection = null
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun corpusFiles(): List<File> =
        corporaRoot.listFiles { f ->
            f.extension == "db" && f.name != "scholars_info.db" && f.name != indexDb.name
        }?.sortedBy { it.name } ?: emptyList()

    /** Fingerprint = per-corpus max-hadith-id + content-row-count for [lang]. */
    private fun fingerprint(lang: String): String = corpusFiles().mapNotNull { db ->
        try {
            DriverManager.getConnection(jdbcUri(db.absolutePath)).use { src ->
                src.prepareStatement("SELECT MAX(hadith_id), COUNT(*) FROM hadith_contents WHERE lang=?").use { ps ->
                    ps.setString(1, lang)
                    val rs = ps.executeQuery()
                    if (rs.next()) "${db.name}:${rs.getString(1)}:${rs.getInt(2)}" else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }.joinToString("|")

    private fun tableExists(t: String): Boolean {
        val c = conn()
        return c.prepareStatement("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?").use { ps ->
            ps.setString(1, t)
            val rs = ps.executeQuery()
            rs.next() && rs.getInt(1) == 1
        }
    }

    /** blocks_json → searchable plain text (markup stripped, spans preserved). */
    private fun plainText(blocksJson: String): String =
        try {
            ContentBlockParser.parseBlocks(blocksJson).joinToString(" ") { block ->
                ContentMarkup.plainText(block.spans)
            }
        } catch (e: Exception) {
            "" // never let one malformed row break the whole index
        }

    private fun jdbcUri(path: String) = "jdbc:sqlite:file:${File(path).absolutePath}?mode=ro&immutable=1"

    private fun conn(): Connection {
        connection?.let { return it }
        indexDb.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${indexDb.absolutePath}").also { connection = it }
    }
}

data class IndexBuildSummary(val lang: String, val indexedCount: Int, val rebuilt: Boolean)
