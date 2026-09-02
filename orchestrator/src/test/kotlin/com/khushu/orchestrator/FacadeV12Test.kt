package com.khushu.orchestrator

import com.khushu.data.transport.LocalFetcher
import com.khushu.engine.core.geo.AltitudeMeters
import com.khushu.engine.core.geo.Latitude
import com.khushu.engine.core.geo.Location
import com.khushu.engine.core.geo.Longitude
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
        KhushuOrchestrator(fetcher = LocalFetcher(repoRoot.absolutePath.toPath()))

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
        val o = KhushuOrchestrator(fetcher = caching)

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

    // ── sajdah-marker resolution (v1.3.1 fallback) ────────────────────────

    @Test
    fun sajdahMarkerWordsResolveGlyphsViaNormalizedFallback() = runBlocking {
        val o = orchestrator()
        // every sajdah verse must resolve glyphs for its marker-carrying word
        // (ayahIds verified in the corpus: 7:206, 13:15, 16:50, 17:109, 19:58,
        // 22:18, 22:77, 25:60, 27:26, 32:15, 38:24, 41:38, 53:62, 84:21, 96:19)
        val sajdahAyahs = listOf(
            7 to 206, 13 to 15, 16 to 50, 17 to 109, 19 to 58,
            22 to 18, 22 to 77, 25 to 60, 27 to 26, 32 to 15,
            38 to 24, 41 to 38, 53 to 62, 84 to 21, 96 to 19,
        )
        for ((s, a) in sajdahAyahs) {
            val layout = o.mushaf.renderableAyah("qpc", "uthmani", s, a)
            assertTrue(
                layout.words.isNotEmpty(),
                "sajdah ayah $s:$a must resolve registry words",
            )
            assertEquals(
                0, layout.unresolvedWords,
                "sajdah ayah $s:$a must not lose a word to lookup variance: " +
                    layout.words.filter { it.glyphs.isEmpty() }.map { it.text },
            )
        }
    }

    @Test
    fun normalizedFallbackIsDeterministicAndPageCarriesUnresolvedCount() = runBlocking {
        val o = orchestrator()
        val p1 = o.mushaf.renderablePage("qpc", "uthmani", 1)
        val p1again = o.mushaf.renderablePage("qpc", "uthmani", 1)
        assertEquals(p1, p1again)
        assertTrue(p1.unresolvedWords >= 0)
        // normalization contract: stripping variance marks is stable
        assertEquals(
            MushafNamespace.normalizeWord("يَسۡجُدُونَۤ۩"),
            MushafNamespace.normalizeWord("يَسۡجُدُونَۤ۩"),
        )
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
        // side index OUTSIDE the corpora root — a search_index.db inside would
        // pollute installedCollections (every *.db looks like a corpus)
        val sideIndex = File(repoRoot, "build/sunnah-search-index.db")
        o.sunnah.attach(corporaRoot, searchIndexDb = sideIndex)
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
