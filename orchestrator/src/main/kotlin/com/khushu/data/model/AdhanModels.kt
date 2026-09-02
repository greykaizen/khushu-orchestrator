package com.khushu.data.model

/** One adhan recording from `assets/adhan/` (catalog: adhan_index.json). */
data class AdhanEntry(
    /** Hash-stripped filename stem — stable catalog id. */
    val id: String,
    /** Reciter display name ("Abd Alrazaq Saleh") or place-led label ("Adhan — Haram, Makkah"). */
    val reciter: String,
    /** Region/place label when derivable ("Misr (Egypt)", "Kuwait"); null when unknown. */
    val region: String?,
    /** "Fajr" / "Eid Takbir" variants; null = standard adhan. */
    val style: String?,
    /** Repo-relative path of the opus file. */
    val file: String,
    val format: String,
    val sampleRateHz: Int?,
    val channels: Int?,
    val sizeBytes: Long,
    val sha256: String,
)

/** A reciter (or place) with its catalogued recordings. */
data class AdhanReciter(
    val name: String,
    val entries: List<AdhanEntry>,
) {
    val totalBytes: Long get() = entries.sumOf { it.sizeBytes }
}

// ── Downloads tracking (space management) ──────────────────────────────────

/** One persisted download, as recorded in the cache-dir manifest. */
data class DownloadedItem(
    /** Content path as fetched (e.g. "assets/adhan/….opus"). */
    val path: String,
    /** Derived category prefix: "assets/adhan", "inventory/tafsirs", … */
    val category: String,
    val bytes: Long,
    /** Epoch ms when persisted. */
    val fetchedAtMs: Long,
    val sha256: String?,
)

/** Storage summary for the host's space-management UI. */
data class DownloadsSummary(
    val items: List<DownloadedItem>,
    val totalBytes: Long,
    /** category → bytes ("delete all tafsirs, keep my fonts"). */
    val bytesByCategory: Map<String, Long>,
)

/** Composite ayah retrieval — everything about one ayah in one call. */
data class AyahBundle(
    val surahNo: Int,
    val ayahNo: Int,
    /** Ayah text in each requested script. */
    val texts: Map<String, String>,
    /** Translations keyed by packId (one per requested pack). */
    val translations: Map<String, String>,
    /** Word-by-word rows keyed by language then word index. */
    val wbw: Map<String, Map<Int, WbwWord>>,
    /** Tafsir segments per (slug) — only the ones covering this ayah. */
    val tafsirs: Map<String, List<TafsirSegment>>,
    /** Word-level audio timing anchors per reciter id. */
    val recitationTimings: Map<String, VerseTiming> = emptyMap(),
    /** Chapter info availability flag (fetch separately via chapterInfo). */
    val chapterInfoAvailable: Boolean = true,
)
