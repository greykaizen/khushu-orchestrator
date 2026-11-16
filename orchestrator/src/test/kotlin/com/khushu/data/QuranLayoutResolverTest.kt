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
        assertTrue(lines.isNotEmpty(), "page 1 must have lines")
        assertTrue(lines.any { it.type == com.khushu.data.model.LineType.AYAH }, "page 1 must carry ayah lines")
        c.close()
    }

    @Test
    fun wordRegistryServesFromPackWithoutAnyJsonTransport() = runTest {
        val c = content()
        val words = c.quran.wordRegistry("uthmani")
        assertEquals(83_665, words.size, "registry must be the full uthmani corpus")
        assertEquals(1001, words.first().ayahId, "first word of the corpus")
        c.close()
    }

    @Test
    fun atlasLayerRidesScopedTransportIndependentOfMainFetcher() = runTest {
        val atlasFetcher = ContentFetcher { path -> File(repoRoot, path).readBytes() }
        val c = content(atlasFetcher)
        val catalog = c.quran.atlas.bundles()
        assertTrue(catalog.any { it.id == "uthmani" }, "catalog must list uthmani")
        val layer = c.quran.atlas.glyphTable("uthmani")
        assertTrue(layer.glyphs.isNotEmpty(), "glyph table must be populated")
        assertTrue(layer.textures.isNotEmpty(), "bundle must ship texture pages")
        val png = c.quran.atlas.textureBytes("uthmani", layer.textures.first().index)
        assertTrue(png.size > 10_000, "texture PNG must be non-trivial")
        c.close()
    }
}
