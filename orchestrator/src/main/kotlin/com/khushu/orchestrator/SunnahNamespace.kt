package com.khushu.orchestrator

import com.khushu.data.model.Book
import com.khushu.data.model.Chapter
import com.khushu.data.model.Grade
import com.khushu.data.model.Hadith
import com.khushu.data.model.HadithCollection
import com.khushu.data.model.Scholar
import com.khushu.data.model.SearchResultRow
import com.khushu.data.repo.SunnahApi
import java.io.File
import kotlinx.coroutines.withContext

/**
 * Sunnah offline seam — attaches the consolidated SQLite corpora
 * (`inventory/hadiths/{collection}.db`) and exposes the full local surface:
 * books, hadith, grades, narrators, related, and FTS search.
 *
 * The per-book ONLINE path lives in [ContentNamespace.sunnahBook] (streams
 * the sliced JSON through the caching transport — works without attaching).
 * This namespace is the heavy OFFLINE path: search indexes and random/related
 * queries need the .db corpora on disk (download them via
 * [DownloadsNamespace] — `sunnahCollection`/`sunnahFull` plans).
 *
 * Lifecycle: [attach] once (idempotent — re-attach closes the previous
 * session), [close] on process teardown. DOCTRINE: delegation only.
 *
 * Concurrency (v1.4.2): JDBC `Connection`s are not thread-safe and the
 * repositories share them across queries — every member here runs on
 * [KhushuContent.sqlDispatcher], the ONE single-threaded SQLite executor
 * shared with the quran FTS index (v1.6.0: it had its own slice, which did
 * not exclude concurrent quran-search statements). [attach] is the one sync
 * member (it opens no connections; they are lazy).
 */
class SunnahNamespace internal constructor(private val o: KhushuOrchestrator) {

    private val sql = o.data.sqlDispatcher

    @Volatile
    private var attached: SunnahApi? = null

    val isAttached: Boolean get() = attached != null

    /**
     * Attach local hadith corpora. [scholarsDb] defaults to
     * `{corporaRoot}/scholars_info.db` when present (enables narrator→scholar
     * joins); [searchIndexDb] defaults to a side index next to the corpora.
     */
    fun attach(
        corporaRoot: File,
        scholarsDb: File? = File(corporaRoot, "scholars_info.db"),
        searchIndexDb: File? = null,
    ): SunnahNamespace {
        attached = o.data.attachSunnah(corporaRoot, scholarsDb, searchIndexDb)
        return this
    }

    /** Release SQLite connections + the FTS index (eager close). */
    suspend fun close() {
        attached = null
        withContext(sql) { o.data.closeSunnahOnly() }
    }

    private fun api(): SunnahApi =
        attached ?: error(
            "Sunnah corpora not attached — call sunnah.attach(corporaRoot) after " +
                "downloading collection plans (orch.downloads), or use the online " +
                "per-book path: orch.content.sunnahBook(...)",
        )

    // ── delegation (all SQLite-touching calls confined to [sql]) ───────────

    suspend fun collections(lang: String = "en"): List<HadithCollection> =
        withContext(sql) { api().collections(lang) }

    suspend fun books(collectionId: String, lang: String = "en"): List<Book> =
        withContext(sql) { api().books(collectionId, lang) }

    suspend fun chapters(bookId: String, lang: String = "en"): List<Chapter> =
        withContext(sql) { api().chapters(bookId, lang) }

    suspend fun hadith(id: String, lang: String): Hadith? =
        withContext(sql) { api().hadith(id, lang) }

    suspend fun hadiths(ids: List<String>, lang: String): List<Hadith> =
        withContext(sql) { api().hadiths(ids, lang) }

    suspend fun hadithsForBook(bookId: String, lang: String, limit: Int = 50, offset: Int = 0): List<Hadith> =
        withContext(sql) { api().hadithsForBook(bookId, lang, limit, offset) }

    suspend fun random(lang: String, gradeFilter: String? = null): Hadith? =
        withContext(sql) { api().random(lang, gradeFilter) }

    suspend fun grades(hadithId: String, lang: String? = null): List<Grade> =
        withContext(sql) { api().grades(hadithId, lang) }

    suspend fun narratorsOf(hadithId: String): List<Scholar> =
        withContext(sql) { api().narratorsOf(hadithId) }

    suspend fun related(hadithId: String, lang: String): List<Pair<String, Hadith?>> =
        withContext(sql) { api().related(hadithId, lang) }

    suspend fun buildSearchIndex(lang: String, force: Boolean = false): com.khushu.data.sunnah.IndexBuildSummary =
        withContext(sql) { api().buildSearchIndex(lang, force) }

    suspend fun search(query: String, lang: String, limit: Int = 20, offset: Int = 0): List<SearchResultRow> =
        withContext(sql) { api().search(query, lang, limit, offset) }
}
