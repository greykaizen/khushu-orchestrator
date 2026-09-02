package com.khushu.data.atlas

import com.khushu.data.transport.ContentFetcher
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ── catalog (inventory/atlas/available_atlas_info.json) ────────────────────

@Serializable
data class AtlasSizeInfo(
    val label: String,
    val ppem: Int,
    val url: String,
    val version: Int = 1,
)

@Serializable
data class AtlasBundleInfo(
    val id: String,
    @SerialName("display_name")
    val displayName: String,
    /** Rendering script this bundle typesets (matches a mushaf code's script). */
    val renders: String,
    val sizes: List<AtlasSizeInfo> = emptyList(),
)

@Serializable
data class AtlasCatalogInfo(
    val version: Int,
    val atlas: List<AtlasBundleInfo>,
)

// ── bundle zip schema (generator canonical, mirrors the donor reader) ───────

@Serializable
data class AtlasFontMeta(
    @SerialName("units_per_em")
    val unitsPerEm: Int = 1000,
    @SerialName("ascender_fu")
    val ascenderFu: Int = 0,
    @SerialName("descender_fu")
    val descenderFu: Int = 0,
    @SerialName("line_gap_fu")
    val lineGapFu: Int = 0,
    @SerialName("height_fu")
    val heightFu: Int? = null,
)

@Serializable
data class AtlasMetaLayoutRef(
    val kind: String,
    val file: String,
)

@Serializable
data class AtlasTextureSlice(
    val index: Int,
    val width: Int,
    val height: Int,
    val padding: Int = 0,
    val channels: String = "L",
    val format: String = "png",
    val image: String,
)

@Serializable
data class AtlasSizeEntry(
    val label: String,
    val scale: Int = 1,
    val ppem: Int,
    val atlas: String,
    val meta: String = "",
    val textures: List<AtlasTextureSlice> = emptyList(),
)

@Serializable
data class AtlasMetaRoot(
    @SerialName("schema_version")
    val schemaVersion: Int? = null,
    val kind: String? = null,
    val font: AtlasFontMeta,
    @SerialName("base_ppem")
    val basePpem: Int = 32,
    val layout: AtlasMetaLayoutRef,
    val sizes: List<AtlasSizeEntry> = emptyList(),
)

/** One pre-rastered glyph: texture rect (px at bundle ppem) + FreeType metrics. */
@Serializable
data class AtlasGlyphEntry(
    @SerialName("atlas")
    val textureIndex: Int = 0,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    @SerialName("bearing_x")
    val bearingX: Int = 0,
    @SerialName("bearing_y")
    val bearingY: Int = 0,
    val advance: Double = 0.0,
)

@Serializable
data class AtlasLayerRoot(
    @SerialName("schema_version")
    val schemaVersion: Int? = null,
    val ppem: Int,
    val textures: List<AtlasTextureSlice>,
    val glyphs: Map<String, AtlasGlyphEntry> = emptyMap(),
    val label: String? = null,
    val scale: Int? = null,
)

/** Placement of one glyph within a word; advances/offsets in font units. */
@Serializable
data class AtlasGlyphPlacement(
    val g: Int,
    val xa: Double = 0.0,
    val ya: Double = 0.0,
    val xo: Double = 0.0,
    val yo: Double = 0.0,
)

@Serializable
data class AtlasLayoutDocument(
    val text: String,
    val glyphs: List<AtlasGlyphPlacement> = emptyList(),
    val page: Int? = null,
)

@Serializable
data class AtlasLayoutRoot(
    @SerialName("schema_version")
    val schemaVersion: Int? = null,
    val documents: Map<String, AtlasLayoutDocument>,
)

private val atlasJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Parses `inventory/atlas/available_atlas_info.json` — which glyph-atlas
 * bundles are distributed and how to reach them.
 */
class AtlasCatalogSource(
    private val fetcher: ContentFetcher,
    private val path: String = "inventory/atlas/available_atlas_info.json",
) {
    private var cached: AtlasCatalogInfo? = null

    suspend fun catalog(): AtlasCatalogInfo = cached ?: run {
        val info = atlasJson.decodeFromString<AtlasCatalogInfo>(fetcher.fetch(path).decodeToString())
        cached = info
        info
    }

    suspend fun bundles(): List<AtlasBundleInfo> = catalog().atlas

    suspend fun bundle(id: String): AtlasBundleInfo? =
        catalog().atlas.firstOrNull { it.id == id }

    suspend fun bundleForScript(scriptCode: String): AtlasBundleInfo? =
        catalog().atlas.lastOrNull { it.renders == scriptCode }
}

/**
 * One-stop access to a glyph-atlas bundle zip (`{size}.zip` containing
 * `meta.json`, the layout document file, `atlas.json` and the glyph texture
 * pages). All entries are parsed in-memory from the fetched zip bytes and
 * cached for the source's lifetime; call [clearCache] to release.
 *
 * Feeds khushu-engine's `engine-mushaf` capability ([AtlasMetaRoot] /
 * [AtlasLayerRoot] / [AtlasLayoutRoot] map 1:1 onto engine `AtlasSpec` +
 * `GlyphPlacement`), so hosts can compose mushaf pages purely in the engine
 * after pulling bytes through this source.
 */
