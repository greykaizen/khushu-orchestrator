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
        // Resolver yielding null for the domain must behave identically.
        val noStore = KhushuContent(fetcher = null, resolver = SqlStoreResolver { null })
        assertFailsWith<IllegalStateException> { noStore.dua.duas() }
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
