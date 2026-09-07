package com.khushu.orchestrator

import com.khushu.data.adaptive.AdaptiveContext
import com.khushu.data.adaptive.AdaptiveDuaSection
import com.khushu.data.repo.KhushuContent
import com.khushu.data.transport.ContentFetcher
import com.khushu.engine.core.geo.Location
import com.khushu.engine.KhushuEngine
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Host-side per-day settings the orchestrator threads into engine + data-api
 * calls. Everything that can change ANY composed output is part of the
 * DayModel key — see [DayKey].
 */
data class DaySettings(
    val prayerConfig: com.khushu.engine.prayer.PrayerConfiguration =
        com.khushu.engine.prayer.PrayerConfiguration(),
    /** Local bedtime; null → BEFORE_SLEEP slot inactive. */
    val bedtime: LocalTime? = null,
    /** Local wake time; null → WAKING_UP slot inactive. */
    val wakeTime: LocalTime? = null,
    /** Global AFTER_SALAH window length (host setting, default 20 min). */
    val postPrayerDuration: Duration = Duration.ofMinutes(20),
    /** Which instant opens the EVENING adhkar window. */
    val eveningStart: com.khushu.data.adaptive.EveningStart =
        com.khushu.data.adaptive.EveningStart.MAGHRIB,
    /** Max adaptive action buttons/sections. */
    val maxSections: Int = 2,
) {
    init {
        require(maxSections in 1..6) { "maxSections must be 1..6" }
    }
}

/**
 * Everything that can change any composed output for a day. Identity is
 * semantic: two identical keys share one DayModel build (one prayer-time
 * computation, one slot-window set — zero recompute).
 */
data class DayKey(
    val location: Location,
    val date: LocalDate,
    val zoneId: ZoneId,
    val settings: DaySettings,
)

/**
 * The per-day plan — built ONCE per [DayKey], served by lookup for the rest of
 * the day. This is the orchestrator's recomputation killer: prayer times,
 * anchors, the full adaptive slot set, and every trigger boundary are
 * computed a single time; [sections] and [nextBoundary] are O(log n)/O(n-tiny)
 * reads afterwards.
 *
 * Deterministic: same inputs → same plan (no Clock anywhere).
 */
class DayModel internal constructor(
    val key: DayKey,
    /** Today's prayer computation (raw + adjusted). */
    val prayerTimes: com.khushu.engine.prayer.PrayerTimesResult,
    /** Tomorrow's fajr (night windows crossing midnight; null polar). */
    val nextFajr: Instant?,
    /** Adaptive sections valid across the whole day (union of slot matches). */
    val allSections: List<AdaptiveDuaSection>,
) {
    /**
     * Trigger boundaries, sorted ascending: every instant where the correct
     * adaptive section set CHANGES (prayer entries opening AFTER_SALAH, slot
     * window ends, day rollover). The host (or adaptiveFlow) wakes at exactly
     * these — never polls, never recomputes.
     */
    val boundaries: List<Instant>

    init {
        val b = sortedSetOf<Instant>()
        for (s in allSections) {
            val w = s.window
            if (w != null) { b.add(w.first); b.add(w.second) }
        }
        // EVERY prayer entry is a boundary — statusFlow sleeps until the next
        // one, so omitting any entry makes the countdown go stale for up to
        // maxWait (v1.4.2 fix: only maghrib/isha were listed).
        for (t in listOf(
            prayerTimes.fajr, prayerTimes.sunrise, prayerTimes.dhuhr,
            prayerTimes.asr, prayerTimes.maghrib, prayerTimes.isha,
        )) {
            t.raw?.let { b.add(it) }
        }
        nextFajr?.let { b.add(it) }
        b.add(key.date.plusDays(1).atStartOfDay(key.zoneId).toInstant())
        boundaries = b.toList()
    }

    /** Next trigger boundary strictly after [now] (null only past day end). */
    fun nextBoundary(now: Instant): Instant? = boundaries.firstOrNull { it > now }

    /**
     * Sections active at [now]: slot matches (priority order) + ANYTIME
     * backfill to maxSections. Served from the precomputed [allSections] —
     * NO re-evaluation of window tables, NO engine recompute, NO refetch.
     */
    fun sections(now: Instant): List<AdaptiveDuaSection> {
        val active = allSections.filter { s ->
            val w = s.window
            w == null || (now >= w.first && now < w.second)
        }
        // Re-apply the data-api contract: strict time-slot matches first
        // (priority order), then ANYTIME backfill. The union across probe
        // points loses this ordering — restore it here.
        val strict = active.filter { it.window != null }
            .sortedBy { it.slot.priority }
        val backfill = active.filter { it.window == null }
        return (strict + backfill).take(key.settings.maxSections)
    }
}

