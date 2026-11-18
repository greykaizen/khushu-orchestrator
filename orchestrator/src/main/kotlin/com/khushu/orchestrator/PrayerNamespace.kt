package com.khushu.orchestrator

import com.khushu.engine.prayer.PrayerStatus
import com.khushu.orchestrator.config.PrayerEntry
import com.khushu.orchestrator.config.PrayerOccasionCategory
import com.khushu.orchestrator.config.PrayerOccurrence
import com.khushu.orchestrator.config.PrayerFastingFacts
import com.khushu.orchestrator.config.PrayerForbiddenWindows
import com.khushu.orchestrator.config.PrayerJumuah
import com.khushu.orchestrator.config.PrayerLogRecord
import com.khushu.orchestrator.config.PrayerStreakStats
import com.khushu.orchestrator.config.PrayerTravelFacts
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Prayer presentation composites — served from the cached [DayModel] (zero
 * recomputation; every window/derivation below reads precomputed prayer
 * times). The home countdown loop reuses the exact boundary-wake machinery
 * proven in [DuaNamespace.adaptiveFlow].
 *
 * NOTIFICATION CONTRACT: this namespace computes WHEN; the host schedules
 * HOW (AlarmManager/channels/boot-restore stay host-side — family doctrine:
 * notifications/scheduling are caller-side).
 *
 * All members are suspend (v1.4.2): cold-path day builds fetch content and
 * must never runBlocking on the caller's thread.
 */
class PrayerNamespace internal constructor(private val o: KhushuOrchestrator) {

    /**
     * Current prayer state at [now], derived from the DayModel's cached
     * [com.khushu.engine.prayer.PrayerTimesResult] — the engine's own
     * [PrayerStatus] shape (same API, no recomputation).
     *
     * Suspend (v1.4.2): a cold cache builds the day model here instead of
     * runBlocking network I/O on the caller's thread.
     */
    suspend fun status(key: DayKey, now: Instant): PrayerStatus =
        deriveStatus(key, now)

    /**
     * One-stop home-screen snapshot: the cached day's full prayer times plus
     * the current [PrayerStatus] and the next precomputed trigger boundary —
     * a single call for hosts that render all three together. The status may
     * derive from a neighbouring day's chain (see [status]); [times]/[key] are
     * the plan for [key] itself.
     */
    suspend fun dayOverview(key: DayKey, now: Instant): PrayerDayOverview {
        val model = o.dayModel(key)
        return PrayerDayOverview(
            key = key,
            date = key.date,
            zoneId = key.zoneId,
            times = model.prayerTimes,
            status = status(key, now),
            nextBoundary = model.nextBoundary(now),
        )
    }

    /**
     * Reactive [dayOverview]: reuses [statusFlow]'s boundary-wake timer (no
     * duplicated polling/arithmetic) and maps each emitted status onto a fresh
     * overview, tracking the civil date of `now`. Suspend only because the
     * initial value needs a cold day build — the flow itself is hot in [scope].
     */
    suspend fun dayOverviewFlow(
        key: DayKey,
        scope: CoroutineScope,
        startNow: Instant,
        maxWait: Duration = Duration.ofHours(1),
        nowNow: () -> Instant = { Instant.now() },
        timeScale: Double = 1.0,
    ): StateFlow<PrayerDayOverview> {
        require(timeScale > 0) { "timeScale must be positive" }
        val state = MutableStateFlow(dayOverview(key, startNow))
        val statuses = statusFlow(key, scope, startNow, maxWait, nowNow, timeScale)
        scope.launch {
            statuses.collect { s ->
                val civilKey = key.copy(
                    date = ZonedDateTime.ofInstant(s.now, key.zoneId).toLocalDate(),
                )
                val model = o.dayModel(civilKey)
                state.value = PrayerDayOverview(
                    key = civilKey,
                    date = civilKey.date,
                    zoneId = civilKey.zoneId,
                    times = model.prayerTimes,
                    status = s,
                    nextBoundary = model.nextBoundary(s.now),
                )
            }
        }
        return state.asStateFlow()
    }

