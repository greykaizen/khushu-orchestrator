package com.khushu.orchestrator

import com.khushu.data.model.LineType
import com.khushu.data.repo.KhushuContent
import kotlin.math.max
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mushaf rendering composite: data-api atlas assets × page-line maps → a
 * draw-ready, pixel-independent layout model. Donor port: QuranApp
 * ReaderItemsBuilder.buildMushafPages + QuranAtlasBundle shape resolution
 * (see docs/orchestrator-plan.md §5).
 *
 * Resolution chain (donor parity):
 *   PageLine (word-id bounds) → registry words → placementsByWord[text] →
 *   font-unit advances + glyph-table rects (texture index, bitmap px @ ppem).
 *
 * Surah-name headers and basmallah lines are TYPED DECORATION SLOTS — the
 * atlas carries text glyphs only; the host renders header art from its own
 * assets (donor parity with QuranApp's Title/Bismillah line items).
 */
class MushafNamespace internal constructor(private val o: KhushuOrchestrator) {

    /** Per-bundle memo: glyph table, word→placements, word registry — built once, reused across pages. */
    private class BundleAssets(
        val layer: com.khushu.data.atlas.AtlasLayerRoot,
        val placementsByWord: Map<String, List<com.khushu.data.atlas.AtlasGlyphPlacement>>,
        val registry: List<com.khushu.data.model.RegistryWord>,
        val unitsPerEm: Int,
    )

    private val assetsMutex = Mutex()
    private val assets = HashMap<String, BundleAssets>()

    private suspend fun bundleAssets(bundleId: String, sizeLabel: String): BundleAssets {
        val key = "$bundleId|$sizeLabel"
        assets[key]?.let { return it }
        return assetsMutex.withLock {
            assets[key]?.let { return@withLock it }
            val layer = o.data.quran.atlas.glyphTable(bundleId, sizeLabel)
            val placements = o.data.quran.atlas.placementsByWord(bundleId, sizeLabel)
            val script = o.data.quran.mushafScript(bundleId)
            val registry = o.data.quran.wordRegistry(script)
            val meta = o.data.quran.atlas.meta(bundleId, sizeLabel)
            BundleAssets(layer, placements, registry, meta.font.unitsPerEm).also { assets[key] = it }
        }
    }

    /** One glyph instance: font-unit positioning + texture-page rect. */
    data class PositionedGlyph(
        val glyphId: Int,
        val textureIndex: Int,
        /** Font-unit advances/offsets (× fontSizePx/unitsPerEm at draw time). */
        val xAdvanceFu: Double,
        val yOffsetFu: Double,
        val xOffsetFu: Double,
        /** Bitmap rect in px at the bundle's layer ppem (× fontSizePx/ppem). */
        val x: Int, val y: Int, val w: Int, val h: Int,
    )

    data class PositionedWord(
        val wordId: Int,
        val text: String,
        val glyphs: List<PositionedGlyph>,
        val widthFu: Double,
    )

    sealed interface MushafLine {
        val lineNumber: Int

        /** Decoration slot — host renders surah-header art (not an atlas glyph). */
        data class Title(override val lineNumber: Int, val chapterNo: Int) : MushafLine
        /** Decoration slot — host renders bismillah (calligraphy font). */
        data class Bismillah(override val lineNumber: Int) : MushafLine
        data class Text(
            override val lineNumber: Int,
            val centered: Boolean,
            val words: List<PositionedWord>,
            /** Measured width in font units (host scales to px). */
            val widthFu: Double,
        ) : MushafLine
    }

    /** Immutable, pixel-independent page layout. */
    data class MushafPageLayout(
        val mushafCode: String,
        val bundleId: String,
        val page: Int,
        val unitsPerEm: Int,
        val ppem: Int,
        val lines: List<MushafLine>,
    )

    /**
     * Warm the bundle assets (glyph table, layout docs) + the page-line map
     * for [pages] through the caching transport. Resumable and offline-capable;
     * later [renderablePage] calls for these pages are cache hits.
     */
    suspend fun prefetchPages(mushafCode: String, bundleId: String, pages: IntRange, sizeLabel: String = "6x") {
        o.data.quran.atlas.glyphTable(bundleId, sizeLabel)
        o.data.quran.atlas.layoutDocuments(bundleId, sizeLabel)
        for (page in pages) {
            o.data.quran.pageLines(mushafCode, page)
        }
    }

    /**
     * One mushaf page → ordered typed lines with positioned glyphs in font
     * units. Line fitting to a pixel width is host-draw-time work via engine
     * `mushaf.fitLineShrink` — this composite resolves WORDS→GLYPHS and
     * preserves donor line semantics.
     */
    suspend fun renderablePage(
        mushafCode: String,
        bundleId: String,
        page: Int,
        sizeLabel: String = "6x",
    ): MushafPageLayout {
        val a = bundleAssets(bundleId, sizeLabel)
        val lines = o.data.quran.pageLines(mushafCode, page)

        // Registry word ids are 1-based running order — direct index math.
        val out = mutableListOf<MushafLine>()
        for (line in lines) {
            when (line.type) {
                LineType.SURAH_NAME -> line.surahNo?.let {
                    out += MushafLine.Title(line.lineNumber, it)
                }
                LineType.BASMALLAH -> out += MushafLine.Bismillah(line.lineNumber)
                else -> out += resolveTextLine(line.lineNumber, line.isCentered, line, a.registry, a.placementsByWord, a.layer)
            }
        }
        return MushafPageLayout(
            mushafCode = mushafCode,
            bundleId = bundleId,
            page = page,
            unitsPerEm = a.unitsPerEm,
            ppem = a.layer.ppem,
            lines = out,
        )
    }

    private fun resolveTextLine(
        lineNumber: Int,
        centered: Boolean,
        line: com.khushu.data.model.PageLine,
        registry: List<com.khushu.data.model.RegistryWord>,
        placementsByWord: Map<String, List<com.khushu.data.atlas.AtlasGlyphPlacement>>,
        layer: com.khushu.data.atlas.AtlasLayerRoot,
    ): MushafLine.Text {
        val first = line.firstWordId ?: return MushafLine.Text(lineNumber, centered, emptyList(), 0.0)
        val last = line.lastWordId ?: first
        // 1-based running word order → registry slice.
        val words = registry.drop((first - 1).coerceAtLeast(0)).take(max(0, last - first + 1))
        var widthFu = 0.0
        val positioned = words.mapIndexed { idx, w ->
            val placements = placementsByWord[w.text].orEmpty()
            val glyphs = placements.map { p ->
                val rect = layer.glyphs[p.g.toString()]
                PositionedGlyph(
                    glyphId = p.g,
                    textureIndex = rect?.textureIndex ?: 0,
                    xAdvanceFu = p.xa,
                    yOffsetFu = p.yo,
                    xOffsetFu = p.xo,
                    x = rect?.x ?: 0, y = rect?.y ?: 0,
                    w = rect?.w ?: 0, h = rect?.h ?: 0,
                )
            }
            val wWidth = glyphs.sumOf { it.xAdvanceFu }
            widthFu += wWidth
            PositionedWord(first + idx, w.text, glyphs, wWidth)
        }
        return MushafLine.Text(lineNumber, centered, positioned, widthFu)
    }
}
