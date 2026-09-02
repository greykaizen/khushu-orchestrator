package com.khushu.orchestrator

import com.khushu.data.adaptive.AdaptiveContext
import com.khushu.data.adaptive.AdaptiveDuaSection
import com.khushu.data.adaptive.PrayerAnchors
import com.khushu.data.repo.KhushuContent
import com.khushu.engine.prayer.PrayerTimesResult
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Prayer ↔ content adaptive composite: engine prayer facts → data-api dua
 * sections, with the trigger loop that wakes exactly at precomputed day
 * boundaries (prayer entries, window expiries, day rollover) — never polls.
 */
class DuaNamespace internal constructor(private val o: KhushuOrchestrator) {

    /**
     * The mechanical engine→data mapping. PrayerTimesResult carries per-prayer
     * [raw, adjusted]; adaptive windows anchor on RAW times (offsets are a
     * display concern, not a worship-window concern).
     */
    fun anchors(t: PrayerTimesResult, nextFajr: Instant?): PrayerAnchors = PrayerAnchors(
        fajr = t.fajr.raw,
        sunrise = t.sunrise.raw,
        dhuhr = t.dhuhr.raw,
        asr = t.asr.raw,
        maghrib = t.maghrib.raw,
        isha = t.isha.raw,
        midnight = t.midnight,
        lastThird = t.lastThirdOfNight,
        nextFajr = nextFajr,
    )

    /**
     * Adaptive sections for [now] — served from the cached [DayModel] (no
     * recompute; no refetch). Cache miss builds the day once.
     */
    suspend fun sections(
        key: DayKey,
        now: Instant,
        forceRecompute: Boolean = false,
    ): List<AdaptiveDuaSection> = o.dayModel(key, forceRecompute).sections(now)

    /**
     * The smart real-time loop: emits the active section set, then sleeps
     * until the DayModel's next precomputed boundary, re-emits, repeats.
     * All recomputation already happened at [dayModel] build time — every
     * emission here is a lookup. Virtual-time testable (delay honors test
     * dispatchers). Emissions stop when the scope is cancelled.
     */
    fun adaptiveFlow(
        key: DayKey,
        scope: CoroutineScope,
        startNow: Instant,
        maxWait: java.time.Duration = java.time.Duration.ofHours(1),
        /** Time source — inject a virtual clock in tests. */
        nowNow: () -> Instant = { Instant.now() },
        /**
         * Time compression: how many real ms one virtual ms costs in the
         * boundary sleep. 1.0 = production (sleep real gaps); tests pass
         * e.g. 3600.0 to turn an hour-long boundary gap into a 1s wait.
         */
        timeScale: Double = 1.0,
    ): StateFlow<List<AdaptiveDuaSection>> {
        require(timeScale > 0) { "timeScale must be positive" }
        val state = MutableStateFlow<List<AdaptiveDuaSection>>(emptyList())
        scope.launch {
            var now = startNow
            var currentKey = key
            while (true) {
                val model = o.dayModel(currentKey)
                val sections = model.sections(now)
                state.value = sections
                // Wake exactly when the world changes; cap wait so late wall-clock
                // drift (settings edits, resume) still re-evaluates periodically.
                val boundary = model.nextBoundary(now)
                val waitMs = when (boundary) {
                    null -> (maxWait.toMillis() / timeScale).toLong().coerceAtLeast(50L)
                    else -> (java.time.Duration.between(now, boundary).toMillis() / timeScale)
                        .toLong().coerceIn(50L, (maxWait.toMillis() / timeScale).toLong())
                }
                withTimeoutOrNull(waitMs) { delay(Long.MAX_VALUE) }
                now = nowNow()
                // Day rollover → new semantic key (new day, fresh plan).
                val zoneNow = java.time.ZonedDateTime.ofInstant(now, currentKey.zoneId)
                val dayForNow = zoneNow.toLocalDate()
                if (dayForNow != currentKey.date) {
                    currentKey = currentKey.copy(date = dayForNow)
                }
            }
        }
        return state.asStateFlow()
    }
}