    /**
     * The home-screen countdown loop — same boundary-wake machinery as
     * [DuaNamespace.adaptiveFlow]: emits the status, sleeps until the next
     * precomputed prayer boundary, re-emits. Virtual-time testable via
     * [nowNow] + [timeScale].
     */
    fun statusFlow(
        key: DayKey,
        scope: CoroutineScope,
        startNow: Instant,
        maxWait: Duration = Duration.ofHours(1),
        nowNow: () -> Instant = { Instant.now() },
        timeScale: Double = 1.0,
    ): StateFlow<PrayerStatus> {
        require(timeScale > 0) { "timeScale must be positive" }
        val state = MutableStateFlow(PrayerStatus(null, null, null, null, startNow))
        scope.launch {
            var now = startNow
            var currentKey = key
            while (true) {
                state.value = deriveStatus(currentKey, now)
                val boundary = o.dayModel(currentKey).nextBoundary(now)
                val waitMs = when (boundary) {
                    null -> (maxWait.toMillis() / timeScale).toLong().coerceAtLeast(50L)
                    else -> (Duration.between(now, boundary).toMillis() / timeScale)
                        .toLong().coerceIn(50L, (maxWait.toMillis() / timeScale).toLong())
                }
                delay(waitMs)
                now = nowNow()
                // Track the civil date of `now` in BOTH directions — the plan
                // of the day the instant belongs to is the one that serves.
                currentKey = currentKey.copy(
                    date = java.time.ZonedDateTime.ofInstant(now, currentKey.zoneId).toLocalDate(),
                )
            }
        }
        return state.asStateFlow()
    }

    /**
     * Preparatory fast boundary (a conventional margin before Fajr) from the
     * cached day's configuration — the host never recomputes it.
     */
    suspend fun imsak(key: DayKey, minutesBeforeFajr: Int = 10): Instant? =
        o.engine.prayer.windows.imsak(
            key.location, key.date, minutesBeforeFajr, key.settings.prayerConfig,
        )

    /**
     * The full prayer-row list for one day: five obligatory prayers, sunrise
     * as a boundary event and the voluntary Duha/Tahajjud references, sorted
     * by instant. Served from the cached DayModel's single computation.
     */
    suspend fun occasions(
        key: DayKey,
        categories: Set<PrayerOccasionCategory> = PrayerOccasionCategory.entries.toSet(),
    ): List<PrayerOccurrence> = o.dayModel(key).occasions.filter { it.category in categories }

    /**
     * Current-first "rotation" around [from]: the in-progress prayer plus the
     * next [count] - 1 entries, spanning midnight. NOTE: rotation entries are
     * the six-value enum; use [occasions] when optional rows are needed.
     */
    suspend fun upcoming(key: DayKey, from: Instant, count: Int = 5): List<PrayerEntry> =
        o.engine.prayer.upcoming(key.location, from, key.zoneId, count, key.settings.prayerConfig)

    /** Previous/current/next slice around [now]; same enum limitation as [upcoming]. */
    suspend fun navigationSequence(
        key: DayKey,
        now: Instant,
        before: Int = 0,
        after: Int = 1,
    ): List<PrayerEntry> = o.engine.prayer.navigationSequence(
        key.location, now, key.zoneId, before, after, key.settings.prayerConfig,
    )

    /** Next occurrence of one obligatory kind strictly after [after]. */
    suspend fun nextOccurrenceOf(
        key: DayKey,
        kind: PrayerStatus.Prayer,
        after: Instant,
    ): PrayerEntry? = o.engine.prayer.nextOccurrenceOf(
        kind, key.location, after, key.zoneId, key.settings.prayerConfig,
    )

    /** Tahajjud / Witr window for the night beginning on [key]'s date. */
    suspend fun tahajjudWindow(key: DayKey) =
        o.engine.prayer.tahajjudWindow(key.location, key.date, key.settings.prayerConfig)

    /** Night divisions (thirds, midpoint) for [key]'s date. */
    suspend fun nightDivisions(key: DayKey) =
        o.engine.prayer.nightDivisions(key.location, key.date, key.settings.prayerConfig)

