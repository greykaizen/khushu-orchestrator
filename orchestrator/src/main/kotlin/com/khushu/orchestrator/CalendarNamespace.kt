package com.khushu.orchestrator


import com.khushu.engine.calendar.CalendarConfiguration
import com.khushu.engine.calendar.CalendarParams
import com.khushu.engine.core.geo.Location
import java.time.LocalDate
import java.time.YearMonth
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

    /**
     * Calendar-grid composite for one civil month: engine's one-pass
     * [MonthSummary] (dual dates incl. regional lines, engine events, fast
     * rules, moon facts) joined with data-api localized display entries —
     * the exact join the calendar screen would otherwise hand-write.
     */
    suspend fun monthGrid(
        yearMonth: YearMonth,
        location: Location,
        zoneId: ZoneId,
        config: CalendarConfiguration,
        calendarParams: CalendarParams = CalendarParams(hijriOffsetDays = config.hijriOffsetDays),
    ): MonthGrid {
        val summary = o.engine.calendar.month.summary(yearMonth, location, zoneId, config, calendarParams)
        val displayByDate = summary.days.associate { day ->
            val hijri = day.dual.hijriLine()
            val entries = hijri?.let {
                o.data.islamicEvents.forHijriMonth(it.month).filter { e -> e.hijriDay == it.day }
            }.orEmpty()
            day.date to entries
        }
        return MonthGrid(
            yearMonth = yearMonth,
            days = summary.days.map { MonthGridDay(it, displayByDate[it.date].orEmpty()) },
        )
    }

    /** Hijri side of a dual-date pair (exactly one side is always Hijri — engine-enforced). */
    private fun com.khushu.engine.calendar.DualDate.hijriLine(): com.khushu.engine.calendar.HijriDate? =
        when (val line = primary) {
            is com.khushu.engine.calendar.DateLine.Hijri -> line.date
            else -> (secondary as? com.khushu.engine.calendar.DateLine.Hijri)?.date
        }
}

/** One grid cell: engine month-summary day + data-api localized display entries. */
data class MonthGridDay(
    val summary: com.khushu.engine.calendar.MonthSummaryDay,
    val displayEvents: List<com.khushu.data.repo.IslamicEventEntry>,
)

data class MonthGrid(
    val yearMonth: YearMonth,
    val days: List<MonthGridDay>,
)
