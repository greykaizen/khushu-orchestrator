package com.khushu.data.repo

import com.khushu.data.atlas.AtlasBundleInfo
import com.khushu.data.atlas.AtlasBundleSource
import com.khushu.data.atlas.AtlasCatalogSource
import com.khushu.data.atlas.AtlasGlyphPlacement
import com.khushu.data.atlas.AtlasLayerRoot
import com.khushu.data.atlas.AtlasLayoutRoot
import com.khushu.data.atlas.AtlasMetaRoot
import com.khushu.data.catalog.CatalogSource
import com.khushu.data.catalog.SyncTracker
import com.khushu.data.content.CuratedContentSource
import com.khushu.data.content.RecommendedSource
import com.khushu.data.content.ScienceTopicsSource
import com.khushu.data.content.SimilarVersesSource
import com.khushu.data.content.TopicsSource
import com.khushu.data.content.VerseSet
import com.khushu.data.model.AyahMeta
import com.khushu.data.model.AyahText
import com.khushu.data.model.AyahBundle
import com.khushu.data.model.AyahWord
import com.khushu.data.model.Book
import com.khushu.data.dua.DuaSource
import com.khushu.data.dua.AsmaSource
import com.khushu.data.sunnah.SunnahBookSource
import com.khushu.data.model.AdhanEntry
import com.khushu.data.model.AdhanReciter
import com.khushu.data.transport.CachingFetcher
import com.khushu.data.transport.DownloadedItemView
import com.khushu.data.transport.DownloadsSnapshot
import com.khushu.data.plans.CollectionPlan
import com.khushu.data.plans.PlanFactory
import com.khushu.data.plans.DownloadsLedger
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.khushu.data.model.AsmaName
import com.khushu.data.model.AsmaPack
import com.khushu.data.adaptive.AdaptiveContext
import com.khushu.data.adaptive.AdaptiveDuaSection
import com.khushu.data.adaptive.DuaTimeSlot
import com.khushu.data.adaptive.SlotWindows
import com.khushu.data.model.Dua
import com.khushu.data.model.DuaArticle
import com.khushu.data.model.DuaArticleCategory
import com.khushu.data.model.DuaArticleInfo
import com.khushu.data.model.DuaCategory
import com.khushu.data.model.CatalogEntry
import com.khushu.data.model.Chapter
import com.khushu.data.model.DownloadState
import com.khushu.data.model.FontFileEntry
import com.khushu.data.model.FontPackEntry
import com.khushu.data.model.Grade
import com.khushu.data.model.Hadith
import com.khushu.data.model.HadithCollection
import com.khushu.data.model.MushafInfo
import com.khushu.data.model.NavigationType
import com.khushu.data.model.NavigationUnit
import com.khushu.data.model.PageLine
import com.khushu.data.model.QuranSearchHit
import com.khushu.data.model.RegistryWord
import com.khushu.data.model.ScriptInfo
import com.khushu.data.model.Scholar
import com.khushu.data.model.SearchResultRow
import com.khushu.data.model.SimilarVerse
import com.khushu.data.model.MutashabihatPhrase
import com.khushu.data.model.MutashabihatOccurrence
import com.khushu.data.model.Surah
import com.khushu.data.model.TafsirEntry
import com.khushu.data.model.Topic
import com.khushu.data.model.TopicRelation
import com.khushu.data.model.TranslatedAyah
import com.khushu.data.model.TranslationPackInfo
import com.khushu.data.model.WbwPackEntry
import com.khushu.data.model.ChapterInfo
import com.khushu.data.model.ChapterInfoVariant
import com.khushu.data.model.ChapterTimings
import com.khushu.data.model.QuranGlyphTable
import com.khushu.data.model.ReciterInfo
import com.khushu.data.model.TafsirSegment
import com.khushu.data.model.VerseTiming
import com.khushu.data.model.WbwWord
import com.khushu.data.quran.ChapterInfoSource
import com.khushu.data.quran.MushafLayoutSource
import com.khushu.data.quran.QuranMetadataSource
import com.khushu.data.quran.QuranGlyphSource
import com.khushu.data.quran.QuranScriptSource
import com.khushu.data.quran.QuranSearchIndex
import com.khushu.data.quran.RecitationSource
import com.khushu.data.quran.TafsirSource
import com.khushu.data.quran.TranslationCatalogSource
import com.khushu.data.quran.TranslationPackSource
import com.khushu.data.quran.WbwSource
import com.khushu.data.sunnah.HadithSearchRepository
import com.khushu.data.sunnah.IndexBuildSummary
import com.khushu.data.sunnah.LocalHadithRepository
import com.khushu.data.transport.ContentFetcher
import java.io.File

