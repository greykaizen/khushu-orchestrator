# Khushu family — fix plan & research tracker (2026-09)

Companion to `docs/family-audit-2026-09.md` (findings + evidence). This file is the
**working tracker**: check things off here as they land. The audit doc explains *why*;
this doc tells you *what, where, and done-means-what*.

## How to use this doc

- **Agents:** pick a task card from the current release train, read its card fully
  (files → fix sketch → acceptance), do ONLY that card, run the repo's verification
  command, then tick the checkbox and fill `status`. Never move a tag — new code = new
  tag (family rule, AGENTS.md §7).
- **Owner:** the Status Board is the single glanceable state. Update `status` values
  only: `todo → in-progress → done → verified`. Anything disputed gets a ⚠ note inline.
- **Conventions:** IDs are stable (`E#` engine, `D#` data-api, `O#` orchestrator,
  `H#` host). Severity: 🔴 correctness/user-harm · 🟠 degraded behavior · 🟡 perf/smell · ⚪ docs.
- Tag mapping: orchestrator `v1.4.2` → host+engine `v1.5.0`/engine `v2.0.1` → orchestrator `v1.6.0` → docs/content-release.

---

## Status board

| ID | Sev | Repo | One-liner | Release | Status |
|----|-----|------|-----------|---------|--------|
| O1 | 🔴 | orchestrator | DayModel.boundaries miss 4 of 6 prayer entries → stale countdown | v1.4.2 | done |
| O2 | 🔴 | orchestrator | alarmSchedule infinite loop at polar locations (sync hang) | v1.4.2 | done (cap; see Landed note) |
| O3 | 🔴 | orchestrator | DayModelCache peek/clear race (LinkedHashMap outside mutex) | v1.4.2 | done |
| O4 | 🔴 | orchestrator | Sync APIs runBlocking network I/O on cold cache (ANR) | v1.4.2 | done (dayModelSync deleted) |
| O5 | 🟠 | orchestrator | download(): O(N²) path-set + unsynchronized failures list | v1.4.2 | done |
| O6 | 🟠 | orchestrator | CachingFetcher: races, non-atomic flush, name collisions | v1.4.2 | done |
| O7 | 🟠 | orchestrator | localAudioPath downloads full opus to test existence (2× net) | v1.4.2 | done |
| O8 | 🟠 | orchestrator | SQLite Connections shared across threads unsynchronized | v1.4.2 | done |
| E1 | 🔴 | engine | hijri() timezone-dependent — wrong dates west of UTC | v1.5.0 (engine v2.0.1) | done |
| H1 | 🔴 | host | Bare RemoteFetcher — no disk cache, downloads dead, offline sunnah unreachable | v1.5.0 | done (train 3) |
| H2 | 🟠 | host | warmup() never called → every sync API risks cold path | v1.5.0 | done (train 3) |
| D2 | 🟠 | host | Content fetched from `master` HEAD — unpinned moving target | v1.5.0 | done (train 3: `content-v2026.09`) |
| D3 | 🟡 | data-api | downloads_manifest.json is 0 bytes at HEAD | v1.5.0 | done |
| H3 | ⚪ | doctrine | Second family coordinate (store) — amend doctrine text, don't move code | v1.5.0 | done |
| E5 | 🟠 | engine | Qada: Hanafi Witr obligation deduped away (modeled as duplicate ISHA) | v1.5.0 (engine v2.0.1) | done |
| E6 | ⚠ | engine | Qada: Shafiʿi sufficient-time boundary rule appears unimplemented — VERIFY vs design doc rev 3 | v1.5.0 (verify first) | done (disclosed in provenance) |
| D1 | 🟠 | data-api | assets/ tier has no sha256 rows → shaVerify silently no-ops | content-release | done |
| O9 | 🟡 | orchestrator | renderableAyah O(77k) scan; unbounded zips/memos; dup code; unused param | v1.6.0 | done |
| O10 | ⚪ | orchestrator | Doctrine drift: CachedEngine unused-but-cited; no close(); coord strings | v1.6.0/docs | done |
| E2 | 🟡 | engine | ANTI_TRANSIT_FAJR provenance mislabels fallback rule | v1.6.0 | done (v2.0.1) |
| E3 | ⚪ | engine | Mushaf dead `max(0.35f, 0.4f)` expression | v1.6.0 | done (v2.0.1) |
| E4 | ⚪ | engine | store schemaVersion migrate() is passthrough (note only) | — | wontfix-now |

