package com.khushu.data.quran

import com.khushu.data.model.ChapterInfo
import com.khushu.data.model.ChapterInfoVariant
import com.khushu.data.model.LineType
import com.khushu.data.model.PageLine
import com.khushu.data.model.QuranGlyphTable
import com.khushu.data.model.QuranSearchHit
import com.khushu.data.model.RegistryWord
import com.khushu.data.model.SpecialGlyph
import com.khushu.data.store.SqlStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SQL-backed glyph table + chapter info + mushaf layout + Arabic search — the remaining
 * Quran-domain reads that were JSON-only. All run over the `quran-core` pack (or the
 * `khushu-quran` remote) through [SqlStore], emitting the SAME models the JSON sources did.
 */

/** PUA glyph table. Reads the verbatim `quran_config('quran_glyphs')` blob and parses it
 *  with the exact donor mapping, so surahIcon/juzIcon/ayahReference are byte-identical to
 *  the JSON path. */
class SqlGlyphSource(private val store: SqlStore) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cache: QuranGlyphTable? = null

    suspend fun table(): QuranGlyphTable = cache ?: run {
        val blob = store.query("SELECT json FROM quran_config WHERE key='quran_glyphs'")
            .firstOrNull()?.stringAt(0) ?: error("quran_glyphs config missing")
        val root = json.parseToJsonElement(blob).jsonObject
        fun sp(name: String, cpName: String): SpecialGlyph {
            val o = root["special"]!!.jsonObject
            return SpecialGlyph(o[name]!!.jsonPrimitive.content, o[cpName]!!.jsonPrimitive.content)
        }
        fun rd(name: String): SpecialGlyph {
            val o = root["reference_decorations"]!!.jsonObject[name]!!.jsonObject
            return SpecialGlyph(o["glyph"]!!.jsonPrimitive.content, o["cp"]!!.jsonPrimitive.content)
        }
        val ch = root["chapter_icon"]!!.jsonObject
        val out = QuranGlyphTable(
            bismillah = sp("bismillah", "bismillah_cp"),
            titleFrame = sp("title_frame", "title_frame_cp"),
            meccan = sp("meccan", "meccan_cp"),
            medinan = sp("medinan", "medinan_cp"),
            sejda = sp("sejda", "sejda_cp"),
            chapterPrefix = SpecialGlyph(ch["prefix"]!!.jsonPrimitive.content, ch["prefix_cp"]!!.jsonPrimitive.content),
            chapterBySurah = ch["by_surah"]!!.jsonObject
                .mapValues { (_, v) -> glyphOf(v.jsonObject) }.mapKeys { it.key.toInt() },
            juzByNumber = root["juz_icon"]!!.jsonObject["by_juz"]!!.jsonObject
                .mapValues { (_, v) -> glyphOf(v.jsonObject) }.mapKeys { it.key.toInt() },
            ornateParenLeft = rd("ornate_paren_left"),
            ornateParenRight = rd("ornate_paren_right"),
            salawat = rd("salawat"),
            rtlMark = rd("rtl_mark"),
            ltrMark = rd("ltr_mark"),
        )
        cache = out
        out
    }

    private fun glyphOf(o: JsonObject) = SpecialGlyph(o["glyph"]!!.jsonPrimitive.content, o["cp"]!!.jsonPrimitive.content)

    suspend fun surahIcon(surahNo: Int): String? =
        table().chapterBySurah[surahNo]?.let { it.char + table().chapterPrefix.char }

    suspend fun juzIcon(juzNo: Int): String? = table().juzByNumber[juzNo]?.char

    suspend fun ayahReference(surahNo: Int, ayahNo: Int): String {
        val t = table()
        return t.rtlMark.char + t.ornateParenLeft.char + "$surahNo:$ayahNo" + t.ornateParenRight.char + t.rtlMark.char + " "
    }
}

/** Per-surah chapter info (verbatim HTML) from `quran_chapter_info`. */
class SqlChapterInfoSource(private val store: SqlStore) {
    suspend fun info(surahNo: Int, lang: String = "en", variant: ChapterInfoVariant = ChapterInfoVariant.DEFAULT): ChapterInfo? {
        val rows = store.query(
            "SELECT chapter_id, lang_code, source, short_text, text FROM quran_chapter_info " +
                "WHERE chapter_id=? AND lang_code=?", listOf(surahNo, lang),
        )
        val row = when (variant) {
            ChapterInfoVariant.DEFAULT -> rows.firstOrNull()
            else -> rows.firstOrNull { it.stringAt(4)?.isNotEmpty() == true } // best-effort variant
        } ?: return null
        return ChapterInfo(
            surahNo = row.intAt(0) ?: surahNo, langCode = row.stringAt(1) ?: lang, variant = variant,
            source = row.stringAt(2)?.ifBlank { null }, shortText = row.stringAt(3)?.ifBlank { null },
            textHtml = row.stringAt(4) ?: "",
        )
    }

