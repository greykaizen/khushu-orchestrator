# Orchestrator Plan — adaptive content, collection downloads, mushaf composition

Status: approved direction (2026-09-01). This document is the family-level design record for the
composition layer; implementation tracks the phases at the bottom.

## 1. Problem

Hosts compose khushu-engine (computation) with khushu-data-api (content) by hand:
fetch atlas assets → pass to engine mushaf math → draw; compute prayer times → map to
dua anchors → fetch sections → render buttons; compute hijri dates → join localized event
entries. Every host reinvents these pipelines, and per-day facts (prayer times, hijri,
slot windows) get recomputed on every touch.

## 2. Solution shape

A fourth layer, khushu-orchestrator:

```
engine computes  ·  data-api serves  ·  orchestrator composes  ·  host renders
```

- Engine and data-api never depend on each other (verified by construction: engine's Gradle
  purity lock; data-api has no engine dependency). The orchestrator is the only seam.
- Composites only: `fetch facts + apply math → immutable result`. No domain logic.
- The **DayModel**: one cached plan per `(Location, LocalDate, ZoneId, params)`.
  Per-day derived values computed once; served by lookup all day; trigger boundaries
  precomputed (prayer entries, window expiries, day rollover).

## 3. Adaptive dua/adhkar (data-api + orchestrator)

### Slot table (library-owned, documented; slugs verified against the corpus)

| Slot | Window | Anchor source | Priority |
|---|---|---|---|
| AFTER_SALAH ×5 | [prayerEnter, +postPrayerDuration] — global, host-configurable (default 20 min) | engine PrayerTimesResult | 1 |
| WAKING_UP | [wakeTime, +30 min] | host setting (null → inactive) | 1 |
| BEFORE_SLEEP | [bedtime, +45 min] | host setting (null → inactive) | 2 |
| TAHAJJUD | [lastThirdOfNight, fajr] | engine (lastThirdOfNight) | 2 |
| MORNING | [fajr, dhuhr] | engine | 3 |
| EVENING | [eveningStart, midnight] — EveningStart.MAGHRIB (default) or .ASR (documented minority position) | engine + policy param | 3 |
| ANYTIME | always | — | 4 (backfill) |

Corpus slugs: `after-salah(12), waking-up(5), before-sleep(18), tahajjud(12), morning(24),
evening(23)` + backfill pool (`istighfar 19, salawat 9, praises-of-allah 30, quranic-duas 41,
sunnah-duas 75`). Overlaps resolve by priority (post-fajr dhikr outranks "morning" during the
first N minutes). No location → ANYTIME only. Polar-safe by construction (engine anchors).

### Rotation

Prayer-entry is the day's natural segmentation — rotation keyed to slot-window transitions,
not wall-clock hours. Host-triggered variant bumps (manual refresh) plus a documented
deterministic default (`epochDay + slotOrdinal`) so a lazy host still gets daily shuffle.
Deterministic: same inputs → same sections.

### API

```kotlin
// data-api
data class AdaptiveContext(now: Instant, zoneId: ZoneId, anchors: PrayerAnchors?,
    bedtime: LocalTime?, wakeTime: LocalTime?, postPrayerDuration: Duration = 20.minutes,
    eveningStart: EveningStart = MAGHRIB, maxSections: Int = 2, variant: Int)
suspend fun DuaApi.adaptive(ctx): List<AdaptiveDuaSection>
// AdaptiveDuaSection(subcategory, title, count, slot, window: Pair<Instant, Instant>?)

// orchestrator
fun anchors(PrayerTimesResult): PrayerAnchors
fun adaptiveFlow(engine, data, settingsFlow, scope): StateFlow<List<AdaptiveDuaSection>>
```

## 4. Collection downloads (data-api)