---

## Release train v1.4.2 — orchestrator bugfix (P1)

**Rule for this train:** orchestrator-only, no API breaks beyond making suspend what
was sync (that IS a break — see O4 note). All cards need tests. Verify with:
`cd orchestrator && ./gradlew :orchestrator:test`

### [ ] O1 — boundaries must contain every prayer entry
- **Files:** `orchestrator/src/main/kotlin/com/khushu/orchestrator/KhushuOrchestrator.kt` (`DayModel.init`)
- **Problem:** init adds only maghrib/isha/nextFajr/section-edges/midnight. fajr, sunrise, dhuhr, asr missing → `statusFlow` sleeps up to `maxWait` (1 h) past an entry.
- **Fix:** add all six `prayerTimes.*.raw` to the boundary set (raw, not adjusted — boundaries are engine facts; document the choice).
- **Accept:** new test in `DayModelTest`: for a normal + a polar day, `nextBoundary` chain from day start visits every non-null raw entry.

### [ ] O2 — cap alarmSchedule day scan
- **Files:** `orchestrator/src/main/kotlin/com/khushu/orchestrator/PrayerNamespace.kt` (`alarmSchedule`)
- **Problem:** `while (out.size < count)` with all-null raw times (polar) never advances out → infinite loop on a **sync** call.
- **Fix:** cap at ~400 day iterations; on cap-out throw `IllegalStateException` naming the location/date (caller-visible, not silent partial).
- **Accept:** test with a polar location (e.g. 78.2N, 15.6E at midsummer) terminates with the exception in <1s (virtual time not needed — pure loop).

### [ ] O3 — make DayModelCache actually thread-safe
- **Files:** `KhushuOrchestrator.kt` (`DayModelCache`)
- **Problem:** `peek()` reads access-order LinkedHashMap without mutex (mutates order); `clear()` unlocked while its comment claims otherwise.
- **Fix:** route peek/clear through `mutex.withLock` (peek becomes suspend — callers are suspend or already bridged); OR replace with `ConcurrentHashMap` + access-order tracked separately. Prefer the mutex route: smallest diff.
- **Accept:** test hammering 100 concurrent `dayModel` + `peek` + `clear` under `runTest` with real dispatchers — no CME, no lost entry.

### [ ] O4 — suspend the status surface; keep dayModelSync hit-only
- **Files:** `PrayerNamespace.kt` (`status`, `statusFlow`), `KhushuOrchestrator.kt` (`dayModelSync`)
- **Problem:** cold-cache `dayModelSync` → `runBlocking` → network fetch on caller thread. `statusFlow` calls it inside its own coroutine (should at least be `dayModel`).
- **Fix:** `status` and `statusFlow`'s loop use suspend `dayModel`. `dayModelSync` becomes: peek-only, and on miss log a warning + throw `IllegalStateException("DayModel not warm — call warmup() or use suspend dayModel()")`. This is an API break → lands IN the v1.4.2 tag notes as breaking.
- **Accept:** no `runBlocking` remains in main source (grep); virtual-time test: statusFlow wakes at each of the six boundaries (depends on O1).

### [ ] O5 — download(): hoist path-set, guard failures
- **Files:** `orchestrator/src/main/kotlin/com/khushu/data/repo/ContentRepository.kt` (`download`)
- **Problem:** `cachingPathSet(caching)` per path = O(N²) over manifest; `failures +=` from 4 workers unsynchronized.
- **Fix:** `val presentBefore = cachingPathSet(caching)` hoisted once; failures via the existing `doneLock` or `ConcurrentLinkedQueue`.
- **Accept:** existing ContentTiers/AdhanAndDownloads tests pass; add a 20-path concurrent download test with forced failures asserting no lost failure rows.

