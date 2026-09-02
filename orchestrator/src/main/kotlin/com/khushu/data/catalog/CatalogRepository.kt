package com.khushu.data.catalog

import com.khushu.data.transport.ContentFetcher
import com.khushu.data.model.CatalogEntry
import com.khushu.data.model.DownloadState
import com.khushu.data.model.DownloadStatus
import com.khushu.data.model.FontFileEntry
import com.khushu.data.model.FontPackEntry
import com.khushu.data.model.TafsirEntry
import com.khushu.data.model.WbwPackEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parsers for the real catalog manifest shapes (see docs/formats.md):
 *
 * - translations: `{translations: {lang: {packId: {book, author, ...}}}}`
 * - tafsirs:      `{tafsirs: {lang: [{key, name, author, ...}]}}`
 * - wbw (v1/v2):  `{version, wbw: [{id, lang_code, url, version, ...}]}`
 *
 * All `url`/`downloadPath` fields are repo-root-relative paths; hosts prefix
 * their base URL. (External audio stays on templated hosts, see recitations.)
 */
object CatalogParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parseTranslations(jsonText: String): List<CatalogEntry> {
        val root = json.parseToJsonElement(jsonText).jsonObject["translations"]?.jsonObject ?: return emptyList()
        val out = mutableListOf<CatalogEntry>()
        for ((lang, packs) in root) {
            for ((packId, el) in packs.jsonObject) {
                val o = el.jsonObject
                out += CatalogEntry(
                    id = packId,
                    langCode = o.str("langCode") ?: lang,
                    displayName = o.str("displayName") ?: o.str("book") ?: packId,
                    version = o["version"]?.jsonPrimitive?.longOrNull ?: 1L,
                    url = o.str("downloadPath"),
                    author = o.str("author"),
                )
            }
        }
        return out
    }

    fun parseTafsirs(jsonText: String): List<TafsirEntry> {
        val root = json.parseToJsonElement(jsonText).jsonObject["tafsirs"]?.jsonObject ?: return emptyList()
        val out = mutableListOf<TafsirEntry>()
        for ((lang, arr) in root) {
            for (el in arr.jsonArray) {
                val o = el.jsonObject
                out += TafsirEntry(
                    slug = o.str("slug") ?: o.str("key") ?: continue,
                    name = o.str("name") ?: o.str("key") ?: continue,
                    author = o.str("author"),
                    langCode = o.str("langCode") ?: lang,
                    langName = o.str("langName"),
                )
            }
        }
        return out
    }

    fun parseWbw(jsonText: String): List<WbwPackEntry> {
        val arr = json.parseToJsonElement(jsonText).jsonObject["wbw"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            WbwPackEntry(
                id = o.str("id") ?: return@mapNotNull null,
                langCode = o.str("lang_code") ?: return@mapNotNull null,
                langName = o.str("lang_name"),
                hasTranslation = o["has_translation"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                hasTransliteration = o["has_transliteration"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                version = o["version"]?.jsonPrimitive?.longOrNull ?: 0L,
                url = o.str("url"),
            )
        }
    }

    /** Font packs from `fonts/available_fonts_info.json`. */
    fun parseFonts(jsonText: String): List<FontPackEntry> {
        val arr = json.parseToJsonElement(jsonText).jsonObject["fonts"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            FontPackEntry(
                id = o.str("id") ?: return@mapNotNull null,
                displayName = o.str("display_name") ?: return@mapNotNull null,
                usage = o.str("usage"),
                files = (o["files"]?.jsonArray ?: emptyList()).mapNotNull { f ->
                    val fo = f.jsonObject
                    FontFileEntry(
                        id = fo.str("id") ?: return@mapNotNull null,
                        displayName = fo.str("display_name") ?: fo.str("id") ?: return@mapNotNull null,
                        path = fo.str("path") ?: return@mapNotNull null,
                        format = fo.str("format") ?: "ttf",
                        weight = fo["weight"]?.jsonPrimitive?.intOrNull ?: 400,
                        provenance = fo.str("provenance"),
                    )
                },
            )
        }
    }

    /** Project web links from `inventory/other/urls.json` (flat `{key: url}`). */
    fun parseWebLinks(jsonText: String): Map<String, String> =
        json.parseToJsonElement(jsonText).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }

    /**
     * Generic fallback for unknown catalog shapes: first array found in the
     * document whose elements carry an `id`.
     */
    fun parseGeneric(jsonText: String): List<CatalogEntry> {
        val root = runCatching { json.parseToJsonElement(jsonText) }.getOrNull() ?: return emptyList()
        val arr = collectArrays(root).firstOrNull { els ->
            els.any { runCatching { it.jsonObject["id"]?.jsonPrimitive }.isSuccess }
        } ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            CatalogEntry(
                id = id,
                langCode = o.str("lang_code") ?: o.str("langCode"),
                displayName = o.str("display_name") ?: o.str("name") ?: id,
                version = o["version"]?.jsonPrimitive?.longOrNull ?: 0L,
                url = o.str("url"),
            )
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun collectArrays(el: JsonElement): List<List<JsonElement>> {
        val out = mutableListOf<List<JsonElement>>()
        fun walk(e: JsonElement) {
            when {
                e is kotlinx.serialization.json.JsonArray -> {
                    out += e
                    e.forEach { walk(it) }
                }
                e is kotlinx.serialization.json.JsonObject -> e.values.forEach { walk(it) }
            }
        }
        walk(el)
        return out
    }

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        val c = v.jsonPrimitive.content
        return if (c == "null" || c.isBlank()) null else c
    }
}

