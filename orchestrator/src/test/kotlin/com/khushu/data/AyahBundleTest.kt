package com.khushu.data

import com.khushu.data.repo.KhushuContent
import com.khushu.data.transport.LocalFetcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

/** v-next grouped composite — one call for everything about an ayah. */
class AyahBundleTest {

    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "inventory/quran_scripts").exists() }
            // absorbed-test resolver: content corpus lives in the sibling checkout
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-quran-data/inventory/quran_scripts").exists() }
                ?.let { File(it, "khushu-quran-data") }
            ?: error("repo root not found")

    private val content = KhushuContent(LocalFetcher(repoRoot.absolutePath.toPath()))

    @Test
    fun ayahBundleComposesAllRequestedTiers() = runTest {
        val b = content.quran.ayahBundle(
            surahNo = 1, ayahNo = 1,
            scripts = listOf("uthmani", "kfqpc_v1"),
            translationPacks = listOf("en_pickthall", "en_yusuf-ali"),
            wbwLanguages = listOf("en"),
            tafsirSlugs = listOf("en-tafisr-ibn-kathir"),
        )
        // texts in both scripts
        assertEquals(2, b.texts.size)
        assertTrue(b.texts["uthmani"]!!.isNotBlank())
        assertTrue(b.texts["kfqpc_v1"]!!.isNotBlank())
        // two translations side by side
        assertEquals(2, b.translations.size)
        assertTrue(b.translations.values.all { it.isNotBlank() })
        // wbw rows for the ayah
        assertTrue(b.wbw["en"]!!.isNotEmpty())
        // tafsir segments covering 1:1
        assertTrue(b.tafsirs["en-tafisr-ibn-kathir"]!!.isNotEmpty())
    }

    @Test
    fun ayahBundleEmptySelectionsAreCheap() = runTest {
        val b = content.quran.ayahBundle(surahNo = 112, ayahNo = 1)
        assertEquals(1, b.texts.size) // default uthmani
        assertTrue(b.translations.isEmpty())
        assertTrue(b.wbw.isEmpty())
        assertTrue(b.tafsirs.isEmpty())
        assertTrue(b.recitationTimings.isEmpty())
    }

    @Test
    fun ayahBundleTafsirFiltersToCoveringSegments() = runTest {
        // 2:255 spans segments; ask for ayah 255 of surah 2
        val b = content.quran.ayahBundle(
            surahNo = 2, ayahNo = 255,
            tafsirSlugs = listOf("en-tafisr-ibn-kathir"),
        )
        val segs = b.tafsirs["en-tafisr-ibn-kathir"]!!
        assertTrue(segs.isNotEmpty())
        assertTrue(segs.all { it.fromVerse <= 255 && it.toVerse >= 255 })
    }

    @Test
    fun ayahBundleUnknownAyahYieldsEmptyStringsNotCrash() = runTest {
        val b = content.quran.ayahBundle(
            surahNo = 1, ayahNo = 99, // no such ayah in Fatihah
            translationPacks = listOf("en_pickthall"),
        )
        assertEquals("", b.texts["uthmani"])
        assertEquals("", b.translations["en_pickthall"])
    }
}