### [ ] O6 — CachingFetcher: synchronize + atomic flush + safe names
- **Files:** `orchestrator/src/main/kotlin/com/khushu/data/transport/CachingFetcher.kt`
- **Problem:** unsynchronized map from concurrent suspends; `flush()` rewrite non-atomic (crash = corrupt manifest); `ensureLoaded()` check-then-act race; `fileFor` flatten+takeLast(200) collision risk.
- **Fix:** single `synchronized(manifestLock)` around all manifest state; `flush()` = write `MANIFEST.tmp` + atomic `renameTo`; `ensureLoaded` under same lock with a `@Volatile` latch; `fileFor` = full flattened subpath when ≤200 chars else `sha256(path)` hex + original extension.
- **Accept:** concurrent fetch/download test (10 coroutines, 50 paths) — manifest always parseable, no duplicate rows; crash-injection test: kill between write and rename → old manifest intact.

### [ ] O7 — localAudioPath: no full download for existence
- **Files:** `orchestrator/src/main/kotlin/com/khushu/data/dua/DuaSource.kt` (`localAudioPath`, `audio`)
- **Problem:** fetches the whole opus to test existence; `audio(id)` fetches again → 2× network per dua (pre-CachingFetcher).
- **Fix:** memoize `audio(id)` bytes in a small LRU (say 8 MB) and derive `localAudioPath` existence from the memo; OR add an `exists()` probe to `ContentFetcher` default-interface (HEAD when remote, file-exists when local) and use it. Prefer the probe — it fixes all callers.
- **Accept:** test: `localAudioPath` on a recording fetcher fetches 0 bytes of body; `audio(id)` fetches exactly once.

### [ ] O8 — single-threaded SQLite access
- **Files:** `orchestrator/src/main/kotlin/com/khushu/data/sunnah/LocalHadithRepository.kt`, `HadithSearchRepository.kt`, `SunnahNamespace.kt`
- **Problem:** shared JDBC Connections across threads, all-sync surface, lazy-init race in `narratorsOf`.
- **Fix:** smallest correct move: a process-wide single-threaded dispatcher per repository; namespace methods become suspend and `withContext(sqlDispatcher)`. (Alternative per-call connections also fine — pick one, document in KDoc.)
- **Accept:** test firing 20 parallel searches/hadith loads — no SQLite threading errors, results consistent.

---

## Release train v1.5.0 — host wiring + engine v2.0.1 (P2)

Host verify: `cd ../Khushu && ./gradlew :app:test`. Engine verify: `cd ../khushu-engine && ./gradlew test`.

### [ ] E1 — hijri() must be timezone-independent (engine v2.0.1)
- **Files:** `khushu-engine/engine/calendar/src/main/kotlin/com/khushu/engine/calendar/HijriCalendar.kt` (`hijri`)
- **Problem:** `UmmalquraCalendar()` no-arg ctor = device-default TZ; fed UTC-midnight → gregorian fields read previous civil day west of UTC. All hijri dates/events/fast-flags one day early for the Americas.
- **Fix:** `UmmalquraCalendar(TimeZone.getTimeZone("UTC"))` in `hijri()` (and audit any other no-arg ctor use in the module). Consider adding an optional explicit-zone overload later — NOT in this fix.
- **Accept:** new test class setting `TimeZone.setDefault` to `America/New_York`, `Pacific/Kiritimati`, `UTC` (restore after) asserting identical hijri for fixed dates; golden round-trip `hijriToGregorian(hijri(d)) == d` across 100 random dates under non-UTC default.

### [ ] E5 — Qada Witr must survive distinct() (engine v2.0.1)
- **Files:** `khushu-engine/engine/prayer/src/main/kotlin/com/khushu/engine/prayer/Qada.kt` (Witr block + return)
- **Problem:** Witr added as `PrayerInstance(date, ISHA, enteredAt)` — identical to the Isha boundary instance → `distinct()` removes it. Hanafi hosts cannot count Witr from `boundaryObligations`.
- **Fix:** add `WITR` to `PrayerStatus.Prayer` (or a parallel `witrObligations` list — pick the smaller ABI impact and document). Rulings text already correct.
- **Accept:** Hanafi boundary test asserts a distinct Witr obligation row for each Isha boundary.