/**
 * Single entry point for khushu content — the retrieval half of the Khushu
 * stack (computation lives in khushu-engine; the two never depend on each
 * other).
 *
 * ```kotlin
 * val content = KhushuContent(fetcher)          // online or offline transport
 * val fatiha = content.quran.ayahTexts(1)       // suspend
 * val packs  = content.quran.translationPacks("en")
 * val duas   = content.dua.duas()               // 491 duas (lifewithallah corpus)
 *
 * // Sunnah needs local .db corpora (SQLite cannot stream from a URL):
 * val sunnah = content.attachSunnah(corporaRoot = File("inventory/hadiths"))
 * val hadith = sunnah.hadith("bukhari_urn_100010", lang = "en")
 * ```
 *
 * All Quran-side calls work through the injected [ContentFetcher] — a remote
 * GitHub-raw transport for streaming mode, or [com.khushu.data.transport.LocalFetcher]
 * against a checkout for offline mode.
 */
class KhushuContent(
    fetcher: ContentFetcher,
) : AutoCloseable {

    val quran: QuranApi = QuranApi(fetcher)
    val catalogs: CatalogApi = CatalogApi(fetcher)
    val curated: CuratedApi = CuratedApi(fetcher)
    val dua: DuaApi = DuaApi(fetcher)
    val adhan: AdhanApi = AdhanApi(fetcher)
    val islamicEvents: IslamicEventsApi = IslamicEventsApi(fetcher)

    /** Download/space management — available only when the injected fetcher
     *  is (or wraps) a [com.khushu.data.transport.CachingFetcher]. */
    val downloads: DownloadsApi = DownloadsApi(fetcher)

    /** Per-book sunnah reading — ONLINE path over the sliced corpus
     *  (inventory/hadiths/{c}/books/{lang}/{c}_bNN.json), cached by the
     *  transport when it caches; works WITHOUT [attachSunnah]. The local .db
     *  surface ([attachSunnah]) remains the search-heavy offline path. */
    val sunnahBooks: SunnahBookSource = SunnahBookSource(fetcher)

    private var sunnahRepo: LocalHadithRepository? = null
    private var sunnahSearch: HadithSearchRepository? = null

    /**
     * Attach the hadith layer over locally-synced corpora
     * (`inventory/hadiths/{collection}.db` files after download).
     */
    fun attachSunnah(
        corporaRoot: File,
        scholarsDb: File? = File(corporaRoot, "scholars_info.db"),
        searchIndexDb: File? = null,
    ): SunnahApi {
        closeSunnah()
        val repo = LocalHadithRepository(corporaRoot, scholarsDb?.takeIf { it.exists() })
        val search = HadithSearchRepository(corporaRoot, searchIndexDb)
        sunnahRepo = repo
        sunnahSearch = search
        return SunnahApi(repo, search)
    }

    override fun close() {
        closeSunnah()
        quran.close()
    }

    /** Close the attached sunnah corpora without tearing down the content instance. */
    fun closeSunnahOnly() {
        closeSunnah()
    }

    private fun closeSunnah() {
        sunnahRepo?.close()
        sunnahSearch?.close()
        sunnahRepo = null
        sunnahSearch = null
    }
}

/** Quran surface: text, words, translations, metadata, navigation, layout, search, insight. */
class QuranApi internal constructor(private val fetcher: ContentFetcher) : AutoCloseable {

    private val scriptSource = QuranScriptSource(fetcher)
    private val metadata = QuranMetadataSource(fetcher)
    private val glyphs = QuranGlyphSource(fetcher)
    private val layout = MushafLayoutSource(fetcher)
    private val translations = TranslationPackSource(fetcher, catalog = TranslationCatalogSource(fetcher))
    private val similar = SimilarVersesSource(fetcher)
    private val topics = TopicsSource(fetcher)
    private val chapterInfo = ChapterInfoSource(fetcher)
    private val wbw = WbwSource(fetcher)
    private val tafsir = TafsirSource(fetcher)
    private val recitations = RecitationSource(fetcher)
    private var searchIndex: QuranSearchIndex? = null

    /** Mushaf glyph-atlas bundles feeding khushu-engine `engine-mushaf`. */
    val atlas = QuranAtlasApi(fetcher)

    // ── metadata ────────────────────────────────────────────────────────────

    suspend fun surahs(): List<Surah> = metadata.surahs()
    suspend fun surah(number: Int): Surah? = metadata.surah(number)
    suspend fun ayahMeta(surahNo: Int): List<AyahMeta> = metadata.ayahMeta(surahNo)
    suspend fun ayahMeta(surahNo: Int, ayahNo: Int): AyahMeta? = metadata.ayahMeta(surahNo, ayahNo)
    suspend fun navigation(type: NavigationType? = null): Map<NavigationType, List<NavigationUnit>> =
        metadata.navigation(type)
    suspend fun mushafs(): List<MushafInfo> = metadata.registry().second
    suspend fun scripts(): List<ScriptInfo> = metadata.registry().first
    suspend fun searchAliases(surahNo: Int, langCode: String? = null): Map<String, List<String>> =
        metadata.searchAliases(surahNo, langCode)

    // ── decorative glyphs ──────────────────────────────────────────────────

