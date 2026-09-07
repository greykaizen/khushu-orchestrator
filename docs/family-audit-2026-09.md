# Khushu family audit — 2026-09-07

Scope: `khushu-orchestrator` (full), `khushu-engine` (all modules + store), `khushu-data-api` (as content store), `Khushu` host wiring. Verdict: **architecture is strong; not peak.** The engine math is genuinely good (adhan2 + cosinekitty + ummalqura, wrapped with typed errors, provenance, and 288 tests). The problems concentrate in (a) the v1.4.0 data absorption inside the orchestrator, (b) the host's transport wiring, and (c) one real engine bug (timezone-dependent hijri).

Test coverage: engine 288 tests across 10 modules; orchestrator 127. Store module thin. All engine tests run in UTC — masking bug E1.

**Companion tracker:** `docs/family-fix-plan.md` — status board, release trains, per-task cards with acceptance criteria, research appendix. Update the tracker, not this doc, as work lands.

---

## E — khushu-engine

### E1 · hijri() is timezone-dependent — WRONG DATES WEST OF UTC (bug, high)
`HijriCalendar.hijri()` builds `UmmalquraCalendar()` with the no-arg ctor → **`TimeZone.getDefault()`** — and sets `timeInMillis = date.atStartOfDay(UTC)`. For any device west of UTC, the library's gregorian field read lands on the *previous* civil day → every hijri date, Islamic event, and fast-day flag shifts a day early. The Americas are systematically wrong. Tests pass only because CI runs UTC.
Also violates engine doctrine §"no zone-defaulting" (the whole codebase takes explicit ZoneId everywhere else).
**Fix (as landed, v2.0.1):** the ctor was only half the story — bytecode showed `HijrahChronology.toHijri(Date)` reads Y/M/D via `GregorianCalendar.getInstance()` (default zone) regardless of the calendar's own zone. Landed fix: anchor the millis at **default-zone midnight** of the target civil date, making the result device-independent by construction; pinned by `HijriTimezoneTest` (golden anchors, 5-zone identity, round-trips). `monthNameZeroBased` dead code removed.

### E2 · ANTI_TRANSIT_FAJR provenance mismatch (minor)
At normal latitudes adhan2 computes with the `MIDDLE_OF_THE_NIGHT` fallback while `resolution()` reports `COMPUTED_WITH_REQUESTED_RULE` — the audit trail lies about which rule produced fajr. Fix: report `RESOLVED_BY_FALLBACK_RULE(MIDDLE_OF_NIGHT)` when `antiTransitFajr == null` under that rule.

### E3 · Mushaf dead expression (trivial)
`max(fontSizePx * 0.35f, fontSizePx * 0.4f)` in `layoutWord` — always `0.4f`. Delete.

### E5 · Qada: Hanafi Witr obligation is deduped away (bug, medium)
The Witr block adds `PrayerInstance(date, ISHA, enteredAt)` — identical to the Isha boundary instance — and the return's `distinct()` removes it. Hanafi hosts cannot count Witr from `boundaryObligations` despite the ruling text claiming it's owed. Fix: distinct `WITR` kind or a parallel `witrObligations` list (smaller ABI impact). Discovered during the full-family sweep; see plan doc E5.

### E6 · Qada: Shafiʿi sufficient-time rule apparently unimplemented (verify)
`docs/qada-design.md` rev 3 specifies a Shafiʿi boundary check ("sufficient time elapsed before bleeding"); the implementation always owes the current prayer at boundary with no time check. Either the doc over-specifies or the code under-implements — verify against the design doc before coding (plan card E6).

### E4 · store schemaVersion is write-only (minor, forward-looking)
`SettingsCodec.migrate()` is a passthrough and `SETTINGS_SCHEMA_VERSION` never gates anything yet. Fine for now — leave, but note that the lenient-decode contract ("missing fields fall back") is what carries compatibility; do not add required fields without a migration branch.

**Otherwise clean:** prayer wraps adhan2 with raw/adjusted split + polar fallbacks + warnings; astronomy wraps cosinekitty with an explicit 1700–2200 envelope and typed `UpstreamComputationException`; qibla math standard; CachedEngine is fully `synchronized` (no orchestrator-style race); facade has zero logic. Divergences doc is disciplined.

---

