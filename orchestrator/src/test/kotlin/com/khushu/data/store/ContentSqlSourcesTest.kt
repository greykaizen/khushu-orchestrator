package com.khushu.data.store

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue

/** SQL-backed Asma / Events / WBW / asset resolution against the real packs. */
class ContentSqlSourcesTest {

    private val packsRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/packs/content.db").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/data/packs/content.db").exists() }
                ?.let { File(it, "khushu-data-api/data/packs") }
            ?: File("missing")

    private val have get() = File(packsRoot, "content.db").isFile && File(packsRoot, "wbw-en.db").isFile

    @Test fun assetResolverBuildsGithubUrls() {
        val r = AssetResolver()
        val url = r.url("names-audio", "names/rahman.opus")
        assertTrue(url.startsWith("https://github.com/"))
        assertTrue(url.contains("assets-names-audio-"), url)
        assertTrue(url.endsWith("names__rahman.opus"), url)
    }

    @Test fun urlForAssetLooksUpKind() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "content.db")).use { s ->
            val u = AssetResolver().urlForAsset(s, "names/rahman.opus")
            assertNotNull(u); assertTrue(u!!.contains("names-audio"), u)
        }
    }

    @Test fun asmaPackAndName() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "content.db")).use { s ->
            val src = SqlAsmaSource(s, AssetResolver())
            val pack = src.pack("en") ?: src.pack("ar")
            assertNotNull(pack)
            assertTrue(pack!!.names.isNotEmpty())
            assertEquals(pack.total, pack.names.size)
            assertNotNull(src.name(pack.langCode, 1))
            // audio resolved to a GH URL, not a bare relative path
            assertTrue(src.name(pack.langCode, 1)?.audio?.startsWith("https://") == true || src.name(pack.langCode, 1)?.audio?.contains("names__") == true)
        }
    }

    @Test fun eventsLoad() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "content.db")).use { s ->
            val ev = SqlEventsSource(s).all()
            assertEquals(11, ev.size)
            assertTrue(ev.all { it.hijriMonth in 1..12 })
        }
    }

    @Test fun wbwEnglishWordJsonParsed() = runTest {
        assumeTrue(have)
        JdbcSqlStore(File(packsRoot, "wbw-en.db")).use { s ->
            val pack = SqlWbwSource(s).langPack("en")
            assertTrue(pack.verses.isNotEmpty())
            // ayah_id 1001 = 1:1; its first word carries translation + transliteration
            val first = pack.verses[1001]?.get(0)
            assertNotNull(first)
            assertTrue(first!!.translation.isNotBlank())
        }
    }
}