    /**
     * PUA glyph table (surah icons, juz icons, bismillah, frames, markers,
     * reference decorations) — render with the `quran_icons` font pack
     * (`fonts/available_fonts_info.json`).
     */
    suspend fun glyphTable(): QuranGlyphTable = glyphs.table()

    /** Ready-to-render surah header icon: number glyph + prefix (donor RTL order). */
    suspend fun surahIcon(surahNo: Int): String? = glyphs.surahIcon(surahNo)

    /** Ready-to-render juz icon (render with `quran_common`). */
    suspend fun juzIcon(juzNo: Int): String? = glyphs.juzIcon(juzNo)

    /** Decorated inline ayah reference ﴿s:a﴾ with donor RLM guards. */
    suspend fun ayahReference(surahNo: Int, ayahNo: Int): String = glyphs.ayahReference(surahNo, ayahNo)

    // ── text & words ────────────────────────────────────────────────────────

    /**
     * Plain ayah texts for a surah.
     * Script keys: `uthmani`, `indopak`, `kfqpc_v1` (see `inventory/quran_scripts/`).
     */
    suspend fun ayahTexts(
        surahNo: Int,
        script: String = "uthmani",
        includeAyahMarker: Boolean = false,
    ): List<AyahText> = scriptSource.ayahTexts(script, surahNo, includeAyahMarker)

    /** Word-level data; `kind` flags ayah-end marker glyphs. */
    suspend fun words(
        surahNo: Int,
        script: String = "uthmani",
        includeAyahMarker: Boolean = true,
    ): List<AyahWord> = scriptSource.words(script, surahNo, includeAyahMarker)

    // ── grouped composite ──────────────────────────────────────────────────

    /**
     * EVERYTHING about one ayah in a single call (v-next): texts in any
     * scripts, any number of translations, word-by-word rows in any
     * languages, tafsir segments from any slugs covering this ayah, and
     * per-reciter word-timing anchors. Each selection list is independent —
     * the host iterates the returned maps to render side-by-side views
     * (e.g. two translations + Arabic + one tafsir).
     *
     * Cost note: this composes the per-surah sources, so it fetches at most
     * one pack per requested item — the same lazy granularity as individual
     * calls, just batched for the host.
     */
    suspend fun ayahBundle(
        surahNo: Int,
        ayahNo: Int,
        scripts: List<String> = listOf("uthmani"),
        translationPacks: List<String> = emptyList(),
        wbwLanguages: List<String> = emptyList(),
        tafsirSlugs: List<String> = emptyList(),
        reciters: List<String> = emptyList(),
        tafsirLang: String = "en",
    ): AyahBundle {
        val texts = scripts.associateWith { script ->
            scriptSource.ayahTexts(script, surahNo)
                .firstOrNull { it.ayahNo == ayahNo }?.text ?: ""
        }
        val translations = translationPacks.associateWith { pack ->
            translations.verses(pack, surahNo)
                .firstOrNull { it.ayahNo == ayahNo }?.text ?: ""
        }
        val wbw = wbwLanguages.associateWith { lang ->
            wbw.forSurah(surahNo, lang)[ayahNo] ?: emptyMap()
        }
        val tafsirs = tafsirSlugs.associateWith { slug ->
            tafsir.forSurah(slug, surahNo, tafsirLang)
                .filter { seg -> ayahNo >= seg.fromVerse && ayahNo <= seg.toVerse }
        }
        val timings = reciters.mapNotNull { reciterId ->
            val verse = recitations.verseTimings(reciterId, surahNo)
                .firstOrNull { it.verse == ayahNo } ?: return@mapNotNull null
            reciterId to verse
        }.toMap()
        return AyahBundle(
            surahNo = surahNo,
            ayahNo = ayahNo,
            texts = texts,
            translations = translations,
            wbw = wbw,
            tafsirs = tafsirs,
            recitationTimings = timings,
        )
    }

    // ── translations ────────────────────────────────────────────────────────

    suspend fun translationPacks(langCode: String? = null): List<TranslationPackInfo> =
        translations.availablePacks(langCode)

    suspend fun translationText(packId: String, surahNo: Int? = null): List<TranslatedAyah> =
        translations.verses(packId, surahNo)

    // ── tafsir ──────────────────────────────────────────────────────────────

    /** Tafsir books are listed via [CatalogApi.tafsirs]; here: content by slug. */
    suspend fun tafsirForSurah(slug: String, surahNo: Int, lang: String = "en"): List<TafsirSegment> =
        tafsir.forSurah(slug, surahNo, lang)

    // ── word-by-word ────────────────────────────────────────────────────────

    suspend fun wbwLanguages(v2: Boolean = true): List<String> = wbw.availableLanguages(v2)

    /** WBW data of one surah: ayahNo → wordIndex (0-based) → [WbwWord]. */
    suspend fun wbwForSurah(surahNo: Int, lang: String = "en", v2: Boolean = true): Map<Int, Map<Int, WbwWord>> =
        wbw.forSurah(surahNo, lang, v2)

