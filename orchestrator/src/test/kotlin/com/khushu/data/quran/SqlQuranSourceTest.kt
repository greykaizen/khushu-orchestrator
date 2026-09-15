package com.khushu.data.quran

import com.khushu.data.store.JdbcSqlStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue

/** SQL-backed Quran reads against the REAL pack fragments (offline path). */
class SqlQuranSourceTest {

    private val packsRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/packs/quran-core.db").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/data/packs/quran-core.db").exists() }
                ?.let { File(it, "khushu-data-api/data/packs") }
            ?: File("missing")

    private val havePacks get() = File(packsRoot, "quran-core.db").isFile &&
        File(packsRoot, "translation-en_saheeh-international.db").isFile

    @Test fun surahsAndAyahs() = runTest {
        assumeTrue(havePacks)
        JdbcSqlStore(File(packsRoot, "quran-core.db")).use { store ->
            val src = SqlQuranSource(store)
            val surahs = src.surahs()
            assertEquals(114, surahs.size)
            assertEquals(1, surahs.first().number)
            assertEquals(7, surahs.first().ayahCount)
            assertEquals("meccan", surahs.first().revelationType)
            // 18 languages of names carried through the model
            assertEquals("Al-Fatihah", surahs.first().names["en"]?.name)
            // ayah meta: Ayat al-Kursi 2:255 -> juz 3
            val meta = src.ayahs(2).first { it.ayahNo == 255 }
            assertEquals(3, meta.juzNo)
            // uthmani text of 1:1 is non-empty
            assertTrue(src.ayahText(1, 1).isNotEmpty())
        }
    }

    @Test fun translationPackReadAndSearch() = runTest {
        assumeTrue(havePacks)
        JdbcSqlStore(File(packsRoot, "translation-en_saheeh-international.db")).use { store ->
            val t = SqlTranslationSource("en_saheeh-international", store)
            val info = t.info()
            assertNotNull(info); assertEquals("en", info!!.langCode)
            // 1:1 ayah present + text
            val a = t.ayah(1, 1)
            assertNotNull(a); assertTrue(a!!.text.contains("name", ignoreCase = true))
            assertEquals(7, t.ayahs(1).size)
            // contentless FTS5 search -> rowid join (offline-capable)
            val hits = t.search("merciful", limit = 5)
            assertTrue(hits.isNotEmpty())
            assertEquals(1, hits.first().surahNo)
        }
    }
}
