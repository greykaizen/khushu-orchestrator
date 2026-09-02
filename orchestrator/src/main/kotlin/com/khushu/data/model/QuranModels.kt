package com.khushu.data.model


// ── Quran core (inventory/quran_metadata) ───────────────────────────────────

/** Localized surah name: transliterated `name` and/or translated `meaning`. */
data class SurahName(val name: String? = null, val meaning: String? = null)

data class Surah(
    val number: Int,
    val ayahCount: Int,
    val revelationOrder: Int? = null,
    /** "meccan" / "medinan" as recorded in the source corpus. */
    val revelationType: String? = null,
    val rukusCount: Int? = null,
    /** lang code -> name/meaning (18 languages in the canonical export). */
    val names: Map<String, SurahName> = emptyMap(),
)

/** One ayah text row assembled from a word-level script pack. */
data class AyahText(val surahNo: Int, val ayahNo: Int, val text: String)

/** Classification of a word token inside an ayah word pack. */
enum class WordKind { TEXT, AYAH_END_MARKER }

/**
 * Word-level granularity — served identically by JSON script packs and word
 * registries. `kind` separates the trailing ayah-end marker glyph from text.
 */
data class AyahWord(
    val surahNo: Int,
    val ayahNo: Int,
    /** 1-based position within the ayah (including the end marker when present). */
    val position: Int,
    val text: String,
    /** "surah:ayah:word" locator as shipped in script packs. */
    val location: String = "",
    val kind: WordKind = WordKind.TEXT,
)

/** Ayah structural facts (inventory/quran_metadata/ayahs.json). */
data class AyahMeta(
    val ayahId: Int,         // surah*1000 + ayahNo
    val surahNo: Int,
    val ayahNo: Int,
    val juzNo: Int,
    val hizbNo: Int,
    val rubNo: Int,
    val manzilNo: Int,
    val rukuNo: Int,
    /** 0 = none, 1 = sajdah verse, 2 = emphasized sajdah. */
    val sajdahType: Int,
)

enum class NavigationType { JUZ, HIZB, RUB, MANZIL }

/** One contiguous same-surah segment of a navigation unit. */
data class NavigationSegment(val surahNo: Int, val fromAyah: Int, val toAyah: Int)

/** A full navigation unit (e.g. juz 2) = ordered segments across surahs. */
data class NavigationUnit(val type: NavigationType, val number: Int, val segments: List<NavigationSegment>)

/** Rendering script registry entry (ayah_words coverage). */
data class ScriptInfo(val id: Int, val code: String, val displayName: String, val parentId: Int? = null)

/** Mushaf (page layout) registry entry. */
data class MushafInfo(
    val id: Int,
    val code: String,
    val pageCount: Int,
    val linesPerPage: Int,
    /** Which layout specs cover this mushaf: "mushaf_map" and/or "page_info". */
    val layoutSources: List<String> = emptyList(),
)

// ── Mushaf layout (inventory/mushaf_layout) ─────────────────────────────────

enum class LineType { SURAH_NAME, BASMALLAH, AYAH, UNKNOWN;
    companion object {
        fun of(raw: String?): LineType = when (raw) {
            "surah_name" -> SURAH_NAME
            "basmallah" -> BASMALLAH
            "ayah" -> AYAH
            else -> UNKNOWN
        }
    }
}

/**
 * One rendered line of a mushaf page, unified across both donor addressing
 * schemes (page_info word-ids / mushaf_map ayah+word-index).
 */
data class PageLine(
    val mushaf: String,
    val pageNumber: Int,
    val lineNumber: Int,
    val type: LineType,
    val isCentered: Boolean,
    /** page_info scheme: 1-based running word ids of the rendered script. */
    val firstWordId: Int? = null,
    val lastWordId: Int? = null,
    /** mushaf_map scheme: ayah-id + 0-based word index bounds. */
    val startAyahId: Int? = null,
    val startWordIndex: Int? = null,
    val endAyahId: Int? = null,
    val endWordIndex: Int? = null,
    /** Surah this line belongs to (surah_name lines: the surah being headed). */
    val surahNo: Int? = null,
)

/** A word of the canonical word registry for one rendering script. */
data class RegistryWord(val ayahId: Int, val wordIndex: Int, val text: String)

// ── Translations (flat {version, suras} packs) ──────────────────────────────

