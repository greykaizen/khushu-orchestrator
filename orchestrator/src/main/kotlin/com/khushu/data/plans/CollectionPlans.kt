package com.khushu.data.plans

import com.khushu.data.transport.ContentFetcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * One downloadable unit: a set of repo-relative file paths (real file units —
 * the same granularity the corpus ships). Plans are pure data: factories build
 * them from catalogs + the downloads ledger, and
 * [com.khushu.data.repo.DownloadsApi.download] executes them.
 */
data class CollectionPlan(
    /** Stable identity for UI/progress bookkeeping (e.g. `sunnah:bukhari:book:bukhari_b1`). */
    val id: String,
    /** Human title for download managers. */
    val title: String,
    /** Files to have present, repo-relative. */
    val paths: List<String>,
    /** Byte total from the downloads ledger (null when the ledger isn't served). */
    val totalBytes: Long? = null,
)

/** Per-file ledger row from `inventory/downloads_manifest.json` (pipeline-generated). */
data class LedgerRow(val path: String, val bytes: Long, val sha256: String?)

/**
 * Reads the pipeline-generated batch-download ledger — sizes + sha256 for
 * every downloadable file, BEFORE anything is downloaded.
 */
class DownloadsLedger private constructor(private val byPath: Map<String, LedgerRow>) {

    fun row(path: String): LedgerRow? = byPath[path]

    fun rowsFor(paths: List<String>): List<LedgerRow> = paths.mapNotNull { byPath[it] }

    fun totalBytes(paths: List<String>): Long = rowsFor(paths).sumOf { it.bytes }

    fun size(): Int = byPath.size

    companion object {
        suspend fun load(fetcher: ContentFetcher): DownloadsLedger {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(
                fetcher.fetch("inventory/downloads_manifest.json").decodeToString(),
            ).jsonObject
            val m = root["_meta"]!!.jsonObject
            // version read for forward-compat; only v1 semantics assumed today
            m["version"]!!.jsonPrimitive.int
            val map = root["files"]!!.jsonArray.associate {
                val o = it.jsonObject
                o["path"]!!.jsonPrimitive.content to LedgerRow(
                    path = o["path"]!!.jsonPrimitive.content,
                    bytes = o["bytes"]!!.jsonPrimitive.long,
                    sha256 = o["sha256"]?.jsonPrimitive?.content,
                )
            }
            return DownloadsLedger(map)
        }
    }
}

/**
 * Plan factories — one per domain, all built over real file units. Sizing comes
 * from the ledger when the fetcher serves it; totals are null otherwise.
 *
 * All factories are suspend (ledger + metadata load over the transport).
 */
class PlanFactory(private val fetcher: ContentFetcher, preloaded: DownloadsLedger? = null) {

    private val mutex = Mutex()
    private var ledgerCache: DownloadsLedger? = preloaded
    private var ledgerFailed = preloaded == null
    private suspend fun ledger(): DownloadsLedger? {
        ledgerCache?.let { return it }
        if (ledgerFailed) return null
        return mutex.withLock {
            ledgerCache?.let { return@withLock it }
            try {
                DownloadsLedger.load(fetcher).also { ledgerCache = it }
            } catch (e: Exception) {
                ledgerFailed = true
                null // ledger not served by this transport — sizing degrades to null
            }
        }
    }

    /** Corpus layout: English books live in `books/`, translations in `books_{lang}/`. */
    private fun booksDir(collectionId: String, lang: String): String =
        if (lang.equals("en", ignoreCase = true)) "inventory/hadiths/$collectionId/books"
        else "inventory/hadiths/$collectionId/books_${lang.lowercase()}"

    private suspend fun finish(id: String, title: String, paths: List<String>): CollectionPlan =
        CollectionPlan(id, title, paths, ledger()?.totalBytes(paths))

    // ── Sunnah ────────────────────────────────────────────────────────────

    /** One book of one collection, one language (inventory/hadiths/{c}/books/{lang}/{c}_bNN.json). */
    suspend fun sunnahBook(collectionId: String, bookId: String, lang: String = "en"): CollectionPlan =
        finish(
            id = "sunnah:$collectionId:book:$bookId",
            title = "$collectionId — $bookId ($lang)",
            paths = listOf("${booksDir(collectionId, lang)}/$bookId.json"),
        )