    suspend fun wbwWord(surahNo: Int, ayahNo: Int, wordIndex: Int, lang: String = "en", v2: Boolean = true): WbwWord? =
        wbw.word(surahNo, ayahNo, wordIndex, lang, v2)

    // ── chapter info ────────────────────────────────────────────────────────

    suspend fun chapterInfo(
        surahNo: Int,
        lang: String = "en",
        variant: ChapterInfoVariant = ChapterInfoVariant.DEFAULT,
    ): ChapterInfo? = chapterInfo.info(surahNo, lang, variant)

    suspend fun chapterInfoVariants(surahNo: Int, lang: String = "en"): List<ChapterInfoVariant> =
        chapterInfo.availableVariants(surahNo, lang)

    // ── recitations ─────────────────────────────────────────────────────────

    /** Reciter catalog (audio stays on the external host via `urlTemplate`). */
    suspend fun reciters(): List<ReciterInfo> = recitations.reciters()

    suspend fun reciter(id: String): ReciterInfo? = recitations.reciter(id)

    /** Verse- and word-level audio timing anchors of one surah. */
    suspend fun chapterTimings(reciterId: String, surahNo: Int): ChapterTimings? =
        recitations.chapterTimings(reciterId, surahNo)

    suspend fun verseTimings(reciterId: String, surahNo: Int): List<VerseTiming> =
        recitations.verseTimings(reciterId, surahNo)

    // ── mushaf layout ───────────────────────────────────────────────────────

    suspend fun pageLines(mushaf: String, page: Int): List<PageLine> = layout.pageLines(mushaf, page)

    /** Rendering script for a mushaf code (atlas bundle + word registry addressing). */
    fun mushafScript(mushafCode: String): String = layout.mushafScriptOf(mushafCode)
    suspend fun linesOfAyah(mushaf: String, surahNo: Int, ayahNo: Int): List<PageLine> =
        layout.linesOfAyah(mushaf, surahNo, ayahNo)
    suspend fun pageOfAyah(mushaf: String, surahNo: Int, ayahNo: Int): Int? =
        layout.pageOfAyah(mushaf, surahNo, ayahNo)

    /** Canonical word registry (ayah order) for a rendering script. */
    suspend fun wordRegistry(script: String): List<RegistryWord> = layout.words(script)

    // ── insight: similarity, mutashabihat, topics ───────────────────────────

    suspend fun similarTo(ayahId: Int): List<SimilarVerse> = similar.similarTo(ayahId)
    suspend fun mutashabihatPhrases(): List<MutashabihatPhrase> = similar.phrases()
    suspend fun mutashabihatOccurrences(phraseId: Int): List<MutashabihatOccurrence> =
        similar.occurrencesOf(phraseId)
    suspend fun topics(type: String? = null): List<Topic> = topics.topics(type)
    suspend fun topic(id: Int): Topic? = topics.topic(id)
    suspend fun topicBySlug(slug: String): Topic? = topics.topicBySlug(slug)
    suspend fun topicsForAyah(ayahId: Int): List<Topic> = topics.topicsForAyah(ayahId)
    suspend fun topicRelations(topicId: Int, type: String? = null): List<TopicRelation> =
        topics.relations(topicId, type)

    // ── search ──────────────────────────────────────────────────────────────

    /**
     * Arabic full-text search (FTS5 over the normalized text export). The
     * index is built on first use; pass [indexDb] to persist it across runs.
     */
    fun search(indexDb: File? = null): QuranSearchIndex =
        searchIndex ?: QuranSearchIndex(fetcher, indexDb = indexDb).also { searchIndex = it }

    override fun close() {
        searchIndex?.close()
        searchIndex = null
        scriptSource.clearCache()
        layout.clearCache()
        atlas.clearCache()
        wbw.clearCache()
        recitations.clearCache()
    }
}

/** Sunnah surface over local corpora (attached via [KhushuContent.attachSunnah]). */
class SunnahApi internal constructor(
    private val repo: LocalHadithRepository,
    private val search: HadithSearchRepository,
) {
    fun collections(lang: String = "en"): List<HadithCollection> =
        repo.installedCollections.mapNotNull { repo.collection(it, lang) }

    fun books(collectionId: String, lang: String = "en"): List<Book> = repo.books(collectionId, lang)
    fun chapters(bookId: String, lang: String = "en"): List<Chapter> = repo.chapters(bookId, lang)
    fun hadith(id: String, lang: String): Hadith? = repo.byId(id, lang)
    fun hadiths(ids: List<String>, lang: String): List<Hadith> = repo.byIds(ids, lang)
    fun hadithsForBook(bookId: String, lang: String, limit: Int = 50, offset: Int = 0): List<Hadith> =
        repo.forBook(bookId, lang, limit, offset)

    fun random(lang: String, gradeFilter: String? = null): Hadith? = repo.random(lang, gradeFilter)
    fun grades(hadithId: String, lang: String? = null): List<Grade> = repo.grades(hadithId, lang)
    fun narratorsOf(hadithId: String): List<Scholar> = repo.narratorsOf(hadithId)
    fun related(hadithId: String, lang: String): List<Pair<String, Hadith?>> = repo.related(hadithId, lang)

    fun buildSearchIndex(lang: String, force: Boolean = false): IndexBuildSummary =
        search.buildIndex(lang, force)

    fun search(query: String, lang: String, limit: Int = 20, offset: Int = 0): List<SearchResultRow> =
        search.search(query, lang, limit, offset)
}