data class TranslationPackInfo(
    val id: String,              // e.g. "en_hilali-khan"
    val langCode: String,
    val book: String,
    val author: String? = null,
    val displayName: String,
    val langName: String? = null,
    val version: Int = 1,
    /** Repo-root-relative path of the pack JSON. */
    val downloadPath: String? = null,
)

data class TranslatedAyah(
    val packId: String,
    val surahNo: Int,
    val ayahNo: Int,
    val text: String,
    /** Physical page of the source mushaf when shipped by the pack. */
    val page: Int? = null,
)

// ── Search (inventory/quran_search) ─────────────────────────────────────────

data class QuranSearchHit(val ayahId: Int, val surahNo: Int, val ayahNo: Int, val text: String)

// ── Similar verses / mutashabihat (inventory/similar) ───────────────────────

data class SimilarVerse(
    val matchedAyahId: Int,
    val matchedWordsCount: Int,
    /** Percent of source covered by the match. */
    val coverage: Int,
    val score: Int,
    /** Matched word-index ranges. */
    val matchWords: List<Pair<Int, Int>>,
)

data class MutashabihatPhrase(
    val phraseId: Int,
    val surahsCount: Int,
    val ayahsCount: Int,
    val occurrenceCount: Int,
    val sourceAyahId: Int,
    val wordFrom: Int,
    val wordTo: Int,
)

data class MutashabihatOccurrence(val ayahId: Int, val wordRanges: List<Pair<Int, Int>>, val inAyahOrder: Int)

// ── Topics (inventory/topics) ───────────────────────────────────────────────

data class Topic(
    val id: Int,
    val slug: String,
    val type: String,          // concept/category/prophet/place/...
    /** Repo-root-relative image path when available. */
    val imageUrl: String? = null,
    val icon: String? = null,
    val flags: Int = 0,
    val titleEn: String? = null,
    val titleAr: String? = null,
    val ayahIds: List<Int> = emptyList(),
)

data class TopicRelation(val sourceTopicId: Int, val targetTopicId: Int, val type: String, val sortOrder: Int)

// ── Catalogs & sync ─────────────────────────────────────────────────────────

data class CatalogEntry(
    val id: String,
    val langCode: String? = null,
    val displayName: String,
    val version: Long,
    /** Repo-root-relative path, or full URL for whitelisted external hosts. */
    val url: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val author: String? = null,
)

data class TafsirEntry(
    val slug: String,
    val name: String,
    val author: String?,
    val langCode: String,
    val langName: String?,
)

data class WbwPackEntry(
    val id: String,
    val langCode: String,
    val langName: String?,
    val hasTranslation: Boolean,
    val hasTransliteration: Boolean,
    val version: Long,
    val url: String?,
)

/** One downloadable font file inside a [FontPackEntry]. */
data class FontFileEntry(
    val id: String,
    val displayName: String,
    /** Repo-root-relative path (fetch via ContentFetcher). */
    val path: String,
    val format: String,
    val weight: Int,
    val provenance: String?,
)

/** A themed font pack (e.g. `quran_icons`, `sunnah`) from the fonts catalog. */
data class FontPackEntry(
    val id: String,
    val displayName: String,
    val usage: String?,
    val files: List<FontFileEntry>,
)

enum class DownloadStatus { NOT_DOWNLOADED, DOWNLOADED, UPDATE_AVAILABLE }

data class DownloadState(
    val packId: String,
    val version: Long,
    val localPath: String?,
    val status: DownloadStatus,
)

// ── Chapter info (inventory/chapters/info) ──────────────────────────────────

/** Which chapter-info resource variant a file carries (file-name suffix). */
enum class ChapterInfoVariant(val suffix: String) {
    /** Primary resource of the export (Ibn Ashur). */
    DEFAULT(""),

    /** Tafhim al-Qur'an (Maududi) — `{N}_58.json` in the export. */
    MAUDUDI("_58"),
}

/** Surah background: themes, names, revelation context (HTML body). */
data class ChapterInfo(
    val surahNo: Int,
    val langCode: String,
    val variant: ChapterInfoVariant,
    val source: String?,
    val shortText: String?,
    val textHtml: String,
)

// ── Word-by-word (inventory/wbw) ────────────────────────────────────────────

/** One WBW word: translation plus optional transliteration. */
data class WbwWord(val translation: String, val transliteration: String? = null)

