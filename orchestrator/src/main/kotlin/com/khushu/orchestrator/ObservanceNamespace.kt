package com.khushu.orchestrator

import com.khushu.orchestrator.config.Bookmark
import com.khushu.orchestrator.config.BookmarkDomain
import com.khushu.orchestrator.config.BookmarkViews
import com.khushu.orchestrator.config.HistoryWindow
import com.khushu.orchestrator.config.KhatmFacts
import com.khushu.orchestrator.config.ReadStats
import com.khushu.orchestrator.config.ReadingHistory
import com.khushu.orchestrator.config.ReadingSession
import com.khushu.orchestrator.config.ReadingTarget
import java.time.LocalDate

/**
 * Observance surface — the SINGLE bookmark system (quran/sunnah/dua/names/
 * articles) plus reading-session facts for the Profile tab.
 *
 * Doctrine: the engine computes, the HOST persists. Every method here is a
 * pure delegation to `engine.observance.*`; no state, no Clock, no persistence.
 * `nowEpochMs` / `today` are explicit caller inputs so the composition layer
 * stays deterministic (the host stamps them).
 */
class ObservanceNamespace internal constructor(
    private val orch: KhushuOrchestrator,
) {
    /** Add-or-remove by (domain, ref); host persists the returned list. */
    fun toggleBookmark(
        bookmarks: List<Bookmark>,
        domain: BookmarkDomain,
        ref: String,
        nowEpochMs: Long,
    ): List<Bookmark> = orch.engine.observance.toggleBookmark(bookmarks, domain, ref, nowEpochMs)

    /** Insert/merge — same (domain, ref) keeps its original createdAt. */
    fun upsertBookmark(bookmarks: List<Bookmark>, candidate: Bookmark): List<Bookmark> =
        orch.engine.observance.upsertBookmark(bookmarks, candidate)

    /** Grouped/recent/all views for the Profile saved-items grid. */
    fun bookmarkViews(bookmarks: List<Bookmark>): BookmarkViews =
        orch.engine.observance.bookmarkViews(bookmarks)

    /** Per-domain streaks, today's progress and continue-reading candidates. */
    fun readStats(
        sessions: List<ReadingSession>,
        targets: List<ReadingTarget>,
        today: LocalDate,
        excusedRanges: List<ClosedRange<LocalDate>> = emptyList(),
    ): ReadStats = orch.engine.observance.readStats(sessions, targets, today, excusedRanges)

    /** Profile history feed (week/month) over logged sessions. */
    fun history(
        sessions: List<ReadingSession>,
        window: HistoryWindow,
        today: LocalDate,
    ): ReadingHistory = orch.engine.observance.history(sessions, window, today)
}
