package com.khushu.data.content

import com.khushu.data.store.JdbcSqlStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue

class SqlTopicsSelectionTest {
    private val packsRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/packs/quran-core.db").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/data/packs/quran-core.db").exists() }
                ?.let { File(it, "khushu-data-api/data/packs") }
            ?: File("missing")
    private val have get() = File(packsRoot, "quran-core.db").isFile

    @Test fun topicsTaxonomyAyahsRelations() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "quran-core.db")).use { s ->
            val src = SqlTopicsSource(s)
            val all = src.topics()
            assertEquals(2512, all.size)
            val withAyahs = all.first { it.ayahIds.isNotEmpty() }
            assertTrue(withAyahs.titleEn?.isNotBlank() == true || withAyahs.titleAr?.isNotBlank() == true)
            // round-trip: a linked ayah resolves back to its topic
            assertTrue(src.topicsForAyah(withAyahs.ayahIds.first()).any { it.id == withAyahs.id })
            assertNotNull(src.topicBySlug(withAyahs.slug))
        }
    }

    @Test fun similarAndMutashabihat() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "quran-core.db")).use { s ->
            val sel = SqlSelectionSource(s)
            // 1:1 (ayah_id 1001) has similar-verse matches
            val sim = sel.similarTo(1001)
            assertEquals(4, sim.size)
            assertTrue(sim.all { it.matchedAyahId > 0 })
            val phrases = sel.phrases()
            assertEquals(814, phrases.size)
            val occ = sel.occurrencesOf(phrases.first().phraseId)
            assertTrue(occ.isNotEmpty())
            assertTrue(occ.first().wordRanges.isNotEmpty())
        }
    }
}
