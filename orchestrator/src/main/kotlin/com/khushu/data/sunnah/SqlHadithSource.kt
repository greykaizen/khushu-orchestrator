package com.khushu.data.sunnah

import com.khushu.data.markup.ContentBlockParser
import com.khushu.data.model.ContentBlock
import com.khushu.data.model.Book
import com.khushu.data.model.Chapter
import com.khushu.data.model.Grade
import com.khushu.data.model.Hadith
import com.khushu.data.model.HadithCollection
import com.khushu.data.model.NarratorRef
import com.khushu.data.model.Reference
import com.khushu.data.model.Scholar
import com.khushu.data.store.Row
import com.khushu.data.store.SqlStore

/**
 * SQL-backed hadith reads over a `hadith-<collection>` pack. Tables are namespaced by
 * collection (e.g. `bukhari_hadiths`), and one store holds one collection. This is the
 * Turso/packs replacement for `LocalHadithRepository` (which read the old flattened
 * `hadiths/<coll>.db` via sqlite-jdbc — unusable on Android); it emits the same models.
 */
class SqlHadithSource(private val collection: String, private val store: SqlStore) {

    private fun t(name: String) = "${collection}_$name"

    suspend fun collectionInfo(lang: String): HadithCollection? {
        val row = store.query(
            "SELECT id,type,sort_order,has_volumes,has_books,has_chapters,numbering_source FROM ${t("collections")} LIMIT 1",
        ).firstOrNull() ?: return null
        val tr = store.query(
            "SELECT title,intro,description FROM ${t("collection_translations")} " +
                "WHERE collection_id=? AND lang IN (?, 'en') ORDER BY CASE WHEN lang=? THEN 0 ELSE 1 END LIMIT 1",
            listOf(collection, lang, lang),
        ).firstOrNull()
        val meta = runCatching {
            store.query("SELECT key,value FROM ${t("bundle_meta")}").associate { it.stringAt(0)!! to (it.stringAt(1) ?: "") }
        }.getOrDefault(emptyMap())
        return HadithCollection(
            id = row.stringAt(0) ?: collection, type = row.stringAt(1) ?: "", sortOrder = row.intAt(2) ?: 0,
            hasVolumes = row.intAt(3) == 1 || row.stringAt(3) == "1", hasBooks = row.intAt(4) == 1 || row.stringAt(4) == "1",
            hasChapters = row.intAt(5) == 1 || row.stringAt(5) == "1", numberingSource = row.stringAt(6)?.ifBlank { null },
            title = tr?.stringAt(0), intro = tr?.stringAt(1), description = tr?.stringAt(2),
            schemaVersion = meta["schema_version"]?.toIntOrNull(), contentVersion = meta["content_version"]?.ifBlank { null },
        )
    }

    suspend fun books(lang: String): List<Book> = store.query(
        "SELECT id,number FROM ${t("books")} ORDER BY CAST(number AS INTEGER)",
    ).mapNotNull { b ->
        val id = b.stringAt(0) ?: return@mapNotNull null
        val title = store.query("SELECT title FROM ${t("book_translations")} WHERE book_id=? AND lang=? LIMIT 1", listOf(id, lang))
            .firstOrNull()?.stringAt(0)
        Book(id = id, collectionId = collection, number = b.stringAt(1) ?: "", title = title, intro = null, preamble = null, notes = null)
    }

    suspend fun chapters(bookId: String, lang: String): List<Chapter> = store.query(
        "SELECT id,number FROM ${t("chapters")} WHERE book_id=? ORDER BY CAST(number AS INTEGER)", listOf(bookId),
    ).map { c ->
        val id = c.stringAt(0) ?: ""
        val title = store.query("SELECT title FROM ${t("chapter_translations")} WHERE chapter_id=? AND lang=? LIMIT 1", listOf(id, lang))
            .firstOrNull()?.stringAt(0)
        Chapter(id = id, collectionId = collection, bookId = bookId, number = c.stringAt(1) ?: "", title = title)
    }

