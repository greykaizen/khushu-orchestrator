package com.khushu.data

import com.khushu.data.model.LineType
import com.khushu.data.model.NavigationType
import com.khushu.data.model.WordKind
import com.khushu.data.quran.MushafLayoutSource
import com.khushu.data.quran.QuranMetadataSource
import com.khushu.data.quran.QuranScriptSource
import com.khushu.data.quran.QuranSearchIndex
import com.khushu.data.quran.TranslationCatalogSource
import com.khushu.data.quran.TranslationPackSource
import com.khushu.data.repo.KhushuContent
import com.khushu.data.transport.LocalFetcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

/** Quran slice against REAL repo content — anchored to full-corpus facts. */
class QuranSliceTest {

    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "inventory/quran_scripts").exists() }
            // absorbed-test resolver: content corpus lives in the sibling checkout
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-quran-data/inventory/quran_scripts").exists() }
                ?.let { File(it, "khushu-quran-data") }
            ?: error("repo root not found")

    private val fetcher = LocalFetcher(repoRoot.absolutePath.toPath())

    // ── script packs ────────────────────────────────────────────────────────

    @Test
    fun fatihaWordsAreWordLevelWithMarkerClassification() = runTest {
        val source = QuranScriptSource(fetcher = fetcher)
        val words = source.words("uthmani", 1)
        assertEquals(1, words.first().surahNo)
        assertEquals(1, words.first().ayahNo)
        assertTrue(words.first().location.startsWith("1:1:"))
        assertEquals(WordKind.TEXT, words.first().kind)
        assertEquals(WordKind.AYAH_END_MARKER, words.last().kind)

        val texts = source.ayahTexts("uthmani", 1)
        assertEquals(7, texts.size)

        fun stripMarks(s: String) = java.text.Normalizer
            .normalize(s.replace('ٱ', 'ا'), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        assertTrue(stripMarks(texts[0].text).contains("الرحمن"), "fatihah v1: ${texts[0].text}")
        // default excludes markers
        assertTrue(texts.none { it.text.contains('١') })
    }

    @Test
    fun baqarahWordCountInExpectedBand() = runTest {
        val source = QuranScriptSource(fetcher = fetcher)
        val words = source.words("uthmani", 2)
        assertTrue(words.size in 5_000..10_000, "al-Baqarah word count ${words.size}")
    }

    @Test
    fun indopakAndMonolithicKfqpcResolve() = runTest {
        val source = QuranScriptSource(fetcher = fetcher)
        assertEquals(7, source.ayahTexts("indopak", 1).size)
        assertEquals(7, source.ayahTexts("kfqpc_v1", 1).size) // monolithic file path
        assertEquals(6, source.ayahTexts("kfqpc_v1", 114).size) // an-Nas via monolithic
    }

    // ── metadata ────────────────────────────────────────────────────────────

    @Test
    fun surahRegistryIsCompleteAndNamed() = runTest {
        val meta = QuranMetadataSource(fetcher = fetcher)
        val surahs = meta.surahs()
        assertEquals(114, surahs.size)
        assertEquals(6236, surahs.sumOf { it.ayahCount })

        val fatiha = surahs.first()
        assertEquals(7, fatiha.ayahCount)
        assertEquals("meccan", fatiha.revelationType)
        assertEquals("Al-Fatihah", fatiha.names["en"]?.name)
        assertEquals("The Opening", fatiha.names["en"]?.meaning)

        val anNas = surahs.last()
        assertEquals(114, anNas.number)
        assertEquals(6, anNas.ayahCount)
    }

    @Test
    fun ayahMetaCarriesNavigationNumbers() = runTest {
        val meta = QuranMetadataSource(fetcher = fetcher)
        val all = meta.ayahs()
        assertEquals(6236, all.size)
        assertEquals(1001, all.first().ayahId)
        assertEquals(30, all.maxOf { it.juzNo })
        assertEquals(60, all.maxOf { it.hizbNo })
        assertEquals(240, all.maxOf { it.rubNo })
        assertEquals(7, all.maxOf { it.manzilNo })

        // 2:255 = Ayat al-Kursi, juz 3
        val kursi = meta.ayahMeta(2, 255)
        assertNotNull(kursi)
        assertEquals(3, kursi.juzNo)
    }

    @Test
    fun navigationUnitsCoverTheWholeMushaf() = runTest {
        val meta = QuranMetadataSource(fetcher = fetcher)
        val nav = meta.navigation()
        assertEquals(30, nav[NavigationType.JUZ]!!.size)
        assertEquals(60, nav[NavigationType.HIZB]!!.size)
        assertEquals(240, nav[NavigationType.RUB]!!.size)
        assertEquals(7, nav[NavigationType.MANZIL]!!.size)

        val juz2 = nav[NavigationType.JUZ]!!.first { it.number == 2 }
        assertEquals(2, juz2.segments.first().surahNo)
        assertEquals(142, juz2.segments.first().fromAyah)
    }

    @Test
    fun mushafRegistryMatchesDonorPageCounts() = runTest {
        val meta = QuranMetadataSource(fetcher = fetcher)
        val (_, mushafs) = meta.registry()
        assertEquals(5, mushafs.size)
        val qpc = mushafs.first { it.code == "qpc" }
        assertEquals(604, qpc.pageCount)
        assertEquals(15, qpc.linesPerPage)
        assertTrue(qpc.layoutSources.containsAll(listOf("mushaf_map", "page_info")))
        val i13 = mushafs.first { it.code == "indopak_13" }
        assertEquals(847, i13.pageCount)
        assertEquals(listOf("page_info"), i13.layoutSources)
    }

    // ── layout ──────────────────────────────────────────────────────────────

    @Test
    fun qpcPageOneLinesMatchKnownLayout() = runTest {
        val layout = MushafLayoutSource(fetcher = fetcher)
        val lines = layout.pageLines("qpc", 1)
        // donor page_info ships only populated lines: page 1 = surah_name + Fatihah's 7 ayahs
        assertEquals(8, lines.size)
        assertEquals(LineType.SURAH_NAME, lines.first().type)
        // Fatiha's basmallah IS verse 1, so the first text line holds words 1..5
        val v1 = lines.first { it.firstWordId == 1 }
        assertEquals(5, v1.lastWordId)
        assertEquals(LineType.AYAH, v1.type)
        // from surah 2 onward the basmallah gets its own line type
        assertTrue(layout.pageLines("qpc", 2).any { it.type == LineType.BASMALLAH })
    }

    @Test
    fun pageOfAyahResolvesBothAddressingSchemes() = runTest {
        val layout = MushafLayoutSource(fetcher = fetcher)
        // qpc: 2:255 famously on page 42 (Ayat al-Kursi page) via mushaf_map or words
        val page = layout.pageOfAyah("qpc", 2, 255)
        assertNotNull(page)
        assertTrue(page in 2..604)
        // kfqpc_v1 is mushaf_map-only
        val kPage = layout.pageOfAyah("kfqpc_v1", 1, 1)
        assertNotNull(kPage)
        assertEquals(1, kPage)
    }

    @Test
    fun wordRegistryCoversAllAyahs() = runTest {
        val layout = MushafLayoutSource(fetcher = fetcher)
        val words = layout.words("uthmani")
        assertEquals(83_665, words.size)
        assertEquals(1001, words.first().ayahId)
        assertEquals(114_006, words.last().ayahId)
        // first word of Fatiha is the (diacriticized) bismillah opening
        fun norm(s: String) = java.text.Normalizer
            .normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        assertEquals("بسم", norm(words.first().text).filterNot { it.isWhitespace() })
    }

    // ── translations ────────────────────────────────────────────────────────

    @Test
    fun translationPacksParseFromFlatFiles() = runTest {
        val catalog = TranslationCatalogSource(fetcher = fetcher)
        val en = catalog.packs("en")
        assertTrue(en.size >= 9, "en packs: ${en.map { it.id }}")

        val src = TranslationPackSource(fetcher = fetcher, catalog = catalog)
        val fatiha = src.verses("en_abdul-haleem", surahNumber = 1)
        assertEquals(7, fatiha.size)
        assertTrue(fatiha[0].text.contains("God", ignoreCase = true), fatiha[0].text)
    }

    // ── search ──────────────────────────────────────────────────────────────

    @Test
    fun arabicSearchFindsByNormalizedText() = runTest {
        QuranSearchIndex(fetcher = fetcher).use { idx ->
            val built = idx.build()
            assertEquals(6236, built)
            // normalized text has no diacritics: search plain form
            val hits = idx.search("الرحمن الرحيم", limit = 5)
            assertTrue(hits.isNotEmpty())
            assertTrue(hits.any { it.surahNo == 1 })
        }
    }

    // ── facade smoke ────────────────────────────────────────────────────────

    @Test
    fun khushuContentFacadeWiresEverything() = runTest {
        KhushuContent(fetcher).use { content ->
            assertEquals(114, content.quran.surahs().size)
            assertEquals(7, content.quran.ayahTexts(1).size)
            assertTrue(content.quran.translationPacks("en").size >= 9)
            val nav = content.quran.navigation(NavigationType.JUZ)
            assertEquals(30, nav[NavigationType.JUZ]!!.size)
            assertTrue(content.quran.similarTo(1001).isNotEmpty())
            assertTrue(content.quran.topics().size > 2000)
        }
    }
}
