package com.khushu.orchestrator

import com.khushu.orchestrator.config.Location
import com.khushu.orchestrator.config.SolarEventType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The astronomy namespace is pure delegation over the (already golden-tested)
 * engine ephemeris. These tests pin the host-facing contract only: the facts
 * the AR/sky views consume line up with each other and land inside the civil
 * day they are asked for.
 */
class AstronomyNamespaceTest {

    private val orch = KhushuOrchestrator(fetcher = null)
    private val zone = ZoneId.of("Asia/Karachi")
    private val location = Location.of(33.6844, 73.0479) // Islamabad
    private val date = LocalDate.of(2026, 6, 21)

    @Test
    fun solarDayFactsAreConsistentAndChronological() {
        val events = orch.astronomy.sun.events(location, date, zone).events
        val byType = events.associateBy { it.type }
        val rise = assertNotNull(byType[SolarEventType.SUNRISE])
        val noon = assertNotNull(byType[SolarEventType.SOLAR_NOON])
        val set = assertNotNull(byType[SolarEventType.SUNSET])

        assertTrue(rise.epochMs < noon.epochMs, "sunrise precedes solar noon")
        assertTrue(noon.epochMs < set.epochMs, "solar noon precedes sunset")
        assertEquals(events.sortedBy { it.epochMs }, events, "events are chronological")

        // `events` places rise/set at the refracted-altitude crossing, `riseSet`
        // uses the ephemeris definition — they agree to within a few seconds.
        val riseSet = orch.astronomy.sun.riseSet(location, date, zone)
        assertTrue(kotlin.math.abs(rise.epochMs - assertNotNull(riseSet.riseEpochMs)) < 60_000)
        assertTrue(kotlin.math.abs(set.epochMs - assertNotNull(riseSet.setEpochMs)) < 60_000)

        val dayLength = assertNotNull(orch.astronomy.sun.dayLength(location, date, zone))
        val fromEvents = java.time.Duration.ofMillis(set.epochMs - rise.epochMs)
        assertTrue(kotlin.math.abs(dayLength.seconds - fromEvents.seconds) <= 60)
    }

    @Test
    fun solarPositionAtSunriseSitsOnTheHorizon() {
        val rise = assertNotNull(orch.astronomy.sun.riseSet(location, date, zone).riseEpochMs)
        val atRise = orch.astronomy.sun.position(location, Instant.ofEpochMilli(rise))
        // Rise/set is defined at the refracted upper limb; the disc centre is a little below.
        assertTrue(atRise.altitudeDeg in -1.5..0.5, "altitude at rise = ${atRise.altitudeDeg}")
        assertTrue(atRise.azimuthDeg in 0.0..360.0)
    }

    @Test
    fun sunTrackReturnsOneDayPerCivilDayWithTransitInside() {
        val month = YearMonth.of(2026, 6)
        val days = orch.astronomy.sun.track(location, month, zone)
        assertEquals(month.lengthOfMonth(), days.size)
        val day = days.first { it.date == date }
        val transit = assertNotNull(day.transitEpochMs)
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        assertTrue(transit in dayStart until dayStart + 86_400_000L)

        val withPath = orch.astronomy.sun.track(location, month, zone, includePath = true)
        assertTrue(withPath.pathPoints.size >= 24 * month.lengthOfMonth(), "hourly render samples")
    }

    @Test
    fun moonFactsCoverRiseSetTransitPhaseAndPath() {
        val month = YearMonth.of(2026, 6)
        val track = orch.astronomy.moon.track(location, month, zone, includePath = true)
        assertEquals(month.lengthOfMonth(), track.days.size)
        assertTrue(track.pathPoints.isNotEmpty())

        val day = track.days.first { it.date == date }
        val transit = assertNotNull(day.transitEpochMs)
        val state = orch.astronomy.moon.state(location, Instant.ofEpochMilli(transit))
        assertTrue(state.illuminationFraction in 0.0..1.0)
        assertTrue(state.phaseAngleDeg in 0.0..360.0)
        assertTrue(state.phaseName.isNotBlank())

        val position = orch.astronomy.moon.position(location, Instant.ofEpochMilli(transit))
        assertTrue(position.distanceKm > 300_000.0)
    }

    @Test
    fun seasonsReturnsFourInstantsForTheYear() {
        val seasons = orch.astronomy.seasons(2026)
        assertTrue(seasons.marchEquinox < seasons.juneSolstice)
        assertTrue(seasons.juneSolstice < seasons.septemberEquinox)
        assertTrue(seasons.septemberEquinox < seasons.decemberSolstice)
    }
}