**Also deep-read during the full sweep:** Qada (parameterized schools, provenance strings — see E5/E6 above), zakat (clean: AAOIFI shares, madhab-defaulted debt/jewelry policies, provenance notes, single rounding point). Tasbih/observance/core light-surveyed, low risk.

---

## D — khushu-data-api (as content store)

### D1 · assets/ tier has NO integrity hashes (medium)
`inventory/MANIFEST.sha256` = 6,457 rows covering **inventory/ only**. `assets/` (dua audio, adhan, asma) is unhashed → `shaVerify` in `DownloadsApi.download` silently no-ops for those paths (ledger rows have `sha256 = null` → `expected != null` gate skips). Audio corruption goes undetected.
**Fix:** extend the pipeline (`tools/generate_downloads_manifest.py`) to emit rows for assets/, or a second `assets/MANIFEST.sha256`; make the ledger carry them.

### D2 · Content pinning: host fetches `master` HEAD (medium, host-side)
`KhushuOrchestratorProvider` points at `https://raw.githubusercontent.com/.../master` — unversioned moving target over a 1.4 GB corpus; raw.githubusercontent rate-limits and caches ~5 min. A bad push instantly breaks every install.
**Fix:** fetch from a release tag (e.g. `.../khushu-data-api-<ver>/`) and pin the version in the host like `khushuOrchestrator` is pinned. (GitLab releases / jsDelivr also viable.)

### D3 · `inventory/downloads_manifest.json` is 0 bytes (low)
Ledger empty at HEAD → `plans()` returns a PlanFactory with zero rows; `download()` would fetch nothing and report success. Presumably generated by tools/ per release — ensure the release pipeline regenerates it, and consider `check(plan.paths.isNotEmpty())` in `download()`.

**Otherwise clean:** all orchestrator fetch paths verified to exist (`dua_data.json`, `articles_index.json`, `dua_*.opus`, `quran_glyphs.json`, ledger); `.gitattributes` marks binaries; formats.md is exemplary documentation.

---

## O — khushu-orchestrator (v1.4.x absorbed data layer)

### O1 · DayModel.boundaries omit fajr/sunrise/dhuhr/asr (bug, high)
`DayModel.init` adds only maghrib, isha, nextFajr, section-window edges, next-midnight. `statusFlow`'s boundary-wake contract ("never polls") is broken: countdown goes stale up to `maxWait` (1 h default) after each un-listed entry. **Fix:** add all six raw times. Test: `nextBoundary` covers every prayer entry.

### O2 · alarmSchedule infinite loop at polar locations (bug, high)
`PrayerNamespace.alarmSchedule`: all-null raw times (polar day/night) → `while (out.size < count)` never fills → **hangs the calling thread forever** (sync API). **Fix:** cap the day scan (e.g. 400 days) and throw/return-partial.

### O3 · DayModelCache not thread-safe (bug, high)
`peek()` reads an access-order `LinkedHashMap` outside the mutex (mutates LRU order → structural-modification race with mid-build `get()`); `clear()` runs unlocked while its comment claims "Mutex held". **Fix:** guard peek/clear with the mutex (peek becomes a suspending `withLock` or use a plain ConcurrentHashMap + explicit LRU order list).

### O4 · Sync APIs can runBlocking network I/O → ANR (bug, high)
`dayModelSync` cold path, `prayer.status()`, `statusFlow` (calls dayModelSync inside a coroutine), `alarmSchedule` — a cold cache fetches the dua corpus over HTTP on the caller's thread. **Fix:** make status/statusFlow suspend (use `dayModel`); keep dayModelSync only for cache-hit steady state with a documented "warm first" contract + warn-log on miss.

### O5 · download(): O(N²) path-set + lost failures (bug, medium)
`cachingPathSet()` rebuilds+sorts the whole manifest per path; `failures +=` mutates a plain list from 4 concurrent workers (only `done` is mutex-guarded). **Fix:** hoist `val present = cachingPathSet()` before the loop; guard failures with the existing mutex (or `ConcurrentLinkedQueue`).

