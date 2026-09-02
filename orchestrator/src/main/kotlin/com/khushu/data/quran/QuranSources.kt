package com.khushu.data.quran

import com.khushu.data.transport.ContentFetcher
import com.khushu.data.model.AyahMeta
import com.khushu.data.model.AyahText
import com.khushu.data.model.AyahWord
import com.khushu.data.model.LineType
import com.khushu.data.model.MushafInfo
import com.khushu.data.model.NavigationSegment
import com.khushu.data.model.NavigationType
import com.khushu.data.model.NavigationUnit
import com.khushu.data.model.PageLine
import com.khushu.data.model.RegistryWord
import com.khushu.data.model.ScriptInfo
import com.khushu.data.model.SpecialGlyph
import com.khushu.data.model.Surah
import com.khushu.data.model.SurahName
import com.khushu.data.model.QuranGlyphTable
import com.khushu.data.model.TranslatedAyah
import com.khushu.data.model.TranslationPackInfo
import com.khushu.data.model.WordKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.zip.GZIPInputStream

/** Lenient JSON accessors (donor JSON emits literal `null` and omits optional keys). */
private fun JsonElement?.strSafe(): String? =
    (this as? JsonPrimitive)?.content?.takeIf { it != "null" && it.isNotBlank() }

private fun JsonElement?.intSafe(): Int? =
    runCatching { (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int }.getOrNull()

/**
 * Reads per-surah word-level script packs:
 * `inventory/quran_scripts/{script}/{NNN}.json` — each file is a JSON array of
 * `{chapter_number, verse_number, words:[{position, text, location}]}`.
 * The monolithic `script_kfqpc_v1.json` variant is handled transparently.
 */
class QuranScriptSource(
    private val fetcher: ContentFetcher,
    private val scriptDir: String = "inventory/quran_scripts",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, List<AyahWord>>()

    companion object {
        /** Arabic-Indic digits used for ayah-end markers (٠..٩). */
        private val MARKER_RE = Regex("^[\\u06D6-\\u06ED\\u0660-\\u0669]+$")

        fun classifyWord(text: String): WordKind =
            if (MARKER_RE.matches(text.trim())) WordKind.AYAH_END_MARKER else WordKind.TEXT
    }

    /** All ayah words of one surah (word-level, location-tagged). */
    suspend fun words(
        scriptKey: String,
        surahNumber: Int,
        includeAyahMarker: Boolean = true,
    ): List<AyahWord> {
        val all = wordsCached(scriptKey, surahNumber)
        return if (includeAyahMarker) all else all.filter { it.kind == WordKind.TEXT }
    }

    /** Plain-text ayahs assembled from word packs (space-joined, markers excluded by default). */
    suspend fun ayahTexts(
        scriptKey: String,
        surahNumber: Int,
        includeAyahMarker: Boolean = false,
    ): List<AyahText> =
        words(scriptKey, surahNumber, includeAyahMarker)
            .groupBy { it.ayahNo }
            .toSortedMap()
            .map { (ayahNo, ws) ->
                AyahText(surahNumber, ayahNo, ws.sortedBy { it.position }.joinToString(" ") { it.text })
            }

    fun clearCache() = cache.clear()

    private suspend fun wordsCached(scriptKey: String, surahNumber: Int): List<AyahWord> {
        val key = "$scriptKey/$surahNumber"
        cache[key]?.let { return it }
        val words = mutableListOf<AyahWord>()

        val perSurah = "$scriptDir/$scriptKey/${surahNumber.toString().padStart(3, '0')}.json"
        val monolithic = "$scriptDir/script_$scriptKey.json"
        val perSurahBytes = runCatching { fetcher.fetch(perSurah) }.getOrNull()
        val bytes = perSurahBytes ?: runCatching { fetcher.fetch(monolithic) }.getOrElse {
            error("no script pack for $scriptKey/$surahNumber (tried $perSurah and $monolithic)")
        }
        val root = json.parseToJsonElement(bytes.decodeToString())
        when (root) {
            // per-surah pack: top-level JSON array of verses with word arrays
            is kotlinx.serialization.json.JsonArray -> for (verse in root) {
                val obj = verse.jsonObject
                val surahNo = obj["chapter_number"]!!.jsonPrimitive.int
                if (surahNo != surahNumber) continue
                val ayahNo = obj["verse_number"]!!.jsonPrimitive.int
                for (w in obj["words"]!!.jsonArray) {
                    val wo = w.jsonObject
                    val text = wo["text"]!!.jsonPrimitive.content
                    val position = wo["position"]!!.jsonPrimitive.int
                    words += AyahWord(
                        surahNo = surahNo,
                        ayahNo = ayahNo,
                        position = position,
                        text = text,
                        location = wo["location"]?.jsonPrimitive?.content ?: "$surahNo:$ayahNo:$position",
                        kind = classifyWord(text),
                    )
                }
            }
            // monolithic pack: {"suras":[{index, ayas:[{id,index,text,page}]}]} —
            // ayah text only; derive words by whitespace split
            is JsonObject -> for (sura in root["suras"]!!.jsonArray) {
                val so = sura.jsonObject
                val surahNo = so["index"]!!.jsonPrimitive.int
                if (surahNo != surahNumber) continue
                for (aya in so["ayas"]!!.jsonArray) {
                    val ao = aya.jsonObject
                    val ayahNo = ao["index"]!!.jsonPrimitive.int
                    val text = ao["text"]!!.jsonPrimitive.content
                    val parts = text.split(' ').filter { it.isNotEmpty() }
                    parts.forEachIndexed { i, part ->
                        words += AyahWord(
                            surahNo = surahNo,
                            ayahNo = ayahNo,
                            position = i + 1,
                            text = part,
                            location = "$surahNo:$ayahNo:${i + 1}",
                            kind = classifyWord(part),
                        )
                    }
                }
            }
            else -> error("unrecognized script pack shape for $scriptKey")
        }
        require(words.isNotEmpty()) { "script pack for $scriptKey/$surahNumber contained no matching verses" }
        cache[key] = words
        return words
    }
}

/**
 * Translation pack reader over the REAL flat pack layout:
 * `inventory/translations/{lang}/{lang}_{translator}.json` shaped
 * `{version, suras: [{index, ayas: [{id, index, text, page}]}]}`.
 * Discovery is driven by `available_translations_info.json` entries.
 */
class TranslationPackSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "inventory/translations",
    private val catalog: TranslationCatalogSource,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Pack metadata for one language, or all languages when [langCode] is null. */
    suspend fun availablePacks(langCode: String? = null): List<TranslationPackInfo> =
        catalog.packs(langCode)

    /** Every translated ayah of [packId], ordered by surah then ayah. */
    suspend fun verses(packId: String, surahNumber: Int? = null): List<TranslatedAyah> {
        val info = catalog.pack(packId) ?: error("unknown translation pack $packId")
        val path = info.downloadPath ?: "$baseDir/${info.langCode}/$packId.json"
        val root = json.parseToJsonElement(fetcher.fetch(path).decodeToString()).jsonObject
        val out = mutableListOf<TranslatedAyah>()
        for (sura in root["suras"]!!.jsonArray) {
            val so = sura.jsonObject
            val surahNo = so["index"]!!.jsonPrimitive.int
            if (surahNumber != null && surahNo != surahNumber) continue
            for (aya in so["ayas"]!!.jsonArray) {
                val ao = aya.jsonObject
                val text = (ao["translation"] ?: ao["text"]).strSafe() ?: continue
                out += TranslatedAyah(
                    packId = packId,
                    surahNo = surahNo,
                    ayahNo = ao["index"]!!.jsonPrimitive.int,
                    text = text,
                    page = ao["page"].intSafe(),
                )
            }
        }
        return out
    }
}

