package com.khushu.orchestrator

import com.khushu.orchestrator.config.Bookmark
import com.khushu.orchestrator.config.BookmarkDomain
import com.khushu.orchestrator.config.ReadingDomain
import com.khushu.orchestrator.config.ReadingSession
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The host-facing seam delegates to the engine's locked observance algebra. */
class ObservanceNamespaceTest {
    private val orch = KhushuOrchestrator(fetcher = null)

    @Test
    fun `toggle and views stay idempotent through the namespace`() {
        val empty = emptyList<Bookmark>()
        val added = orch.observance.toggleBookmark(empty, BookmarkDomain.DUA, "dua_47", 100L)
        assertEquals(1, added.size)
        assertEquals("dua_47", added.first().ref)

        val views = orch.observance.bookmarkViews(added)
        assertEquals(listOf(added.first()), views.byDomain[BookmarkDomain.DUA])
        assertEquals(added.first(), views.all.first())

        val removed = orch.observance.toggleBookmark(added, BookmarkDomain.DUA, "dua_47", 200L)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `upsert preserves createdAt and readStats computes over logs`() {
        val original = Bookmark(BookmarkDomain.QURAN, "2:255", createdAtEpochMs = 10L)
        val merged = orch.observance.upsertBookmark(
            listOf(original),
            Bookmark(BookmarkDomain.QURAN, "2:255", label = "Ayat al-Kursi", createdAtEpochMs = 99L),
        )
        assertEquals(1, merged.size)
        assertEquals(10L, merged.first().createdAtEpochMs)
        assertEquals("Ayat al-Kursi", merged.first().label)

        val today = LocalDate.of(2026, 11, 18)
        val session = ReadingSession(ReadingDomain.QURAN, "2:255", today, 120, completedToday = true)
        val stats = orch.observance.readStats(listOf(session), emptyList(), today)
        assertEquals(1, stats.byDomain[ReadingDomain.QURAN]?.streakDays)
        assertEquals(2, stats.byDomain[ReadingDomain.QURAN]?.minutesActiveToday)
    }
}
