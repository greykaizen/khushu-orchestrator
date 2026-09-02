package com.khushu.data

import com.khushu.data.adhan.AdhanSource
import com.khushu.data.transport.CachingFetcher
import com.khushu.data.transport.LocalFetcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

/**
 * Adhan catalog + download tracking + dua audio + grouped bundle — anchored
 * to the real repo content.
 */
class AdhanAndDownloadsTest {

    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "assets/adhan").exists() }
            // absorbed-test resolver: content corpus lives in the sibling checkout
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-quran-data/assets/adhan").exists() }
                ?.let { File(it, "khushu-quran-data") }
            ?: error("repo root not found")

    private val fetcher = LocalFetcher(repoRoot.absolutePath.toPath())

    // ── adhan ──────────────────────────────────────────────────────────────

    @Test
    fun catalogHas178EntriesWithParsedMetadata() = runTest {
        val src = AdhanSource(fetcher)
        val entries = src.entries()
        assertEquals(178, entries.size)
        // ids unique-ish: hash-stripped stems (146 unique — some reciters have multiple files)
        assertTrue(entries.size >= entries.map { it.id }.distinct().size)
        // every entry: reciter named, real file, sha256 recorded, size plausible
        entries.forEach {
            assertTrue(it.reciter.isNotBlank(), "reciter for ${it.id}")
            assertTrue(it.sizeBytes > 10_000, "size for ${it.id}")
            assertEquals(64, it.sha256.length, "sha256 for ${it.id}")
            assertTrue(it.file.startsWith("assets/adhan/"))
        }
        // audio fetch works for a known entry
        val first = entries.first()
        val bytes = src.audio(first.id)
        assertNotNull(bytes)
        assertEquals(first.sizeBytes.toInt(), bytes.size, "full-file fetch")
    }

    @Test
    fun recitersGroupAndPlaceAdhansAreLabeled() = runTest {
        val src = AdhanSource(fetcher)
        val reciters = src.reciters()
        assertTrue(reciters.isNotEmpty())
        // place-led adhans labeled with their place, not garbage
        val haram = src.entries().first { "al_haram" in it.id && it.reciter.startsWith("Adhan") }
        assertTrue(haram.reciter.contains("Haram") || haram.reciter.contains("Mosque"), haram.reciter)
        // Fajr style detected
        assertTrue(src.entries().any { it.style == "Fajr" })
        // standard() excludes styles
        assertTrue(src.standard().all { it.style == null })
    }

    @Test
    fun outOfRangeAdhanIsNull() = runTest {
        val src = AdhanSource(fetcher)
        assertNull(src.entry("no_such_reciter"))
        assertNull(src.audio("no_such_reciter"))
    }

    // ── downloads tracking ────────────────────────────────────────────────

    @Test
    fun cachingFetcherTracksCategoriesAndDeletes() = runTest {
        val dir = File(System.getProperty("java.io.tmpdir"), "khushu-test-${System.nanoTime()}")
        val caching = CachingFetcher(dir, fetcher)

        // fetch from two different content tiers
        caching.fetch("inventory/other/urls.json")
        caching.fetch("assets/adhan/adhan_index.json")

        val snap = caching.downloads()
        assertEquals(2, snap.items.size)
        assertEquals(snap.items.sumOf { it.bytes }, snap.totalBytes)
        assertTrue(snap.bytesByCategory.containsKey("inventory/other"))
        assertTrue(snap.bytesByCategory.containsKey("assets/adhan"))

        // manifest survives a new instance (restart semantics)
        val reopened = CachingFetcher(dir, fetcher)
        assertEquals(2, reopened.downloads().items.size)

        // second fetch of the same path is served from disk, still tracked once
        caching.fetch("inventory/other/urls.json")
        assertEquals(2, caching.downloads().items.size)

        // per-category deletion
        val removed = caching.deleteWhere { it.category == "assets/adhan" }
        assertEquals(1, removed)
        assertEquals(1, caching.downloads().items.size)

        // clearAll wipes everything
        assertEquals(1, caching.clearAll())
        assertEquals(0, caching.downloads().items.size)
        dir.deleteRecursively()
    }

    @Test
    fun reconcileDropsVanishedFiles() = runTest {
        val dir = File(System.getProperty("java.io.tmpdir"), "khushu-test2-${System.nanoTime()}")
        val caching = CachingFetcher(dir, fetcher)
        caching.fetch("inventory/other/urls.json")
        assertEquals(1, caching.downloads().items.size)
        // user manually deletes files out from under us
        dir.listFiles { f -> f.name != CachingFetcher.MANIFEST_NAME }?.forEach { it.delete() }
        assertEquals(1, caching.reconcile())
        assertEquals(0, caching.downloads().items.size)
        dir.deleteRecursively()
    }

    @Test
    fun downloadsApiDegradesGracefullyWithoutCachingFetcher() = runTest {
        val content = com.khushu.data.repo.KhushuContent(fetcher)
        assertTrue(!content.downloads.isTracking)
        assertEquals(0, content.downloads.totalBytes())
        assertEquals(0, content.downloads.clearAll())
    }

    // ── dua local audio ────────────────────────────────────────────────────

    @Test
    fun duaAudioMirrorsExposed() = runTest {
        val content = com.khushu.data.repo.KhushuContent(fetcher)
        val path = content.dua.localAudioPath(1)
        assertNotNull(path, "dua 1 has a local mirror")
        assertTrue(path.endsWith("dua_1.opus"))
        val bytes = content.dua.audio(1)
        assertNotNull(bytes)
        assertTrue(bytes.size > 10_000)
        // invalid id → null (dua lookup fails first)
        assertNull(content.dua.localAudioPath(9999))
    }

    // ── islamic events display data ───────────────────────────────────────

    @Test
    fun islamicEventsExposeDisplayEntries() = runTest {
        val content = com.khushu.data.repo.KhushuContent(fetcher)
        val all = content.islamicEvents.all()
        assertTrue(all.size >= 10, "events export carries the engine's set")
        assertTrue(all.any { it.id == "ashura" || it.hijriMonth == 1 })
        val muharram = content.islamicEvents.forHijriMonth(1)
        assertTrue(muharram.isNotEmpty())
        assertTrue(muharram.all { it.hijriMonth == 1 })
    }
}