### O6 · CachingFetcher thread-safety + non-atomic flush (bug, medium)
Unsynchronized `mutableMapOf` touched from concurrent suspends; `flush()` rewrites the whole manifest non-atomically (crash mid-write = corrupt manifest); `ensureLoaded()` check-then-act race; `fileFor` flattening + `takeLast(200)` can collide. **Fix:** synchronize all manifest access; flush = write temp + atomic rename; single-load latch; collision-proof file naming (hash or full subpath).

### O7 · DuaSource.localAudioPath downloads the full opus to test existence (perf, medium)
And `audio(id)` fetches again — 2× network per dua today. **Fix:** memoize bytes (or a HEAD/exists probe once CachingFetcher is wired — then it's a disk hit).

### O8 · SQLite access unsynchronized (bug, medium)
`LocalHadithRepository`/`HadithSearchRepository` share JDBC `Connection`s across threads; `SunnahNamespace` surface is all-sync → concurrent host queries are UB; `narratorsOf` lazy-init can leak a connection. **Fix:** funnel all calls through a single-threaded dispatcher (make the API suspend) or per-call connections (SQLite handles this fine in ro/immutable mode).

### O9 · Mushaf perf + unbounded memos (perf, low)
`renderableAyah` linear-scans the ~77k-word registry per ayah → index `ayahId → words` once in BundleAssets. `AtlasSources.zipCache` + bundle memo unbounded in memory. `CalendarNamespace.eventsFor` takes `zoneId` and never uses it; `forceRecompute` clears the whole LRU (should clear one key); `neighbourEntries`/`entriesOf` + glyph-mapping blocks duplicated; `withTimeoutOrNull { delay(MAX) }` → just `delay(waitMs)`.

### O10 · Doctrine drift (docs, low)
AGENTS.md cites engine CachedEngine as authoritative but the orchestrator never uses it (adjacent day builds recompute day D twice — µs-scale, but the doc lies). `KhushuOrchestrator` exposes no `close()` though `KhushuContent` is AutoCloseable. AGENTS.md coordinate ≠ host's actual JitPack coordinate. Retired khushu-data-api README still advertises library coordinates.

---

## H — Host wiring (Khushu)

### H1 · Bare RemoteFetcher — the design's core assumption is unmet (high leverage)
No `CachingFetcher` → no disk cache (every launch refetches), `orch.downloads` is dead (`plans()` → null w/ empty ledger, `download()` errors), offline sunnah unreachable, ContentNamespace's "cheap by design" claim false in the only shipping host. **Fix:** wrap `RemoteFetcher` in `CachingFetcher(cacheDir, …)` in `KhushuOrchestratorProvider`.

### H2 · warmup() never called
The app-start hook exists precisely to keep sync APIs off the cold path; nobody calls it. Wire into Application startup.

### H3 · Second family coordinate
Host adds `khushu-engine:store` alongside the orchestrator — violates "one coordinate" doctrine. **Do NOT fold store into the orchestrator** (purity rule §1 forbids persistence there). Amend the doctrine text: "one coordinate for compute+content; the settings store is a separate host concern."

### H4 · Unpinned content source — see D2.

---

## The plan

### v1.4.2 — orchestrator bugfix tag (P1)
1. O1 boundaries + test; O2 scan cap + polar test; O3 mutex-guard peek/clear + concurrency test.
2. O4 suspend status/statusFlow; dayModelSync hit-only + warn on miss.
3. O5 hoisted path-set + guarded failures; O6 atomic CachingFetcher (sync map, temp+rename, single-load, safe names).
4. O7 memoize localAudioPath; O8 single-threaded dispatcher for sunnah repos.
5. Virtual-time tests for all of the above.

### v1.5.0 — host wiring + engine bugfix (P2)
1. H1 CachingFetcher wrap; H2 warmup() at app start; H4 pin content tag.
2. E1 hijri UTC fix + non-UTC-TZ tests (engine tag v2.0.1 — semver: bugfix, ABI unchanged).
3. H3 doctrine amendment (store coordinate), D3 ledger regen + non-empty check.

### v1.6.0 — perf + cleanup (P3)
O9 (ayahId index, bounded zips, dedupe, unused param, per-key invalidation), E2 provenance, E3 dead expr, `close()` on orchestrator.

### docs pass (P4)
O10 + D1 note + data-api README deprecation banner + coordinate reconciliation.

### D1 assets hashing rides the next data-api content release (no code tag needed — store-only change + pipeline tool update).
