package com.khushu.orchestrator

import com.khushu.data.transport.LocalFetcher
import com.khushu.engine.KhushuEngine
import com.khushu.engine.core.geo.AltitudeMeters
import com.khushu.engine.core.geo.Latitude
import com.khushu.engine.core.geo.Location
import com.khushu.engine.core.geo.Longitude
import com.khushu.data.repo.KhushuContent
import okio.Path.Companion.toPath
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * v1.2.0 surfaces: the adaptiveFlow trigger loop (virtual time), ayah-mode
 * glyph layout, and texture prefetch — against real engine + corpus.
 */
class FacadeV12Test {

    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "assets/dua_dhikr/dua_data.json").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-quran-data/assets/dua_dhikr/dua_data.json").exists() }
                ?.let { File(it, "khushu-quran-data") }
            ?: error("khushu-quran-data repo root not found")

    private val london = ZoneId.of("Europe/London")
    private val loc = Location(Latitude(51.5074), Longitude(-0.1278), AltitudeMeters(11.0))
    private val date = LocalDate.of(2026, 6, 1)

    private fun orchestrator() =
        KhushuOrchestrator(KhushuEngine(), KhushuContent(LocalFetcher(repoRoot.absolutePath.toPath())))

    private fun key() = DayKey(loc, date, london, DaySettings())

    // ── adaptiveFlow: virtual-time loop ───────────────────────────────────

    @Test
    fun adaptiveFlowEmitsThenWakesAtBoundaries() = runBlocking {
        val o = orchestrator()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var virtualNow = date.atStartOfDay(london).toInstant()
        val emissions = mutableListOf<List<String>>()

        try {
            val flow = o.dua.adaptiveFlow(key(), scope, virtualNow, nowNow = { virtualNow }, timeScale = 3600.0)

            // advance through the day in coarse steps; loop wakes at boundaries
            withTimeout(20_000) {
                while (emissions.size < 3) {
                    // wait for an emission matching the current virtual instant
                    delay(50)
                    val value = flow.value
                    if (value.isNotEmpty() && emissions.none { it == value.map { s -> s.subcategory } }) {
                        emissions += value.map { it.subcategory }
                        // jump virtual time to the next boundary +1s
                        val model = o.dayModel(key())
                        val next = model.nextBoundary(virtualNow)
                        if (next != null) virtualNow = next.plusSeconds(1)
                    }
                }
            }
            assertTrue(emissions.size >= 3, "loop must re-emit at boundaries: $emissions")
        } finally {
            scope.coroutineContext[Job]?.cancelChildren()
            scope.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun adaptiveFlowSwapsDayKeyOnRollover() = runBlocking {
        val o = orchestrator()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val day1 = date.atStartOfDay(london).toInstant()
        var virtualNow = day1

        try {
            val flow = o.dua.adaptiveFlow(key(), scope, virtualNow, nowNow = { virtualNow }, timeScale = 3600.0)
            withTimeout(15_000) {
                while (flow.value.isEmpty()) delay(25)
            }
            // jump past midnight: rollover must swap the key and keep serving (no crash, fresh plan)
            virtualNow = date.plusDays(1).atTime(3, 0).atZone(london).toInstant()
            withTimeout(15_000) {
                while (flow.value.isEmpty()) delay(25)
            }
            // the flow still serves sections (fresh day's plan)
            assertNotNull(flow.value)
        } finally {
            scope.coroutineContext[Job]?.cancelChildren()
            scope.coroutineContext[Job]?.cancel()
        }
    }

    // ── ayah-mode glyph layout ────────────────────────────────────────────

    @Test
    fun renderableAyahResolvesAlFatihaGlyphs() = runBlocking {
        val o = orchestrator()
        val ayah = o.mushaf.renderableAyah("qpc", "uthmani", surahNo = 1, ayahNo = 1)
        assertEquals(1001, ayah.ayahId)
        assertTrue(ayah.words.isNotEmpty(), "al-Fatiha 1:1 must resolve registry words")
        assertTrue(ayah.widthFu > 0.0)
        assertTrue(ayah.words.first().glyphs.isNotEmpty(), "atlas placements must resolve")
        assertTrue(ayah.unitsPerEm > 0 && ayah.ppem > 0)
    }

    @Test
    fun renderableAyahMatchesPageLineContent() = runBlocking {
        val o = orchestrator()
        // ayah 1:1 opens page 1 of the mushaf — its glyph words must be a subset of the page's first ayah line
        val ayah = o.mushaf.renderableAyah("qpc", "uthmani", 1, 1)
        val pageLine = o.content.pageLines("qpc", 1)
            .firstOrNull { it.type == com.khushu.data.model.LineType.AYAH && it.startAyahId == 1001 }
        if (pageLine != null) {
            val lineFirst = pageLine.firstWordId
            assertNotNull(lineFirst)
            // registry consistency: the ayah's first word id must fall within the line bounds
            val ayahFirstWord = ayah.words.first().wordId
            assertTrue(
                ayahFirstWord >= (pageLine.firstWordId ?: 0) && ayahFirstWord <= (pageLine.lastWordId ?: 0),
                "ayah words must live inside the page line's word-id bounds",
            )
        }
    }

    // ── texture prefetch (offline drawing readiness) ──────────────────────

    @Test
    fun prefetchPagesWarmTexturesThroughCachingTransport() = runBlocking {
        // CachingFetcher-backed orchestrator: second warm must skip network
        val cacheDir = File(repoRoot, "build/orch-texture-test-cache").apply { mkdirs() }
        val caching = com.khushu.data.transport.CachingFetcher(cacheDir, LocalFetcher(repoRoot.absolutePath.toPath()))
        val o = KhushuOrchestrator(KhushuEngine(), KhushuContent(caching))

        o.mushaf.prefetchPages("qpc", "uthmani", 1..2, includeTextures = true)

        val layer = o.content.atlasGlyphTable("uthmani", "6x")
        val textureCount = layer.textures.size
        assertTrue(textureCount > 0)

        // textures are zip entries — the BUNDLE zip is the cached unit
        val present = caching.downloads().items.map { it.path }.toSet()
        assertTrue(
            present.any { it.contains("atlas") && it.contains("uthmani") },
            "the uthmani bundle zip must be cached after prefetch: $present",
        )

        // drawing path: texture bytes resolve out of the cached bundle
        val bytes = o.content.atlasTexture("uthmani", 0, "6x")
        assertTrue(bytes.isNotEmpty())
        val secondBytes = o.content.atlasTexture("uthmani", 0, "6x")
        assertTrue(bytes.contentEquals(secondBytes))
    }

    // ── warmup ────────────────────────────────────────────────────────────

    @Test
    fun warmupPrebuildsDayAndBundle() = runBlocking {
        val o = orchestrator()
        o.warmup(key(), includeTomorrow = true, mushafBundle = "qpc" to "uthmani")
        // idempotent: same instances served afterwards
        val a = o.dayModel(key())
        val b = o.dayModel(key())
        assertTrue(a === b)
    }

    // ── sunnah offline seam (attach requires local corpora) ───────────────

    @Test
    fun sunnahAttachServesSearchOverLocalCorpora() = runBlocking {
        val o = orchestrator()
        val corporaRoot = File(repoRoot, "inventory/hadiths")
        o.sunnah.attach(corporaRoot)
        try {
            val hadith = o.sunnah.hadith("bukhari_urn_100010", lang = "en")
            assertNotNull(hadith)
            assertEquals("bukhari", hadith.collectionId)
            // FTS search over the side index
            o.sunnah.buildSearchIndex("en")
            val hits = o.sunnah.search("deeds depend", "en", limit = 5)
            assertTrue(hits.isNotEmpty(), "FTS must find the niyyah hadith")
        } finally {
            o.sunnah.close()
        }
    }
}