/** Parses `available_translations_info.json` (nested `{translations:{lang:{pack:{...}}}}`). */
class TranslationCatalogSource(
    private val fetcher: ContentFetcher,
    private val path: String = "inventory/translations/available_translations_info.json",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cached: List<TranslationPackInfo>? = null

    suspend fun packs(langCode: String? = null): List<TranslationPackInfo> {
        val all = cached ?: run {
            val root = json.parseToJsonElement(fetcher.fetch(path).decodeToString()).jsonObject["translations"]!!.jsonObject
            val out = mutableListOf<TranslationPackInfo>()
            for ((lang, packs) in root) {
                for ((packId, el) in packs.jsonObject) {
                    val o = el.jsonObject
                    out += TranslationPackInfo(
                        id = packId,
                        langCode = o.str("langCode") ?: lang,
                        book = o.str("book") ?: packId,
                        author = o.str("author"),
                        displayName = o.str("displayName") ?: o.str("book") ?: packId,
                        langName = o.str("langName"),
                        version = o["version"]?.jsonPrimitive?.int ?: 1,
                        downloadPath = o.str("downloadPath"),
                    )
                }
            }
            cached = out
            out
        }
        return if (langCode == null) all else all.filter { it.langCode.equals(langCode, ignoreCase = true) }
    }

    suspend fun pack(packId: String): TranslationPackInfo? = packs().firstOrNull { it.id == packId }

    private fun JsonObject.str(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        val c = v.jsonPrimitive.content
        return if (c == "null" || c.isBlank()) null else c
    }
}

