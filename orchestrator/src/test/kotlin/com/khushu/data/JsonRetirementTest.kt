package com.khushu.data

import com.khushu.data.repo.KhushuContent
import com.khushu.data.store.JdbcSqlStore
import com.khushu.data.store.SqlStoreResolver
import com.khushu.engine.core.geo.AltitudeMeters
import com.khushu.engine.core.geo.Latitude
import com.khushu.engine.core.geo.Location
import com.khushu.engine.core.geo.Longitude
import com.khushu.orchestrator.DayKey
import com.khushu.orchestrator.DaySettings
import com.khushu.orchestrator.KhushuOrchestrator
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Phase 4 JSON retirement contract: a host going fully pack/Turso-backed
 * constructs with `fetcher = null`; covered domains (dua, islamic events)
 * serve SQL, uncovered JSON-only domains fail fast with a clear error, and
 * the aggregate DayModel builds without any transport.
 */
class JsonRetirementTest {

    private val contentPack: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/packs/content.db").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/data/packs/content.db").exists() }
                ?.let { File(it, "khushu-data-api/data/packs/content.db") }
            ?: error("content pack not found (khushu-data-api sibling checkout required)")

    private val resolver = SqlStoreResolver { JdbcSqlStore(contentPack) }

    @Test
    fun eventsServeFromPackWithoutAnyJsonTransport() = runTest {
        val content = KhushuContent(fetcher = null, resolver = resolver)
        val events = content.islamicEvents.all()
        assertEquals(11, events.size)
        val ashura = events.first { it.id == "ashura" }
        assertEquals(1, ashura.hijriMonth)
        assertEquals(10, ashura.hijriDay)
        assertEquals("Day of Ashura", ashura.title)
        val month = content.islamicEvents.forHijriMonth(1)
        assertTrue(month.isNotEmpty() && month.all { it.hijriMonth == 1 })
    }

    @Test
    fun jsonLessConstructionFailsFastOnUncoveredDomains() = runTest {
        val content = KhushuContent(fetcher = null, resolver = resolver)
        val e = assertFailsWith<IllegalStateException> { content.quran.surahs() }
        assertTrue("ContentFetcher" in (e.message ?: ""), e.message)
    }

    /**
     * A missing `content` pack is a NORMAL first-launch state, not a bug: the
     * prebundled seed may still be copying, or the user deleted the pack. Dua
     * reads must then return empty instead of throwing, because the day model
     * (prayer times) shares this call path. This is the regression that killed
     * boot with `No ContentFetcher configured`.
     */
    @Test
    fun duaDegradesToEmptyWhenNoPackAndNoJson() = runTest {
        val noStore = KhushuContent(fetcher = null, resolver = SqlStoreResolver { null })
        assertEquals(emptyList(), noStore.dua.duas())
        assertEquals(emptyList(), noStore.dua.categories())
        assertEquals(emptyList(), noStore.dua.bySubcategory("morning-evening"))
        assertEquals(emptyList(), noStore.dua.search("prayer"))
        assertEquals(null, noStore.dua.dua(1))
        // Adaptive sections still evaluate (the slot table is static) but carry
        // no duas: count 0 is the honest answer, not an exception.
        val sections = noStore.dua.adaptive(
            com.khushu.data.adaptive.AdaptiveContext(
                now = java.time.Instant.parse("2026-06-01T09:00:00Z"),
                zoneId = ZoneId.of("Europe/London"),
                anchors = null,
            )
        )
        assertTrue(sections.all { it.count == 0 }, "no corpus means no duas in any section")
    }

    /**
     * The day model must build with NO dua corpus at all. Prayer times are the
     * product; dua sections are an enhancement. Before the fix this threw and
     * took the whole day model — and therefore Home — down with it.
     */
    @Test
    fun dayModelBuildsWithoutAnyDuaCorpus() = runTest {
        val london = ZoneId.of("Europe/London")
        val location = Location(Latitude(51.5074), Longitude(-0.1278), AltitudeMeters(11.0))
        val key = DayKey(location, LocalDate.of(2026, 6, 1), london, DaySettings())
        val orch = KhushuOrchestrator(fetcher = null, resolver = SqlStoreResolver { null })
        val model = orch.dayModel(key)
        // Prayer times are real even with no content pack.
        assertTrue(model.prayerTimes.fajr.raw!!.isBefore(model.prayerTimes.isha.raw!!), "fajr must precede isha")
        // No dua content, and no exception. Sections may still be listed (the
        // slot table is static) but every one must be empty.
        val sections = model.sections(LocalDate.of(2026, 6, 1).atTime(9, 0).atZone(london).toInstant())
        assertTrue(sections.all { it.count == 0 }, "day model must carry no duas when the corpus is absent")
    }

    @Test
    fun orchestratorBuildsDayModelWithoutAnyFetcher() = runTest {
        val london = ZoneId.of("Europe/London")
        val location = Location(Latitude(51.5074), Longitude(-0.1278), AltitudeMeters(11.0))
        val key = DayKey(location, LocalDate.of(2026, 6, 1), london, DaySettings())
        val orch = KhushuOrchestrator(fetcher = null, resolver = resolver)
        val model = orch.dayModel(key)
        val sections = model.sections(
            LocalDate.of(2026, 6, 1).atTime(9, 0).atZone(london).toInstant(),
        )
        assertTrue(sections.isNotEmpty(), "DayModel must serve adaptive sections fully pack-backed")
        // Events join (engine hijri × pack display entries) works transport-free too.
        val days = orch.calendar.eventsFor(listOf(LocalDate.of(2026, 6, 25)))
        assertTrue(days.first().hijriDay > 0)
    }
}
