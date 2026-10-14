package com.khushu.data

import com.khushu.data.repo.KhushuContent
import com.khushu.data.store.JdbcSqlStore
import com.khushu.data.store.SqlStoreResolver
import com.khushu.data.transport.ContentFetcher
import com.khushu.data.transport.ContentMissingException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * v1.7.7 Quran layout resolver contract: with a quran-core pack store behind
 * the resolver, `pageLines` / `wordRegistry` serve SQL — even with a DEAD JSON
 * transport — and stay identical to the JSON-source contract. Atlas bundles
 * ride a separate scoped transport (file-backed in hosts).
 */
class QuranLayoutResolverTest {

    /** Dead transport: any JSON fetch fails loudly. */
    private val deadFetcher = ContentFetcher { path ->
        throw ContentMissingException(path)
    }

    private val quranPack: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/packs/quran-core.db").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/data/packs/quran-core.db").exists() }
                ?.let { File(it, "khushu-data-api/data/packs/quran-core.db") }
            ?: error("quran-core pack not found (khushu-data-api sibling checkout required)")

    /** Repo root with the atlas bundle (same discovery pattern as the dua tests). */
    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/assets/atlas/uthmani/6x.zip").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/data/assets/atlas/uthmani/6x.zip").exists() }
                ?.let { File(it, "khushu-data-api") }
            ?: error("khushu-data-api repo root not found")

    private fun content(atlasFetcher: ContentFetcher? = null) = KhushuContent(
        fetcher = null,
        resolver = SqlStoreResolver { packId -> if (packId == "quran-core") JdbcSqlStore(quranPack) else null },
        atlasFetcher = atlasFetcher,
    )

    @Test
    fun pageLinesServeFromPackWithoutAnyJsonTransport() = runTest {
        val c = content()
        val lines = c.quran.pageLines("qpc", 1)
        assertTrue("page 1 must have lines", lines.isNotEmpty())
        assertTrue("page 1 must carry ayah lines", lines.any { it.type == com.khushu.data.model.LineType.AYAH })
        c.close()
    }

    @Test
    fun wordRegistryServesFromPackWithoutAnyJsonTransport() = runTest {
        val c = content()
        val words = c.quran.wordRegistry("uthmani")
        assertEquals("registry must be the full uthmani corpus", 83_665, words.size)
        assertEquals("first word of the corpus", 1001, words.first().ayahId)
        c.close()
    }

    @Test
    fun atlasLayerRidesScopedTransportIndependentOfMainFetcher() = runTest {
        val atlasFetcher = ContentFetcher { path -> File(repoRoot, path).readBytes() }
        val c = content(atlasFetcher)
        val catalog = c.quran.atlas.bundles()
        assertTrue("catalog must list uthmani", catalog.any { it.id == "uthmani" })
        val layer = c.quran.atlas.glyphTable("uthmani")
        assertTrue("glyph table must be populated", layer.glyphs.isNotEmpty())
        assertTrue("bundle must ship texture pages", layer.textures.isNotEmpty())
        val png = c.quran.atlas.textureBytes("uthmani", layer.textures.first().index)
        assertTrue("texture PNG must be non-trivial", png.size > 10_000)
        c.close()
    }
}
