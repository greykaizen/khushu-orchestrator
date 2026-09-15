package com.khushu.data.content

import com.khushu.data.store.JdbcSqlStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue

class SqlCuratedContentTest {
    private val packsRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/packs/content.db").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/data/packs/content.db").exists() }
                ?.let { File(it, "khushu-data-api/data/packs") }
            ?: File("missing")
    private val have get() = File(packsRoot, "content.db").isFile

    @Test fun curatedEntriesRangeParsedNotCharSplit() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "content.db")).use { s ->
            val src = SqlCuratedSource(s)
            val t0 = src.entries("type0", "en")
            assertTrue(t0.isNotEmpty())
            // regression: a "2:153,3:173" style value must parse to MULTIPLE ayah refs,
            // not one-per-character (the fixed builder stores the range string whole).
            val multi = t0.first { it.ayahRefs.size > 1 }
            assertTrue(multi.ayahRefs.all { it.surah in 1..114 })
            assertTrue(multi.title?.isNotBlank() == true)
            assertNotNull(src.entry("type0", t0.first().id))
        }
    }

    @Test fun recommendedRulesAndTexts() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "content.db")).use { s ->
            val src = SqlRecommendedSource(s)
            val rules = src.rules()
            assertEquals(2, rules.size)
            val kahf = rules.first { it.id == "friday_kahf" }
            assertEquals(100, kahf.priority)
            assertTrue(kahf.clauses.isNotEmpty()); assertTrue(kahf.clauses.first().weekdays.contains(5))
            assertTrue(kahf.segments.isNotEmpty())
            val texts = src.texts("en")
            assertTrue(texts.isNotEmpty()); assertTrue(texts.values.all { it.title.isNotBlank() })
        }
    }

    @Test fun scienceTopics() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "content.db")).use { s ->
            val topics = SqlScienceSource(s).topics()
            assertEquals(12, topics.size)
            val a = topics.first { it.id == "astronomy" }
            assertEquals("astronomy.html", a.path)
            assertTrue(a.translations.isNotEmpty())
            assertEquals(16, a.referencesCount)
        }
    }
}
