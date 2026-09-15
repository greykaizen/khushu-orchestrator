package com.khushu.data.dua

import com.khushu.data.adhan.SqlAdhanSource
import com.khushu.data.quran.SqlRecitationSource
import com.khushu.data.store.AssetResolver
import com.khushu.data.store.JdbcSqlStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue

/** SQL-backed Dua / Adhan / Recitation against the real packs (device parity via Jdbc). */
class SqlDuaAdhanRecitationTest {

    private val packsRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/packs/content.db").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/data/packs/content.db").exists() }
                ?.let { File(it, "khushu-data-api/data/packs") }
            ?: File("missing")

    private val have get() = File(packsRoot, "content.db").isFile && File(packsRoot, "audio.db").isFile

    @Test fun duasLoad() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "content.db")).use { s ->
            val src = SqlDuaSource(s, AssetResolver())
            val duas = src.duas()
            assertEquals(491, duas.size)
            assertTrue(duas.first().arabic.isNotBlank())
            // audioUrl resolved to a GH mirror URL (not a bare relative path)
            val withAudio = duas.first { it.audioUrl != null }
            assertTrue(withAudio.audioUrl!!.startsWith("https://"), withAudio.audioUrl ?: "")
            assertEquals(1, src.dua(1)?.id)
            assertTrue(src.categories().isNotEmpty())
            assertTrue(src.bySubcategory(duas.first().subcategory).isNotEmpty())
        }
    }

    @Test fun adhanCatalog() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "audio.db")).use { s ->
            val src = SqlAdhanSource(s)
            assertEquals(178, src.entries().size)
            assertEquals(166, src.standard().size)
            assertTrue(src.reciters().isNotEmpty())
            val e = src.entries().first()
            assertTrue(e.file.startsWith("assets/"), e.file)
            assertTrue(e.sha256.length == 64, e.sha256)
            assertNotNull(src.entry(e.id))
        }
    }

    @Test fun recitationIndexAndTimings() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "audio.db")).use { s ->
            val src = SqlRecitationSource(s)
            val reciters = src.reciters()
            assertEquals(18, reciters.size)
            val r = reciters.first()
            assertTrue(r.urlTemplate!!.contains("quranicaudio"), r.urlTemplate ?: "")
            val timings = src.timings(r.id)
            assertEquals(114, timings.size)
            val alFatihah = src.chapterTimings(r.id, 1)!!
            assertEquals(1, alFatihah.chapter)
            assertTrue(alFatihah.verses.isNotEmpty())
            assertEquals(1, alFatihah.verses.first().verse)
            assertTrue(alFatihah.verses.first().segments.isNotEmpty())
        }
    }
}