/**
 * Reads the structural tier: `inventory/quran_metadata/` packs — surahs, ayahs,
 * navigation ranges, mushaf/script registry, search aliases. Cached for life.
 */
class QuranMetadataSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "inventory/quran_metadata",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var surahsCache: List<Surah>? = null
    private var ayahsCache: List<AyahMeta>? = null
    private var navCache: Map<NavigationType, List<NavigationUnit>>? = null
    private var mushafsCache: Pair<List<ScriptInfo>, List<MushafInfo>>? = null
    private var aliasesCache: Map<Int, Map<String, List<String>>>? = null

    suspend fun surahs(): List<Surah> = surahsCache ?: run {
        val root = json.parseToJsonElement(fetcher.fetch("$baseDir/surahs.json").decodeToString()).jsonObject
        val out = root["surahs"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            val names = o["names"]?.jsonObject?.mapValues { (_, v) ->
                val n = v.jsonObject
                SurahName(name = n["name"].strSafe(), meaning = n["meaning"].strSafe())
            } ?: emptyMap()
            Surah(
                number = o["number"]!!.jsonPrimitive.int,
                ayahCount = o["ayah_count"]!!.jsonPrimitive.int,
                revelationOrder = o["revelation_order"].intSafe(),
                revelationType = o["revelation_type"].strSafe(),
                rukusCount = o["rukus_count"].intSafe(),
                names = names,
            )
        }
        surahsCache = out
        out
    }

    suspend fun surah(number: Int): Surah? = surahs().firstOrNull { it.number == number }

    suspend fun ayahs(): List<AyahMeta> = ayahsCache ?: run {
        val root = json.parseToJsonElement(fetcher.fetch("$baseDir/ayahs.json").decodeToString()).jsonObject
        val out = root["ayahs"]!!.jsonArray.map { el ->
            val a = el.jsonArray.map { it.jsonPrimitive.int }
            AyahMeta(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8])
        }
        ayahsCache = out
        out
    }

    suspend fun ayahMeta(surahNo: Int): List<AyahMeta> =
        ayahs().filter { it.surahNo == surahNo }

    suspend fun ayahMeta(surahNo: Int, ayahNo: Int): AyahMeta? =
        ayahs().firstOrNull { it.surahNo == surahNo && it.ayahNo == ayahNo }

    suspend fun navigation(type: NavigationType? = null): Map<NavigationType, List<NavigationUnit>> =
        navCache ?: run {
            val root = json.parseToJsonElement(fetcher.fetch("$baseDir/navigation_ranges.json").decodeToString()).jsonObject
            val out = mutableMapOf<NavigationType, MutableList<NavigationUnit>>()
            for ((typeName, units) in root["ranges"]!!.jsonObject) {
                val nt = NavigationType.valueOf(typeName.uppercase())
                val list = units.jsonObject.map { (unitNo, segs) ->
                    NavigationUnit(
                        type = nt,
                        number = unitNo.toInt(),
                        segments = segs.jsonArray.map { s ->
                            val v = s.jsonArray.map { it.jsonPrimitive.int }
                            NavigationSegment(v[0], v[1], v[2])
                        },
                    )
                }.sortedBy { it.number }
                out.getOrPut(nt) { mutableListOf() }.addAll(list)
            }
            val frozen = out.mapValues { it.value.toList() }
            navCache = frozen
            frozen
        }.let { all -> if (type == null) all else all.filterKeys { it == type } }

    suspend fun registry(): Pair<List<ScriptInfo>, List<MushafInfo>> = mushafsCache ?: run {
        val root = json.parseToJsonElement(fetcher.fetch("$baseDir/mushafs.json").decodeToString()).jsonObject
        val scripts = root["scripts"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            ScriptInfo(
                id = o["id"]!!.jsonPrimitive.int,
                code = o["code"]!!.jsonPrimitive.content,
                displayName = o["display_name"]!!.jsonPrimitive.content,
                parentId = o["parent_id"].intSafe(),
            )
        }
        val mushafs = root["mushafs"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            MushafInfo(
                id = o["id"]!!.jsonPrimitive.int,
                code = o["code"]!!.jsonPrimitive.content,
                pageCount = o["no_of_pages"].intSafe() ?: 0,
                linesPerPage = o["lines_per_page"].intSafe() ?: 0,
                layoutSources = o["layout_sources"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            )
        }
        val pair = scripts to mushafs
        mushafsCache = pair
        pair
    }

    suspend fun searchAliases(surahNo: Int, langCode: String? = null): Map<String, List<String>> =
        (aliasesCache ?: run {
            val root = json.parseToJsonElement(fetcher.fetch("$baseDir/surah_search_aliases.json").decodeToString()).jsonObject
            val out = root["aliases"]!!.jsonObject.mapValues { (_, langs) ->
                langs.jsonObject.mapValues { (_, arr) -> arr.jsonArray.map { it.jsonPrimitive.content } }
            }.mapKeys { it.key.toInt() }
            aliasesCache = out
            out
        })[surahNo]?.let { m -> if (langCode == null) m else m.filterKeys { it == langCode } } ?: emptyMap()
}