`CachingFetcher`'s manifest (path → bytes/fetchedAt/sha256) is the completion ledger:
- Batch download = iterate plan paths through the fetcher; resume = manifest hits skipped.
- Progress = manifest diff against plan. Delete = `deleteWhere(path in plan)`.
- **Pipeline prerequisite**: `downloads_manifest.json` (path, bytes, sha256 per file) so
  byte totals display BEFORE download and integrity verifies after.

Plans (real file units only):

| Domain | Plans | Unit |
|---|---|---|
| Quran audio | full per reciter / **per-surah** | per-surah mp3 files |
| Quran text | per-pack + full | pack JSON (monolithic per language) |
| Sunnah | **per-book** (per-language JSON, already sliced in inventory: `hadiths/{c}/books/{lang}/{c}_bNN.json`) / per-collection `.db` / full | book JSON ~158 KB avg (Bukhari: 97 files, 15.3 MB) |
| Dua | data JSON / audio (491 opus) / full | per-id opus |
| Asma | per-language pack | pack JSON |

## 5. Mushaf composition (orchestrator; donor: QuranApp)

Atlas bundles are text glyphs only — verified. Surah-name headers, basmallah lines, and
page footers are **typed decoration slots** in the mushaf map (`MushafLineType.surah_name /
basmallah`); the host renders them with its own calligraphy assets (donor parity:
`ReaderItemsBuilder.kt:1106` maps them to Title/Bismillah items, not glyphs).

Port map (divergences recorded in khushu-engine `docs/divergences.md`):

| QuranApp donor | Destination |
|---|---|
| ReaderItemsBuilder.buildMushafPages (:646) | orchestrator `renderablePage` |
| QuranAtlasBundle shape resolution `(bundle, word, page)` + batch prefetch | orchestrator over data-api atlas sources |
| QuranTextMeasurer page-scale median / line-fit / MUSHAF_* constants | engine mushaf owns fit/measure (fitPageScale, fitLineShrink, measureLineWidthPx); orchestrator composes page-scale median from them; engine v2.1.0 additive port only if goldens drift |
| Two-scale glyph math (fontScale = fontSize/upm, glyphScale = fontSize/ppem) | engine mushaf (layoutWord) |
| Texture decode LRU + per-glyph Canvas draw | host (Android) |

Model: `MushafPageLayout(pageNo, scale, lines)` where lines are
`TitleLine(chapterNo, iconRef) | BismillahLine | TextLine(centered, words → PositionedGlyph(gid, textureIndex, x, y, w, h))`.
Layout math in font units, cached per `(script, mushafId, page)` — pixel-independent; scale
applied per call (trivial arithmetic, no cache inflation by screen size).

## 6. DayModel (orchestrator)

- Key: `(Location, LocalDate, ZoneId, prayerConfig, userSettings)` — everything that can
  change any output is in the key. LRU over 3 days (yesterday midnight window, today,
  tomorrow warmup). `forceRecompute()` explicit.
- Build: prayer times (+ tomorrow's fajr for night windows) → anchors → full-day slot
  window set → sorted trigger list.
- Serve: `daySections(now)` O(log n); `nextBoundary(now)` = next trigger; host schedules
  nothing, recomputes nothing.
- Rollover: date change → new key; mushaf page cache is day-independent (separate memo).

## 7. Phases

| Phase | Scope | Exit |
|---|---|---|
| P1 | pipeline `downloads_manifest.json` generator | manifest committed; byte totals match `du -b` |
| P2 | data-api v1.3.0 | adaptive + anchors + per-book sunnah read + plans/batch executor; suites green |
| P3 | khushu-orchestrator v1.0.0 | DayModel + dua/calendar/mushaf composites; virtual-time tests green |
| P4 | host swap | host adds one coordinate; blocks consume composites |

## 8. Guardrails

- Composites-only doctrine (no god orchestrator) — ADR-01.
- Engine stays locked; any mushaf delta requires golden evidence first.
- Donor code is reference, not gospel — divergences recorded, authoritative sources win.
- Runtime version handshakes rejected: Gradle resolution + compile-time pinning + KDoc matrix.
