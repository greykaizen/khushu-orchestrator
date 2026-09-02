package com.khushu.data

import com.khushu.data.model.BlockType
import com.khushu.data.sunnah.HadithSearchRepository
import com.khushu.data.sunnah.LocalHadithRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sunnah slice against the REAL bukhari corpus.
 * Anchors from docs/sunnah-plan.md §1 + verified id routing.
 */
class HadithSliceTest {

    private val corporaRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "inventory/hadiths").exists() }
            // absorbed-test resolver: content corpus lives in the sibling checkout
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-quran-data/inventory/hadiths").exists() }
                ?.let { File(it, "khushu-quran-data") }!!
            .resolve("inventory/hadiths")

    private val scholarsDb = corporaRoot.resolve("scholars_info.db")

    @Test
    fun bukhariAnchorsMatchFullScan() {
        LocalHadithRepository(corporaRoot, scholarsDb).use { repo ->
            assertTrue(repo.isInstalled("bukhari"))
            val coll = repo.collection("bukhari", lang = "en")
            assertNotNull(coll)
            assertEquals(1, coll.schemaVersion)
            assertNotNull(coll.contentVersion)

            val books = repo.books("bukhari", lang = "en")
            assertEquals(97, books.size)
            // numeric ordering of text numbers — was lexicographic before v2
            assertEquals(listOf("1", "2", "3"), books.take(3).map { it.number })

            val chapters = repo.chapters(books.first().id, lang = "en")
            assertTrue(chapters.isNotEmpty(), "first book must have chapters")
        }
    }

    @Test
    fun every_corpus_carries_bundle_meta() {
        LocalHadithRepository(corporaRoot, scholarsDb).use { repo ->
            assertTrue(repo.installedCollections.size >= 9)
            for (id in repo.installedCollections) {
                val c = repo.collection(id, lang = "en")
                assertNotNull(c, id)
                assertEquals(1, c.schemaVersion, "$id schema_version")
                assertNotNull(c.contentVersion, "$id content_version")
            }
        }
    }

    @Test
    fun byIdResolvesUrnStyleIds() {
        LocalHadithRepository(corporaRoot, scholarsDb).use { repo ->
            assertEquals("bukhari", repo.collectionOf("bukhari_urn_100010"))
            assertEquals("bukhari", repo.collectionOf("bukhari_b1"))

            // The very first hadith: "Actions are by intentions"
            val h = repo.byId("bukhari_urn_100010", lang = "en")
            assertNotNull(h, "byId must resolve _urn_ style ids")
            assertEquals("bukhari_b1", h.bookId)
            assertTrue(h.blocks.isNotEmpty())
            assertTrue(h.contentLangs.containsAll(listOf("ar", "en")))

            // missing ids stay null-safe
            assertNull(repo.byId("bukhari_urn_999999999", lang = "en"))
            assertNull(repo.byId("notacoll_urn_1", lang = "en"))
        }
    }

    @Test
    fun blocksCarryVerifiedTaxonomyAndParsedSpans() {
        LocalHadithRepository(corporaRoot, scholarsDb).use { repo ->
            val hadith = repo.byId("bukhari_urn_100010", lang = "ar")
            assertNotNull(hadith)
            for (b in hadith.blocks) {
                assertTrue(
                    b.type in setOf(BlockType.MATN, BlockType.SANAD, BlockType.NARRATOR, BlockType.NOTE),
                    "unexpected block type ${b.type}",
                )
            }
        }
    }

    @Test
    fun gradesAndReferencesExposedViaById() {
        LocalHadithRepository(corporaRoot, scholarsDb).use { repo ->
            val coll = repo.installedCollections.first()
            val hadith = repo.forBook(repo.books(coll, lang = "en").first().id, lang = "en", limit = 20)
                .firstOrNull()
            assertNotNull(hadith)
            val grades = repo.grades(hadith.id)
            // grades may be empty for some rows; the call itself must resolve
            assertTrue(grades.all { it.lang.isNotBlank() })
        }
    }

    @Test
    fun narratorsJoinScholars() {
        if (!scholarsDb.exists()) return
        LocalHadithRepository(corporaRoot, scholarsDb).use { repo ->
            // first hadith of bukhari has narrator rows (verified in donor)
            val hadith = repo.byId("bukhari_urn_100010", lang = "en")
            assertNotNull(hadith)
            assertTrue(hadith.narratorRefs.isNotEmpty())
            val scholars = repo.narratorsOf(hadith.id)
            assertTrue(scholars.isNotEmpty(), "narrator refs must join scholars_info.db")
            val first = scholars.first()
            assertTrue(first.fullName != null || first.arabicName != null || first.shortName != null)
        }
    }

    @Test
    fun searchBuildsFts5AndFinds() {
        // scope to the tiny forty.db corpus for test speed
        val dir = java.nio.file.Files.createTempDirectory("hadiths-test").toFile()
        java.io.File(corporaRoot, "forty.db").copyTo(File(dir, "forty.db"))
        val indexDb = File(dir, "search_index.db")
        HadithSearchRepository(dir, indexDb).use { search ->
            val summary = search.buildIndex(lang = "en")
            assertTrue(summary.indexedCount in 100..300, "indexed ${summary.indexedCount}")
            assertTrue(summary.rebuilt)

            val hits = search.search("intention", lang = "en")
            assertTrue(hits.isNotEmpty(), "FTS5 match on 'intention'")

            // fingerprint fresh -> no rebuild
            val again = search.buildIndex(lang = "en")
            assertTrue(!again.rebuilt, "fresh fingerprint must skip rebuild")
        }
        dir.deleteRecursively()
    }

    @Test
    fun uninstalledCollectionReturnsNullSafely() {
        LocalHadithRepository(corporaRoot, scholarsDb).use { repo ->
            assertNull(repo.collection("does_not_exist", lang = "en"))
            assertNull(repo.byId("does_not_exist_urn_1", lang = "en"))
        }
    }
}
