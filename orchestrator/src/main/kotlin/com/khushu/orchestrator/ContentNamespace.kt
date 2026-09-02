package com.khushu.orchestrator

import com.khushu.data.model.AsmaName
import com.khushu.data.model.AsmaPack
import com.khushu.data.model.Dua
import com.khushu.data.model.DuaArticle
import com.khushu.data.model.DuaArticleCategory
import com.khushu.data.model.DuaArticleInfo
import com.khushu.data.model.DuaCategory

/**
 * Read-only delegation over data-api retrieval — the host's content surface.
 *
 * DOCTRINE: delegation only, one line per call, never add logic here. Any
 * function needing domain knowledge belongs in data-api; any composite of
 * fetch+math belongs in a namespace. Data-api fetches are cheap by design
 * (CachingFetcher disk cache + source memoization) — direct content reads
 * through this surface carry no recomputation risk.
 *
 * This surface mirrors the full retrieval capability of KhushuContent so the
 * wall never blocks a host feature: quran (text/pages/atlases), mushaf
 * addressing, recitations, adhan, dua/asma, sunnah books, islamic events,
 * catalogs, topics, similar-verse, wbw, chapter info.
 */
class ContentNamespace internal constructor(private val o: KhushuOrchestrator) {

    // ── dua & dhikr ────────────────────────────────────────────────────────

    suspend fun duas(): List<Dua> = o.data.dua.duas()
    suspend fun dua(id: Int): Dua? = o.data.dua.dua(id)
    suspend fun duaCategories(): List<DuaCategory> = o.data.dua.categories()
    suspend fun duasBySubcategory(subcategory: String): List<Dua> =
        o.data.dua.bySubcategory(subcategory)

    suspend fun duaArticleCategories(): List<DuaArticleCategory> = o.data.dua.articleCategories()
    suspend fun duaArticles(): List<DuaArticleInfo> = o.data.dua.articles()
    suspend fun duaArticle(filePath: String): DuaArticle? = o.data.dua.article(filePath)

    /** Dua recitation audio (bytes of `assets/dua_dhikr/dua_{id}.opus`). */
    suspend fun duaAudio(id: Int): ByteArray? = o.data.dua.audio(id)

    /** Repo-relative mirror path for a dua's audio, or null (3 mirrorless entries). */
    suspend fun duaLocalAudioPath(id: Int): String? = o.data.dua.localAudioPath(id)

    // ── asma ul husna ──────────────────────────────────────────────────────

    val asmaLanguages: List<String> get() = o.data.dua.asmaLanguages
    suspend fun asmaPack(lang: String): AsmaPack? = o.data.dua.asmaPack(lang)
    suspend fun asmaName(lang: String, number: Int): AsmaName? = o.data.dua.asmaName(lang, number)

    // ── sunnah (per-book online read; offline .db via sunnah.attach) ───────

    suspend fun sunnahBookIndex(collectionId: String) = o.data.sunnahBooks.bookIndex(collectionId)
    suspend fun sunnahBook(collectionId: String, bookId: String, lang: String = "en") =
        o.data.sunnahBooks.book(collectionId, bookId, lang)

    // ── quran — text & metadata ────────────────────────────────────────────

    suspend fun surahs() = o.data.quran.surahs()
    suspend fun surah(number: Int) = o.data.quran.surah(number)
    suspend fun ayahTexts(surahNo: Int, script: String = "uthmani", includeAyahMarker: Boolean = false) =
        o.data.quran.ayahTexts(surahNo, script, includeAyahMarker)
    suspend fun ayahMeta(surahNo: Int) = o.data.quran.ayahMeta(surahNo)
    suspend fun ayahMeta(surahNo: Int, ayahNo: Int) = o.data.quran.ayahMeta(surahNo, ayahNo)
    suspend fun ayahBundle(surahNo: Int, ayahNo: Int) = o.data.quran.ayahBundle(surahNo, ayahNo)
    suspend fun navigation(type: com.khushu.data.model.NavigationType? = null) =
        o.data.quran.navigation(type)
    suspend fun searchAliases(surahNo: Int, langCode: String? = null) =
        o.data.quran.searchAliases(surahNo, langCode)

    /** Rendering-script registry (ayah_words coverage). */
    suspend fun scripts() = o.data.quran.scripts()

    /** Available mushaf layouts (page-line map sources). */
    suspend fun mushafs() = o.data.quran.mushafs()

    /** Canonical word registry for a script — 1-based running word ids. */
    suspend fun wordRegistry(script: String) = o.data.quran.wordRegistry(script)

    // ── quran — mushaf page addressing ─────────────────────────────────────

    suspend fun pageLines(mushaf: String, page: Int) = o.data.quran.pageLines(mushaf, page)
    suspend fun linesOfAyah(mushaf: String, surahNo: Int, ayahNo: Int) =
        o.data.quran.linesOfAyah(mushaf, surahNo, ayahNo)
    suspend fun pageOfAyah(mushaf: String, surahNo: Int, ayahNo: Int) =
        o.data.quran.pageOfAyah(mushaf, surahNo, ayahNo)

    // ── quran — glyph atlas ────────────────────────────────────────────────

    /** Glyph texture table (rects + bearings at bundle ppem). */
    suspend fun atlasGlyphTable(bundleId: String, sizeLabel: String = "6x") =
        o.data.quran.atlas.glyphTable(bundleId, sizeLabel)

