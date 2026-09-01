package com.khushu.orchestrator


import com.khushu.engine.core.geo.Location
import com.khushu.engine.prayer.PrayerConfiguration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Engine-computed hijri dates × data-api localized event display entries —
 * the join the host calendar screen would otherwise hand-write.
 */
class CalendarNamespace internal constructor(private val o: KhushuOrchestrator) {

    /** One day where the two libraries meet. */
    data class LocatedDay(
        val gregorian: LocalDate,
        val hijriYear: Int,
        val hijriMonth: Int,
        val hijriDay: Int,
        val hijriOffsetApplied: Int,
        /** Display entries from the islamic-calendar corpus for this hijri date. */
        val events: List<com.khushu.data.repo.IslamicEventEntry>,
    )

    /**
     * Compose engine hijri conversion with data-api display entries for each
     * date in [range]. Engine computes (authoritative); data-api localizes.
     * @param hijriOffsetDays host calendar display offset (−2..+2).
     */
    suspend fun eventsFor(
        range: List<LocalDate>,
        zoneId: ZoneId,
        hijriOffsetDays: Int = 0,
    ): List<LocatedDay> = range.map { date ->
        val hijri = o.engine.calendar.hijri(date, hijriOffsetDays)
        val entries = o.data.islamicEvents.forHijriMonth(hijri.month)
            .filter { it.hijriDay == hijri.day }
        LocatedDay(
            gregorian = date,
            hijriYear = hijri.year,
            hijriMonth = hijri.month,
            hijriDay = hijri.day,
            hijriOffsetApplied = hijri.offsetApplied,
            events = entries,
        )
    }
}