/**
 * Decorative Quran glyph table (`quran_metadata/quran_glyphs.json`) — the
 * PUA codepoints for surah header icons, juz icons, bismillah, title frames,
 * meccan/medinan and sajdah markers. Exported verbatim from the donor's
 * hardcoded `QuranGlyphs.kt`; the paired fonts (`suracon`, `quran_common`)
 * live under `inventory/fonts/quran_icons/`.
 */
class QuranGlyphSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "inventory/quran_metadata",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: QuranGlyphTable? = null

    private fun glyph(o: JsonObject): SpecialGlyph = SpecialGlyph(
        char = o["glyph"]!!.jsonPrimitive.content,
        codepoint = o["cp"]!!.jsonPrimitive.content,
    )

    /** Full table (cached). Surah icons need the prefix appended after the glyph. */
    suspend fun table(): QuranGlyphTable = cache ?: run {
        val root = json.parseToJsonElement(fetcher.fetch("$baseDir/quran_glyphs.json").decodeToString()).jsonObject
        val sp = root["special"]!!.jsonObject
        fun sp(name: String): SpecialGlyph = SpecialGlyph(
            char = sp[name]!!.jsonPrimitive.content,
            codepoint = sp[name + "_cp"]!!.jsonPrimitive.content,
        )
        val rd = root["reference_decorations"]!!.jsonObject
        fun rd(name: String): SpecialGlyph = SpecialGlyph(
            char = rd[name]!!.jsonObject["glyph"]!!.jsonPrimitive.content,
            codepoint = rd[name]!!.jsonObject["cp"]!!.jsonPrimitive.content,
        )
        val ch = root["chapter_icon"]!!.jsonObject
        val out = QuranGlyphTable(
            bismillah = sp("bismillah"),
            titleFrame = sp("title_frame"),
            meccan = sp("meccan"),
            medinan = sp("medinan"),
            sejda = sp("sejda"),
            chapterPrefix = SpecialGlyph(
                char = ch["prefix"]!!.jsonPrimitive.content,
                codepoint = ch["prefix_cp"]!!.jsonPrimitive.content,
            ),
            chapterBySurah = ch["by_surah"]!!.jsonObject
                .mapValues { (_, v) -> glyph(v.jsonObject) }
                .mapKeys { it.key.toInt() },
            juzByNumber = root["juz_icon"]!!.jsonObject["by_juz"]!!.jsonObject
                .mapValues { (_, v) -> glyph(v.jsonObject) }
                .mapKeys { it.key.toInt() },
            ornateParenLeft = rd("ornate_paren_left"),
            ornateParenRight = rd("ornate_paren_right"),
            salawat = rd("salawat"),
            rtlMark = rd("rtl_mark"),
            ltrMark = rd("ltr_mark"),
        )
        cache = out
        out
    }

    /** Ready-to-render surah header icon: number glyph + prefix (donor RTL order). */
    suspend fun surahIcon(surahNo: Int): String? =
        table().chapterBySurah[surahNo]?.let { it.char + table().chapterPrefix.char }

    /** Ready-to-render juz icon. */
    suspend fun juzIcon(juzNo: Int): String? = table().juzByNumber[juzNo]?.char

    /** Decorated inline ayah reference: ﴿12:3﴾ with donor RLM guards. */
    suspend fun ayahReference(surahNo: Int, ayahNo: Int): String {
        val t = table()
        return t.rtlMark.char + t.ornateParenLeft.char + "$surahNo:$ayahNo" +
            t.ornateParenRight.char + t.rtlMark.char + " "
    }
}

