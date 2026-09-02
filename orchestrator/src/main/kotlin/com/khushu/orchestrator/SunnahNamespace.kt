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
 */
class SunnahNamespace internal constructor(private val o: KhushuOrchestrator) {

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

    /** Release SQLite connections + the FTS index. */
    fun close() {
        attached = null
        o.data.closeSunnahOnly()
    }

    private fun api(): SunnahApi =
        attached ?: error(
            "Sunnah corpora not attached — call sunnah.attach(corporaRoot) after " +
                "downloading collection plans (orch.downloads), or use the online " +
                "per-book path: orch.content.sunnahBook(...)",
        )

    // ── delegation ─────────────────────────────────────────────────────────

    fun collections(lang: String = "en"): List<HadithCollection> = api().collections(lang)
    fun books(collectionId: String, lang: String = "en"): List<Book> = api().books(collectionId, lang)
    fun chapters(bookId: String, lang: String = "en"): List<Chapter> = api().chapters(bookId, lang)
    fun hadith(id: String, lang: String): Hadith? = api().hadith(id, lang)
    fun hadiths(ids: List<String>, lang: String): List<Hadith> = api().hadiths(ids, lang)
    fun hadithsForBook(bookId: String, lang: String, limit: Int = 50, offset: Int = 0): List<Hadith> =
        api().hadithsForBook(bookId, lang, limit, offset)

    fun random(lang: String, gradeFilter: String? = null): Hadith? = api().random(lang, gradeFilter)
    fun grades(hadithId: String, lang: String? = null): List<Grade> = api().grades(hadithId, lang)
    fun narratorsOf(hadithId: String): List<Scholar> = api().narratorsOf(hadithId)
    fun related(hadithId: String, lang: String): List<Pair<String, Hadith?>> = api().related(hadithId, lang)

    fun buildSearchIndex(lang: String, force: Boolean = false): com.khushu.data.sunnah.IndexBuildSummary =
        api().buildSearchIndex(lang, force)

    fun search(query: String, lang: String, limit: Int = 20, offset: Int = 0): List<SearchResultRow> =
        api().search(query, lang, limit, offset)
}
