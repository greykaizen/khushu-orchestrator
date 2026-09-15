package com.khushu.data.catalog

import com.khushu.data.store.JdbcSqlStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue

class SqlCatalogSourceTest {
    private val packsRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/packs/content.db").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/data/packs/content.db").exists() }
                ?.let { File(it, "khushu-data-api/data/packs") }
            ?: File("missing")
    private val have get() = File(packsRoot, "content.db").isFile && File(packsRoot, "quran-core.db").isFile

    @Test fun fontsAndWebLinksAndTranslations() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "content.db")).use { content ->
            JdbcSqlStore(File(packsRoot, "quran-core.db")).use { quran ->
                val src = SqlCatalogSource(content, quran)
                val fonts = src.fonts()
                assertEquals(3, fonts.size)
                assertTrue(fonts.any { it.id == "quran_icons" && it.files.isNotEmpty() })
                assertTrue(fonts.flatMap { it.files }.any { it.weight > 0 })
                assertEquals(6, src.webLinks().size)
                assertTrue(src.translations("en").isNotEmpty())
            }
        }
    }
}
