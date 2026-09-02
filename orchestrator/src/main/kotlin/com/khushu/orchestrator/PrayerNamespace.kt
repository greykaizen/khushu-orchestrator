package com.khushu.orchestrator

import com.khushu.engine.prayer.PrayerStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Prayer presentation composites — served from the cached [DayModel] (zero
 * recomputation; every window/derivation below reads precomputed prayer
 * times). The home countdown loop reuses the exact boundary-wake machinery
 * proven in [DuaNamespace.adaptiveFlow].
 *
 * NOTIFICATION CONTRACT: this namespace computes WHEN; the host schedules
 * HOW (AlarmManager/channels/boot-restore stay host-side — family doctrine:
 * notifications/scheduling are caller-side).
 */
class PrayerNamespace internal constructor(private val o: KhushuOrchestrator) {

    /**
     * Current prayer state at [now], derived from the DayModel's cached
     * [com.khushu.engine.prayer.PrayerTimesResult] — the engine's own
     * [PrayerStatus] shape (same API, no recomputation).
     */
    fun status(key: DayKey, now: Instant): PrayerStatus =
        deriveStatus(key, now)

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
        val state = MutableStateFlow(deriveStatus(key, startNow))
        scope.launch {
            var now = startNow
            var currentKey = key
            while (true) {
                state.value = deriveStatus(currentKey, now)
                val boundary = o.dayModelSync(currentKey).nextBoundary(now)
                val waitMs = when (boundary) {
                    null -> (maxWait.toMillis() / timeScale).toLong().coerceAtLeast(50L)
                    else -> (Duration.between(now, boundary).toMillis() / timeScale)
                        .toLong().coerceIn(50L, (maxWait.toMillis() / timeScale).toLong())
                }
                withTimeoutOrNull(waitMs) { delay(Long.MAX_VALUE) }
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
     * Upcoming prayer-entry instants — the pure "when" data for the host's
     * alarm scheduler (orch computes times; the host fires notifications).
     * Reads today's DayModel plus tomorrow's when the schedule crosses
     * midnight. Sorted ascending, first [count] entries at/after [from].
     */
    fun alarmSchedule(key: DayKey, from: Instant, count: Int): List<AlarmInstant> {
        require(count in 1..20) { "count must be 1..20" }
        val out = mutableListOf<AlarmInstant>()
        var date = key.date
        while (out.size < count) {
            val times = o.dayModelSync(
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

    private fun deriveStatus(key: DayKey, now: Instant): PrayerStatus {
        val civilDate = java.time.ZonedDateTime.ofInstant(now, key.zoneId).toLocalDate()
        val civilModel = o.dayModelSync(key.copy(date = civilDate))
        val fajr = civilModel.prayerTimes.fajr.raw
        // Chain-extension: `now` beyond this model's chain end (into tomorrow's
        // fajr→sunrise window) or before its start (yesterday's isha tail) —
        // derive from the neighbouring day's chain.
        return when {
            civilModel.nextFajr != null && now.isAfter(civilModel.nextFajr) ->
                derive(neighbourEntries(o.dayModelSync(key.copy(date = civilDate.plusDays(1)))), now)
            fajr != null && now.isBefore(fajr) ->
                derive(neighbourEntries(o.dayModelSync(key.copy(date = civilDate.minusDays(1)))), now)
            else -> derive(entriesOf(civilModel, civilModel.nextFajr), now)
        }
    }

    /** Neighbour-day chain (full entry list; its own nextFajr edge irrelevant near `now`). */
    private fun neighbourEntries(model: DayModel): List<Pair<PrayerStatus.Prayer, Instant>> {
        val t = model.prayerTimes
        return buildList {
            t.fajr.raw?.let { add(PrayerStatus.Prayer.FAJR to it) }
            t.sunrise.raw?.let { add(PrayerStatus.Prayer.SUNRISE to it) }
            t.dhuhr.raw?.let { add(PrayerStatus.Prayer.DHUHR to it) }
            t.asr.raw?.let { add(PrayerStatus.Prayer.ASR to it) }
            t.maghrib.raw?.let { add(PrayerStatus.Prayer.MAGHRIB to it) }
            t.isha.raw?.let { add(PrayerStatus.Prayer.ISHA to it) }
            model.nextFajr?.let { add(PrayerStatus.Prayer.FAJR to it) }
        }.sortedBy { it.second }
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

/** One schedulable prayer entry — the pure "when" for host alarm scheduling. */
data class AlarmInstant(
    val instant: Instant,
    val prayer: PrayerStatus.Prayer,
    /** Civil date the prayer belongs to (disambiguates the post-midnight isha). */
    val date: LocalDate,
)