    /** All book files of a collection (one language) + the consolidated .db (FTS corpora). */
    suspend fun sunnahCollection(collectionId: String, lang: String = "en"): CollectionPlan {
        val json = Json { ignoreUnknownKeys = true }
        val meta = json.parseToJsonElement(
            fetcher.fetch("inventory/hadiths/$collectionId/metadata.json").decodeToString(),
        ).jsonObject["books"]!!.jsonArray
        val dir = booksDir(collectionId, lang)
        val bookFiles = meta.jsonArray.map {
            "$dir/${it.jsonObject["id"]!!.jsonPrimitive.content}.json"
        }
        return finish(
            id = "sunnah:$collectionId:full",
            title = "$collectionId (complete, $lang + search db)",
            paths = bookFiles + "inventory/hadiths/$collectionId.db",
        )
    }

    /** Every sunnah collection (all books, one language, + search dbs). */
    suspend fun sunnahFull(lang: String = "en"): CollectionPlan {
        val collections = listOf(
            "bukhari", "muslim", "abudawud", "tirmidhi", "nasai",
            "ibnmajah", "malik", "riyadussalihin", "forty",
        )
        val seen = linkedSetOf<String>()
        val paths = collections.flatMap { sunnahCollection(it, lang).paths }.filter { seen.add(it) }
        val bytes = ledger()?.totalBytes(paths)
        return CollectionPlan("sunnah:full", "Sunnah — all collections ($lang)", paths, bytes)
    }

    // ── Quran text packs ──────────────────────────────────────────────────

    /** One translation/tafsir/wbw pack JSON (monolithic per pack — the honest unit). */
    suspend fun pack(info: com.khushu.data.model.TranslationPackInfo): CollectionPlan {
        val path = info.downloadPath ?: "inventory/translations/${info.langCode}/${info.id}.json"
        return finish(
            id = "quran:pack:${info.id}",
            title = "${info.displayName} (${info.langCode})",
            paths = listOf(path),
        )
    }

    // ── Dua ───────────────────────────────────────────────────────────────

    /** Dua corpus data (tiny). */
    suspend fun duaData(): CollectionPlan = finish(
        id = "dua:data",
        title = "Dua & Adhkar — data",
        paths = listOf("assets/dua_dhikr/dua_data.json"),
    )

    /** All dua audio mirrors (3 corpus entries are mirrorless — absent by design). */
    suspend fun duaAudio(): CollectionPlan {
        val l = ledger()
        val paths = (1..491).map { "assets/dua_dhikr/dua_$it.opus" }
            .filter { l == null || l.row(it) != null }
        return finish("dua:audio", "Dua audio (recitations)", paths)
    }

    /** Dua data + audio. */
    suspend fun duaFull(): CollectionPlan {
        val d = duaData(); val a = duaAudio()
        return CollectionPlan(
            "dua:full", "Dua — complete", d.paths + a.paths,
            listOfNotNull(d.totalBytes, a.totalBytes).takeIf { it.size == 2 }?.sum(),
        )
    }

    // ── Asma ──────────────────────────────────────────────────────────────

    /** One 99-Names language pack. */
    suspend fun asmaPack(lang: String): CollectionPlan = finish(
        id = "asma:pack:$lang",
        title = "99 Names ($lang)",
        paths = listOf("inventory/asma_ul_husna/asma_data_$lang.json"),
    )

    // ── Fonts (donor rendering assets — QuranApp provenance) ─────────────

    /**
     * One thematic font pack from the fonts catalog (paths from
     * available_fonts_info.json): `quran_icons` (surah icons + bismillah +
     * title frames — APK-bundle candidates), `quran_text` (uthmanic_hafs
     * non-atlas fallback), `sunnah` (hadith Arabic Naskh + Urdu/Bengali).
     */
    suspend fun fontPack(packId: String): CollectionPlan {
        val catalog = fetchCatalog()
        val pack = catalog.firstOrNull { it.id == packId }
            ?: error("unknown font pack '$packId' — available: ${catalog.map { it.id }}")
        return finish(
            id = "fonts:pack:$packId",
            title = pack.displayName,
            paths = pack.files.map { it.path },
        )
    }

    /** All catalog font packs (fonts only — the per-page QPC bundles below are separate). */
    suspend fun allFontPacks(): CollectionPlan {
        val catalog = fetchCatalog()
        val paths = catalog.flatMap { pack -> pack.files.map { it.path } }
        return finish("fonts:all", "All catalog font packs", paths)
    }