    suspend fun hadithIdsForBook(bookId: String, limit: Int = 50, offset: Int = 0): List<String> = store.query(
        "SELECT id FROM ${t("hadiths")} WHERE book_id=? ORDER BY CAST(number AS INTEGER) LIMIT ? OFFSET ?",
        listOf(bookId, limit, offset),
    ).mapNotNull { it.stringAt(0) }

    suspend fun byId(hadithId: String, lang: String): Hadith? {
        val base = store.query(
            "SELECT id,urn,collection_id,book_id,chapter_id,number FROM ${t("hadiths")} WHERE id=?", listOf(hadithId),
        ).firstOrNull() ?: return null
        var blocks = emptyList<ContentBlock>(); val langs = mutableListOf<String>()
        store.query("SELECT lang,blocks_json FROM ${t("hadith_contents")} WHERE hadith_id=?", listOf(hadithId)).forEach { r ->
            val l = r.stringAt(0); langs += (l ?: "")
            if (l == lang) blocks = r.stringAt(1)?.let { ContentBlockParser.parseBlocks(it) } ?: blocks
        }
        val references = store.query("SELECT type,value FROM ${t("hadith_references")} WHERE hadith_id=?", listOf(hadithId))
            .map { Reference(it.stringAt(0) ?: "", it.stringAt(1) ?: "") }
        val related = store.query("SELECT related_hadith_id FROM ${t("hadith_related")} WHERE hadith_id=?", listOf(hadithId))
            .mapNotNull { it.stringAt(0) }
        val grades = store.query("SELECT grade_id,label,lang FROM ${t("hadith_grades")} WHERE hadith_id=?", listOf(hadithId))
            .map { Grade(it.stringAt(0) ?: "", it.stringAt(1) ?: "", it.stringAt(2) ?: "") }
        val narrators = store.query("SELECT source,narrator_id,position FROM ${t("hadith_narrators")} WHERE hadith_id=? ORDER BY position", listOf(hadithId))
            .map { NarratorRef(it.stringAt(0) ?: "", (it.stringAt(1) ?: "0").toInt(), (it.intAt(2) ?: 0)) }
        return Hadith(
            id = base.stringAt(0) ?: hadithId, urn = base.stringAt(1)?.toLongOrNull(),
            collectionId = base.stringAt(2) ?: collection, bookId = base.stringAt(3) ?: "", chapterId = base.stringAt(4),
            number = base.stringAt(5), blocks = blocks, references = references, relatedIds = related,
            grades = grades, narratorRefs = narrators, contentLangs = langs.filter { it.isNotEmpty() }.distinct(),
        )
    }

    /** Full-text search over this collection (external-content FTS5 -> rowid -> hadith id). */
    suspend fun search(query: String, lang: String, limit: Int = 50): List<Hadith> = store.query(
        "SELECT ht.hadith_id FROM ${t("hadith_fts")} JOIN ${t("hadith_text")} ht ON ht.rowid = ${t("hadith_fts")}.rowid " +
            "WHERE ${t("hadith_fts")} MATCH ? LIMIT ?",
        listOf(query, limit),
    ).mapNotNull { r -> r.stringAt(0)?.let { byId(it, lang) } }
}

/** SQL-backed narrator biographies over the `hadith-scholars` pack. */
class SqlScholarSource(private val store: SqlStore) {
    suspend fun byId(id: Long): Scholar? = store.query("SELECT * FROM scholars WHERE id=?", listOf(id)).firstOrNull()?.let(::map)

    suspend fun byIds(ids: List<Long>): List<Scholar> =
        if (ids.isEmpty()) emptyList()
        else store.query("SELECT * FROM scholars WHERE id IN (${ids.joinToString(",") { "?" }})", ids).map(::map)

    private fun map(r: Row) = Scholar(
        id = r.long("id") ?: 0L, shortName = r.string("short_name"), fullName = r.string("full_name"),
        arabicName = r.string("arabic"), rank = r.string("rank")?.toIntOrNull(), birthDate = r.string("birth_date"),
        birthPlace = r.string("birth_place"), deathDate = r.string("death_date"), deathPlace = r.string("death_place"),
        bio = r.string("bio"), teachers = r.string("teachers"), students = r.string("students"), kunya = r.string("kunya"),
    )
}