/** Catalog surface: discovery manifests + download-state tracking. */
class CatalogApi internal constructor(fetcher: ContentFetcher) {

    private val source = CatalogSource(fetcher)
    private val tracker = SyncTracker()

    suspend fun translations(): List<CatalogEntry> = source.translations()
    suspend fun tafsirs(): List<TafsirEntry> = source.tafsirs()
    suspend fun wbw(v2: Boolean = false): List<WbwPackEntry> = source.wbw(v2)

    /** Font packs (quran_icons, sunnah text fonts) — fetch files by [FontFileEntry.path]. */
    suspend fun fonts(): List<FontPackEntry> = source.fonts()

    /** Project web links (privacy policy, help, donation) from `inventory/other/urls.json`. */
    suspend fun webLinks(): Map<String, String> = source.webLinks()

    fun pendingUpdates(entries: List<CatalogEntry>): List<DownloadState> = tracker.pendingUpdates(entries)
    fun markDownloaded(packId: String, version: Long) = tracker.markDownloaded(packId, version)
}

/**
 * Dua & dhikr surface: the lifewithallah corpus (491 duas across 30
 * subcategories, 186 reading articles, 99 Names of Allah in 11 languages).
 * Structured fields are typed; article bodies are RAW HTML passthrough —
 * hosts render them with their own markup stack (chapter-info/tafsir
 * contract). Local recitation audio mirrors ship as
 * `assets/dua_dhikr/dua_{id}.opus`.
 */
class DuaApi internal constructor(fetcher: ContentFetcher) {

    private val duas = DuaSource(fetcher)
    private val asma = AsmaSource(fetcher)

    // ── duas & dhikr ──────────────────────────────────────────────────────

    /** All 491 duas (arabic + translation + transliteration + virtue + audio). */
    suspend fun duas(): List<Dua> = duas.duas()

    /** One dua by id (1..491). */
    suspend fun dua(id: Int): Dua? = duas.dua(id)

    /** The 30 subcategory groups (12 main-adhkar + 18 other-adhkar). */
    suspend fun categories(): List<DuaCategory> = duas.categories()

    /** Duas of one subcategory slug (e.g. `morning-evening`). */
    suspend fun bySubcategory(subcategory: String): List<Dua> = duas.bySubcategory(subcategory)

    /**
     * Time-adaptive dua/adhkar sections — the "evening now, so give me evening
     * adhkar (+N action buttons)" surface. Deterministic: same [AdaptiveContext]
     * → same sections. Window math + slot table live in
     * [com.khushu.data.adaptive.SlotWindows]; rotation: strict time-slot matches
     * first (priority order), then ANYTIME backfill rotated by [AdaptiveContext.variant].
     *
     * Anchors are host-supplied (engine-derived, see khushu-orchestrator.dua.anchors);
     * null anchors → only ANYTIME sections (graceful no-location mode).
     */
    suspend fun adaptive(ctx: AdaptiveContext): List<AdaptiveDuaSection> =
        adaptiveImpl(ctx) { slug -> duas.duas().filter { it.subcategory == slug } }

    /**
     * Batch-evaluator overload: callers holding a pre-indexed corpus
     * (slug → duas, e.g. orchestrator DayModel builds evaluating many probe
     * points) skip the per-call filter entirely.
     */
    suspend fun adaptive(
        ctx: AdaptiveContext,
        contentIndex: Map<String, List<Dua>>,
    ): List<AdaptiveDuaSection> =
        adaptiveImpl(ctx) { contentIndex[it].orEmpty() }

    private suspend fun adaptiveImpl(
        ctx: AdaptiveContext,
        forSlug: suspend (String) -> List<Dua>,
    ): List<AdaptiveDuaSection> {
        val active = SlotWindows.evaluate(ctx, forSlug)
        val sections = mutableListOf<AdaptiveDuaSection>()

        // Strict time-slot matches first, priority order.
        val used = mutableListOf<DuaTimeSlot>()
        for (eval in active) {
            if (sections.size >= ctx.maxSections) break
            if (eval.slot in used) continue
            used += eval.slot
            sections += AdaptiveDuaSection(
                slot = eval.slot,
                subcategory = eval.slot.subcategory,
                title = subcategoryTitle(eval.slot.subcategory),
                count = eval.duas.size,
                window = eval.window,
            )
        }

        // ANYTIME backfill, deterministic rotation by variant.
        if (sections.size < ctx.maxSections) {
            val usedSlugs = used.map { it.subcategory }
            val pool = ANYTIME_BACKFILL.filter { it !in usedSlugs }
            if (pool.isNotEmpty()) {
                val offset = ((ctx.variant % pool.size) + pool.size) % pool.size
                for (i in 0 until minOf(ctx.maxSections - sections.size, pool.size)) {
                    val slug = pool[(offset + i) % pool.size]
                    sections += AdaptiveDuaSection(
                        slot = DuaTimeSlot.ANYTIME,
                        subcategory = slug,
                        title = subcategoryTitle(slug),
                        count = forSlug(slug).size,
                        window = null,
                    )
                }
            }
        }
        return sections
    }