/**
 * Unified mushaf layout access over both donor specs:
 * `mushaf_layout/page_info/{code}.json` (word-id addressed) and
 * `mushaf_layout/mushaf_map/{code}.json` (ayah+word-index addressed).
 */
class MushafLayoutSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "inventory/mushaf_layout",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lineCache = HashMap<String, List<PageLine>>()
    private val wordsCache = HashMap<String, List<RegistryWord>>()

    suspend fun pageLines(mushafCode: String, pageNumber: Int): List<PageLine> =
        allLines(mushafCode).filter { it.pageNumber == pageNumber }

    suspend fun linesOfAyah(mushafCode: String, surahNo: Int, ayahNo: Int): List<PageLine> {
        val ayahId = surahNo * 1000 + ayahNo
        return allLines(mushafCode).filter { l ->
            when {
                l.startAyahId != null && l.endAyahId != null -> ayahId in l.startAyahId..l.endAyahId
                else -> false
            }
        }.ifEmpty {
            // page_info-addressed mushafs: resolve via word registry
            val words = words(mushafScriptOf(mushafCode))
            val first = words.indexOfFirst { it.ayahId == ayahId }
            if (first < 0) return emptyList()
            val last = words.indexOfLast { it.ayahId == ayahId }
            allLines(mushafCode).filter { l ->
                val f = l.firstWordId ?: return@filter false
                val t = l.lastWordId ?: return@filter false
                f <= last + 1 && t >= first + 1
            }
        }
    }

    suspend fun pageOfAyah(mushafCode: String, surahNo: Int, ayahNo: Int): Int? =
        linesOfAyah(mushafCode, surahNo, ayahNo).minOfOrNull { it.pageNumber }

    /** Canonical word registry of the script a mushaf renders (ayah order). */
    suspend fun words(scriptCode: String): List<RegistryWord> = wordsCache[scriptCode] ?: run {
        val bytes = fetcher.fetch("$baseDir/words/$scriptCode.json.gz")
        val text = GZIPInputStream(bytes.inputStream()).readBytes().decodeToString()
        val root = json.parseToJsonElement(text).jsonObject
        val out = root["words"]!!.jsonArray.map { el ->
            val a = el.jsonArray
            RegistryWord(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int, a[2].jsonPrimitive.content)
        }
        wordsCache[scriptCode] = out
        out
    }

    fun clearCache() {
        lineCache.clear()
        wordsCache.clear()
    }

    /** qpc renders uthmani text; indopak_* render dk_indopak; per donor script wiring. */
    fun mushafScriptOf(mushafCode: String): String = when (mushafCode) {
        "qpc", "uthmani" -> "uthmani"
        "kfqpc_v1" -> "kfqpc_v1"
        else -> if (mushafCode.startsWith("indopak")) "dk_indopak" else mushafCode
    }

    private suspend fun allLines(mushafCode: String): List<PageLine> = lineCache[mushafCode] ?: run {
        // Prefer page_info (word-id addressed — matches the atlas glyph pipeline);
        // fall back to mushaf_map (ayah+word-index addressed) for mushafs without one.
        val out = mutableListOf<PageLine>()
        val pageInfo = runCatching {
            json.parseToJsonElement(fetcher.fetch("$baseDir/page_info/$mushafCode.json").decodeToString()).jsonObject
        }.getOrNull()
        pageInfo?.get("lines")?.jsonArray?.forEach { el ->
            val a = el.jsonArray
            out += PageLine(
                mushaf = mushafCode,
                pageNumber = a[0].jsonPrimitive.int,
                lineNumber = a[1].jsonPrimitive.int,
                type = LineType.of(a[2].jsonPrimitive.content),
                isCentered = a[3].jsonPrimitive.content.let { it == "1" || it.equals("true", ignoreCase = true) },
                firstWordId = a[4].intOrNullSafe(),
                lastWordId = a[5].intOrNullSafe(),
                surahNo = a[6].intOrNullSafe(),
            )
        }
        if (out.isEmpty()) {
            json.parseToJsonElement(fetcher.fetch("$baseDir/mushaf_map/$mushafCode.json").decodeToString())
                .jsonObject["lines"]?.jsonArray?.forEach { el ->
                    val a = el.jsonArray
                    out += PageLine(
                        mushaf = mushafCode,
                        pageNumber = a[0].jsonPrimitive.int,
                        lineNumber = a[1].jsonPrimitive.int,
                        type = LineType.of(a[2].jsonPrimitive.content),
                        isCentered = a[3].jsonPrimitive.content.let { it == "1" || it.equals("true", ignoreCase = true) },
                        startAyahId = a[4].intOrNullSafe(),
                        startWordIndex = a[5].intOrNullSafe(),
                        endAyahId = a[6].intOrNullSafe(),
                        endWordIndex = a[7].intOrNullSafe(),
                        surahNo = a[8].intOrNullSafe(),
                    )
                }
        }
        require(out.isNotEmpty()) { "no layout spec available for mushaf $mushafCode" }
        lineCache[mushafCode] = out
        out
    }

    private fun JsonElement.intOrNullSafe(): Int? = runCatching { jsonPrimitive.int }.getOrNull()
}

