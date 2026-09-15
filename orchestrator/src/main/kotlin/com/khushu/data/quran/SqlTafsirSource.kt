package com.khushu.data.quran

import com.khushu.data.model.TafsirEntry
import com.khushu.data.model.TafsirSegment
import com.khushu.data.store.SqlStore

/**
 * SQL-backed tafsir over a `tafsir-<slug>` pack (or the `khushu-tafsir` DB filtered by
 * book). The pack carries one `tafsir_books` row + its `tafsir_entries` (range-keyed,
 * verbatim HTML commentary). Returns the same [TafsirSegment]/[TafsirEntry] models as the
 * JSON `TafsirSource`. No FTS here (tafsir is fetched by book+range, matching the corpus
 * design — see khushu-data-api build_tafsir.py).
 */
class SqlTafsirSource(
    private val slug: String,
    private val store: SqlStore,
    /** Optional unfiltered store (e.g. the whole khushu-tafsir DB) for listing all books. */
    private val catalogStore: SqlStore? = null,
) {
    suspend fun book(): TafsirEntry? = store.query(
        "SELECT slug, name, author, lang_code, lang_name FROM tafsir_books WHERE slug=?", listOf(slug),
    ).firstOrNull()?.let { r ->
        TafsirEntry(r.stringAt(0) ?: slug, r.stringAt(1) ?: "", r.stringAt(2), r.stringAt(3) ?: "", r.stringAt(4))
    }

    suspend fun books(): List<TafsirEntry> = (catalogStore ?: store).query(
        "SELECT slug, name, author, lang_code, lang_name FROM tafsir_books ORDER BY lang_code, name",
    ).map { r -> TafsirEntry(r.stringAt(0) ?: "", r.stringAt(1) ?: "", r.stringAt(2), r.stringAt(3) ?: "", r.stringAt(4)) }

    suspend fun forSurah(surahNo: Int): List<TafsirSegment> = store.query(
        "SELECT surah_no, from_verse, to_verse, text FROM tafsir_entries WHERE book=? AND surah_no=? ORDER BY from_verse",
        listOf(slug, surahNo),
    ).map { seg(it) }

    suspend fun monolithic(): List<TafsirSegment> = store.query(
        "SELECT surah_no, from_verse, to_verse, text FROM tafsir_entries WHERE book=? ORDER BY surah_no, from_verse",
        listOf(slug),
    ).map { seg(it) }

    private fun seg(r: com.khushu.data.store.Row) = TafsirSegment(
        chapter = r.intAt(0) ?: 0, fromVerse = r.intAt(1) ?: 0, toVerse = r.intAt(2) ?: 0, textHtml = r.stringAt(3) ?: "",
    )
}
