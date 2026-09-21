package com.khushu.data

import com.khushu.data.repo.KhushuContent
import com.khushu.data.store.SqlStore
import com.khushu.data.store.SqlStoreResolver
import com.khushu.data.store.JdbcSqlStore
import com.khushu.data.transport.ContentFetcher
import com.khushu.data.transport.ContentMissingException
import com.khushu.data.transport.LocalFetcher
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
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

/**
 * SqlStoreResolver wiring (Phase 4 groundwork): the Dua domain serves from the
 * host-resolved `content` pack (SQL) when the resolver yields a store, and the
 * legacy JSON path remains the default/fallback. DayModel builds — the one
 * aggregate consumer of `data.dua.duas()` — must be identical over either path.
 */
class SqlDuaResolverTest {

    private val contentPack: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/packs/content.db").exists() }
            // composite-build sibling checkout
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/data/packs/content.db").exists() }
                ?.let { File(it, "khushu-data-api/data/packs/content.db") }
            ?: error("content pack not found (khushu-data-api sibling checkout required)")

    /** A transport that must never be touched when the resolver covers a domain. */
    private val deadFetcher = ContentFetcher { path ->
        throw ContentMissingException(path)
    }

    private val resolver = SqlStoreResolver { JdbcSqlStore(contentPack) }

    // ── dua domain ─────────────────────────────────────────────────────────

    @Test
    fun duaServesFromPackWithoutAnyJsonTransport() = runTest {
        val content = KhushuContent(deadFetcher, resolver)
        val duas = content.dua.duas()
        assertEquals(491, duas.size)
        assertTrue(duas.first().arabic.isNotBlank())
        // categories/subcategories intact over SQL
        val cats = content.dua.categories()
        assertTrue(cats.isNotEmpty())
        assertEquals("marriage-and-children", content.dua.dua(1)?.subcategory)
    }

    /** Repo root with the JSON archive corpus (same discovery as the other slice tests). */
    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "assets/dua_dhikr/dua_data.json").exists() }
            // composite-build sibling checkout
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/archive/assets/dua_dhikr/dua_data.json").exists() }
                ?.let { File(it, "khushu-data-api/archive") }
            ?: error("khushu-data-api repo root not found")

    @Test
    fun jsonPathRemainsTheDefaultAndFallback() = runTest {
        // Default construction (no resolver): JSON still serves.
        val json = KhushuContent(LocalFetcher(repoRoot.absolutePath.toPath()))
        assertEquals(491, json.dua.duas().size)
        // Resolver yields null for the domain → falls back to JSON.
        val fallback = KhushuContent(LocalFetcher(repoRoot.absolutePath.toPath()), SqlStoreResolver { null })
        assertEquals(491, fallback.dua.duas().size)
    }

    // ── aggregate DayModel over the resolver ────────────────────────────────

    @Test
    fun dayModelBuildsIdenticallyOverSqlAndJson() = runTest {
        val london = ZoneId.of("Europe/London")
        val location = Location(Latitude(51.5074), Longitude(-0.1278), AltitudeMeters(11.0))
        val date = LocalDate.of(2026, 6, 1)
        val key = DayKey(location, date, london, DaySettings())
        val at = { i: Long -> date.atStartOfDay(london).toInstant().plusSeconds(i) }

        val overSql = KhushuOrchestrator(fetcher = deadFetcher, resolver = resolver)
        val overJson = KhushuOrchestrator(fetcher = LocalFetcher(repoRoot.absolutePath.toPath()))

        val sqlModel = overSql.dayModel(key)
        val jsonModel = overJson.dayModel(key)
        assertTrue(sqlModel.sections(at(9 * 3600)).isNotEmpty(), "SQL-served DayModel has adaptive sections")
        assertEquals(
            jsonModel.sections(at(9 * 3600)).map { it.subcategory },
            sqlModel.sections(at(9 * 3600)).map { it.subcategory },
            "identical corpus → identical adaptive rotation",
        )
    }
}
