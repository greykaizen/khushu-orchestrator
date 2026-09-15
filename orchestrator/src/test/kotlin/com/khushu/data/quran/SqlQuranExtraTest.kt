package com.khushu.data.quran

import com.khushu.data.model.ChapterInfoVariant
import com.khushu.data.store.JdbcSqlStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue

class SqlQuranExtraTest {
    private val packsRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/packs/quran-core.db").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/data/packs/quran-core.db").exists() }
                ?.let { File(it, "khushu-data-api/data/packs") }
            ?: File("missing")
    private val have get() = File(packsRoot, "quran-core.db").isFile

    @Test fun glyphsRenderIdentically() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "quran-core.db")).use { s ->
            val g = SqlGlyphSource(s)
            val t = g.table()
            assertEquals(114, t.chapterBySurah.size)
            assertEquals(30, t.juzByNumber.size)
            assertTrue(t.bismillah.char.isNotBlank() && t.bismillah.codepoint.startsWith("U+"))
            assertNotNull(g.surahIcon(1))                 // number glyph + prefix
            assertNotNull(g.juzIcon(1))
            val ref = g.ayahReference(12, 3)
            assertTrue(ref.contains("12:3"), ref)
        }
    }

    @Test fun chapterInfoHtml() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "quran-core.db")).use { s ->
            val ci = SqlChapterInfoSource(s).info(1, "en", ChapterInfoVariant.DEFAULT)
            assertNotNull(ci)
            assertEquals(1, ci!!.surahNo)
            assertTrue(ci.textHtml.isNotBlank())
        }
    }

    @Test fun mushafLayoutPagesAndRegistry() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "quran-core.db")).use { s ->
            val lay = SqlMushafLayoutSource(s)
            assertEquals("uthmani", lay.mushafScriptOf("qpc"))
            // page scheme (word-id) for qpc
            val p1 = lay.pageLines("qpc", 1)
            assertTrue(p1.isNotEmpty())
            assertTrue(p1.all { it.pageNumber == 1 })
            // registry words for the uthmani script
            val words = lay.words("uthmani")
            assertTrue(words.isNotEmpty())
            assertTrue(words.first().ayahId > 0)
        }
    }

    @Test fun arabicSearch() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "quran-core.db")).use { s ->
            val hits = SqlSearchSource(s).search("الرحمن", limit = 10)
            assertTrue(hits.isNotEmpty())
            assertTrue(hits.all { it.surahNo in 1..114 })
            assertNotNull(hits.first().text)
        }
    }
}
