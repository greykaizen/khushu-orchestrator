package com.khushu.data.adaptive

import com.khushu.data.model.Dua
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Prayer-derived anchors for one day — mirrors khushu-engine's PrayerTimesResult
 * field-for-field so hosts map mechanically (see khushu-orchestrator.dua.anchors).
 * Null = physically uncomputable (polar day/night) — windows degrade, never throw.
 */
data class PrayerAnchors(
    val fajr: Instant?,
    val sunrise: Instant?,
    val dhuhr: Instant?,
    val asr: Instant?,
    val maghrib: Instant?,
    val isha: Instant?,
    /** Islamic midnight (maghrib→fajr midpoint). */
    val midnight: Instant?,
    /** Last third of the night — Tahajjud threshold. */
    val lastThird: Instant?,
    /** Next day's fajr (night windows crossing midnight). */
    val nextFajr: Instant? = null,
)

/** Which instant opens the EVENING adhkar window. Documented school positions. */
enum class EveningStart { MAGHRIB, ASR }

/**
 * Time-of-day slot for adaptive dua sections. The corpus's time-relevant
 * subcategories map 1:1 (see [DuaTimeSlot.subcategory]); overlap resolution is
 * priority-ordered (lower value wins).
 */
enum class DuaTimeSlot(val subcategory: String, val priority: Int) {
    AFTER_SALAH("after-salah", 0),
    WAKING_UP("waking-up", 1),
    BEFORE_SLEEP("before-sleep", 2),
    TAHAJJUD("tahajjud", 2),
    MORNING("morning", 3),
    EVENING("evening", 3),
    ANYTIME("", 4);

    companion object {
        internal val BY_PRIORITY: List<DuaTimeSlot> = entries.sortedBy { it.priority }
    }
}

/**
 * Explicit, deterministic inputs for [DuaApi.adaptive]. No Clock — the host
 * passes `now`; same inputs → same sections (property-testable).
 *
 * @param anchors null → only ANYTIME sections (graceful no-location mode)
 * @param bedtime wakeTime host settings, zone-resolved; null → slot inactive
 * @param postPrayerDuration global AFTER_SALAH window length (host setting)
 * @param variant deterministic rotation seed; bump to rotate backfill picks
 */
data class AdaptiveContext(
    val now: Instant,
    val zoneId: ZoneId,
    val anchors: PrayerAnchors?,
    val bedtime: LocalTime? = null,
    val wakeTime: LocalTime? = null,
    val postPrayerDuration: Duration = Duration.ofMinutes(20),
    val eveningStart: EveningStart = EveningStart.MAGHRIB,
    val maxSections: Int = 2,
    val variant: Int = defaultVariant(now, zoneId),
) {
    init {
        require(maxSections in 1..6) { "maxSections must be 1..6" }
    }

    companion object {
        /** Daily-stable default rotation seed (lazy-host policy). */
        fun defaultVariant(now: Instant, zoneId: ZoneId): Int =
            LocalDate.ofInstant(now, zoneId).toEpochDay().toInt()
    }
}

/** One adaptive suggestion: opens the subcategory via DuaApi.bySubcategory. */
data class AdaptiveDuaSection(
    val slot: DuaTimeSlot,
    val subcategory: String,
    val title: String,
    val count: Int,
    /** Exact validity window; host re-adapts at `window.second`. */
    val window: Pair<Instant, Instant>?,
)

/** Slot result of one adaptive() evaluation — orchestrator DayModel input. */
internal data class SlotEvaluation(
    val slot: DuaTimeSlot,
    val window: Pair<Instant, Instant>?,
    val duas: List<Dua>,
)

/**
 * The slot-window table. Library-owned content knowledge (slot → corpus slug
 * windows over engine-derived anchors). All arithmetic in zone-local time for
 * host settings (bedtime/wake), absolute instants for prayer anchors.
 */
internal object SlotWindows {

    /** All windows active at [ctx.now] for the civil date of `now`. Sorted by priority. */
    suspend fun evaluate(
        ctx: AdaptiveContext,
        duasFor: suspend (String) -> List<Dua>,
    ): List<SlotEvaluation> {
        val active = mutableListOf<SlotEvaluation>()

        val a = ctx.anchors
        if (a != null) {
            // AFTER_SALAH: five windows, one per prayer entry.
            for (prayer in listOf(a.fajr, a.dhuhr, a.asr, a.maghrib, a.isha)) {
                val start = prayer ?: continue
                val end = start.plus(ctx.postPrayerDuration)
                if (ctx.now in start..<end) {
                    active += SlotEvaluation(DuaTimeSlot.AFTER_SALAH, start to end, duasFor(DuaTimeSlot.AFTER_SALAH.subcategory))
                    break // one salah window at a time by construction
                }
            }
            // MORNING: fajr → dhuhr
            a.fajr?.let { f -> a.dhuhr?.let { d ->
                if (ctx.now in f..<d) active += SlotEvaluation(DuaTimeSlot.MORNING, f to d, duasFor(DuaTimeSlot.MORNING.subcategory))
            } }
            // EVENING: eveningStart → midnight
            val evStart = when (ctx.eveningStart) {
                EveningStart.MAGHRIB -> a.maghrib
                EveningStart.ASR -> a.asr
            }
            evStart?.let { s -> a.midnight?.let { m ->
                if (ctx.now in s..<m) {
                    active += SlotEvaluation(DuaTimeSlot.EVENING, s to m, duasFor(DuaTimeSlot.EVENING.subcategory))
                }
            } }
            // TAHAJJUD: lastThird → next fajr (polar null → skip).
            a.lastThird?.let { lt -> a.nextFajr?.let { nf ->
                val end = maxOf(nf, lt)
                if (ctx.now in lt..<end) {
                    active += SlotEvaluation(DuaTimeSlot.TAHAJJUD, lt to end, duasFor(DuaTimeSlot.TAHAJJUD.subcategory))
                }
            } }
        }

        // Host-clock settings, zone-local.
        val today = LocalDate.ofInstant(ctx.now, ctx.zoneId)
        ctx.wakeTime?.let { t ->
            val start = t.atDate(today).atZone(ctx.zoneId).toInstant()
            val end = start.plus(Duration.ofMinutes(30))
            if (ctx.now in start..<end) active += SlotEvaluation(DuaTimeSlot.WAKING_UP, start to end, duasFor(DuaTimeSlot.WAKING_UP.subcategory))
        }
        ctx.bedtime?.let { t ->
            // Bedtime may be late evening (same day) or past midnight (previous-day anchor).
            for (anchorDate in listOf(today, today.minusDays(1))) {
                val start = t.atDate(anchorDate).atZone(ctx.zoneId).toInstant()
                val end = start.plus(Duration.ofMinutes(45))
                if (ctx.now in start..<end) {
                    active += SlotEvaluation(DuaTimeSlot.BEFORE_SLEEP, start to end, duasFor(DuaTimeSlot.BEFORE_SLEEP.subcategory))
                    break
                }
            }
        }

        return active.sortedBy { it.slot.priority }
    }
}
