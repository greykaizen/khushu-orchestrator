package com.khushu.orchestrator

import com.khushu.data.transport.LocalFetcher
import com.khushu.engine.core.geo.Location
import com.khushu.orchestrator.config.PrayerConfiguration
import com.khushu.orchestrator.config.PrayerOffsets
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

/**
 * Status and alarm triggers must use the SAME adjusted instants the day list
 * renders — a per-prayer offset must never make the hero/countdown disagree
 * with the visible rows.
 */
class PrayerAdjustmentTest {

    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "assets/dua_dhikr/dua_data.json").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/archive/assets/dua_dhikr/dua_data.json").exists() }
                ?.let { File(it, "khushu-data-api/archive") }
            ?: error("khushu-data-api repo root not found (run with -PlocalFamily)")

    private fun orchestrator() =
        KhushuOrchestrator(fetcher = LocalFetcher(repoRoot.absolutePath.toPath()))

    private val zone = ZoneId.of("Europe/London")
    private val date = LocalDate.of(2026, 6, 1)

    private fun offsetKey() = DayKey(
        location = Location.of(51.5074, -0.1278),
        date = date,
        zoneId = zone,
        settings = DaySettings(
            prayerConfig = PrayerConfiguration(
                offsets = PrayerOffsets(fajr = 10, dhuhr = -5, isha = 7),
            ),
        ),
    )

    @Test
    fun statusStartsUseAdjustedTimes() = runTest {
        val o = orchestrator()
        val key = offsetKey()
        val model = o.dayModel(key)
        val adjustedFajr = model.prayerTimes.fajr.adjusted!!
        val rawFajr = model.prayerTimes.fajr.raw!!
        assertTrue(adjustedFajr.isAfter(rawFajr), "10-minute offset moves Fajr later")

        // Just before adjusted Fajr: the covering window is yesterday's Isha
        // and the next start must be the ADJUSTED Fajr, not the raw one.
        val now = adjustedFajr.minusSeconds(60)
        val status = o.prayer.status(key, now)
        assertEquals(adjustedFajr, status.nextStart)
    }

    @Test
    fun alarmScheduleHonoursAdjustedTimesAndLead() = runTest {
        val o = orchestrator()
        val key = offsetKey()
        val adjustedFajr = o.dayModel(key).prayerTimes.fajr.adjusted!!
        val from = adjustedFajr.minus(Duration.ofHours(2))

        val alarms = o.prayer.alarmSchedule(key, from, count = 1, leadMinutes = 15)
        val fajrAlarm = alarms.first { it.prayer == com.khushu.engine.prayer.PrayerStatus.Prayer.FAJR }
        assertEquals(adjustedFajr.minus(Duration.ofMinutes(15)), fajrAlarm.instant)
    }

    @Test
    fun occasionAlarmsApplyLeadToAdjustedAnchors() = runTest {
        val o = orchestrator()
        val key = offsetKey()
        val adjustedFajr = o.dayModel(key).prayerTimes.fajr.adjusted!!
        val from = adjustedFajr.minus(Duration.ofHours(2))

        val alarms = o.prayer.occasionAlarms(key, from, count = 40, leadMinutes = 15)
        val fajr = alarms.first { it.kind == com.khushu.engine.prayer.PrayerStatus.Prayer.FAJR }
        assertEquals(adjustedFajr.minus(Duration.ofMinutes(15)), fajr.instant)
        assertTrue(alarms.any { it.kind == null && it.label.startsWith("Duha") })
        assertTrue(alarms.any { it.kind == null && it.label.startsWith("Tahajjud") })
    }

    @Test
    fun alarmScheduleCanRequestRawTimesWhenAsked() = runTest {
        val o = orchestrator()
        val key = offsetKey()
        val rawFajr = o.dayModel(key).prayerTimes.fajr.raw!!
        val from = rawFajr.minus(Duration.ofHours(2))

        val alarms = o.prayer.alarmSchedule(
            key, from, count = 1, leadMinutes = 0, useAdjusted = false,
        )
        val fajrAlarm = alarms.first { it.prayer == com.khushu.engine.prayer.PrayerStatus.Prayer.FAJR }
        assertEquals(rawFajr, fajrAlarm.instant)
    }
}