    /** ANYTIME backfill rotation pool — timeless subcategories (counts verified against corpus). */
    private val ANYTIME_BACKFILL = listOf(
        "istighfar", "salawat", "praises-of-allah", "quranic-duas", "sunnah-duas",
    )

    private fun subcategoryTitle(slug: String): String = when (slug) {
        "after-salah" -> "After Salah"
        "waking-up" -> "Waking Up"
        "before-sleep" -> "Before Sleep"
        "tahajjud" -> "Tahajjud & Night"
        "morning" -> "Morning Adhkar"
        "evening" -> "Evening Adhkar"
        "istighfar" -> "Istighfar"
        "salawat" -> "Salawat"
        "praises-of-allah" -> "Praises of Allah"
        "quranic-duas" -> "Quranic Duas"
        "sunnah-duas" -> "Sunnah Duas"
        else -> slug.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    // ── reading articles (raw HTML passthrough) ───────────────────────────

    /** Article index: 12 categories → 186 entries. */
    suspend fun articleCategories(): List<DuaArticleCategory> = duas.articleCategories()

    /** Flat article list across all categories. */
    suspend fun articles(): List<DuaArticleInfo> = duas.articles()

    /** One article by its [DuaArticleInfo.filePath] — RAW HTML content. */
    suspend fun article(filePath: String): DuaArticle? = duas.article(filePath)

    // ── asma ul husna ─────────────────────────────────────────────────────

    /** The 11 available language codes for the 99 Names packs. */
    val asmaLanguages: List<String> = asma.languages

    /** One 99-Names language pack. */
    suspend fun asmaPack(lang: String): AsmaPack? = asma.pack(lang)

    /** One Name by number (1..99) in a language. */
    suspend fun asmaName(lang: String, number: Int): AsmaName? = asma.name(lang, number)

    // ── local audio mirrors (byte-identical donor Opus; Media3 on Android 12+) ──

    /** Repo-relative mirror path (`assets/dua_dhikr/dua_{id}.opus`) or null. */
    suspend fun localAudioPath(id: Int): String? = duas.localAudioPath(id)

    /** Opus bytes of the local mirror, or null for the 3 mirrorless entries. */
    suspend fun audio(id: Int): ByteArray? = duas.audio(id)
}

/**
 * Adhan audio surface: 178 donor-collected recordings catalogued in
 * `assets/adhan/adhan_index.json` (reciter/region/style parsed from
 * filenames; sha256 per file). Per-reciter permissions per LICENSE-CONTENT.
 * Playback is host-side (Media3/ExoPlayer decodes Opus on Android 12+).
 */
class AdhanApi internal constructor(fetcher: ContentFetcher) {
    private val source = com.khushu.data.adhan.AdhanSource(fetcher)

    /** Full catalog (178 entries). */
    suspend fun entries(): List<AdhanEntry> = source.entries()

    /** Grouped by reciter (137 groups) with per-group byte sizing. */
    suspend fun reciters(): List<AdhanReciter> = source.reciters()

    /** One reciter's recordings. */
    suspend fun byReciter(name: String): List<AdhanEntry> = source.byReciter(name)

    /** One catalog entry by id. */
    suspend fun entry(id: String): AdhanEntry? = source.entry(id)

    /** Opus bytes for one entry (fetches the full file — typically 0.3–1.3 MB). */
    suspend fun audio(id: String): ByteArray? = source.audio(id)

    /** Standard-style entries only (excludes Fajr-only / Eid-Takbir variants). */
    suspend fun standard(): List<AdhanEntry> = source.standard()
}

/**
 * Download/space management over the injected fetcher. Tracking requires
 * `KhushuContent(CachingFetcher(dir, fetcher))`; with a bare fetcher the
 * surface reports empty and deletes nothing (no silent surprises).
 *
 * The host picks `dir` — on Android, `context.cacheDir` (auto-managed by OS)
 * or `getExternalFilesDir` (user-visible); desktop default suggestion:
 * `~/.khushu/content-cache`. The API records/deletes/verifies; it never
 * owns storage policy.
 */
class DownloadsApi internal constructor(private val fetcher: ContentFetcher) {

    private val caching: com.khushu.data.transport.CachingFetcher?
        get() = fetcher as? com.khushu.data.transport.CachingFetcher

