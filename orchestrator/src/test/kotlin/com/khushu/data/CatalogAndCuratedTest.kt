package com.khushu.data

import com.khushu.data.catalog.CatalogParser
import com.khushu.data.content.CuratedContentSource
import com.khushu.data.content.RecommendedSource
import com.khushu.data.content.ScienceTopicsSource
import com.khushu.data.content.SimilarVersesSource
import com.khushu.data.content.TopicsSource
import com.khushu.data.content.VerseSet
import com.khushu.data.repo.KhushuContent
import com.khushu.data.transport.LocalFetcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

/** Catalogs + curated/insight content against REAL repo files. */
class CatalogAndCuratedTest {

    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "inventory/quran_scripts").exists() }
            // absorbed-test resolver: content corpus lives in the sibling checkout
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-quran-data/inventory/quran_scripts").exists() }
                ?.let { File(it, "khushu-quran-data") }
            ?: error("repo root not found")

    private val fetcher = LocalFetcher(repoRoot.absolutePath.toPath())

    // ── catalogs ────────────────────────────────────────────────────────────

    @Test
    fun translationCatalogParsesAllPacks() = runTest {
        val text = File(repoRoot, "inventory/translations/available_translations_info.json").readText()
        val entries = CatalogParser.parseTranslations(text)
        assertEquals(48, entries.size)
        assertTrue(entries.all { it.langCode != null })
        // urls are repo-relative — the supply-chain rewrite (no ghraw:// anywhere)
        assertTrue(entries.all { it.url?.startsWith("inventory/") == true })
        assertTrue(entries.any { it.id == "en_abdul-haleem" })
    }

    @Test
    fun tafsirCatalogParses() = runTest {
        val text = File(repoRoot, "inventory/tafsirs/available_tafsirs_info.json").readText()
        val entries = CatalogParser.parseTafsirs(text)
        assertTrue(entries.size >= 20, "tafsirs: ${entries.size}")
        assertTrue(entries.any { it.slug == "en-tafisr-ibn-kathir" && it.langCode == "en" })
    }

    @Test
    fun wbwCatalogsParseWithRepoRelativeUrls() = runTest {
        val v1 = CatalogParser.parseWbw(File(repoRoot, "inventory/wbw/available_wbw_info.json").readText())
        assertEquals(14, v1.size)
        assertTrue(v1.all { it.url?.startsWith("inventory/wbw/packs/") == true }, v1.map { it.url }.toString())

        val v2 = CatalogParser.parseWbw(File(repoRoot, "inventory/wbw/available_wbw_info_v2.json").readText())
        assertEquals(14, v2.size)
        assertTrue(v2.all { it.url?.startsWith("inventory/wbw/packs_v2/") == true })
    }

    @Test
    fun fontsCatalogParsesWithAllDonorFonts() = runTest {
        val text = File(repoRoot, "inventory/fonts/available_fonts_info.json").readText()
        val packs = CatalogParser.parseFonts(text)
        assertEquals(3, packs.size)

        val icons = packs.first { it.id == "quran_icons" }
        assertEquals(2, icons.files.size)
        assertTrue(icons.files.any { it.id == "suracon" && it.path == "inventory/fonts/quran_icons/suracon.ttf" })
        assertTrue(icons.files.any { it.id == "quran_common" })

        val quranText = packs.first { it.id == "quran_text" }
        assertEquals(1, quranText.files.size)
        assertTrue(quranText.files.any { it.id == "uthmanic_hafs" })

        val sunnah = packs.first { it.id == "sunnah" }
        assertEquals(6, sunnah.files.size)
        assertTrue(sunnah.files.any { it.id == "kfgqpc_uthman_taha_naskh" && it.weight == 400 })
        assertTrue(sunnah.files.any { it.id == "kfgqpc_uthman_taha_naskh_bold" && it.weight == 700 })
        assertTrue(sunnah.files.any { it.id == "noto_nastaliq_urdu" })
        assertTrue(sunnah.files.any { it.id == "scheherazadenew" })

        // every cataloged file actually exists in the inventory
        packs.flatMap { it.files }.forEach { f ->
            assertTrue(File(repoRoot, f.path).exists(), "missing font file: ${f.path}")
        }
    }

    @Test
    fun recitationTimingUrlsAreRepoRelative() = runTest {
        val text = File(repoRoot, "inventory/recitations/available_recitations_info_v2.json").readText()
        assertTrue(!text.contains("ghraw://"), "all timing urls must be mirrored repo-relative")
    }

    // ── similar verses / mutashabihat ───────────────────────────────────────

    @Test
    fun similarVersesResolve() = runTest {
        val src = SimilarVersesSource(fetcher = fetcher)
        val pairs = src.pairs()
        assertTrue(pairs.isNotEmpty())
        val forFatiha1 = src.similarTo(1001)
        assertTrue(forFatiha1.isNotEmpty(), "1:1 has known similar verses")
        assertTrue(forFatiha1.all { it.coverage in 0..100 && it.score in 0..100 })
    }

    @Test
    fun mutashabihatPhrasesAndOccurrences() = runTest {
        val src = SimilarVersesSource(fetcher = fetcher)
        val phrases = src.phrases()
        assertEquals(814, phrases.size)
        val p = phrases.first()
        val occ = src.occurrencesOf(p.phraseId)
        assertTrue(occ.isNotEmpty())
    }

    // ── topics ──────────────────────────────────────────────────────────────

    @Test
    fun topicTaxonomyLoads() = runTest {
        val src = TopicsSource(fetcher = fetcher)
        val all = src.topics()
        assertEquals(2512, all.size)

        val anxiety = src.topicBySlug("anxiety")
        assertNotNull(anxiety)
        assertTrue(anxiety.ayahIds.isNotEmpty(), "topics must carry ayah links")
        assertNotNull(anxiety.titleEn)

        val byAyah = src.topicsForAyah(1001)
        assertTrue(byAyah.isNotEmpty(), "Fatiha v1 is linked to topics")

        val rels = src.relations(1)
        // topic 1 ('allah') has thematic/ontology parents in the export
        assertTrue(rels.all { it.type.isNotBlank() })
        // image urls are repo-relative after mirror rewrite
        val withImage = all.firstOrNull { it.imageUrl != null }
        assertNotNull(withImage)
        assertTrue(withImage.imageUrl!!.startsWith("inventory/topics/images/"), withImage.imageUrl)
    }

    // ── curated verse sets ──────────────────────────────────────────────────

    @Test
    fun exclusiveVerseSetsCarryRefsAndLocalizedTitles() = runTest {
        val src = CuratedContentSource(fetcher = fetcher)
        val solutions = src.entries(VerseSet.SOLUTION, lang = "en")
        assertTrue(solutions.size >= 30, "type0 entries: ${solutions.size}")
        val anxiety = solutions.firstOrNull { it.title?.contains("Anxiety") == true }
        assertNotNull(anxiety, "expected an 'Anxiety' entry in SOLUTION set")
        assertTrue(anxiety.ayahRefs.isNotEmpty())
        assertEquals(solutions.size, src.refs(VerseSet.SOLUTION).size)

        val majorSins = src.entries(VerseSet.MAJOR_SINS, lang = "en")
        assertTrue(majorSins.size >= 50, "major sins entries: ${majorSins.size}")
        assertTrue(majorSins.all { it.ayahRefs.isNotEmpty() }, "every major sin needs evidence refs")
    }

    @Test
    fun recommendedRulesAndTextsLoad() = runTest {
        val rec = RecommendedSource(fetcher = fetcher)
        val rules = rec.rules()
        assertTrue(rules.any { it.id == "friday_kahf" })
        val night = rules.first { it.id == "night" }
        assertTrue(night.segments.isNotEmpty())
        assertTrue(night.segments.all { it.verseRefs.isNotEmpty() || it.langKey != null })

        val texts = rec.texts("en")
        assertTrue(texts.containsKey("night_mulk"))
        assertTrue(texts["night_mulk"]!!.title.isNotBlank())
    }

    @Test
    fun scienceTopicsLoad() = runTest {
        val sci = ScienceTopicsSource(fetcher = fetcher)
        val topics = sci.topics()
        assertTrue(topics.size >= 10)
        assertTrue(topics.any { it.id == "astronomy" })
    }

    // ── facade: catalogs + curated ──────────────────────────────────────────

    @Test
    fun facadeCatalogAndCuratedWire() = runTest {
        KhushuContent(fetcher).use { content ->
            assertEquals(48, content.catalogs.translations().size)
            assertTrue(content.catalogs.tafsirs().isNotEmpty())
            assertEquals(14, content.catalogs.wbw().size)
            assertTrue(content.curated.exclusiveVerses(VerseSet.DUA, "en").isNotEmpty())
            assertTrue(content.curated.recommendedRules().isNotEmpty())
            assertTrue(content.curated.scienceTopics().isNotEmpty())
        }
    }
}
