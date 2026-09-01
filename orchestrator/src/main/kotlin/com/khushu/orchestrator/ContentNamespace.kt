package com.khushu.orchestrator

import com.khushu.data.model.AsmaName
import com.khushu.data.model.AsmaPack
import com.khushu.data.model.Dua
import com.khushu.data.model.DuaArticle
import com.khushu.data.model.DuaArticleCategory
import com.khushu.data.model.DuaArticleInfo
import com.khushu.data.model.DuaCategory

/**
 * Read-only delegation over data-api retrieval — the host's content surface.
 *
 * DOCTRINE: delegation only, one line per call, never add logic here. Any
 * function needing domain knowledge belongs in data-api; any composite of
 * fetch+math belongs in a namespace. Data-api fetches are cheap by design
 * (CachingFetcher disk cache + source memoization) — direct content reads
 * through this surface carry no recomputation risk.
 */
class ContentNamespace internal constructor(private val o: KhushuOrchestrator) {

    // ── dua & dhikr ────────────────────────────────────────────────────────

    suspend fun duas(): List<Dua> = o.data.dua.duas()
    suspend fun dua(id: Int): Dua? = o.data.dua.dua(id)
    suspend fun duaCategories(): List<DuaCategory> = o.data.dua.categories()
    suspend fun duasBySubcategory(subcategory: String): List<Dua> =
        o.data.dua.bySubcategory(subcategory)

    suspend fun duaArticleCategories(): List<DuaArticleCategory> = o.data.dua.articleCategories()
    suspend fun duaArticles(): List<DuaArticleInfo> = o.data.dua.articles()
    suspend fun duaArticle(filePath: String): DuaArticle? = o.data.dua.article(filePath)

    // ── asma ul husna ──────────────────────────────────────────────────────

    val asmaLanguages: List<String> get() = o.data.dua.asmaLanguages
    suspend fun asmaPack(lang: String): AsmaPack? = o.data.dua.asmaPack(lang)
    suspend fun asmaName(lang: String, number: Int): AsmaName? = o.data.dua.asmaName(lang, number)

    // ── sunnah (per-book online read) ──────────────────────────────────────

    suspend fun sunnahBookIndex(collectionId: String) = o.data.sunnahBooks.bookIndex(collectionId)
    suspend fun sunnahBook(collectionId: String, bookId: String, lang: String = "en") =
        o.data.sunnahBooks.book(collectionId, bookId, lang)

    // ── quran retrieval (pass-through of the established surface) ──────────

    suspend fun surahs() = o.data.quran.surahs()
    suspend fun surah(number: Int) = o.data.quran.surah(number)
    suspend fun ayahTexts(surahNo: Int, script: String = "uthmani", includeAyahMarker: Boolean = false) =
        o.data.quran.ayahTexts(surahNo, script, includeAyahMarker)
    suspend fun translationPacks(langCode: String? = null) =
        o.data.quran.translationPacks(langCode)
    suspend fun translationText(packId: String, surahNo: Int? = null) =
        o.data.quran.translationText(packId, surahNo)
    suspend fun tafsirForSurah(slug: String, surahNo: Int, lang: String = "en") =
        o.data.quran.tafsirForSurah(slug, surahNo, lang)
    suspend fun ayahBundle(surahNo: Int, ayahNo: Int) = o.data.quran.ayahBundle(surahNo, ayahNo)

    // ── islamic events (display entries) ───────────────────────────────────

    suspend fun islamicEvents() = o.data.islamicEvents.all()
    suspend fun islamicEventsForHijriMonth(month: Int) =
        o.data.islamicEvents.forHijriMonth(month)

    // ── adhan catalog ──────────────────────────────────────────────────────

    suspend fun adhanEntries() = o.data.adhan.entries()
    suspend fun adhanReciters() = o.data.adhan.reciters()
}