### [ ] E6 — VERIFY Shafiʿi sufficient-time rule (engine; verify BEFORE coding)
- **Files:** `Qada.kt` vs `khushu-engine/docs/qada-design.md` rev 3
- **Problem (apparent):** design doc says Shafiʿi boundary = "sufficient-time-elapsed-before-bleeding"; implementation always owes the current prayer at boundary, no time check anywhere. Either the doc over-specifies or the code under-implements.
- **Action:** read design doc rev 3 first. If the check belongs to the host: add explicit KDoc + a `BoundaryRuling.rule` string stating delegation. If it belongs here: implement behind a `sufficientTimeCheck: Boolean` school default.
- **Accept:** design doc and code agree, with a test pinning whichever behavior is canonical.

### [ ] H1 — wrap the fetcher in CachingFetcher (host)
- **Files:** `Khushu/app/src/main/java/com/kaizen/khushu/core/data/engine/KhushuOrchestratorProvider.kt`
- **Fix:** `CachingFetcher(File(context.cacheDir, "content"), RemoteFetcher(CONTENT_BASE_URL) { httpGet(it) })` (cacheDir or `getExternalFilesDir` — pick one, note why in KDoc).
- **Accept:** cold start → fetch → kill app → airplane-mode relaunch: dua list still renders (disk cache proof); `orch.downloads.summary().totalBytes > 0` after any fetch.

### [ ] H2 — call warmup() at app start (host)
- **Files:** Application startup path (find the Application class / AppContainer)
- **Fix:** `scope.launch { orch.warmup(todayKey, includeTomorrow = true) }` after location/zone resolve; guard for null location (skip + retry on first fix).
- **Accept:** first frame never triggers a network fetch in a profiler trace; log line "dayModel warm" present.

### [ ] D2 — pin the content source (host)
- **Files:** `KhushuOrchestratorProvider.kt` + `gradle/libs.versions.toml`
- **Fix:** URL becomes `https://raw.githubusercontent.com/greykaizen/khushu-data-api/<tag>/` with `<tag>` from versions catalog (e.g. `khushuDataApiContent = "content-v2026.09"`). Document the release process: content releases = tag the data-api repo after regenerating D3's ledger.
- **Accept:** URL string sourced from versions catalog; a doc note in the host README on bumping it.

