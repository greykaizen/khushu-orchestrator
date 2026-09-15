package com.khushu.data.sunnah

import com.khushu.data.store.JdbcSqlStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue

/** SQL-backed hadith reads against the real bukhari + scholars packs. */
class SqlHadithSourceTest {
    private val packsRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/packs/hadith-bukhari.db").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/data/packs/hadith-bukhari.db").exists() }
                ?.let { File(it, "khushu-data-api/data/packs") }
            ?: File("missing")
    private val have get() = File(packsRoot, "hadith-bukhari.db").isFile && File(packsRoot, "hadith-scholars.db").isFile

    @Test fun collectionBooksChaptersHadith() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "hadith-bukhari.db")).use { s ->
            val src = SqlHadithSource("bukhari", s)
            val coll = src.collectionInfo("en"); assertNotNull(coll)
            assertEquals(1, coll!!.schemaVersion); assertNotNull(coll.contentVersion); assertTrue(coll.hasBooks)
            assertEquals(97, src.books("en").size)
            val b1 = src.books("en").first { it.id == "bukhari_b1" }
            assertEquals(7, src.hadithIdsForBook("bukhari_b1", limit = 50).size)
            val h = src.byId("bukhari_urn_100010", "en"); assertNotNull(h)
            assertTrue(h!!.blocks.isNotEmpty()); assertTrue(h.narratorRefs.isNotEmpty())
            assertTrue(h.contentLangs.containsAll(listOf("ar", "en")))
            // external-content FTS5 search -> rowid -> hadith
            assertTrue(src.search("Allah", "en", limit = 5).isNotEmpty())
        }
    }

    @Test fun scholarsById() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "hadith-scholars.db")).use { s ->
            val sc = SqlScholarSource(s).byId(1)
            assertNotNull(sc); assertEquals(1L, sc!!.id); assertTrue(sc.shortName!!.contains("Prophet"))
        }
    }
}