/** Parses + caches the shipped catalog manifests behind a fetcher. */
class CatalogSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "inventory",
) {
    private var translations: List<CatalogEntry>? = null
    private var tafsirs: List<TafsirEntry>? = null
    private var wbw: List<WbwPackEntry>? = null
    private var wbwV2: List<WbwPackEntry>? = null

    suspend fun translations(): List<CatalogEntry> = translations ?: run {
        val v = CatalogParser.parseTranslations(fetch("$baseDir/translations/available_translations_info.json"))
        translations = v; v
    }

    suspend fun tafsirs(): List<TafsirEntry> = tafsirs ?: run {
        val v = CatalogParser.parseTafsirs(fetch("$baseDir/tafsirs/available_tafsirs_info.json"))
        tafsirs = v; v
    }

    suspend fun wbw(v2: Boolean = false): List<WbwPackEntry> {
        if (v2) return wbwV2 ?: run {
            val v = CatalogParser.parseWbw(fetch("$baseDir/wbw/available_wbw_info_v2.json"))
            wbwV2 = v; v
        }
        return wbw ?: run {
            val v = CatalogParser.parseWbw(fetch("$baseDir/wbw/available_wbw_info.json"))
            wbw = v; v
        }
    }

    private var fonts: List<FontPackEntry>? = null

    /** Font packs (icon fonts, hadith text fonts) from `fonts/available_fonts_info.json`. */
    suspend fun fonts(): List<FontPackEntry> = fonts ?: run {
        val v = CatalogParser.parseFonts(fetch("$baseDir/fonts/available_fonts_info.json"))
        fonts = v; v
    }

    private var webLinks: Map<String, String>? = null

    /** Project web links (privacy policy, help, donation) from `inventory/other/urls.json`. */
    suspend fun webLinks(): Map<String, String> = webLinks ?: run {
        val root = CatalogParser.parseWebLinks(fetch("$baseDir/other/urls.json"))
        webLinks = root; root
    }

    private suspend fun fetch(path: String): String = fetcher.fetch(path).decodeToString()
}

/**
 * Download-state tracker. Pure in-memory; hosts persist via their own storage.
 */
class SyncTracker {

    private val downloaded = mutableMapOf<String, Long>()

    fun markDownloaded(packId: String, version: Long) {
        downloaded[packId] = maxOf(version, downloaded[packId] ?: 0L)
    }

    fun downloadedVersion(packId: String): Long? = downloaded[packId]

    fun pendingUpdates(catalogEntries: List<CatalogEntry>): List<DownloadState> =
        catalogEntries.mapNotNull { entry ->
            val local = downloaded[entry.id]
            when {
                local == null -> DownloadState(entry.id, entry.version, null, DownloadStatus.NOT_DOWNLOADED)
                entry.version > local -> DownloadState(entry.id, entry.version, null, DownloadStatus.UPDATE_AVAILABLE)
                else -> null
            }
        }

    fun clear() = downloaded.clear()
}