    /** Times for an inclusive date range — month/table hosts use this. */
    suspend fun timesForRange(
        key: DayKey,
        start: LocalDate,
        endInclusive: LocalDate,
    ): List<com.khushu.engine.prayer.PrayerTimesResult> =
        o.engine.prayer.timesForRange(key.location, start, endInclusive, key.settings.prayerConfig)

    /** Whole civil month of times. */
    suspend fun month(
        key: DayKey,
        yearMonth: YearMonth,
    ): List<com.khushu.engine.prayer.PrayerTimesResult> =
        o.engine.prayer.month(key.location, yearMonth, key.settings.prayerConfig)

    suspend fun nextJumuah(key: DayKey, after: Instant): PrayerJumuah? =
        o.engine.prayer.nextJumuah(key.location, after, key.zoneId)

    /** Ishraq → Dhuhr-entry voluntary window. */
    suspend fun duha(key: DayKey): ClosedRange<Instant>? =
        o.engine.prayer.windows.duha(key.location, key.date, key.settings.prayerConfig)

    suspend fun forbidden(
        key: DayKey,
        lateAfternoonMarginMinutes: Int = 20,
    ): PrayerForbiddenWindows =
        o.engine.prayer.windows.forbidden(
            key.location, key.date, lateAfternoonMarginMinutes, key.settings.prayerConfig,
        )

    /** Imsak/fast-start/fast-end composite for the fasting card. */
    suspend fun fastingFacts(
        key: DayKey,
        imsakMinutesBeforeFajr: Int = 10,
    ): PrayerFastingFacts =
        o.engine.prayer.windows.fastingFacts(
            key.location, key.date, imsakMinutesBeforeFajr, key.settings.prayerConfig,
        )

    suspend fun fastingFactsForMonth(
        key: DayKey,
        yearMonth: YearMonth,
        imsakMinutesBeforeFajr: Int = 10,
    ): List<PrayerFastingFacts> =
        o.engine.prayer.windows.fastingFactsForMonth(
            key.location, yearMonth, imsakMinutesBeforeFajr, key.settings.prayerConfig,
        )

    suspend fun travelFacts(
        key: DayKey,
        travelledDistanceKm: com.khushu.engine.core.units.Kilometers,
        distanceThresholdKm: Double? = null,
    ): PrayerTravelFacts =
        o.engine.prayer.windows.travelFacts(
            key.location, key.date, travelledDistanceKm, distanceThresholdKm, key.settings.prayerConfig,
        )

    /** Completion streaks over host-owned logs; excused days are excluded. */
    suspend fun streak(
        records: List<PrayerLogRecord>,
        excusedRanges: List<ClosedRange<LocalDate>> = emptyList(),
        config: com.khushu.engine.prayer.PrayerConfiguration =
            com.khushu.engine.prayer.PrayerConfiguration(),
    ): PrayerStreakStats = o.engine.prayer.stats.streak(records, excusedRanges, config)

    /**
     * Upcoming prayer-entry instants — the pure "when" data for the host's
     * alarm scheduler (orch computes times; the host fires notifications).
     * Reads today's DayModel plus tomorrow's when the schedule crosses
     * midnight. Sorted ascending, first [count] entries at/after [from].
     */
    suspend fun alarmSchedule(key: DayKey, from: Instant, count: Int): List<AlarmInstant> {
        require(count in 1..20) { "count must be 1..20" }
        val out = mutableListOf<AlarmInstant>()
        var date = key.date
        var scanned = 0
        while (out.size < count) {
            // v1.4.2: polar day/night yields all-null raw times — the old loop
            // advanced dates forever. 400 days bounds any real schedule.
            check(++scanned <= 400) {
                "alarmSchedule: no computable prayer times within 400 days of ${key.date} " +
                    "at ${key.location} (polar day/night?)"
            }
            val times = o.dayModel(
                if (date == key.date) key else key.copy(date = date),
            ).prayerTimes
            for (kind in PrayerOrder) {
                val raw = times[kind]?.raw ?: continue
                if (!raw.isBefore(from)) out += AlarmInstant(raw, kind, date)
            }
            date = date.plusDays(1)
        }
        return out.sortedBy { it.instant }.take(count)
    }