/**
 * LRU over day plans. Capacity 4: yesterday (midnight-window tail), today,
 * tomorrow (warmup), one spare.
 *
 * Fully mutex-guarded — peek, get and clear serialize on [mutex]; there is NO
 * lock-free path (an access-order LinkedHashMap mutates on every read, so an
 * unsynchronized peek raced with a mid-build get — v1.4.2 fix).
 */
internal class DayModelCache(private val capacity: Int = 4) {
    private val mutex = Mutex()
    private val map = LinkedHashMap<DayKey, DayModel>(capacity, 0.75f, true)

    /** Non-building lookup — null when absent. Suspend: shares the build mutex. */
    suspend fun peek(key: DayKey): DayModel? = mutex.withLock { map[key] }

    suspend fun get(key: DayKey, build: suspend (DayKey) -> DayModel): DayModel =
        mutex.withLock {
            map[key]?.let { return it }
            val model = build(key)
            map[key] = model
            while (map.size > capacity) {
                map.remove(map.keys.first())
            }
            model
        }

    suspend fun clear() {
        // v1.4.2: actually under the mutex — the old comment claimed it was.
        mutex.withLock { map.clear() }
    }

    /** Drop one day's plan only — forceRecompute no longer nukes the whole LRU. */
    suspend fun invalidate(key: DayKey) {
        mutex.withLock { map.remove(key) }
    }
}

/**
 * The orchestrator facade — host adds ONE dependency and receives engine +
 * data-api transitively. Namespaces hold named composites only (doctrine:
 * compose fetch+math, never own domain logic).
 *
 * SINGLETON CONTRACT: create ONE instance per process (app-scoped) and share
 * it everywhere — the [DayModelCache] and mushaf bundle memo are
 * instance-scoped and Mutex-guarded (safe for concurrent suspend callers);
 * a second instance would duplicate per-day computations. Construction
 * requires the host's transport decision (cache dir + fetcher), so the
 * singleton lives in the host's DI/AppContainer, not here. [warmup] is the
 * app-start hook: prebuild the day plan before first frame.
 */
