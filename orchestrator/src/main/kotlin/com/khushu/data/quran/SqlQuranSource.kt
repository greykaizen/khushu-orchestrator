package com.khushu.data.quran

import com.khushu.data.model.AyahMeta
import com.khushu.data.model.AyahWord
import com.khushu.data.model.Surah
import com.khushu.data.model.SurahName
import com.khushu.data.model.TranslatedAyah
import com.khushu.data.model.TranslationFootnote
import com.khushu.data.model.TranslationPackInfo
import com.khushu.data.model.WordKind
import com.khushu.data.store.SqlStore

/**
 * SQL-backed Quran reads over the `quran-core` pack (or the `khushu-quran` Turso
 * database) through the [SqlStore] seam — the local-first/remote-transparent
 * replacement for the JSON `QuranSources`. Identical SQL runs against an installed
 * pack file or the remote DB; column order is positional so it works with engines
 * that omit labels (Android libSQL). Uthmani script id = 1.
 *
 * Returns the SAME domain models the JSON path produced, so the app/ViewModels are
 * unaffected when the aggregate is pointed here.
 */
class SqlQuranSource(private val quran: SqlStore) {

    suspend fun surahs(): List<Surah> {
        val namesBySurah = HashMap<Int, MutableMap<String, SurahName>>()
        quran.query(
            "SELECT surah_no, lang_code, name, meaning FROM quran_surah_localizations ORDER BY surah_no",
        ).forEach { r ->
            val sn = r.intAt(0) ?: return@forEach
            namesBySurah.getOrPut(sn) { mutableMapOf() }[r.stringAt(1) ?: return@forEach] =
                SurahName(name = r.stringAt(2)?.ifBlank { null }, meaning = r.stringAt(3)?.ifBlank { null })
        }
        return quran.query(
            "SELECT surah_no, ayah_count, revelation_order, rukus_count, revelation_type " +
                "FROM quran_surahs ORDER BY surah_no",
        ).map { r ->
            val n = r.intAt(0) ?: return@map null
            Surah(
                number = n,
                ayahCount = r.intAt(1) ?: 0,
                revelationOrder = r.intAt(2),
                rukusCount = r.intAt(3),
                revelationType = r.stringAt(4),
                names = namesBySurah[n].orEmpty(),
            )
        }.filterNotNull()
    }

    suspend fun surah(surahNo: Int): Surah? =
        surahs().firstOrNull { it.number == surahNo }

    /** Ayah structural facts for a surah (order by ayah). */
    suspend fun ayahs(surahNo: Int): List<AyahMeta> =
        quran.query(
            "SELECT ayah_id, surah_no, ayah_no, juz_no, hizb_no, rub_no, manzil_no, ruku_no, sajdah_type " +
                "FROM quran_ayahs WHERE surah_no=? ORDER BY ayah_no", listOf(surahNo),
        ).map { r ->
            AyahMeta(
                ayahId = r.intAt(0) ?: 0, surahNo = r.intAt(1) ?: surahNo, ayahNo = r.intAt(2) ?: 0,
                juzNo = r.intAt(3) ?: 0, hizbNo = r.intAt(4) ?: 0, rubNo = r.intAt(5) ?: 0,
                manzilNo = r.intAt(6) ?: 0, rukuNo = r.intAt(7) ?: 0, sajdahType = r.intAt(8) ?: 0,
            )
        }

    /** Uthmani word tokens of one ayah, in order, with "surah:ayah:pos" location. */
    suspend fun uthmaniWords(surahNo: Int, ayahNo: Int): List<AyahWord> {
        val ayahId = surahNo * 1000 + ayahNo
        val words = quran.query(
            "SELECT word_index, text FROM quran_ayah_words WHERE ayah_id=? AND script_id=? ORDER BY word_index",
            listOf(ayahId, UTHMANI_SCRIPT_ID),
        )
        return words.mapIndexed { i, r ->
            val pos = i + 1
            AyahWord(
                surahNo = surahNo, ayahNo = ayahNo, position = pos, text = r.stringAt(1) ?: "",
                location = "$surahNo:$ayahNo:$pos",
                kind = if (pos == words.size && r.stringAt(1)?.matches(END_MARKER) == true) WordKind.AYAH_END_MARKER else WordKind.TEXT,
            )
        }
    }

    /** Assembled Uthmani text of an ayah (words joined by spaces, end marker stripped). */
    suspend fun ayahText(surahNo: Int, ayahNo: Int): String =
        uthmaniWords(surahNo, ayahNo).filter { it.kind == WordKind.TEXT }.joinToString(" ") { it.text }

    /**
     * The uthmani words of SEVERAL ayahs in one query (ayah id → words, in
     * reading order). One round trip for a list of matched ayahs — the shape a
     * "similar verses" list needs.
     */
    suspend fun wordsForAyahs(ayahIds: List<Int>): Map<Int, List<String>> {
        if (ayahIds.isEmpty()) return emptyMap()
        val placeholders = ayahIds.joinToString(",") { "?" }
        val rows = quran.query(
            "SELECT ayah_id, text FROM quran_ayah_words WHERE ayah_id IN ($placeholders) AND script_id=? " +
                "ORDER BY ayah_id, word_index",
            ayahIds + UTHMANI_SCRIPT_ID,
        )
        val out = LinkedHashMap<Int, MutableList<String>>()
        rows.forEach { r ->
            val id = r.intAt(0) ?: return@forEach
            out.getOrPut(id) { mutableListOf() }.add(r.stringAt(1) ?: "")
        }
        return out
    }