    // ── status derivation (pure, over cached times) ───────────────────────

    private suspend fun deriveStatus(key: DayKey, now: Instant): PrayerStatus {
        val civilDate = java.time.ZonedDateTime.ofInstant(now, key.zoneId).toLocalDate()
        val civilModel = o.dayModel(key.copy(date = civilDate))
        val fajr = civilModel.prayerTimes.fajr.raw
        // Chain-extension: `now` beyond this model's chain end (into tomorrow's
        // fajr→sunrise window) or before its start (yesterday's isha tail) —
        // derive from the neighbouring day's chain.
        return when {
            civilModel.nextFajr != null && now.isAfter(civilModel.nextFajr) -> {
                val next = o.dayModel(key.copy(date = civilDate.plusDays(1)))
                derive(entriesOf(next, next.nextFajr), now)
            }
            fajr != null && now.isBefore(fajr) -> {
                val prev = o.dayModel(key.copy(date = civilDate.minusDays(1)))
                derive(entriesOf(prev, prev.nextFajr), now)
            }
            else -> derive(entriesOf(civilModel, civilModel.nextFajr), now)
        }
    }

    private fun entriesOf(
        model: DayModel,
        nextFajr: Instant?,
    ): List<Pair<PrayerStatus.Prayer, Instant>> = buildList {
        val t = model.prayerTimes
        t.fajr.raw?.let { add(PrayerStatus.Prayer.FAJR to it) }
        t.sunrise.raw?.let { add(PrayerStatus.Prayer.SUNRISE to it) }
        t.dhuhr.raw?.let { add(PrayerStatus.Prayer.DHUHR to it) }
        t.asr.raw?.let { add(PrayerStatus.Prayer.ASR to it) }
        t.maghrib.raw?.let { add(PrayerStatus.Prayer.MAGHRIB to it) }
        t.isha.raw?.let { add(PrayerStatus.Prayer.ISHA to it) }
        nextFajr?.let { add(PrayerStatus.Prayer.FAJR to it) }
    }.sortedBy { it.second }

    private fun derive(
        entries: List<Pair<PrayerStatus.Prayer, Instant>>,
        now: Instant,
    ): PrayerStatus {
        val nextIdx = entries.indexOfFirst { it.second > now }
        val next = entries.getOrNull(nextIdx)
        val prev = if (nextIdx > 0) entries[nextIdx - 1] else null

        return PrayerStatus(
            current = prev?.first,
            next = next?.first,
            currentStart = prev?.second,
            nextStart = next?.second,
            now = now,
        )
    }

    companion object {
        private val PrayerOrder = listOf(
            PrayerStatus.Prayer.FAJR,
            PrayerStatus.Prayer.SUNRISE,
            PrayerStatus.Prayer.DHUHR,
            PrayerStatus.Prayer.ASR,
            PrayerStatus.Prayer.MAGHRIB,
            PrayerStatus.Prayer.ISHA,
        )

        private operator fun com.khushu.engine.prayer.PrayerTimesResult.get(
            kind: PrayerStatus.Prayer,
        ) = when (kind) {
            PrayerStatus.Prayer.FAJR -> fajr
            PrayerStatus.Prayer.SUNRISE -> sunrise
            PrayerStatus.Prayer.DHUHR -> dhuhr
            PrayerStatus.Prayer.ASR -> asr
            PrayerStatus.Prayer.MAGHRIB -> maghrib
            PrayerStatus.Prayer.ISHA -> isha
        }
    }
}

/** Home-screen snapshot: the day's prayer times + current status + next boundary. */
data class PrayerDayOverview(
    val key: DayKey,
    val date: LocalDate,
    val zoneId: ZoneId,
    val times: com.khushu.engine.prayer.PrayerTimesResult,
    val status: PrayerStatus,
    val nextBoundary: Instant?,
)

/** One schedulable prayer entry — the pure "when" for host alarm scheduling. */
data class AlarmInstant(
    val instant: Instant,
    val prayer: PrayerStatus.Prayer,
    /** Civil date the prayer belongs to (disambiguates the post-midnight isha). */
    val date: LocalDate,
)
