package com.khushu.data.quran

import com.khushu.data.store.JdbcSqlStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue

class SqlTafsirSourceTest {
    private val packsRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/packs/tafsir-ar-tafsir-al-tabari.db").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/data/packs/tafsir-ar-tafsir-al-tabari.db").exists() }
                ?.let { File(it, "khushu-data-api/data/packs") }
            ?: File("missing")
    private val have get() = File(packsRoot, "tafsir-ar-tafsir-al-tabari.db").isFile

    @Test fun tafsirBookAndRanges() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "tafsir-ar-tafsir-al-tabari.db")).use { s ->
            val src = SqlTafsirSource("ar-tafsir-al-tabari", s)
            val book = src.book(); assertNotNull(book); assertEquals("ar", book!!.langCode)
            val surah1 = src.forSurah(1)
            assertTrue(surah1.isNotEmpty())
            assertEquals(1, surah1.first().chapter)
            assertTrue(surah1.first().textHtml.startsWith("<p>") || surah1.first().textHtml.isNotBlank())
            assertEquals(6196, src.monolithic().size)
        }
    }
}
