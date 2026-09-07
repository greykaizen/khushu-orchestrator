package com.khushu.orchestrator

import com.khushu.data.transport.LocalFetcher
import okio.Path.Companion.toPath
import com.khushu.engine.core.geo.AltitudeMeters
import com.khushu.engine.core.geo.Latitude
import com.khushu.engine.core.geo.Location
import com.khushu.engine.core.geo.Longitude
import com.khushu.engine.prayer.PrayerStatus
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** v1.3.0: prayer statusFlow + alarmSchedule + calendar monthGrid composites. */
class FacadeV13Test {

    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "assets/dua_dhikr/dua_data.json").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/assets/dua_dhikr/dua_data.json").exists() }
                ?.let { File(it, "khushu-data-api") }
            ?: error("khushu-data-api repo root not found")

    private val london = ZoneId.of("Europe/London")
    private val loc = Location(Latitude(51.5074), Longitude(-0.1278), AltitudeMeters(11.0))
    private val date = LocalDate.of(2026, 6, 1)

    private fun orchestrator() =
        KhushuOrchestrator(fetcher = LocalFetcher(repoRoot.absolutePath.toPath()))

    private fun key() = DayKey(loc, date, london, DaySettings())

    // ── status (sync, cached) ─────────────────────────────────────────────

    @Test
    fun statusServesCurrentAndNextFromCachedModel() = runBlocking {
        val o = orchestrator()
        val model = o.dayModel(key())
        val fajr = model.prayerTimes.fajr.raw!!
        val asr = model.prayerTimes.asr.raw!!

        val duringFajr = o.prayer.status(key(), fajr.plusSeconds(60))
        assertEquals(PrayerStatus.Prayer.FAJR, duringFajr.current)
        assertEquals(PrayerStatus.Prayer.SUNRISE, duringFajr.next)
        assertTrue(duringFajr.nextStart!! > duringFajr.now)
        assertNotNull(duringFajr.countdown())

        val duringAsr = o.prayer.status(key(), asr.plusSeconds(60))
        assertEquals(PrayerStatus.Prayer.ASR, duringAsr.current)
        assertEquals(PrayerStatus.Prayer.MAGHRIB, duringAsr.next)
    }

    @Test
    fun statusCrossesMidnightIntoTomorrowFajr() = runBlocking {
        val o = orchestrator()
        val model = o.dayModel(key())
        // London June: isha 23:58Z, tomorrow's fajr 23:59Z — a ~1-min window.
        // Probe just before the next fajr entry: still isha's window.
        val nextFajr = model.nextFajr!!
        val lateNight = nextFajr.minusSeconds(30)
        val status = o.prayer.status(key(), lateNight)
        assertEquals(PrayerStatus.Prayer.ISHA, status.current, "isha window runs until the next fajr entry")
        assertEquals(PrayerStatus.Prayer.FAJR, status.next, "next is tomorrow's fajr")
    }

    // ── statusFlow ────────────────────────────────────────────────────────

    @Test
    fun statusFlowAdvancesAtPrayerBoundaries() = runBlocking {
        val o = orchestrator()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        // London June: June 1's fajr = 23:59Z May 31 — the prayer chain covers
        // the whole timeline. Start in May 31's maghrib window (civil May 31),
        // then cross the fajr entry → key swaps to June 1.
        var virtualNow = date.minusDays(1).atTime(21, 30).atZone(london).toInstant()
        try {
            val flow = o.prayer.statusFlow(
                key(), scope, virtualNow, nowNow = { virtualNow }, timeScale = 3600.0,
            )
            withTimeout(15_000) {
                while (flow.value.current != PrayerStatus.Prayer.MAGHRIB) delay(25)
            }
            assertEquals(PrayerStatus.Prayer.ISHA, flow.value.next)

            // jump across the fajr entry: civil date rolls May 31 → June 1
            val fajr = o.dayModel(key()).prayerTimes.fajr.raw!!
            virtualNow = fajr.plusSeconds(30)
            withTimeout(15_000) {
                while (flow.value.current != PrayerStatus.Prayer.FAJR) delay(25)
            }
            assertEquals(PrayerStatus.Prayer.FAJR, flow.value.current)
            assertEquals(PrayerStatus.Prayer.SUNRISE, flow.value.next)
        } finally {
            scope.cancel()
        }
    }

    // ── alarmSchedule ─────────────────────────────────────────────────────

    @Test
    fun alarmScheduleReturnsSortedFuturePrayerEntries() = runBlocking {
        val o = orchestrator()
        val model = o.dayModel(key())
        val from = model.prayerTimes.asr.raw!!
        val alarms = o.prayer.alarmSchedule(key(), from, count = 5)

        assertEquals(5, alarms.size)
        assertTrue(alarms.zipWithNext().all { (a, b) -> a.instant <= b.instant }, "sorted ascending")
        assertTrue(alarms.all { it.instant >= from })
        // first three: asr, maghrib, isha of today; then tomorrow's fajr, sunrise
        assertEquals(PrayerStatus.Prayer.ASR, alarms[0].prayer)
        assertEquals(PrayerStatus.Prayer.MAGHRIB, alarms[1].prayer)
        assertEquals(PrayerStatus.Prayer.ISHA, alarms[2].prayer)
        assertEquals(PrayerStatus.Prayer.FAJR, alarms[3].prayer)
        assertEquals(date.plusDays(1), alarms[3].date, "tomorrow's fajr carries tomorrow's date")
    }

    @Test
    fun alarmScheduleTerminatesOrThrowsAtPolarLocation() = runBlocking {
        // v1.4.2 regression: all-null prayer times (polar) looped forever.
        // The 400-day scan cap must surface as an IllegalStateException.
        val o = orchestrator()
        val oslo = ZoneId.of("Europe/Oslo")
        val tromso = Location(Latitude(69.6492), Longitude(18.9553), AltitudeMeters(10.0))
        val polarKey = DayKey(tromso, LocalDate.of(2026, 12, 21), oslo, DaySettings())
        val from = LocalDate.of(2026, 12, 21).atStartOfDay(oslo).toInstant()
        val result = runCatching { o.prayer.alarmSchedule(polarKey, from, count = 5) }
        if (result.isFailure) {
            assertTrue(result.exceptionOrNull() is IllegalStateException, "cap must throw ISE")
        } else {
            // dhuhr remains computable at the pole (transit) — the loop must
            // still terminate with real entries, never hang.
            assertTrue(result.getOrNull()!!.isNotEmpty())
        }
    }

    @Test
    fun alarmScheduleFivePrayersPerDay() = runBlocking {
        val o = orchestrator()
        // probe from May 31 evening: first 5 entries = the full June-1 sequence
        val from = date.minusDays(1).atTime(22, 0).atZone(london).toInstant()
        val alarms = o.prayer.alarmSchedule(key(), from, count = 5)
        assertEquals(5, alarms.size)
        val kinds = alarms.map { it.prayer }
        assertEquals(
            listOf(
                PrayerStatus.Prayer.FAJR, PrayerStatus.Prayer.SUNRISE,
                PrayerStatus.Prayer.DHUHR, PrayerStatus.Prayer.ASR,
                PrayerStatus.Prayer.MAGHRIB,
            ),
            kinds,
        )
    }

    // ── calendar.monthGrid ────────────────────────────────────────────────

    @Test
    fun monthGridCoversTheWholeMonthWithDualDates() = runBlocking {
        val o = orchestrator()
        val grid = o.calendar.monthGrid(
            YearMonth.of(2026, 6), loc, london,
            config = com.khushu.engine.calendar.CalendarConfiguration(
                primary = com.khushu.engine.calendar.CalendarConfiguration.Side.HIJRI,
                secondary = com.khushu.engine.calendar.CalendarConfiguration.Side.GREGORIAN,
            ),
        )
        assertEquals(30, grid.days.size, "June has 30 days")
        assertTrue(grid.days.all { it.summary.dual.primary != null })
        // moon facts ride along from the engine one-pass
        assertTrue(grid.days.all { it.summary.moonIllumination in 0.0..1.0 })
        // display-join never crashes and returns a (possibly empty) list per day
        assertTrue(grid.days.all { it.displayEvents.size >= 0 })
    }

    @Test
    fun monthGridJoinsDisplayEntriesOnHijriDay() = runBlocking {
        val o = orchestrator()
        // Ramadan 1447 begins ~2026-02-18; the 1st carries the Ramadan display entry
        val grid = o.calendar.monthGrid(
            YearMonth.of(2026, 2), loc, london,
            config = com.khushu.engine.calendar.CalendarConfiguration(
                primary = com.khushu.engine.calendar.CalendarConfiguration.Side.HIJRI,
            ),
        )
        val firstOfDay = grid.days.first()
        val hijri = firstOfDay.summary.dual.primary.let {
            it as com.khushu.engine.calendar.DateLine.Hijri
        }.date
        // display join: any event entries for this hijri day come from the data-api corpus
        val expected = o.content.islamicEventsForHijriMonth(hijri.month).filter { it.hijriDay == hijri.day }
        assertEquals(expected.size, firstOfDay.displayEvents.size)
    }

    // ── v1.6.0: wall completion — quran search + lifecycle ────────────────

    @Test
    fun quranSearchServesThroughTheWall() = runBlocking {
        val o = orchestrator()
        // Same golden query as the data-layer QuranSliceTest FTS contract.
        val hits = o.content.quranSearch("الرحمن الرحيم", limit = 5)
        assertTrue(hits.isNotEmpty(), "FTS must serve الرحمن الرحيم through the wall")
        // Donor-parity assertion (QuranSliceTest): al-Fatihah 1 is among the hits.
        assertTrue(hits.any { it.surahNo == 1 && it.ayahNo == 1 })
        assertTrue(hits.all { it.text.isNotBlank() })
    }

    @Test
    fun quranSearchIsConcurrencySafe() = runBlocking {
        // Two concurrent first-calls must not race the index into existence
        // (v1.6.0: the old lazy init could leak a second connection + index).
        val o = orchestrator()
        val hits = coroutineScope {
            listOf(
                async { o.content.quranSearch("الله") },
                async { o.content.quranSearch("الناس") },
            ).map { it.await() }
        }
        assertTrue(hits.all { it.isNotEmpty() })
        o.close()
        // After close, a fresh search rebuilds the (in-memory) index — the
        // instance stays usable rather than crashing on a closed connection.
        assertTrue(o.content.quranSearch("الله", limit = 3).isNotEmpty())
    }

    @Test
    fun closeDrainsSqlThenReleasesAndSunnahReattaches() = runBlocking {
        val o = orchestrator()
        val corporaRoot = File(repoRoot, "inventory/hadiths")
        val sideIndex = File(repoRoot, "build/sunnah-search-index.db")
        o.sunnah.attach(corporaRoot, searchIndexDb = sideIndex)
        assertNotNull(o.sunnah.hadith("bukhari_urn_100010", lang = "en"))
        o.close()
        // close() released the corpora; a fresh attach reopens them cleanly.
        o.sunnah.attach(corporaRoot, searchIndexDb = sideIndex)
        assertNotNull(o.sunnah.hadith("bukhari_urn_100010", lang = "en"))
        o.sunnah.close()
    }
}