    private companion object { const val UTHMANI_SCRIPT_ID = 1; val END_MARKER = Regex("^[۩۞۝\\u06dd].*|[\\u06dd]$") }
}

/**
 * SQL-backed translation reads over a single `translation-<pack>` pack (or the
 * `khushu-quran` remote DB filtered by pack_id). The pack fragment is self-
 * contained: it carries its `translation_packs` row, its `translation_ayahs`
 * and the `translation_fts` (contentless) index — so offline search works too.
 */
class SqlTranslationSource(
    private val packId: String,
    private val store: SqlStore,
) {
    suspend fun info(): TranslationPackInfo? =
        store.query(
            "SELECT pack_id, lang_code, book, author, display_name, lang_name, version, path " +
                "FROM translation_packs WHERE pack_id=?", listOf(packId),
        ).firstOrNull()?.let { r ->
            TranslationPackInfo(
                id = r.stringAt(0) ?: packId, langCode = r.stringAt(1) ?: "", book = r.stringAt(2) ?: "",
                author = r.stringAt(3), displayName = r.stringAt(4) ?: "", langName = r.stringAt(5),
                version = r.intAt(6) ?: 1, downloadPath = r.stringAt(7),
            )
        }

    suspend fun ayahs(surahNo: Int): List<TranslatedAyah> =
        store.query(
            "SELECT surah_no, ayah_no, translation FROM translation_ayahs WHERE pack_id=? AND surah_no=? ORDER BY ayah_no",
            listOf(packId, surahNo),
        ).map { r -> TranslatedAyah(packId, r.intAt(0) ?: surahNo, r.intAt(1) ?: 0, r.stringAt(2) ?: "") }

    /** Every ayah of this pack (the whole translation, in reading order). */
    suspend fun allAyahs(): List<TranslatedAyah> =
        store.query(
            "SELECT surah_no, ayah_no, translation FROM translation_ayahs WHERE pack_id=? ORDER BY surah_no, ayah_no",
            listOf(packId),
        ).map { r -> TranslatedAyah(packId, r.intAt(0) ?: 0, r.intAt(1) ?: 0, r.stringAt(2) ?: "") }

    /**
     * The registry of translation packs this store knows (`translation_packs`):
     * every pack id with its language, author and display name. The quran-core
     * pack carries the full registry; a per-pack store carries only its own row.
     */
    suspend fun packs(langCode: String? = null): List<TranslationPackInfo> {
        val rows = if (langCode == null)
            store.query(
                "SELECT pack_id, lang_code, book, author, display_name, lang_name, version, path " +
                    "FROM translation_packs ORDER BY lang_code, pack_id",
            )
        else
            store.query(
                "SELECT pack_id, lang_code, book, author, display_name, lang_name, version, path " +
                    "FROM translation_packs WHERE lang_code=? ORDER BY pack_id",
                listOf(langCode),
            )
        return rows.map { r ->
            TranslationPackInfo(
                id = r.stringAt(0) ?: packId, langCode = r.stringAt(1) ?: "", book = r.stringAt(2) ?: "",
                author = r.stringAt(3), displayName = r.stringAt(4) ?: "", langName = r.stringAt(5),
                version = r.intAt(6) ?: 1, downloadPath = r.stringAt(7),
            )
        }
    }

    suspend fun ayah(surahNo: Int, ayahNo: Int): TranslatedAyah? =
        store.query(
            "SELECT surah_no, ayah_no, translation FROM translation_ayahs WHERE pack_id=? AND surah_no=? AND ayah_no=?",
            listOf(packId, surahNo, ayahNo),
        ).firstOrNull()?.let { r -> TranslatedAyah(packId, r.intAt(0) ?: surahNo, r.intAt(1) ?: ayahNo, r.stringAt(2) ?: "") }

    /** contentless FTS5 search → rowid→base join → matching verses (offline-capable). */
    suspend fun search(query: String, limit: Int = 50): List<TranslatedAyah> =
        store.query(
            "SELECT ta.surah_no, ta.ayah_no, ta.translation FROM translation_fts " +
                "JOIN translation_ayahs ta ON ta.rowid = translation_fts.rowid " +
                "WHERE translation_fts MATCH ? ORDER BY ta.surah_no, ta.ayah_no LIMIT ?",
            listOf(query, limit),
        ).map { r -> TranslatedAyah(packId, r.intAt(0) ?: 0, r.intAt(1) ?: 0, r.stringAt(2) ?: "") }

    /**
     * Footnote rows of one ayah (`translation_footnotes`: pack_id, surah_no,
     * ayah_no, fn_index, text). The donor keeps footnote markers inline in the
     * translation text; the rows carry the note bodies.
     */
    suspend fun footnotes(surahNo: Int, ayahNo: Int): List<TranslationFootnote> =
        store.query(
            "SELECT fn_index, text FROM translation_footnotes " +
                "WHERE pack_id=? AND surah_no=? AND ayah_no=? ORDER BY fn_index",
            listOf(packId, surahNo, ayahNo),
        ).map { r -> TranslationFootnote(r.intAt(0) ?: 0, r.stringAt(1) ?: "") }
}
