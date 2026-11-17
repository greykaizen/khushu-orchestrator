package com.khushu.orchestrator

import com.khushu.engine.prayer.Qada
import com.khushu.orchestrator.config.BoundaryPairing
import com.khushu.orchestrator.config.DaySchedule
import com.khushu.orchestrator.config.FiqhPeriod
import com.khushu.orchestrator.config.FiqhPeriodType
import com.khushu.orchestrator.config.QadaConfig
import com.khushu.orchestrator.config.QadaReport
import com.khushu.orchestrator.config.QadaSchool
import java.time.Instant

/**
 * Qadāʾ & excused-range composites — read-only orchestration over the engine's
 * [Qada] facade (docs/qada-design.md rev 3). The host supplies pre-classified
 * periods and the days whose prayer schedules bound them; this namespace turns
 * [DayKey]s into engine [DaySchedule]s and returns the immutable report.
 *
 * No fiqh logic lives here: schools, pairings and boundary rules are engine
 * parameters, selected by the host. The namespace only composes facts already
 * cached in the [DayModel] with the engine computation.
 */
class QadaNamespace internal constructor(private val o: KhushuOrchestrator) {

    /** School default pairing: Ḥanafī = UNPAIRED; the other three = PAIRED. */
    fun defaultPairing(school: QadaSchool): BoundaryPairing =
        o.engine.qada.defaultPairing(school)

    /** School default Witr inclusion: Ḥanafī = true; others = false. */
    fun defaultIncludeWitr(school: QadaSchool): Boolean =
        o.engine.qada.defaultIncludeWitr(school)

    /** Everything a host needs to render a qadāʾ choice screen for one school. */
    fun choices(school: QadaSchool): QadaChoices = QadaChoices(
        school = school,
        schools = QadaSchool.entries.toList(),
        pairing = defaultPairing(school),
        includeWitr = defaultIncludeWitr(school),
        reasons = ExcuseReason.entries.toList(),
    )

    /**
     * Full qadāʾ report over pre-classified [periods] and the supplied [days].
     *
     * Each day's five obligatory anchors + tomorrow's Fajr are read from the
     * cached [DayModel] (adjusted times, raw fallback for next-day Fajr). A day
     * with any uncomputable anchor (polar day/night) is SKIPPED rather than
     * producing a partial schedule; when no day survives, an empty report is
     * returned instead of letting the engine's non-empty-schedule guard throw.
     */
    suspend fun report(
        periods: List<FiqhPeriod>,
        days: List<DayKey>,
        config: QadaConfig,
    ): QadaReport {
        val schedules = buildList<DaySchedule> {
            for (key in days) {
                val t = o.dayModel(key).prayerTimes
                val fajr = t.fajr.adjusted ?: continue
                val dhuhr = t.dhuhr.adjusted ?: continue
                val asr = t.asr.adjusted ?: continue
                val maghrib = t.maghrib.adjusted ?: continue
                val isha = t.isha.adjusted ?: continue
                val tomorrow = o.dayModel(key.copy(date = key.date.plusDays(1))).prayerTimes
                val nextDayFajr = tomorrow.fajr.adjusted ?: tomorrow.fajr.raw ?: continue
                add(
                    DaySchedule(
                        date = key.date,
                        fajr = fajr,
                        dhuhr = dhuhr,
                        asr = asr,
                        maghrib = maghrib,
                        isha = isha,
                        nextDayFajr = nextDayFajr,
                        zoneId = key.zoneId,
                    ),
                )
            }
        }
        if (schedules.isEmpty()) return emptyReport()
        return o.engine.qada.qadaReport(periods, schedules, config)
    }

    private fun emptyReport(): QadaReport = QadaReport(
        suppressedPrayers = emptyList(),
        boundaryObligations = emptyList(),
        witrObligations = emptyList(),
        ghuslRequiredAt = emptyList(),
        classifications = emptyList(),
        provenance = listOf("no computable day schedule in the supplied range (polar day/night?)"),
    )
}

/**
 * Shared host vocabulary for why a qadāʾ period exists. Only ḥayḍ and nifās map
 * to an engine [FiqhPeriodType] (the two prayer-exempt states); travel, illness
 * and other excuses are surfaced for the host's UI but never fabricate a fiqh
 * classification — [period] returns null for them.
 */
enum class ExcuseReason(val fiqhType: FiqhPeriodType?) {
    HAYD(FiqhPeriodType.HAYD),
    NIFAS(FiqhPeriodType.NIFAS),
    TRAVEL(null),
    ILLNESS(null),
    OTHER(null);

    /** The classified engine period, or null when this excuse has no fiqh type. */
    fun period(start: Instant, end: Instant): FiqhPeriod? =
        fiqhType?.let { FiqhPeriod(start, end, it) }
}

/** Host-facing option set for a qadāʾ school selector. */
data class QadaChoices(
    /** The school whose defaults the pairing/Witr values describe. */
    val school: QadaSchool,
    val schools: List<QadaSchool>,
    val pairing: BoundaryPairing,
    val includeWitr: Boolean,
    val reasons: List<ExcuseReason>,
)