    /** Everything persisted, with per-category byte totals. */
    fun summary(): DownloadsSnapshot =
        caching?.downloads() ?: DownloadsSnapshot(emptyList(), 0, emptyMap())

    /** Total bytes occupied. */
    fun totalBytes(): Long = summary().totalBytes

    /** Delete by category (e.g. "inventory/tafsirs") or any predicate. */
    fun deleteWhere(predicate: (DownloadedItemView) -> Boolean): Int =
        caching?.deleteWhere(predicate) ?: 0

    /** Delete every persisted download. Returns items removed. */
    fun clearAll(): Int = caching?.clearAll() ?: 0

    /** Drop manifest rows whose files vanished (manual user clears). */
    fun reconcile(): Int = caching?.reconcile() ?: 0

    /** True when the injected fetcher supports tracking. */
    val isTracking: Boolean get() = caching != null

    // ── batch collection downloads ─────────────────────────────────────────

    /**
     * Download every file of [plan] through the caching fetcher (fetch-through-
     * cache: resume = manifest hits skipped automatically). Progress callback
     * fires per completed file (done, total). [shaVerify] additionally checks
     * each fetched payload against the pipeline ledger's sha256 — a mismatch
     * throws (corrupt transport, never silently accepted).
     *
     * Returns [CollectionResult] with per-path outcomes; failed paths are
     * collected (and safe to retry — the same plan re-run skips successes).
     */
    suspend fun download(
        plan: CollectionPlan,
        shaVerify: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): CollectionResult {
        val caching = caching
            ?: error("DownloadsApi.download requires a CachingFetcher (host injected a non-tracking transport)")
        val ledger = ledgerOrNull()
        var done = 0
        val doneLock = kotlinx.coroutines.sync.Mutex()
        val failures = mutableListOf<Pair<String, String>>()
        val fetchedBytes = AtomicLong()
        val sem = Semaphore(4)
        kotlinx.coroutines.coroutineScope {
            plan.paths.map { path ->
                async {
                    sem.withPermit {
                        try {
                            val before = path in cachingPathSet(caching)
                            val bytes = caching.fetch(path)
                            if (!before) fetchedBytes.addAndGet(bytes.size.toLong())
                            if (shaVerify) {
                                val expected = ledger?.row(path)?.sha256
                                if (expected != null) {
                                    val actual = java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(bytes).joinToString("") { "%02x".format(it) }
                                    check(actual == expected) {
                                        "sha256 mismatch for $path (expected $expected, got $actual)"
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            failures += path to (e.message ?: e::class.simpleName ?: "error")
                        }
                        val d = doneLock.withLock { ++done }
                        onProgress(d, plan.paths.size)
                    }
                }
            }.joinAll()
        }
        return CollectionResult(
            planId = plan.id,
            succeeded = plan.paths.size - failures.size,
            failedPaths = failures,
            bytesFetched = fetchedBytes.get(),
        )
    }

    /** Completion state of [plan] against the current manifest (no network). */
    fun progress(plan: CollectionPlan): CollectionProgress {
        val caching = caching ?: return CollectionProgress(plan.id, 0, plan.paths.size, 0L)
        val present = caching.downloads().items.associateBy { it.path }
        val done = plan.paths.count { it in present }
        val bytes = plan.paths.sumOf { present[it]?.bytes ?: 0L }
        return CollectionProgress(plan.id, done, plan.paths.size, bytes)
    }

    /** Delete every persisted file of [plan]; returns items removed. */
    fun delete(plan: CollectionPlan): Int {
        val paths = plan.paths.toSet()
        return deleteWhere { it.path in paths }
    }

    /** Plan factories over the ledger (null when fetcher doesn't serve it). */
    suspend fun plans(): PlanFactory? {
        val l = ledgerOrNull()
        return if (l != null) PlanFactory(fetcher, l) else null
    }

    private suspend fun ledgerOrNull(): com.khushu.data.plans.DownloadsLedger? {
        if (ledgerServed === UNSET) {
            ledgerServed = try {
                DownloadsLedger.load(fetcher)
            } catch (e: Exception) {
                null
            }
        }
        return ledgerServed as? com.khushu.data.plans.DownloadsLedger?
    }
    private var ledgerServed: Any? = UNSET
    private object UNSET

    private fun cachingPathSet(c: com.khushu.data.transport.CachingFetcher): Set<String> =
        c.downloads().items.map { it.path }.toSet()
}

/** Outcome of a batch [DownloadsApi.download]. */
data class CollectionResult(
    val planId: String,
    val succeeded: Int,
    /** path → error message; empty on full success. Retry-safe: re-run the plan. */
    val failedPaths: List<Pair<String, String>>,
    /** Bytes actually pulled from the network (cached hits excluded). */
    val bytesFetched: Long,
)

/** Snapshot of [DownloadsApi.progress] for a plan. */
data class CollectionProgress(
    val planId: String,
    val done: Int,
    val total: Int,
    val bytesPresent: Long,
)

/**
 * Islamic events DISPLAY data (`assets/islamic_calendar/islamic_events.json`
 * — exported from khushu-engine core with confidence tags). COMPUTATION stays
 * canonical in the engine (`calendar.events`); this is the localization/
 * provenance companion the engine's computed events don't carry.
 */
class IslamicEventsApi internal constructor(private val fetcher: ContentFetcher) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private var cache: List<IslamicEventEntry>? = null

    /** All event entries (title/category/hijri anchors/source/confidence). */
    suspend fun all(): List<IslamicEventEntry> = cache ?: run {
        val root = json.parseToJsonElement(
            fetcher.fetch("assets/islamic_calendar/islamic_events.json").decodeToString(),
        ).jsonObject
        val out = root["events"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            IslamicEventEntry(
                id = o["id"]!!.jsonPrimitive.content,
                title = o["title"]!!.jsonPrimitive.content,
                hijriMonth = o["hijriMonth"]!!.jsonPrimitive.int,
                hijriDay = o["hijriDay"]!!.jsonPrimitive.int,
                category = o["category"]?.jsonPrimitive?.content ?: "OTHER",
                recurrence = o["recurrence"]?.jsonPrimitive?.content ?: "ANNUAL",
                source = o["source"]?.jsonPrimitive?.content ?: "",
                confidence = o["confidence"]?.jsonPrimitive?.content ?: "",
            )
        }
        cache = out
        out
    }

    /** Entries for one hijri month (host overlays these on engine-computed dates). */
    suspend fun forHijriMonth(month: Int): List<IslamicEventEntry> =
        all().filter { it.hijriMonth == month }
}

/** One display entry from the islamic_events export. */
data class IslamicEventEntry(
    val id: String,
    val title: String,
    val hijriMonth: Int,
    val hijriDay: Int,
    val category: String,
    val recurrence: String,
    val source: String,
    val confidence: String,
)

/** Curated-content surface: exclusive verse sets, recommended recitations, science topics. */
class CuratedApi internal constructor(fetcher: ContentFetcher) {

    private val verses = CuratedContentSource(fetcher)
    private val recommended = RecommendedSource(fetcher)
    private val science = ScienceTopicsSource(fetcher)

    suspend fun exclusiveVerses(set: VerseSet, lang: String = "en") = verses.entries(set, lang)
    suspend fun exclusiveVerse(set: VerseSet, id: String, lang: String = "en") = verses.entry(set, id, lang)
    suspend fun exclusiveVerseLanguages(set: VerseSet) = verses.availableLanguages(set)
    suspend fun recommendedRules() = recommended.rules()
    suspend fun recommendedTexts(lang: String = "en") = recommended.texts(lang)
    suspend fun scienceTopics() = science.topics()
}

/**
 * Mushaf glyph-atlas surface: the rendering-data half of pixel-perfect
 * mushaf pages. Bundles ship in `inventory/atlas/{script}/{size}.zip`
 * (meta.json + layout documents + atlas.json + glyph textures). Pair with
 * khushu-engine `engine-mushaf` ([QuranAtlasApi.meta]/[layer]/[layout] map
 * 1:1 onto engine `AtlasSpec`/`GlyphPlacement`) for placement computation.
 */
class QuranAtlasApi internal constructor(fetcher: ContentFetcher) {

    private val catalogSource = AtlasCatalogSource(fetcher)
    private val bundles = AtlasBundleSource(fetcher, catalogSource)

    suspend fun bundles(): List<AtlasBundleInfo> = catalogSource.bundles()
    suspend fun bundle(id: String): AtlasBundleInfo? = catalogSource.bundle(id)

    /** Bundle typography + size manifest. */
    suspend fun meta(bundleId: String, sizeLabel: String = "6x"): AtlasMetaRoot =
        bundles.meta(bundleId, sizeLabel)

    /** Glyph texture table (rects + bearings at bundle ppem). */
    suspend fun glyphTable(bundleId: String, sizeLabel: String = "6x"): AtlasLayerRoot =
        bundles.layer(bundleId, sizeLabel)

    /** All word layout documents (document id → text + glyph placements). */
    suspend fun layoutDocuments(bundleId: String, sizeLabel: String = "6x"): AtlasLayoutRoot =
        bundles.layout(bundleId, sizeLabel)

    /** Word text → glyph placements (lookup key used by the reader pipeline). */
    suspend fun placementsByWord(bundleId: String, sizeLabel: String = "6x"): Map<String, List<AtlasGlyphPlacement>> =
        bundles.placementsByWord(bundleId, sizeLabel)

    suspend fun placementForWord(bundleId: String, wordText: String, sizeLabel: String = "6x") =
        bundles.placementForWord(bundleId, wordText, sizeLabel)

    /** One glyph texture page (PNG bytes). */
    suspend fun textureBytes(bundleId: String, textureIndex: Int, sizeLabel: String = "6x"): ByteArray =
        bundles.textureBytes(bundleId, textureIndex, sizeLabel)

    fun clearCache() = bundles.clearCache()
}