    /**
     * One KFQPC per-page font bundle — page-exact print-fidelity text mode
     * (604 page TTFs per script version; donor ScriptFontsDownloadWorker
     * semantics: download per script, load per page, LRU the reader window).
     */
    suspend fun kfqpcPageFonts(version: KfqpcVersion = KfqpcVersion.V1): CollectionPlan {
        val dir = "inventory/fonts/${version.dirName}"
        val bundle = "${dir}/${version.zipName}"
        return finish(
            id = "fonts:kfqpc:${version.id}",
            title = "KFQPC page fonts — ${version.label}",
            paths = listOf(bundle),
        )
    }

    /**
     * One glyph-atlas bundle zip — rasterized mushaf mode for a script
     * (uthmani / dk_indopak / dk_indopak_v2; no fonts needed for atlas mode).
     */
    suspend fun atlasBundle(bundleId: String, sizeLabel: String = "6x"): CollectionPlan {
        val info = fetchAtlasCatalog()
        val bundle = info.firstOrNull { it.id == bundleId }
            ?: error("unknown atlas bundle '$bundleId' — available: ${info.map { it.id }}")
        val size = bundle.sizes.firstOrNull { it.label == sizeLabel }
            ?: error("atlas $bundleId has no size '$sizeLabel'")
        return finish(
            id = "atlas:$bundleId:$sizeLabel",
            title = "${bundleId} atlas ($sizeLabel, ${size.ppem}ppem)",
            paths = listOf(size.url),
        )
    }

    /** All atlas bundles (every mirrored script at every mirrored size). */
    suspend fun allAtlasBundles(): CollectionPlan {
        val info = fetchAtlasCatalog()
        val paths = info.flatMap { b -> b.sizes.map { it.url } }
        return finish("atlas:all", "All glyph-atlas bundles", paths)
    }

    private suspend fun fetchCatalog(): List<com.khushu.data.model.FontPackEntry> {
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(
            fetcher.fetch("inventory/fonts/available_fonts_info.json").decodeToString(),
        ).jsonObject
        return root["fonts"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            com.khushu.data.model.FontPackEntry(
                id = o["id"]!!.jsonPrimitive.content,
                displayName = o["display_name"]!!.jsonPrimitive.content,
                usage = o["usage"]?.jsonPrimitive?.content,
                files = o["files"]!!.jsonArray.map { f ->
                    val fo = f.jsonObject
                    com.khushu.data.model.FontFileEntry(
                        id = fo["id"]!!.jsonPrimitive.content,
                        displayName = fo["display_name"]?.jsonPrimitive?.content ?: fo["id"]!!.jsonPrimitive.content,
                        path = fo["path"]!!.jsonPrimitive.content,
                        format = fo["format"]?.jsonPrimitive?.content ?: "ttf",
                        weight = fo["weight"]?.jsonPrimitive?.intOrNull ?: 400,
                        provenance = fo["provenance"]?.jsonPrimitive?.content,
                    )
                },
            )
        }
    }

    private suspend fun fetchAtlasCatalog(): List<AtlasBundleInfo> {
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(
            fetcher.fetch("inventory/atlas/available_atlas_info.json").decodeToString(),
        ).jsonObject
        return root["atlas"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            AtlasBundleInfo(
                id = o["id"]!!.jsonPrimitive.content,
                renders = o["renders"]?.jsonPrimitive?.content ?: "",
                sizes = o["sizes"]!!.jsonArray.map { s ->
                    val so = s.jsonObject
                    AtlasSizeInfo(
                        label = so["label"]!!.jsonPrimitive.content,
                        ppem = so["ppem"]!!.jsonPrimitive.int,
                        url = so["url"]!!.jsonPrimitive.content,
                    )
                },
            )
        }
    }
}

/** KFQPC script versions with page-font bundles in the corpus. */
enum class KfqpcVersion(val id: String, val dirName: String, val zipName: String, val label: String) {
    V1("kfqpc_v1", "kfqpc_v1", "kfqpc_v1-1.zip", "KFGQPC V1"),
}

internal data class AtlasBundleInfo(val id: String, val renders: String, val sizes: List<AtlasSizeInfo>)
internal data class AtlasSizeInfo(val label: String, val ppem: Int, val url: String)
