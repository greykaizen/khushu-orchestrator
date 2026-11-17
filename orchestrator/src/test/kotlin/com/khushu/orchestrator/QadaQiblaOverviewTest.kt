package com.khushu.orchestrator

import com.khushu.data.transport.LocalFetcher
import com.khushu.engine.core.geo.Location
import com.khushu.engine.prayer.PrayerStatus
import com.khushu.orchestrator.config.Degrees
import com.khushu.orchestrator.config.Kilometers
import com.khushu.orchestrator.config.QadaConfig
import com.khushu.orchestrator.config.QadaSchool
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

/**
 * Read-only upstream APIs: qadāʾ reporting, the qibla delegation, and the
 * one-call prayer day overview. Same real-engine + real-content setup as
 * [DayModelTest]; virtual-time probes so nothing depends on the wall clock.
 */
class QadaQiblaOverviewTest {

    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "assets/dua_dhikr/dua_data.json").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/archive/assets/dua_dhikr/dua_data.json").exists() }
                ?.let { File(it, "khushu-data-api/archive") }
            ?: error("khushu-data-api repo root not found (run with -PlocalFamily against sibling checkout)")

    private fun orchestrator() =
        KhushuOrchestrator(fetcher = LocalFetcher(repoRoot.absolutePath.toPath()))

    private val london = ZoneId.of("Europe/London")
    private val londonLocation = Location.of(51.5074, -0.1278)
    private val londonDate = LocalDate.of(2026, 6, 1)

    // ── qadāʾ ──────────────────────────────────────────────────────────────

    @Test
    fun hanafiPeriodProducesBoundaryWitrAndGhusl() = runTest {
        val o = orchestrator()
        // Makkah (equinox-ish): Isha→Fajr is a long window, so a period ending
        // 30 min into Isha lands squarely in that boundary and before midnight.
        val zone = ZoneId.of("Asia/Riyadh")
        val key = DayKey(Location.of(21.4225, 39.8262), LocalDate.of(2026, 3, 15), zone, DaySettings())
        val model = o.dayModel(key)

        val start = model.prayerTimes.fajr.adjusted!!
        val end = model.prayerTimes.isha.adjusted!!.plusSeconds(30 * 60)
        val period = ExcuseReason.HAYD.period(start, end)
        assertNotNull(period, "HAYD maps to a classified engine period")

        val report = o.qada.report(
            periods = listOf(period),
            days = listOf(key),
            config = QadaConfig(school = QadaSchool.HANAFI),
        )

        assertTrue(report.suppressedPrayers.isNotEmpty(), "prayers inside the period are suppressed")
        assertTrue(
            report.boundaryObligations.any { it.kind == PrayerStatus.Prayer.ISHA },
            "purity during Isha obligates the current prayer at the boundary",
        )
        assertTrue(report.witrObligations.isNotEmpty(), "Hanafi default includes Witr with Isha qadāʾ")
        assertTrue(report.ghuslRequiredAt.isNotEmpty(), "ghusl is due at the period end")
        assertNotNull(report.provenance.firstOrNull { it.contains("HANAFI") })
    }

    @Test
    fun qadaChoicesExposeSchoolDefaultsAndExcuseVocabulary() = runTest {
        val o = orchestrator()
        val hanafi = o.qada.choices(QadaSchool.HANAFI)
        assertEquals(4, hanafi.schools.size)
        assertTrue(hanafi.includeWitr)
        assertEquals(o.qada.defaultPairing(QadaSchool.HANAFI), hanafi.pairing)
        assertEquals(ExcuseReason.entries.toList(), hanafi.reasons)
        assertEquals(null, ExcuseReason.TRAVEL.period(Instant.EPOCH, Instant.EPOCH.plusSeconds(60)))
    }

    // ── qibla ──────────────────────────────────────────────────────────────

    @Test
    fun qiblaDelegatesBearingDistanceAndSunRelation() {
        val o = KhushuOrchestrator(fetcher = null)
        val bearing: Degrees = o.qibla.bearing(londonLocation).bearingDegFromNorth
        val distance: Kilometers = o.qibla.bearing(londonLocation).greatCircleDistanceKm
        assertTrue(bearing.value in 0.0..360.0)
        assertTrue(distance.value > 0.0)

        val between = o.qibla.bearingBetween(londonLocation, Location.of(21.4225, 39.8262))
        assertTrue(between.distanceKm.value > 0.0)

        val relation = o.qibla.relativeSunAngle(londonLocation, Instant.parse("2026-06-01T12:00:00Z"))
        assertTrue(relation.signedAngleToQiblaDeg in -180.0..180.0)
    }

    // ── day overview ───────────────────────────────────────────────────────

    @Test
    fun dayOverviewCarriesTimesStatusAndNextBoundary() = runTest {
        val o = orchestrator()
        val key = DayKey(londonLocation, londonDate, london, DaySettings())
        val now = o.dayModel(key).prayerTimes.fajr.raw!!.plusSeconds(60)

        val overview = o.prayer.dayOverview(key, now)

        assertEquals(key, overview.key)
        assertEquals(londonDate, overview.date)
        assertEquals(london, overview.zoneId)
        assertEquals(o.dayModel(key).prayerTimes, overview.times)
        assertEquals(PrayerStatus.Prayer.FAJR, overview.status.current)
        assertNotNull(overview.nextBoundary)
        assertTrue(overview.nextBoundary!! > now)
    }
}