class AtlasBundleSource(
    private val fetcher: ContentFetcher,
    private val catalog: AtlasCatalogSource,
) {
    private val zipCache = HashMap<String, ByteArray>()
    private val metaCache = HashMap<String, AtlasMetaRoot>()
    private val layerCache = HashMap<String, AtlasLayerRoot>()
    private val layoutCache = HashMap<String, AtlasLayoutRoot>()
    private val placementCache = HashMap<String, Map<String, List<AtlasGlyphPlacement>>>()

    /** Raw bundle zip bytes for [bundleId] at [sizeLabel] (default `6x`). */
    suspend fun zipBytes(bundleId: String, sizeLabel: String = "6x"): ByteArray {
        val key = "$bundleId/$sizeLabel"
        zipCache[key]?.let { return it }
        val info = catalog.bundle(bundleId) ?: error("unknown atlas bundle $bundleId")
        val size = info.sizes.firstOrNull { it.label == sizeLabel }
            ?: error("atlas bundle $bundleId has no $sizeLabel size (have: ${info.sizes.map { it.label }})")
        return fetcher.fetch(size.url).also { zipCache[key] = it }
    }

    suspend fun meta(bundleId: String, sizeLabel: String = "6x"): AtlasMetaRoot {
        val key = "$bundleId/$sizeLabel"
        metaCache[key]?.let { return it }
        val text = zipEntryText(bundleId, sizeLabel, "meta.json")
        return atlasJson.decodeFromString<AtlasMetaRoot>(text).also { metaCache[key] = it }
    }

    /** Glyph table + texture manifest (the bundle's `atlas.json`). */
    suspend fun layer(bundleId: String, sizeLabel: String = "6x"): AtlasLayerRoot {
        val key = "$bundleId/$sizeLabel"
        layerCache[key]?.let { return it }
        val m = meta(bundleId, sizeLabel)
        val atlasFile = m.sizes.firstOrNull { it.label == sizeLabel }?.atlas ?: "atlas.json"
        val text = zipEntryText(bundleId, sizeLabel, atlasFile)
        return atlasJson.decodeFromString<AtlasLayerRoot>(text).also { layerCache[key] = it }
    }

    /** All word layout documents (meta.layout.file, keyed by document id). */
    suspend fun layout(bundleId: String, sizeLabel: String = "6x"): AtlasLayoutRoot {
        val key = "$bundleId/$sizeLabel"
        layoutCache[key]?.let { return it }
        val m = meta(bundleId, sizeLabel)
        val text = zipEntryText(bundleId, sizeLabel, m.layout.file)
        return atlasJson.decodeFromString<AtlasLayoutRoot>(text).also { layoutCache[key] = it }
    }

    /**
     * Word text → glyph placements. Word texts are unique per bundle, so
     * word-text lookup matches the donor's AtlasWordShapeEntity scheme.
     */
    suspend fun placementsByWord(bundleId: String, sizeLabel: String = "6x"): Map<String, List<AtlasGlyphPlacement>> {
        val key = "$bundleId/$sizeLabel"
        placementCache[key]?.let { return it }
        val byWord = layout(bundleId, sizeLabel).documents.values
            .associate { it.text to it.glyphs }
        placementCache[key] = byWord
        return byWord
    }

    suspend fun placementForWord(
        bundleId: String,
        wordText: String,
        sizeLabel: String = "6x",
    ): List<AtlasGlyphPlacement>? = placementsByWord(bundleId, sizeLabel)[wordText]

    /** One glyph texture page (PNG bytes) out of the bundle. */
    suspend fun textureBytes(bundleId: String, textureIndex: Int, sizeLabel: String = "6x"): ByteArray {
        val layer = layer(bundleId, sizeLabel)
        val slice = layer.textures.firstOrNull { it.index == textureIndex }
            ?: error("atlas $bundleId has no texture index $textureIndex")
        return zipEntryBytes(bundleId, sizeLabel, slice.image)
    }

    fun clearCache() {
        zipCache.clear()
        metaCache.clear()
        layerCache.clear()
        layoutCache.clear()
        placementCache.clear()
    }

    private suspend fun zipEntryText(bundleId: String, sizeLabel: String, entryName: String): String =
        zipEntryBytes(bundleId, sizeLabel, entryName).decodeToString()

    private suspend fun zipEntryBytes(bundleId: String, sizeLabel: String, entryName: String): ByteArray {
        val bytes = zipBytes(bundleId, sizeLabel)
        val wanted = entryName.trimStart('/').replace('\\', '/')
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.trimStart('/').replace('\\', '/')
                if (name == wanted) return zip.readBytes()
                entry = zip.nextEntry
            }
        }
        error("atlas bundle $bundleId ($sizeLabel) is missing entry $entryName")
    }
}
