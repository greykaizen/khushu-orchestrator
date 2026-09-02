package com.khushu.data.sunnah

import com.khushu.data.markup.ContentBlockParser
import com.khushu.data.model.Book
import com.khushu.data.model.Chapter
import com.khushu.data.model.ContentBlock
import com.khushu.data.model.Grade
import com.khushu.data.model.Hadith
import com.khushu.data.model.HadithCollection
import com.khushu.data.model.NarratorRef
import com.khushu.data.model.Reference
import com.khushu.data.model.Scholar
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Read-only repository over downloaded `hadiths/{collection}.db` corpora
 * (flattened CorpusBundle schema) plus `scholars_info.db`.
 *
 * Connections are opened lazily per collection in read-only immutable mode,
 * cached for the repository lifetime, and closed via [close]. Corpora are
 * never mutated.
 */
class LocalHadithRepository(
    /** Directory holding `{collection}.db` files. */
    private val corporaRoot: File,
    /** Optional path to scholars_info.db; enables narrator→scholar joins. */
    private val scholarsDb: File? = null,
) : AutoCloseable {

    private val connections = HashMap<String, Connection>()
    private var scholarsConnection: Connection? = null

    val installedCollections: List<String>
        get() = corporaRoot.listFiles { f -> f.extension == "db" && f.name != "scholars_info.db" && f.name != "search_index.db" }
            ?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    fun isInstalled(collectionId: String): Boolean =
        File(corporaRoot, "$collectionId.db").exists()

    /**
     * Routes any corpus id (hadith `bukhari_urn_100010`, book `bukhari_b12`,
     * chapter `bukhari_b12_c3`) to its collection by longest installed-prefix
     * match — the old substringBefore heuristic broke on `_urn_` ids.
     */
    fun collectionOf(id: String): String? =
        installedCollections.filter { id.startsWith("${it}_") }.maxByOrNull { it.length }

    // ── Collections / books / chapters ──────────────────────────────────────

    fun collection(id: String, lang: String): HadithCollection? {
        val c = connect(id) ?: return null
        val row = one(
            c,
            "SELECT id,type,sort_order,has_volumes,has_books,has_chapters,numbering_source FROM collections LIMIT 1",
            emptyList(),
        ) { r -> listOf(r(0), r(1), r(2), r(3), r(4), r(5), r(6)) } ?: return null

        val tr = one(
            c,
            "SELECT title,intro,description FROM collection_translations WHERE collection_id=? AND lang IN (?, 'en') ORDER BY CASE WHEN lang=? THEN 0 ELSE 1 END LIMIT 1",
            listOf(id, lang, lang),
        ) { Triple(it(0), it(1), it(2)) }

        val meta = try {
            rows(c, "SELECT key,value FROM bundle_meta", emptyList()) { Pair(it(0).orEmpty(), it(1).orEmpty()) }
                .toMap()
        } catch (_: java.sql.SQLException) {
            emptyMap()
        }

        return HadithCollection(
            id = row[0].orEmpty(), type = row[1].orEmpty(),
            sortOrder = row[2]?.toIntOrNull() ?: 0,
            hasVolumes = row[3] == "1", hasBooks = row[4] == "1", hasChapters = row[5] == "1",
            numberingSource = row[6]?.takeIf { it.isNotEmpty() },
            title = tr?.first, intro = tr?.second, description = tr?.third,
            schemaVersion = meta["schema_version"]?.toIntOrNull(),
            contentVersion = meta["content_version"]?.takeIf { it.isNotEmpty() },
        )
    }

    fun books(collectionId: String, lang: String): List<Book> {
        val c = connect(collectionId) ?: return emptyList()
        val ids = rows(c, "SELECT id,number FROM books WHERE collection_id=? ORDER BY CAST(number AS INTEGER)",
                       listOf(collectionId)) { Pair(it(0), it(1)) }
        return ids.map { (id, number) ->
            val tr = one(c, "SELECT title FROM book_translations WHERE book_id=? AND lang=? LIMIT 1",
                         listOf(id ?: "", lang)) { it(0) }
            Book(id = id ?: "", collectionId = collectionId, number = number ?: "", title = tr,
                 intro = null, preamble = null, notes = null)
        }
    }

    fun chapters(bookId: String, lang: String): List<Chapter> {
        val coll = collectionOf(bookId) ?: return emptyList()
        val c = connect(coll) ?: return emptyList()
        val pairs = rows(c, "SELECT id,number FROM chapters WHERE book_id=? ORDER BY CAST(number AS INTEGER)",
                         listOf(bookId)) { Pair(it(0), it(1)) }
        return pairs.map { (id, number) ->
            val tr = one(c, "SELECT title FROM chapter_translations WHERE chapter_id=? AND lang=? LIMIT 1",
                         listOf(id ?: "", lang)) { it(0) }
            Chapter(id = id ?: "", collectionId = coll, bookId = bookId, number = number ?: "", title = tr)
        }
    }

    // ── Hadith access ───────────────────────────────────────────────────────

    fun byId(hadithId: String, lang: String): Hadith? =
        collectionOf(hadithId)?.let { coll -> connect(coll)?.let { loadHadith(it, hadithId, lang) } }

    fun byIds(hadithIds: List<String>, lang: String): List<Hadith> =
        hadithIds.mapNotNull { byId(it, lang) }

    fun forBook(bookId: String, lang: String, limit: Int = 50, offset: Int = 0): List<Hadith> {
        val c = collectionOf(bookId)?.let { connect(it) } ?: return emptyList()
        val ids = rows(c, "SELECT id FROM hadiths WHERE book_id=? ORDER BY CAST(number AS INTEGER) LIMIT ? OFFSET ?",
                       listOf(bookId, limit.toString(), offset.toString())) { it(0) }
        return ids.mapNotNull { id -> id?.let { loadHadith(c, it, lang) } }
    }

    fun random(lang: String, gradeFilter: String? = null): Hadith? {
        for (coll in installedCollections.shuffled()) {
            val c = connect(coll) ?: continue
            val sql = if (gradeFilter != null)
                "SELECT id FROM hadiths WHERE id IN (SELECT hadith_id FROM hadith_grades WHERE label LIKE ?) ORDER BY RANDOM() LIMIT 1"
            else
                "SELECT id FROM hadiths ORDER BY RANDOM() LIMIT 1"
            val args = if (gradeFilter != null) listOf("%$gradeFilter%") else emptyList()
            val id = rows(c, sql, args) { it(0) }.firstOrNull() ?: continue
            loadHadith(c, id, lang)?.let { return it }
        }
        return null
    }

    fun grades(hadithId: String, lang: String? = null): List<Grade> {
        val c = collectionOf(hadithId)?.let { connect(it) } ?: return emptyList()
        val args: List<String> = if (lang != null) listOf(hadithId, lang) else listOf(hadithId)
        val sql = "SELECT grade_id,label,lang FROM hadith_grades WHERE hadith_id=?" +
            (if (lang != null) " AND lang=?" else "")
        return rows(c, sql, args) { Grade(it(0).orEmpty(), it(1).orEmpty(), it(2).orEmpty()) }
    }

    fun relatedIds(hadithId: String): List<String> {
        val c = collectionOf(hadithId)?.let { connect(it) } ?: return emptyList()
        return rows(c, "SELECT related_hadith_id FROM hadith_related WHERE hadith_id=?", listOf(hadithId)) {
            it(0).orEmpty()
        }
    }

    /**
     * Related hadiths resolved where target corpus is installed;
     * uninstalled targets appear as id-stubs with null [Hadith].
     */
    fun related(hadithId: String, lang: String): List<Pair<String, Hadith?>> =
        relatedIds(hadithId).map { rid -> rid to byId(rid, lang) }

    // ── Scholars join ───────────────────────────────────────────────────────

    fun narratorsOf(hadithId: String): List<Scholar> {
        val sc = scholarsConnection ?: scholarsDb?.takeIf { it.exists() }?.let {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection(jdbcUri(it.absolutePath)).also { scholarsConnection = it }
        } ?: return emptyList()

        val refs = collectionOf(hadithId)?.let { connect(it) }?.let {
            rows(it, "SELECT source,narrator_id,position FROM hadith_narrators WHERE hadith_id=? ORDER BY position",
                 listOf(hadithId)) { NarratorRef(it(0).orEmpty(), (it(1) ?: "0").toInt(), (it(2) ?: "0").toInt()) }
        } ?: emptyList()

        return refs.mapNotNull { ref ->
            sc.prepareStatement("SELECT * FROM scholars WHERE id=?").use { ps ->
                ps.setLong(1, ref.narratorId.toLong())
                val rs = ps.executeQuery()
                if (!rs.next()) return@mapNotNull null
                Scholar(
                    id = rs.getLong("id"),
                    shortName = rs.getString("short_name"),
                    fullName = rs.getString("full_name"),
                    arabicName = rs.getString("arabic"),
                    rank = rs.getObject("rank")?.toString()?.toIntOrNull(),
                    birthDate = rs.getString("birth_date"),
                    birthPlace = rs.getString("birth_place"),
                    deathDate = rs.getString("death_date"),
                    deathPlace = rs.getString("death_place"),
                    bio = rs.getString("bio"),
                    teachers = rs.getString("teachers"),
                    students = rs.getString("students"),
                    kunya = rs.getString("kunya"),
                )
            }
        }
    }

    override fun close() {
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        scholarsConnection?.let { runCatching { it.close() } }
        scholarsConnection = null
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun jdbcUri(path: String) = "jdbc:sqlite:file:${File(path).absolutePath}?mode=ro&immutable=1"

    private fun connect(collectionId: String): Connection? {
        connections[collectionId]?.let { return it }
        val file = File(corporaRoot, "$collectionId.db")
        if (!file.exists()) return null
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection(jdbcUri(file.absolutePath)).also { connections[collectionId] = it }
    }

    private fun loadHadith(c: Connection, hadithId: String, lang: String): Hadith? {
        val baseRow = one(
            c, "SELECT id,urn,collection_id,book_id,chapter_id,number FROM hadiths WHERE id=?", listOf(hadithId),
        ) { r -> listOf(r(0), r(1), r(2), r(3), r(4), r(5)) } ?: return null

        var blocks: List<ContentBlock> = emptyList()
        val contentLangs = mutableListOf<String>()
        c.prepareStatement("SELECT lang,blocks_json FROM hadith_contents WHERE hadith_id=?").use { ps ->
            ps.setString(1, hadithId)
            val rs = ps.executeQuery()
            while (rs.next()) {
                contentLangs += rs.getString(1)
                if (rs.getString(1) == lang) blocks = ContentBlockParser.parseBlocks(rs.getString(2))
            }
        }

        val references = rows(c, "SELECT type,value FROM hadith_references WHERE hadith_id=?", listOf(hadithId)) {
            Reference(it(0).orEmpty(), it(1).orEmpty())
        }
        val related = rows(c, "SELECT related_hadith_id FROM hadith_related WHERE hadith_id=?", listOf(hadithId)) { it(0) }
        val grades = rows(c, "SELECT grade_id,label,lang FROM hadith_grades WHERE hadith_id=?", listOf(hadithId)) {
            Grade(it(0).orEmpty(), it(1).orEmpty(), it(2).orEmpty())
        }
        val narrators = rows(c, "SELECT source,narrator_id,position FROM hadith_narrators WHERE hadith_id=? ORDER BY position",
                             listOf(hadithId)) { NarratorRef(it(0).orEmpty(), (it(1) ?: "0").toInt(), (it(2) ?: "0").toInt()) }

        return Hadith(
            id = baseRow[0] as String,
            urn = (baseRow[1] as String?)?.toLongOrNull(),
            collectionId = baseRow[2] as String,
            bookId = baseRow[3] as String,
            chapterId = baseRow[4] as String?,
            number = baseRow[5] as String?,
            blocks = blocks,
            references = references,
            relatedIds = related.map { it.orEmpty() },
            grades = grades,
            narratorRefs = narrators,
            contentLangs = contentLangs.distinct(),
        )
    }

    private fun <T> rows(c: Connection, sql: String, args: List<Any?>, mapper: ((Int) -> String?) -> T): List<T> =
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            val rs = ps.executeQuery()
            buildList { while (rs.next()) add(mapper { idx -> rs.getString(idx + 1) }) }
        }

    private fun <T> one(c: Connection, sql: String, args: List<Any?>, mapper: ((Int) -> String?) -> T): T? =
        rows(c, sql, args, mapper).firstOrNull()
}