/**
 * On-device Arabic search over `inventory/quran_search/arabic_text.json`
 * (diacritic-stripped normalized text). FTS5 index built into a side sqlite
 * db (file-backed when [indexDb] given, in-memory otherwise).
 */
class QuranSearchIndex(
    private val fetcher: ContentFetcher,
    private val arabicTextPath: String = "inventory/quran_search/arabic_text.json",
    private val indexDb: java.io.File? = null,
) : AutoCloseable {

    private val json = Json { ignoreUnknownKeys = true }
    private var connection: java.sql.Connection? = null
    private var built = false

    /** Build the FTS5 index if not already built (or stale). Returns row count. */
    suspend fun build(force: Boolean = false): Int {
        val c = conn()
        if (!force && built) return count(c)
        c.createStatement().use { st ->
            st.executeUpdate("DROP TABLE IF EXISTS quran_fts")
            st.executeUpdate(
                """CREATE VIRTUAL TABLE quran_fts USING fts5(
                   ayah_id UNINDEXED, text, tokenize='unicode61 remove_diacritics 2')""",
            )
        }
        val root = json.parseToJsonElement(fetcher.fetch(arabicTextPath).decodeToString()).jsonObject
        val rows = root["ayahs"]!!.jsonArray
        c.prepareStatement("INSERT INTO quran_fts (ayah_id, text) VALUES (?, ?)").use { ps ->
            var n = 0
            for (el in rows) {
                val a = el.jsonArray
                ps.setInt(1, a[0].jsonPrimitive.int)
                ps.setString(2, a[1].jsonPrimitive.content)
                ps.addBatch()
                if (++n % 1000 == 0) ps.executeBatch()
            }
            ps.executeBatch()
        }
        built = true
        return rows.size
    }

    /**
     * Full-text search. Query words are ANDed; each word gets prefix matching
     * (`word*`) to support Arabic prefix searching.
     */
    suspend fun search(query: String, limit: Int = 20, offset: Int = 0): List<com.khushu.data.model.QuranSearchHit> {
        val c = conn()
        if (!built) build()
        val match = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ") { "\"${it.replace("\"", "")}\"*" }
        if (match.isBlank()) return emptyList()
        return c.prepareStatement(
            """SELECT ayah_id, snippet(quran_fts, 1, '⟨', '⟩', '…', 8)
               FROM quran_fts WHERE quran_fts MATCH ? ORDER BY rank LIMIT ? OFFSET ?""",
        ).use { ps ->
            ps.setString(1, match)
            ps.setInt(2, limit)
            ps.setInt(3, offset)
            val rs = ps.executeQuery()
            buildList {
                while (rs.next()) {
                    val ayahId = rs.getInt(1)
                    add(
                        com.khushu.data.model.QuranSearchHit(
                            ayahId = ayahId,
                            surahNo = ayahId / 1000,
                            ayahNo = ayahId % 1000,
                            text = rs.getString(2),
                        ),
                    )
                }
            }
        }
    }

    override fun close() {
        connection?.let { runCatching { it.close() } }
        connection = null
        built = false
    }

    private fun count(c: java.sql.Connection): Int =
        c.createStatement().use { st ->
            val rs = st.executeQuery("SELECT COUNT(*) FROM quran_fts")
            if (rs.next()) rs.getInt(1) else 0
        }

    private fun conn(): java.sql.Connection {
        connection?.let { return it }
        Class.forName("org.sqlite.JDBC")
        val uri = indexDb?.let { "jdbc:sqlite:${it.absolutePath}" } ?: "jdbc:sqlite::memory:"
        return java.sql.DriverManager.getConnection(uri).also { connection = it }
    }
}