### [ ] D3 — regenerate the downloads ledger (data-api repo)
- **Files:** `khushu-data-api/inventory/downloads_manifest.json` (generated), `tools/generate_downloads_manifest.py`
- **Fix:** run the generator at the content release; add orchestrator-side `check(plan.paths.isNotEmpty())` in `download()` as a guard (orchestrator change — may ride v1.4.2 instead, owner's call).
- **Accept:** ledger non-empty, row count ≈ MANIFEST.sha256 line count (inventory-side); `orch.downloads.plans()` returns non-null with non-empty plans.

### [ ] H3 — doctrine amendment, not code movement
- **Files:** `khushu-orchestrator/AGENTS.md` §1/§2 + `khushu-engine/AGENTS.md`
- **Fix:** text change: "one coordinate for compute+content; the settings store is a separate host concern (persistence stays out of the orchestrator)". Do NOT fold store into orchestrator.
- **Accept:** both AGENTS.md files state the same rule; no build.gradle changes.

---

## Release train v1.6.0 — orchestrator perf + cleanup (P3)

Verify: `cd orchestrator && ./gradlew :orchestrator:test`

### [ ] O9 — mushaf/calendar perf + dedup
- `BundleAssets`: build `Map<Int, List<RegistryWord>>` by ayahId once per bundle; `renderableAyah` uses it.
- Bound `AtlasSources.zipCache` + bundle memo (LRU, ~4 bundles) — measure before/after with a 10-bundle touch test.
- `CalendarNamespace.eventsFor`: remove unused `zoneId` param (API break, note it) or use it for the hijri anchor — decide, document.
- `forceRecompute` per-key: `dayModel(key, forceRecompute = true)` invalidates only that key (whole-LRU clear was never the contract).
- Dedupe `neighbourEntries`/`entriesOf` (one function, param for nextFajr edge) + the two glyph-mapping blocks.
- `PrayerNamespace`: `withTimeoutOrNull(waitMs) { delay(Long.MAX_VALUE) }` → `delay(waitMs)`.
- **Accept:** all existing tests green + a renderableAyah benchmark assertion (e.g. 100 ayahs < 50 ms with index).

### [ ] O10 — close() + drift fixes
- `KhushuOrchestrator.close()` → delegates `data.close()` (KhushuContent is AutoCloseable) + clears caches; document singleton lifecycle.
- AGENTS.md §7: either route adjacent-day builds through engine CachedEngine or reword the claim (pick rewording — DayModel is the cache by design).
- Fix coordinate strings in AGENTS.md to match the host's actual JitPack coordinate.
- **Accept:** grep-clean doc claims; close() test (attach → close → attach again works).

### [ ] E2 — ANTI_TRANSIT_FAJR provenance (engine v2.0.2 or rides 2.0.1)
- `PrayerCalculator.resolution()`: when rule == ANTI_TRANSIT_FAJR and `antiTransitFajr == null`, report a new `RESOLVED_BY_FALLBACK_RULE` carrying the actual rule (MIDDLE_OF_NIGHT).
- **Accept:** audit test at both a normal and a persistent-twilight location.

### [ ] E3 — delete dead Mushaf expression
- `Mushaf.kt` `layoutWord`: `max(fontSizePx * 0.35f, fontSizePx * 0.4f)` → `fontSizePx * 0.4f`.
- **Accept:** compile + existing mushaf tests green (no behavior change by definition).

---

## Content release (data-api, no code tag)

### [ ] D1 — hash the assets tier
- `tools/generate_downloads_manifest.py`: emit rows for `assets/**` (or a sibling `assets/MANIFEST.sha256`); ledger carries sha256 for those paths → `shaVerify` stops no-oping on audio.
- **Accept:** every file under assets/ has a row; a tampered byte fails `download(shaVerify = true)` in a test.

### [ ] Docs pass (P4)
- data-api README: deprecation banner for library coordinates (retired coordinate; store-only now).
- Audit doc ↔ plan doc cross-link; mark resolved IDs.

---

## Research appendix (verified facts, for agent context)

### Architecture map (as audited)
- **khushu-engine** (~8.9k LOC main, 5.3k test, 288 tests): modules prayer (adhan2 wrapper + engine-level ANTI_TRANSIT_FAJR), calendar (ummalqura-library wrapper + fast rules), astronomy (cosinekitty Astronomy Engine wrapper, 1700–2200 envelope), qibla (great-circle + shadow verification), mushaf (pure glyph layout math, donor QuranApp port), observance, tasbih, zakat (provenance-noted, AAOIFI shares), core (geo/units/errors), facade (thin delegation + DayApi + CachedEngine — fully synchronized).
- **khushu-engine/store** (separate coordinate, host uses it): DataStore bridge over `SettingsSnapshot`; lenient JSON codec (unknown keys ignored, defaults backfill, null round-trip); corruption raises rather than silently resetting — deliberate, religiously-relevant settings.
- **khushu-orchestrator** (~1.3k LOC namespaces + ~2.5k absorbed data layer, 127 tests): DayModel per `(Location, LocalDate, ZoneId, DaySettings)`; namespaces dua/prayer/calendar/mushaf/content/downloads/sunnah; `com.khushu.data.*` absorbed from retired khushu-data-api.
- **khushu-data-api** = content STORE now (no library): `inventory/` (1.1 GB, quran scripts/wbw/atlas/tafsirs/hadith .db/translations) + `assets/` (276 MB, dua/adhan/asma audio) + `inventory/MANIFEST.sha256` (6,457 rows, inventory only) + `inventory/downloads_manifest.json` (ledger, **0 bytes at HEAD**) + tools/ python pipeline.
- **Khushu host**: `KhushuOrchestratorProvider` singleton, bare `RemoteFetcher` over raw.githubusercontent **master** HEAD; orchestrator coordinate `com.github.greykaizen:khushu-orchestrator:orchestrator-v1.4.1`; ALSO uses `com.github.greykaizen.khushu-engine:store:2.0.0` (the H3 doctrine question).

### Third-party stack (verified in builds)
adhan2 (batoulapps, Kotlin) · cosinekitty Astronomy Engine · ummalqura-calendar 2.0.2 (msarhan) · kotlinx.serialization · DataStore · sqlite-jdbc (org.sqlite.JDBC, ro/immutable URIs). No unvetted deps found.

### Coverage matrix (confidence per area)
| Area | Depth | Notes |
|---|---|---|
| orchestrator namespaces + data layer | deep read | all O-findings line-verified |
| engine prayer/calendar/astronomy/qibla/mushaf/facade/store | deep read | E1 confirmed via bytecode inspection of UmmalquraCalendar ctor |
| engine zakat | deep read | clean |
| engine Qada | deep read | E5 confirmed; E6 needs design-doc cross-check |
| engine tasbih/observance/core | light survey | low risk, small, tested |
| data-api corpus integrity | structural | paths verified, hashes counted; NOT byte-verified |
| Khushu host | targeted (provider + usage greps) | H1/H2 confirmed by grep + read |

## Landed

### 2026-09-07 (train 3) — wall completion + host wiring + releases (v1.6.0/2.0.1/content-v2026.09)
Engine 303 tests + apiCheck green; orchestrator 133 green; host family code compiles (host's own PrayScreen/QiblaCompass WIP still broken — theirs, untouched).
- **Wall completion (new)**: `content.quranSearch` (Arabic FTS, was unreachable behind the wall — Quran search impossible while sunnah search worked); `ayahWords`, `mushafScript`, `mutashabihatOccurrences`, `topicRelations`, `adhanStandard`, `catalogPendingUpdates`/`catalogMarkDownloaded` all exposed via ContentNamespace.
- **QuranApi.search lazy-init race fixed**: the racy `fun search(indexDb): QuranSearchIndex` is gone; index creation is dispatcher-confined (first call wins on `indexDb`), close() drains on the same thread.
- **One SQLite executor (O8 completion)**: `KhushuContent.sqlDispatcher` = the single `Dispatchers.IO.limitedParallelism(1)`; sunnah + quran FTS + close all serialize on it (SunnahNamespace previously had its own slice, which did not exclude concurrent quran-search statements).
- **close() race fixed**: `KhushuOrchestrator.close()` runs `data.close()` ON the sql dispatcher (runBlocking is teardown-only, the one sanctioned bridge) — in-flight statements drain before connections release. Pinned: search-after-close rebuilds; sunnah attach→close→attach works.
- **Honest exists contract**: `ContentFetcher.exists` distinguishes `ContentMissingException` (→ false) from transport failures (rethrow) — offline no longer reads as "no audio"; `LocalFetcher` throws typed missing; `RemoteFetcher` takes an optional HEAD `existsProbe`; `CachingFetcher.exists` honors the same contract.
- **Test hygiene**: vacuous `assertEquals(o.dayModel(k1), o.dayModel(k1))` replaced with key/aliasing assertions.
- **H1**: host `KhushuOrchestratorProvider` wraps `RemoteFetcher` in `CachingFetcher(cacheDir/content)` — disk cache live, `orch.downloads` reachable.
- **H2**: provider launches a one-shot `warmup(key, includeTomorrow = true)` once settings resolve (default Karachi location until the location milestone).
- **D2**: content URL pinned to `content-v2026.09` (tagged in data-api); tag mirrored in host `libs.versions.toml` (`khushuDataApiContent`); host `httpGet` throws `ContentMissingException` on 404/410 + a real `httpExists` HEAD probe.
- **Releases**: engine `2.0.1` (+ apiDump: `QadaReport` ctor arity +6 — additive for readers), orchestrator publication `1.6.0` pinning `engine-facade:2.0.1`, data-api `content-v2026.09` committed + tagged, host toml bumped (`orchestrator-v1.6.0`, store `2.0.1`).
- **H3 finished**: engine AGENTS.md §1 now carries the mirrored store-coordinate doctrine.
- **Repo hygiene**: `.gitignore` added; 794 tracked `build/`/`.gradle/` artifacts untracked.
- Host dev-mode: `settings.gradle.kts` gains `-PlocalFamily` composite-build substitution (engine + orchestrator), matching the orchestrator repo's convention.
- API breaks in v1.6.0 (beyond v1.4.2's): `ContentFetcher.exists` now rethrows transport errors; `QuranApi.search` changed shape (suspend, dispatcher-confined); `RemoteFetcher` ctor gains `existsProbe` (defaulted); eventsFor lost `zoneId` (train 2).

### 2026-09-07 (train 2) — engine v2.0.1 + orchestrator v1.6.0 + content store (D1/D3)
All engine tests green (`cd ../khushu-engine && ./gradlew test`); all 130 orchestrator tests green.
- **E1 (hijri TZ)**: root cause ran DEEPER than the ctor — `HijrahChronology.toHijri(Date)` reads Y/M/D via `GregorianCalendar.getInstance()` = **default zone, regardless of the calendar's own zone**. Fix: anchor the millis at default-zone midnight of the target civil date (`localDate.plusDays(offset).atStartOfDay(systemDefault)`), making the *result* device-independent by construction. Pinned by `HijriTimezoneTest` (golden anchors 1 Muharram 1440 = 2018-09-11, 1 Ramadan 1446 = 2025-03-01; 5-zone identity; round-trips under non-UTC defaults).
- **E5 (Qada Witr)**: new `QadaReport.witrObligations` list (no enum change — `PrayerStatus.Prayer.WITR` would have broken exhaustive `when`s in engine + orchestrator). Ḥanafī Witr rows can no longer be deduped away. Tests: survives-distinct + non-Ḥanafī empty.
- **E6**: resolved as documented-delegation — the design doc itself marks the Shafiʿi sufficient-time sub-rule ⬜ OPEN (primary-text pull pending). `Qada.qadaReport` now emits a provenance line disclosing the conservative default (current prayer owed for all schools). Do NOT implement a fiqh position the doc hasn't cited.
- **E2**: `ANTI_TRANSIT_FAJR` at reachable-angle latitudes now reports `RESOLVED_BY_MIDDLE_OF_NIGHT` (the rule that actually produced fajr); `AntiTransitFajrTest` updated. `COMPUTED_WITH_REQUESTED_RULE` remains in the enum (no ABI removal) but is currently unused.
- **E3**: dead `max(0.35f, 0.4f)` deleted.
- **O9**: `BundleAssets.wordsByAyah` index (renderableAyah no longer scans ~77k words per ayah); `AtlasBundleSource.zipCache` LRU-capped at 2 zips (parsed JSON caches stay unbounded — bounded by catalog size in practice); `forceRecompute` now per-key (`cache.invalidate(key)`, whole-LRU `clear()` retained); `neighbourEntries` deduped into `entriesOf`; `eventsFor` lost its never-used `zoneId` param; `withTimeoutOrNull { delay(MAX) }` → `delay(waitMs)`.
- **O10**: `KhushuOrchestrator : AutoCloseable` (`close()` → `data.close()`); AGENTS.md coordinate corrected to the real JitPack form (`com.github.greykaizen:khushu-orchestrator:orchestrator-vX.Y.Z`); store-coordinate doctrine amendment added to §1 (H3).
- **D3**: `inventory/downloads_manifest.json` regenerated — **7,210 rows / 1,424 MB, 996 of them assets files, every row sha256-hashed** → `download(shaVerify = true)` now verifies audio too (closes D1's shaVerify gap). Pipeline script path fix: `asma_ul_husna` is under `assets/`, not `inventory/`.
- **D1**: per-tree source-integrity manifests regenerated in the existing `./relative` format — `inventory/MANIFEST.sha256` (6,455 rows) + NEW `assets/MANIFEST.sha256` (996 rows); generated artifacts (ledger, manifests) excluded from self-hashing.
- Content-release note: the data-api repo now has uncommitted generated artifacts (ledger + 2 manifests) — tag the content release (e.g. `content-v2026.09`) after committing so hosts can pin D2's URL to it.
- **Still open**: H1/H2/D2 (host wiring) — deferred until the Khushu UI work lands, per owner. Everything else on the board is done.

### 2026-09-07 (train 1) — v1.4.2 orchestrator bugfix (O1–O8)
- **O1**: all six raw prayer entries added to `DayModel.boundaries`; walk test pins the contract.
- **O2**: 400-day scan cap → `IllegalStateException`. Empirical note: at Tromsø polar night `dhuhr` (solar transit) stays computable, so the reachable hang is the case where **adhan2 throws** (`runCatching` → `rawDay == null` → all six raws null), not merely null fajr/isha. Cap is defense-in-depth; test accepts both terminate-or-throw outcomes.
- **O3**: `DayModelCache` — peek/get/clear all under the build mutex; no lock-free path.
- **O4**: `prayer.status/statusFlow/alarmSchedule/deriveStatus` are suspend; **`dayModelSync` deleted** (lazy root-cause — no sync callers remain) and `runBlocking` is gone from main source. Breaking: these signatures changed; the host doesn't call them yet (verified).
- **O5**: download() path-set hoisted, `failures` serialized under `doneLock`, empty-plan `check` added (D3's orchestrator half rides here).
- **O6**: `CachingFetcher` — every manifest access under one lock; flush = temp + rename; `fileFor` = verbatim when separator-free, else `sha256(path)+leaf` (old flatten truncated/collided); hand-rolled `AtomicBool` deleted.
- **O7**: `ContentFetcher.exists()` added (default = fetch-and-discard; `LocalFetcher` = file check; `CachingFetcher` = manifest-first, fetch-through-cache fallback); `DuaSource.localAudioPath` probes instead of downloading, `audio()` memoized (LRU 4), cache vars `@Volatile`.
- **O8**: `SunnahNamespace` surface is suspend, confined to `Dispatchers.IO.limitedParallelism(1)` — shared JDBC `Connection`s never touched concurrently. Repositories' sync signatures untouched (data-layer tests unaffected).
- **Pre-existing fix (not a card)**: all 14 test resolvers pointed at the retired `khushu-quran-data` sibling; repointed to `khushu-data-api` (the v1.4.0 rename never updated them — the suite was red on any current checkout).
- API breaks to note in the v1.4.2 tag: sunnah + prayer suspend surfaces, `dayModelSync` removal, `CachingFetcher` cache-file naming change (old caches re-fetch once).
- Repo hygiene: `build/` and `.gradle/` artifacts are tracked in git and churn on every build — consider a `.gitignore` + `git rm -r --cached` pass.

### Evidence index (spot-check pointers)
- `KhushuOrchestrator.kt` → `DayModelCache.peek/clear`, `dayModelSync`, `DayModel.init` boundaries
- `PrayerNamespace.kt` → `alarmSchedule` loop, `statusFlow` loop, `deriveStatus`
- `ContentRepository.kt` → `download()` (`cachingPathSet` per path, `failures +=`), `plans()`
- `CachingFetcher.kt` → `fetch/persist/flush/ensureLoaded/fileFor`
- `DuaSource.kt` → `localAudioPath`/`audio`
- `LocalHadithRepository.kt` → `connections` map, `narratorsOf` lazy init
- `HijriCalendar.kt` → `hijri()` ctor + UTC anchor
- `Qada.kt` → Witr block, `distinct()` at return
- `KhushuOrchestratorProvider.kt` → fetcher wiring, CONTENT_BASE_URL
- `~/.gradle .../ummalqura-calendar-2.0.2.jar` → bytecode: no-arg ctor → `TimeZone.getDefault()`