class KhushuOrchestrator(
    // `internal`: the wall. Hosts compose through the namespaces + [content]/
    // [downloads] delegation surfaces — direct engine/data calls (and their
    // recomputation costs) are uncompilable from host code.
    internal val engine: KhushuEngine = KhushuEngine(),
    /** Host transport decision (cache dir + fetcher) — the singleton's only construction input. */
    fetcher: ContentFetcher,
) : AutoCloseable {
    /** Content retrieval — absorbed from khushu-data-api (v1.4.0); package `com.khushu.data` retained. */
    internal val data: KhushuContent = KhushuContent(fetcher)
    private val cache = DayModelCache()

    val dua = DuaNamespace(this)
    val prayer = PrayerNamespace(this)
    val calendar = CalendarNamespace(this)
    val mushaf = MushafNamespace(this)

    /** Read-only delegation over data-api retrieval — see [ContentNamespace]. */
    val content = ContentNamespace(this)

    /** Download plans + batch execution — see [DownloadsNamespace]. */
    val downloads = DownloadsNamespace(this)

    /** Offline hadith corpora lifecycle (attach → search/books) — see [SunnahNamespace]. */
    val sunnah = SunnahNamespace(this)

    /**
     * Release SQLite connections and any closeable data-layer state on process
     * teardown. The data layer's close runs ON the single SQLite thread — an
     * in-flight sunnah/FTS query drains before its connection is released
     * (v1.6.0: this used to race the dispatcher). The instance stays usable
     * for sunnah queries only after a fresh [SunnahNamespace.attach] — the
     * DayModel cache rebuilds lazily.
     */
    override fun close() {
        // runBlocking here is teardown-only (process death), never a request
        // path — the one sanctioned blocking bridge in the artifact.
        kotlinx.coroutines.runBlocking { withContext(data.sqlDispatcher) { data.close() } }
    }

    /**
     * Build (or fetch cached) the [DayModel] for [key]. The ONLY place prayer
     * times are computed for the day; adaptive serves read from it.
     */
    suspend fun dayModel(key: DayKey, forceRecompute: Boolean = false): DayModel {
        if (forceRecompute) cache.invalidate(key)
        return cache.get(key) { build(it) }
    }
    /**
     * App-start hook: prebuild the DayModel for [today] (and optionally
     * [tomorrow]'s — the night windows already span into it) and warm the
     * mushaf bundle assets for [mushafBundle] when supplied. Guarantees the
     * first frame never pays the day-build or bundle-load cost. Idempotent —
     * everything hits the instance caches.
     */
    suspend fun warmup(
        today: DayKey,
        includeTomorrow: Boolean = false,
        mushafBundle: Pair<String, String>? = null,
        mushafPages: IntRange = 1..5,
    ) {
        dayModel(today)
        if (includeTomorrow) {
            dayModel(today.copy(date = today.date.plusDays(1)))
        }
        mushafBundle?.let { bundleSpec -> mushaf.prefetchPages(bundleSpec.first, bundleSpec.second, mushafPages) }
    }

    private suspend fun build(key: DayKey): DayModel {
        // Slug-index the corpus once per build: every probe-point evaluation
        // below reads pre-grouped lists (O(1) per slot) instead of re-filtering
        // the 491-dua corpus.
        val bySlug = HashMap<String, MutableList<com.khushu.data.model.Dua>>()
        for (d in data.dua.duas()) bySlug.getOrPut(d.subcategory) { mutableListOf() }.add(d)

        val times = engine.prayer.times(
            key.location, key.date, key.settings.prayerConfig,
        )
        val nextFajr = engine.prayer.times(
            key.location, key.date.plusDays(1), key.settings.prayerConfig,
        ).fajr.raw

        val anchors = dua.anchors(times, nextFajr)
        val now = key.date.atStartOfDay(key.zoneId).toInstant()
        val ctx = AdaptiveContext(
            now = now,
            zoneId = key.zoneId,
            anchors = anchors,
            bedtime = key.settings.bedtime,
            wakeTime = key.settings.wakeTime,
            postPrayerDuration = key.settings.postPrayerDuration,
            eveningStart = key.settings.eveningStart,
            maxSections = 6, // build the FULL day set; DayModel.sections() applies the host's max
        )
        // Evaluate the day across its whole span: probe the day at each prayer
        // boundary + host-setting window start so every slot match is captured.
        val probePoints = buildList {
            add(now)
            listOf(times.fajr.raw, times.sunrise.raw, times.dhuhr.raw, times.asr.raw,
                times.maghrib.raw, times.isha.raw).forEach { b -> b?.let { add(it) } }
            anchors.let {
                it.lastThird?.let(::add)
                it.midnight?.let(::add)
                nextFajr?.let(::add)
            }
            key.settings.bedtime?.let { t -> add(t.atDate(key.date).atZone(key.zoneId).toInstant()) }
            key.settings.wakeTime?.let { t -> add(t.atDate(key.date).atZone(key.zoneId).toInstant()) }
        }.distinct().sorted()

        val seen = LinkedHashMap<Pair<String, Pair<Instant, Instant>?>, AdaptiveDuaSection>()
        for (probe in probePoints) {
            for (s in data.dua.adaptive(ctx.copy(now = probe), bySlug)) {
                seen.putIfAbsent(s.subcategory to s.window, s)
            }
        }
        return DayModel(key, times, nextFajr, seen.values.toList())
    }
}