    suspend fun availableVariants(surahNo: Int, lang: String = "en"): List<ChapterInfoVariant> =
        if (info(surahNo, lang) != null) listOf(ChapterInfoVariant.DEFAULT) else emptyList()
}

/**
 * Mushaf layout over both DB schemes: `quran_mushaf_pages` (word-id addressed, keyed by
 * script code — the page_info path) preferred, else `quran_mushaf_map` (ayah+word-index,
 * keyed by numeric mushaf_id resolved via `quran_mushafs`) — mirroring MushafLayoutSource.
 * [mushafScriptOf] is the same pure mapping (no DB).
 */
class SqlMushafLayoutSource(private val store: SqlStore) {
    fun mushafScriptOf(mushafCode: String): String = when (mushafCode) {
        "qpc", "uthmani" -> "uthmani"
        "kfqpc_v1" -> "kfqpc_v1"
        else -> if (mushafCode.startsWith("indopak")) "dk_indopak" else mushafCode
    }

    suspend fun pageLines(mushafCode: String, pageNumber: Int): List<PageLine> =
        allLines(mushafCode).filter { it.pageNumber == pageNumber }

    suspend fun linesOfAyah(mushafCode: String, surahNo: Int, ayahNo: Int): List<PageLine> {
        val ayahId = surahNo * 1000 + ayahNo
        return allLines(mushafCode).filter { l ->
            l.startAyahId != null && l.endAyahId != null && ayahId in l.startAyahId..l.endAyahId
        }
    }

    suspend fun pageOfAyah(mushafCode: String, surahNo: Int, ayahNo: Int): Int? =
        linesOfAyah(mushafCode, surahNo, ayahNo).minOfOrNull { it.pageNumber }

    suspend fun words(scriptCode: String): List<RegistryWord> = store.query(
        "SELECT w.ayah_id, w.word_index, w.text FROM quran_ayah_words w JOIN quran_scripts s ON s.script_id=w.script_id " +
            "WHERE s.code=? ORDER BY w.ayah_id, w.word_index", listOf(scriptCode),
    ).map { RegistryWord(it.intAt(0) ?: 0, it.intAt(1) ?: 0, it.stringAt(2) ?: "") }

    private suspend fun allLines(mushafCode: String): List<PageLine> {
        // page_info scheme (word-id) keyed by script code
        val pages = store.query(
            "SELECT page_number, line_number, line_type, is_centered, first_word_id, last_word_id, surah_number " +
                "FROM quran_mushaf_pages WHERE script=? ORDER BY page_number, line_number", listOf(mushafCode),
        ).map { PageLine(mushafCode, it.intAt(0) ?: 0, it.intAt(1) ?: 0, LineType.of(it.stringAt(2)),
            it.stringAt(3) == "1" || it.stringAt(3).equals("true", true),
            firstWordId = it.intAt(4), lastWordId = it.intAt(5), surahNo = it.intAt(6)) }
        if (pages.isNotEmpty()) return pages
        // mushaf_map scheme (ayah+word-index) keyed by numeric mushaf_id
        val mid = store.query("SELECT id FROM quran_mushafs WHERE mushaf_code=?", listOf(mushafCode)).firstOrNull()?.stringAt(0)
            ?: return emptyList()
        return store.query(
            "SELECT page_number, line_number, line_type, is_centered, start_ayah_id, start_word_index, end_ayah_id, end_word_index, surah_no " +
                "FROM quran_mushaf_map WHERE mushaf_id=? ORDER BY page_number, line_number", listOf(mid),
        ).map { PageLine(mushafCode, it.intAt(0) ?: 0, it.intAt(1) ?: 0, LineType.of(it.stringAt(2)),
            it.stringAt(3) == "1" || it.stringAt(3).equals("true", true),
            startAyahId = it.intAt(4), startWordIndex = it.intAt(5), endAyahId = it.intAt(6),
            endWordIndex = it.intAt(7), surahNo = it.intAt(8)) }
    }
}

/** Arabic full-text search via the external-content FTS5 index over `quran_search_arabic`. */
class SqlSearchSource(private val store: SqlStore) {
    suspend fun search(query: String, limit: Int = 20, offset: Int = 0): List<QuranSearchHit> = store.query(
        "SELECT a.ayah_id, a.surah_no, a.ayah_no, s.text FROM quran_arabic_fts f " +
            "JOIN quran_search_arabic s ON s.rowid=f.rowid JOIN quran_ayahs a ON a.ayah_id=s.ayah_id " +
            "WHERE quran_arabic_fts MATCH ? ORDER BY rank LIMIT ? OFFSET ?", listOf(query, limit, offset),
    ).map { QuranSearchHit(it.intAt(0) ?: 0, it.intAt(1) ?: 0, it.intAt(2) ?: 0, it.stringAt(3) ?: "") }
}
