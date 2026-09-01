# khushu-orchestrator — the composition layer

> Family doctrine: **engine computes · data-api serves · orchestrator composes · host renders.**
> The orchestrator is the only place where khushu-engine and khushu-data-api meet.
> Neither library knows the other exists; this module is the seam.

## 1. Identity

| Item | Value |
|---|---|
| Coordinate | `com.github.greykaizen.khushu-orchestrator:orchestrator:vX.Y.Z` |
| Depends on | `khushu-engine:engine-facade` + `khushu-data-api` (both `api` config — signatures expose their types; compile-time pinning IS the compatibility check) |
| Purity | Pure Kotlin/JVM. No Android, no Compose types, no persistence, no Clock. All time/zone/location are explicit parameters. |
| License | GPL-3.0 (family standard) |

Hosts add **one** coordinate and receive both libraries transitively.

## 2. What the orchestrator IS

1. **Composites** — named functions of the shape *fetch facts (data-api) + apply math (engine) → immutable result*. Nothing else. Any function needing new domain knowledge belongs in a library, not here.
2. **The DayModel** — one cached immutable plan per `(Location, LocalDate, ZoneId, params)`. All derived per-day values (prayer anchors, adaptive dua windows, event overlays) are computed once and served by lookup for the rest of the day. Hosts never recompute; triggers (prayer entry, window expiry) are precomputed boundary instants.
3. **Flow coordinators** — e.g. `dua.adaptiveFlow` wakes exactly at precomputed boundaries (prayer entry, window end) and re-serves from the DayModel — no polling arithmetic, no push between libraries.

## 3. What the orchestrator is NOT

- Not a god facade: no generic `execute(recipe)`, no rule systems. Flat namespaces of named composites.
- Not a cache of last resort: caching semantics per capability use documented keys (engine CachedEngine + data-api CachingFetcher stay authoritative for their own facts); the DayModel caches only composed per-day facts keyed by everything that affects them.
- Not a domain owner: no fiqh, no content curation — schools positions are parameters selected by the host, provenance documented in the libraries that own them.

## 4. Composite catalog v1.0.0

| Namespace | Composite | Engine side | Data side |
|---|---|---|---|
| `dua` | `anchors(PrayerTimesResult) → PrayerAnchors` | `PrayerTimesResult` | — |
| `dua` | `adaptiveFlow(...) → StateFlow<List<AdaptiveDuaSection>>` | `PrayerTimesResult` + `PrayerStatus` semantics | `DuaApi.adaptive` |
| `dua` | `daySections(now)` via DayModel | slot windows from anchors | section content lists |
| `calendar` | `eventsFor(range, zone, params)` | `calendar.hijri` / events | `islamicEvents` display entries |
| `mushaf` | `renderablePage / renderableAyah / prefetchPages` | `mushaf` fit/measure/layout math | `atlas` bundles + `pageLines` + surah-header metadata |

Deliberately absent: sunnah (data-only), zakat/tasbih (values are host-supplied parameters), adhan catalog (no engine join). See `docs/orchestrator-plan.md` for the full design, donor port map (QuranApp/SunnahApp), and the mushaf typed-line model (surah-name/bismillah lines are decoration slots for the host, not atlas glyphs).

## 5. DayModel contract

- Key: `(Location, LocalDate, ZoneId, prayerConfig, userSettings)` — every input that can change any output is in the key.
- Built lazily on first touch; LRU over a few days (yesterday's midnight window, today, tomorrow warmup).
- Serves: prayer times, anchors, per-window dua sections, next-boundary instant — all O(log n) lookups after build.
- Invalidation: day rollover (new key), explicit `forceRecompute()`, host constructs a new request when settings/location change.
- Deterministic: same inputs → same plan, virtual-time testable.

## 6. Verification

- Virtual-time tests for the adaptive flow (prayer transitions → section rotation).
- Slot-window tests: overlap priority, midnight-crossing bedtime, polar days (null anchors → ANYTIME only), day rollover.
- Donor-parity goldens for mushaf layouts (QuranApp reference) — see `docs/orchestrator-plan.md`.
