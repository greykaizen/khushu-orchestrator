package com.khushu.data

import com.khushu.data.model.ChapterInfoVariant
import com.khushu.data.repo.KhushuContent
import com.khushu.data.transport.LocalFetcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

class ContentTiersTest {

    private val repoRoot: File = checkNotNull(
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "inventory/quran_scripts").exists() }
            // absorbed-test resolver: content corpus lives in the sibling checkout
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-quran-data/inventory/quran_scripts").exists() }
                ?.let { File(it, "khushu-quran-data") },
    ) { "repository root not found" }

    private val fetcher = LocalFetcher(repoRoot.absolutePath.toPath())

    // ── chapter info ────────────────────────────────────────────────────────

    @Test
    fun chapterInfoCarriesHtmlBodyForBothVariants() = runTest {
        KhushuContent(fetcher).use { content ->
            val fatiha = content.quran.chapterInfo(1)
            assertNotNull(fatiha)
            assertEquals(1, fatiha.surahNo)
            assertEquals("en", fatiha.langCode)
            assertTrue(fatiha.textHtml.contains("<p>"), "expected HTML body")
            assertNotNull(fatiha.source)

            val variants = content.quran.chapterInfoVariants(1)
            assertTrue(variants.contains(ChapterInfoVariant.DEFAULT))
            assertTrue(variants.contains(ChapterInfoVariant.MAUDUDI))
            val maududi = content.quran.chapterInfo(1, variant = ChapterInfoVariant.MAUDUDI)
            assertNotNull(maududi)
            assertTrue(maududi.source!!.contains("Maududi"))
        }
    }

    // ── word-by-word ────────────────────────────────────────────────────────

    @Test
    fun wbwCoversFatihahWithTranslationAndTransliteration() = runTest {
        KhushuContent(fetcher).use { content ->
            assertTrue(content.quran.wbwLanguages().contains("en"))
            val fatiha = content.quran.wbwForSurah(1)
            assertEquals(7, fatiha.size)

            val v1 = fatiha[1]!!
            assertTrue(v1.size >= 4, "Fatiha 1:1 word count ${v1.size}")
            assertTrue(v1[0]!!.translation.contains("name", ignoreCase = true))
            assertNotNull(v1[0]!!.transliteration)

            // the last entry of 1:1 is the ayah-end marker "(1)"
            val marker = v1[v1.keys.max()!!]!!
            assertTrue(marker.translation.contains("1"))

            val direct = content.quran.wbwWord(2, 255, 0)
            assertNotNull(direct) // Ayat al-Kursi exists in the pack
        }
    }

    @Test
    fun wbwWordIndicesAlignWithScriptPacks() = runTest {
        KhushuContent(fetcher).use { content ->
            // uthmani 1:1 is 4 words + marker = 5 entries; wbw must match
            val words = content.quran.words(1, "uthmani")
            val v1Words = words.filter { it.ayahNo == 1 }
            val wbw = content.quran.wbwForSurah(1)[1]!!
            assertEquals(v1Words.size, wbw.size, "wbw/script word count mismatch 1:1")
        }
    }

    // ── tafsir ──────────────────────────────────────────────────────────────

    @Test
    fun tafsirSplitsServePerSurah() = runTest {
        KhushuContent(fetcher).use { content ->
            val books = content.catalogs.tafsirs()
            val kathir = books.first { it.slug == "en-tafisr-ibn-kathir" }
            assertEquals("en", kathir.langCode)

            val fatiha = content.quran.tafsirForSurah(kathir.slug, 1)
            assertTrue(fatiha.isNotEmpty())
            assertTrue(fatiha.all { it.chapter == 1 })
            assertTrue(fatiha.sumOf { it.textHtml.length } > 1_000)
            // verse ranges stay inside the surah
            assertTrue(fatiha.all { it.toVerse <= 7 })
        }
    }

    // ── recitations ─────────────────────────────────────────────────────────

    @Test
    fun recitationTimingsAnchorVersesAndWords() = runTest {
        KhushuContent(fetcher).use { content ->
            val reciters = content.quran.reciters()
            assertTrue(reciters.size >= 15, "reciters: ${reciters.size}")
            val alafasy = content.quran.reciter("al_afasy")
            assertNotNull(alafasy)
            assertNotNull(alafasy.urlTemplate)
            assertNotNull(alafasy.timingUrl)

            val ch1 = content.quran.chapterTimings("al_afasy", 1)
            assertNotNull(ch1)
            assertEquals(7, ch1.verses.size)
            // anchors are chronological
            ch1.verses.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs) }
            // word segments cover the verse span
            val v1 = ch1.verses.first()
            assertTrue(v1.segments.isNotEmpty())
            assertTrue(v1.segments.first().startMs >= v1.startMs - 1)
            assertTrue(v1.segments.last().endMs <= v1.endMs + 1)
        }
    }
}
