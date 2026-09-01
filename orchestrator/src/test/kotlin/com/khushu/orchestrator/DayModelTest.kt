package com.khushu.orchestrator

import com.khushu.data.transport.LocalFetcher
import okio.Path.Companion.toPath
import com.khushu.engine.core.geo.AltitudeMeters
import com.khushu.engine.core.geo.Latitude
import com.khushu.engine.core.geo.Location
import com.khushu.engine.core.geo.Longitude
import com.khushu.engine.KhushuEngine
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * DayModel + adaptive composites against REAL engine prayer times (London,
 * summer) and REAL repo content. Engine computation is deterministic — these
 * are golden-sequence tests over a full day's adaptive rotation.
 */
class DayModelTest {

    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "assets/dua_dhikr/dua_data.json").exists() }
            // composite-build sibling checkout
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-quran-data/assets/dua_dhikr/dua_data.json").exists() }
                ?.let { File(it, "khushu-quran-data") }
            ?: error("khushu-quran-data repo root not found (run with -PlocalFamily against sibling checkout)")

    private val london = ZoneId.of("Europe/London")
    private val londonLocation = Location(Latitude(51.5074), Longitude(-0.1278), AltitudeMeters(11.0))
    private val date = LocalDate.of(2026, 6, 1)

    private fun orchestrator(): KhushuOrchestrator {
        val fetcher = LocalFetcher(repoRoot.absolutePath.toPath())
        return KhushuOrchestrator(engine = KhushuEngine(), data = com.khushu.data.repo.KhushuContent(fetcher))
    }

    private fun key(settings: DaySettings = DaySettings()) = DayKey(londonLocation, date, london, settings)

    private fun at(hour: Int, minute: Int = 0): Instant =
        date.atTime(hour, minute).atZone(london).toInstant()

    // ── day build ──────────────────────────────────────────────────────────

    @Test
    fun dayModelBuildsWithRealEngineTimes() = runTest {
        val model = orchestrator().dayModel(key())
        assertEquals(date, model.key.date)
        // Real London summer solstice-ish values: fajr before sunrise, isha after maghrib.
        val f = model.prayerTimes.fajr.raw
        val s = model.prayerTimes.sunrise.raw
        val m = model.prayerTimes.maghrib.raw
        assertNotNull(f); assertNotNull(s); assertNotNull(m)
        assertTrue(f < s && s < m)
        assertNotNull(model.nextFajr, "London has a computable tomorrow-fajr")
    }

    @Test
    fun dayModelIsCachedNoRebuild() = runTest {
        val o = orchestrator()
        val a = o.dayModel(key())
        val b = o.dayModel(key())
        assertTrue(a === b, "same DayKey must return the same cached plan instance")
    }

    // ── adaptive section serving across the day ────────────────────────────

    @Test
    fun fullDayRotationServesRightSlots() = runTest {
        val model = orchestrator().dayModel(key())
        val sectionsAt = { i: Instant -> model.sections(i).map { it.subcategory } }

        // After fajr entry (post-prayer window): after-salah first, then morning backfill…
        val postFajr = sectionsAt(model.prayerTimes.fajr.raw!!.plusSeconds(60))
        assertTrue("after-salah" in postFajr, "post-fajr must include after-salah: $postFajr")

        // Mid-morning (after post-prayer window, before dhuhr): morning slot
        val lateMorning = sectionsAt(model.prayerTimes.dhuhr.raw!!.minusSeconds(3600))
        assertTrue("morning" in lateMorning, "late morning serves morning: $lateMorning")

        // After maghrib (post-prayer window + evening): after-salah outranks evening
        val postMaghrib = sectionsAt(model.prayerTimes.maghrib.raw!!.plusSeconds(60))
        assertTrue("after-salah" in postMaghrib, "$postMaghrib")

        // Deep evening (past isha, before midnight): evening
        val evening = sectionsAt(model.prayerTimes.midnight!!.minusSeconds(600))
        assertTrue("evening" in evening, "$evening")

        // Last third → tahajjud
        val tahajjud = sectionsAt(model.prayerTimes.lastThirdOfNight!!.plusSeconds(600))
        assertTrue("tahajjud" in tahajjud, "$tahajjud")
    }

    @Test
    fun bedtimeSettingActivatesBeforeSleep() = runTest {
        val settings = DaySettings(bedtime = LocalTime.of(23, 30)) // 23:30 local = 22:30Z
        val model = orchestrator().dayModel(key(settings))
        val probe = date.atTime(23, 40).atZone(london).toInstant() // 23:40 local, inside 45-min window
        val sections = model.sections(probe).map { it.subcategory }
        assertTrue("before-sleep" in sections, "$sections")
    }

    @Test
    fun wakeSettingActivatesWakingUp() = runTest {
        val settings = DaySettings(wakeTime = LocalTime.of(6, 30))
        val model = orchestrator().dayModel(key(settings))
        val probe = date.atTime(6, 40).atZone(london).toInstant()
        val sections = model.sections(probe).map { it.subcategory }
        assertTrue("waking-up" in sections, "$sections")
    }

    @Test
    fun maxSectionsServedExactly() = runTest {
        val settings = DaySettings(maxSections = 1)
        val model = orchestrator().dayModel(key(settings))
        assertEquals(1, model.sections(at(12)).size)
        val settings3 = DaySettings(maxSections = 3)
        val model3 = orchestrator().dayModel(key(settings3))
        assertEquals(3, model3.sections(at(12)).size)
    }

    // ── trigger boundaries ─────────────────────────────────────────────────

    @Test
    fun boundariesAreSortedAndCoverTheDay() = runTest {
        val model = orchestrator().dayModel(key())
        assertTrue(model.boundaries.size >= 8, "prayer entries + window ends + rollover")
        assertTrue(model.boundaries == model.boundaries.sorted())
        // first boundary of the day is before/at fajr
        assertTrue(model.boundaries.first() <= model.prayerTimes.fajr.raw!!)
        // boundaries never extend beyond the next day's first post-midnight isha window
        assertTrue(model.boundaries.last() <= date.plusDays(2).atStartOfDay(london).toInstant())
    }

    @Test
    fun nextBoundaryWalksForward() = runTest {
        val model = orchestrator().dayModel(key())
        val now = model.prayerTimes.fajr.raw!!
        val next = model.nextBoundary(now)
        assertNotNull(next)
        assertTrue(next > now)
        // walking from the returned boundary never repeats it
        val after = model.nextBoundary(next)
        assertNotNull(after)
        assertTrue(after > next)
    }

    // ── polar day: anchors degrade to ANYTIME-only ────────────────────────

    @Test
    fun polarDayServesAnytimeOnly() = runTest {
        val tromso = Location(Latitude(69.6492), Longitude(18.9553), AltitudeMeters(10.0))
        val midsummer = LocalDate.of(2026, 6, 21)
        val tromsoZone = ZoneId.of("Europe/Oslo")
        val model = orchestrator().dayModel(DayKey(tromso, midsummer, tromsoZone, DaySettings()))
        // Polar day: fajr/isha null (twilight never dips); sun/maghrib still computable
        val anySection = model.sections(midsummer.atTime(12, 0).atZone(tromsoZone).toInstant())
        // Whatever serves must come from real corpus sections, never crash
        assertTrue(anySection.size <= 3)
        // The key contract: fajr-based slots (morning) are absent when fajr is null
        val slots = anySection.map { it.slot }
        assertTrue(com.khushu.data.adaptive.DuaTimeSlot.MORNING !in slots,
            "polar day has no fajr → morning slot must not serve")
    }

    // ── settings in the key: different settings = different models ─────────

    @Test
    fun settingsChangeYieldsDistinctPlan() = runTest {
        val o = orchestrator()
        val a = o.dayModel(key(DaySettings(maxSections = 2)))
        val b = o.dayModel(key(DaySettings(maxSections = 3)))
        assertTrue(a !== b)
    }

    // ── mushaf composite against real atlas content ───────────────────────

    @Test
    fun renderablePageResolvesGlyphsAndDecorations() = runTest {
        val o = orchestrator()
        // uthmani atlas bundle + mushaf layout are in the repo inventory
        val layout = o.mushaf.renderablePage("qpc", "uthmani", 2)
        assertEquals(2, layout.page)
        assertTrue(layout.lines.isNotEmpty(), "page 2 must have lines")
        val titles = layout.lines.filterIsInstance<MushafNamespace.MushafLine.Title>()
        assertTrue(titles.isNotEmpty(), "page 2 opens a surah (2 Al-Baqarah) → Title slot expected")
        assertEquals(2, titles.first().chapterNo)
        val textLines = layout.lines.filterIsInstance<MushafNamespace.MushafLine.Text>()
        assertTrue(textLines.isNotEmpty())
        val firstWord = textLines.first().words.firstOrNull()
        assertNotNull(firstWord)
        assertTrue(firstWord.glyphs.isNotEmpty(), "atlas must place glyphs for real words")
        assertTrue(firstWord.widthFu > 0.0)
        assertTrue(layout.unitsPerEm > 0 && layout.ppem > 0)
    }

    @Test
    fun renderablePageIsCachedByTransport() = runTest {
        val o = orchestrator()
        val a = o.mushaf.renderablePage("qpc", "uthmani", 2)
        val b = o.mushaf.renderablePage("qpc", "uthmani", 2)
        assertEquals(a, b, "same inputs → same layout (transport memoized)")
    }
}
