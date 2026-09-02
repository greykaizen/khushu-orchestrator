package com.khushu.data.plans

import com.khushu.data.transport.ContentFetcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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
}
