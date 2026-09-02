package com.khushu.data

import com.khushu.data.atlas.AtlasBundleSource
import com.khushu.data.atlas.AtlasCatalogSource
import com.khushu.data.quran.MushafLayoutSource
import com.khushu.data.repo.KhushuContent
import com.khushu.data.transport.LocalFetcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

class AtlasSliceTest {

    private val repoRoot: File = checkNotNull(
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "inventory/atlas").exists() }
            // absorbed-test resolver: content corpus lives in the sibling checkout
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-quran-data/inventory/atlas").exists() }
                ?.let { File(it, "khushu-quran-data") },
    ) { "repository root with inventory/atlas not found" }

    private val fetcher = LocalFetcher(repoRoot.absolutePath.toPath())

    @Test
    fun catalogListsAllThreeBundles() = runTest {
        val catalog = AtlasCatalogSource(fetcher).bundles()
        assertEquals(listOf("dk_indopak", "dk_indopak_v2", "uthmani"), catalog.map { it.id }.sorted())
        val uthmani = catalog.first { it.id == "uthmani" }
        assertEquals(1, uthmani.sizes.size)
        assertEquals("6x", uthmani.sizes.single().label)
        assertTrue(File(repoRoot, uthmani.sizes.single().url).exists())
    }

    @Test
    fun uthmaniMetaFontMetricsMatchKnownKfgqpcValues() = runTest {
        val source = AtlasBundleSource(fetcher, AtlasCatalogSource(fetcher))
        val meta = source.meta("uthmani")
        assertEquals(2048, meta.font.unitsPerEm)
        assertEquals(2400, meta.font.ascenderFu)
        assertEquals(-1200, meta.font.descenderFu)
        assertEquals("words", meta.layout.kind)
        assertEquals("layout.json", meta.layout.file)
        val sixX = meta.sizes.single { it.label == "6x" }
        assertEquals(192, sixX.ppem)
        assertEquals(1, sixX.textures.size)
    }

    @Test
    fun uthmaniGlyphTableHasRectsAndBearings() = runTest {
        val source = AtlasBundleSource(fetcher, AtlasCatalogSource(fetcher))
        val layer = source.layer("uthmani")
        assertEquals(192, layer.ppem)
        assertTrue(layer.textures.isNotEmpty())
        val tex = layer.textures.first()
        assertTrue(tex.width in 1024..8192 && tex.height in 1024..8192)

        val sample = layer.glyphs.values.first { it.w > 0 }
        assertTrue(sample.x + sample.w <= tex.width)
        assertTrue(sample.y + sample.h <= tex.height)
        assertTrue(layer.glyphs.keys.all { it.toIntOrNull() != null })
    }

    @Test
    fun uthmaniLayoutDocumentsCoverTheWordRegistry() = runTest {
        val atlas = AtlasBundleSource(fetcher, AtlasCatalogSource(fetcher))
        val byWord = atlas.placementsByWord("uthmani")
        assertTrue(byWord.size >= 21_000, "uthmani layout documents: ${byWord.size}")
        // every document carries placements and a non-blank text key
        assertTrue(byWord.keys.all { it.isNotBlank() })
        assertTrue(byWord.values.all { it.isNotEmpty() })

        // cross-check: words of Fatihah in the canonical word registry must
        // resolve to placements in the atlas bundle
        val layout = MushafLayoutSource(fetcher)
        val words = layout.words("uthmani").take(10) // Fatihah 1:1-1:5 region
        val missing = words.filter { atlas.placementForWord("uthmani", it.text) == null }
        assertTrue(missing.isEmpty(), "registry words without atlas placements: ${missing.map { it.text }}")
    }

    @Test
    fun textureBytesArePng() = runTest {
        val atlas = AtlasBundleSource(fetcher, AtlasCatalogSource(fetcher))
        val png = atlas.textureBytes("uthmani", 0)
        assertTrue(png.size > 100_000, "texture png suspiciously small: ${png.size}")
        // PNG magic
        assertEquals(-119, png[0].toInt())
        assertEquals(0x50, png[1].toInt() and 0xFF)
        assertEquals(0x4E, png[2].toInt() and 0xFF)
        assertEquals(0x47, png[3].toInt() and 0xFF)
    }

    @Test
    fun facadeExposesAtlasThroughQuranApi() = runTest {
        KhushuContent(fetcher).use { content ->
            val bundles = content.quran.atlas.bundles()
            assertNotNull(content.quran.atlas.bundle("uthmani"))
            val meta = content.quran.atlas.meta("uthmani")
            assertEquals(bundles.first { it.id == "uthmani" }.sizes.single().ppem.toLong(), meta.sizes.single().ppem.toLong())
            val word = MushafLayoutSource(fetcher).words("uthmani").first().text
            assertNotNull(content.quran.atlas.placementForWord("uthmani", word))
        }
    }
}