    /** Word text → glyph placements (font units). */
    suspend fun atlasPlacementsByWord(bundleId: String, sizeLabel: String = "6x") =
        o.data.quran.atlas.placementsByWord(bundleId, sizeLabel)

    /**
     * One glyph texture page (PNG bytes) — the raster the host decodes and
     * draws `PositionedGlyph` rects from. Pair with [MushafNamespace.renderablePage].
     */
    suspend fun atlasTexture(bundleId: String, textureIndex: Int, sizeLabel: String = "6x"): ByteArray =
        o.data.quran.atlas.textureBytes(bundleId, textureIndex, sizeLabel)

    /** Surah-header icon reference (decoration slot art source). */
    suspend fun surahIcon(surahNo: Int): String? = o.data.quran.surahIcon(surahNo)
    suspend fun juzIcon(juzNo: Int): String? = o.data.quran.juzIcon(juzNo)
    suspend fun ayahReference(surahNo: Int, ayahNo: Int): String =
        o.data.quran.ayahReference(surahNo, ayahNo)

    // ── quran — translations/tafsirs/wbw ───────────────────────────────────

    suspend fun translationPacks(langCode: String? = null) =
        o.data.quran.translationPacks(langCode)
    suspend fun translationText(packId: String, surahNo: Int? = null) =
        o.data.quran.translationText(packId, surahNo)
    suspend fun tafsirForSurah(slug: String, surahNo: Int, lang: String = "en") =
        o.data.quran.tafsirForSurah(slug, surahNo, lang)

    suspend fun wbwLanguages(v2: Boolean = true) = o.data.quran.wbwLanguages(v2)
    suspend fun wbwForSurah(surahNo: Int, lang: String = "en", v2: Boolean = true) =
        o.data.quran.wbwForSurah(surahNo, lang, v2)
    suspend fun wbwWord(surahNo: Int, ayahNo: Int, wordIndex: Int, lang: String = "en", v2: Boolean = true) =
        o.data.quran.wbwWord(surahNo, ayahNo, wordIndex, lang, v2)

    suspend fun chapterInfo(surahNo: Int, lang: String = "en") =
        o.data.quran.chapterInfo(surahNo, lang)
    suspend fun chapterInfoVariants(surahNo: Int, lang: String = "en") =
        o.data.quran.chapterInfoVariants(surahNo, lang)

    // ── quran — topics & similar verses ────────────────────────────────────

    suspend fun similarTo(ayahId: Int) = o.data.quran.similarTo(ayahId)
    suspend fun mutashabihatPhrases() = o.data.quran.mutashabihatPhrases()
    suspend fun topics(type: String? = null) = o.data.quran.topics(type)
    suspend fun topic(id: Int) = o.data.quran.topic(id)
    suspend fun topicBySlug(slug: String) = o.data.quran.topicBySlug(slug)
    suspend fun topicsForAyah(ayahId: Int) = o.data.quran.topicsForAyah(ayahId)

    // ── recitations ────────────────────────────────────────────────────────

    suspend fun recitationReciters() = o.data.quran.reciters()
    suspend fun recitationReciter(id: String) = o.data.quran.reciter(id)
    suspend fun chapterTimings(reciterId: String, surahNo: Int) =
        o.data.quran.chapterTimings(reciterId, surahNo)
    suspend fun verseTimings(reciterId: String, surahNo: Int) =
        o.data.quran.verseTimings(reciterId, surahNo)

    // ── islamic events (display entries) ───────────────────────────────────

    suspend fun islamicEvents() = o.data.islamicEvents.all()
    suspend fun islamicEventsForHijriMonth(month: Int) =
        o.data.islamicEvents.forHijriMonth(month)

    // ── adhan catalog ──────────────────────────────────────────────────────

    suspend fun adhanEntries() = o.data.adhan.entries()
    suspend fun adhanReciters() = o.data.adhan.reciters()
    suspend fun adhanByReciter(name: String) = o.data.adhan.byReciter(name)
    suspend fun adhanEntry(id: String) = o.data.adhan.entry(id)

    /** Adhan audio bytes (donor-collected recordings). */
    suspend fun adhanAudio(id: String): ByteArray? = o.data.adhan.audio(id)

    // ── catalogs (discovery manifests) ─────────────────────────────────────

    suspend fun catalogTranslations() = o.data.catalogs.translations()
    suspend fun catalogTafsirs() = o.data.catalogs.tafsirs()
    suspend fun catalogWbw(v2: Boolean = false) = o.data.catalogs.wbw(v2)
    suspend fun catalogFonts() = o.data.catalogs.fonts()
    suspend fun catalogWebLinks() = o.data.catalogs.webLinks()

    // ── curated ────────────────────────────────────────────────────────────

    suspend fun curatedExclusiveVerses(set: com.khushu.data.content.VerseSet, lang: String = "en") =
        o.data.curated.exclusiveVerses(set, lang)
    suspend fun curatedExclusiveVerse(set: com.khushu.data.content.VerseSet, id: String, lang: String = "en") =
        o.data.curated.exclusiveVerse(set, id, lang)
    suspend fun curatedExclusiveVerseLanguages(set: com.khushu.data.content.VerseSet) =
        o.data.curated.exclusiveVerseLanguages(set)
    suspend fun curatedRecommendedRules() = o.data.curated.recommendedRules()
    suspend fun curatedRecommendedTexts(lang: String = "en") = o.data.curated.recommendedTexts(lang)
    suspend fun curatedScienceTopics() = o.data.curated.scienceTopics()
}