/** Parsed WBW language pack (whole Quran, keyed by ayah_id). */
data class WbwLangPack(
    val langCode: String,
    val version: Int,
    /** ayah_id -> 0-based word index -> word data (includes ayah-end marker). */
    val verses: Map<Int, Map<Int, WbwWord>>,
) {
    fun forSurah(surahNo: Int): Map<Int, Map<Int, WbwWord>> =
        verses.filterKeys { it / 1000 == surahNo }
            .mapKeys { it.key % 1000 }

    fun word(surahNo: Int, ayahNo: Int, wordIndex: Int): WbwWord? =
        verses[surahNo * 1000 + ayahNo]?.get(wordIndex)
}

// ── Tafsir content (inventory/tafsirs) ──────────────────────────────────────

/** One tafsir excerpt covering a verse range (HTML body). */
data class TafsirSegment(
    val chapter: Int,
    val fromVerse: Int,
    val toVerse: Int,
    val textHtml: String,
)

// ── Recitations (inventory/recitations) ─────────────────────────────────────

/** One reciter + how to reach audio (external template) and timings (repo-local). */
data class ReciterInfo(
    val id: String,
    val name: String,
    val style: String?,
    /** External audio host template, e.g. `…/{chapNo:%01d}.mp3` — audio stays off-repo. */
    val urlTemplate: String?,
    /** Repo-root-relative timing pack path (`…/timings/{id}.json.gz`). */
    val timingUrl: String?,
    val timingVersion: Int = 1,
    val audioVersion: Int = 1,
    val translations: Map<String, String> = emptyMap(),
)

/** One word-level audio segment inside an ayah. */
data class WordAudioSegment(val wordIndex: Int, val startMs: Int, val endMs: Int)

/** Timing anchors for one ayah of a recitation. */
data class VerseTiming(
    val verse: Int,
    val startMs: Int,
    val endMs: Int,
    /** Per-word segments when the timing pack carries them. */
    val segments: List<WordAudioSegment> = emptyList(),
)

/** All timing anchors of one surah for one reciter. */
data class ChapterTimings(
    val reciterId: String,
    val chapter: Int,
    val durationMs: Int,
    val verses: List<VerseTiming>,
)

// ── Decorative glyph tables (quran_glyphs.json) ────────────────────────────

/** Special decorative glyph: the char plus its codepoint string (e.g. U+FDFD). */
data class SpecialGlyph(
    /** The character to render (PUA or Unicode). */
    val char: String,
    /** Codepoint as `U+XXXX` for debugging/auditing. */
    val codepoint: String,
)

/**
 * PUA glyph table for decorative Quran rendering — surah header icons,
 * juz icons, special glyphs, and reference decorations. Exported verbatim
 * from the donor's hardcoded `QuranGlyphs.kt`; fonts live in
 * `inventory/fonts/quran_icons/` (and the hadith Naskh for salawat).
 */
data class QuranGlyphTable(
    /** Bismillah (U+FDFD) — render with the `quran_common` font. */
    val bismillah: SpecialGlyph,
    /** Surah title frame (U+E000) — render with `quran_common`. */
    val titleFrame: SpecialGlyph,
    /** Meccan-provenance marker glyph (U+E073). */
    val meccan: SpecialGlyph,
    /** Medinan-provenance marker glyph (U+E075). */
    val medinan: SpecialGlyph,
    /** Sajdah marker (U+06E9 ۩). */
    val sejda: SpecialGlyph,
    /** Chapter-icon prefix glyph — APPEND after the number glyph in visual order. */
    val chapterPrefix: SpecialGlyph,
    /** Surah number (1..114) → icon glyph; NOT sequential — always look up. */
    val chapterBySurah: Map<Int, SpecialGlyph>,
    /** Juz number (1..30) → glyph; render with `quran_common`. */
    val juzByNumber: Map<Int, SpecialGlyph>,
    /** Ornate paren LEFT ﴿ (U+FD3F) — opens inline ayah references. */
    val ornateParenLeft: SpecialGlyph,
    /** Ornate paren RIGHT ﴾ (U+FD3E) — closes inline ayah references. */
    val ornateParenRight: SpecialGlyph,
    /** SALAWAT ligature ﷺ (U+FDFA) — the only special glyph the hadith corpora use. */
    val salawat: SpecialGlyph,
    /** Right-to-left mark (U+200F) — control char embedded in donor text; no glyph. */
    val rtlMark: SpecialGlyph,
    /** Left-to-right mark (U+200E) — control char; no glyph. */
    val ltrMark: SpecialGlyph,
)
