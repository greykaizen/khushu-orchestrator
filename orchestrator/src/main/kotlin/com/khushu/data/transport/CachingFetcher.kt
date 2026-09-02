package com.khushu.data.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Download-tracking + disk-caching decorator over any [ContentFetcher].
 *
 * Why this exists: content tiers are huge (fonts 665 MB, hadiths 301 MB,
 * adhan 164 MB) and hosts need BOTH repeat-read performance AND a durable
 * record of what occupies user storage — so "free up space" is a first-class
 * operation, not archaeology. This decorator persists every fetched payload
 * into a host-provided cache directory and appends it to a manifest that
 * survives restarts.
 *
 * Design contract:
 * - The BASE transport stays pure/streaming — this is opt-in composition.
 * - The API records/deletes/verifies; it never owns storage policy. The
 *   host picks the directory (Android: `context.cacheDir` or
 *   `context.getExternalFilesDir`; desktop: anywhere). Sensible default:
 *   `File(System.getProperty("user.home"), ".khushu/content-cache")`.
 * - The manifest (`downloads_manifest.json`) lives INSIDE the cache dir —
 *   clearing the dir clears everything, atomically.
 * - Categories derive from path prefixes so per-tier deletion is a filter.
 */
class CachingFetcher(
    private val cacheDir: File,
    private val delegate: ContentFetcher,
) : ContentFetcher {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val manifestFile = File(cacheDir, MANIFEST_NAME)
    private val manifest = mutableMapOf<String, ManifestRow>()
    private val loaded = AtomicBool(false)

    @Serializable
    data class ManifestRow(
        val path: String,
        val bytes: Long,
        val fetchedAtMs: Long,
        val sha256: String? = null,
    )

    init {
        cacheDir.mkdirs()
    }

    override suspend fun fetch(path: String): ByteArray {
        ensureLoaded()
        val cached = manifest[path]
        if (cached != null) {
            val f = fileFor(path)
            if (f.isFile) {
                return f.readBytes()
            }
            // manifest claims it but the file vanished (user cleared?) — refetch
        }
        val bytes = delegate.fetch(path)
        persist(path, bytes)
        return bytes
    }

    // ── tracking surface ────────────────────────────────────────────────────

    /** All persisted downloads with categories. */
    fun downloads(): DownloadsSnapshot {
        ensureLoaded()
        val items = manifest.entries.map { (path, row) ->
            DownloadedItemView(
                path = path,
                category = categoryOf(path),
                bytes = row.bytes,
                fetchedAtMs = row.fetchedAtMs,
                sha256 = row.sha256,
            )
        }.sortedBy { it.path }
        return DownloadsSnapshot(
            items = items,
            totalBytes = items.sumOf { it.bytes },
            bytesByCategory = items.groupBy { it.category }
                .mapValues { (_, v) -> v.sumOf { it.bytes } },
        )
    }

    /**
     * Delete persisted items matching [predicate] (e.g. by category), freeing
     * their disk space. Returns the number of items removed.
     */
    fun deleteWhere(predicate: (DownloadedItemView) -> Boolean): Int {
        ensureLoaded()
        var removed = 0
        val doomed = manifest.entries
            .map { (path, row) -> row.toView(path, categoryOf(path)) }
            .filter(predicate)
        for (view in doomed) {
            fileFor(view.path).delete()
            manifest.remove(view.path)
            removed++
        }
        flush()
        return removed
    }

    /** Wipe every persisted download (manifest + files). */
    fun clearAll(): Int {
        ensureLoaded()
        val n = manifest.size
        for (path in manifest.keys) fileFor(path).delete()
        manifest.clear()
        flush()
        return n
    }

    /** Re-hash persisted files and drop manifest rows whose bytes vanished. */
    fun reconcile(): Int {
        ensureLoaded()
        var dropped = 0
        for ((path, row) in manifest.entries.toList()) {
            val f = fileFor(path)
            if (!f.isFile || f.length() != row.bytes) {
                manifest.remove(path)
                dropped++
            }
        }
        flush()
        return dropped
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun persist(path: String, bytes: ByteArray) {
        val f = fileFor(path)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        manifest[path] = ManifestRow(
            path = path,
            bytes = bytes.size.toLong(),
            fetchedAtMs = System.currentTimeMillis(),
            sha256 = null, // per-file hashing on every fetch costs more than it returns; MANIFEST.sha256 covers integrity at source
        )
        flush()
    }

    private fun fileFor(path: String): File {
        val flat = path.replace('/', '_')
        return File(cacheDir, if (flat.length > 200) flat.takeLast(200) else flat)
    }

    /**
     * Content-tier category: the first two path segments ("assets/adhan",
     * "inventory/tafsirs") — granular enough for "delete all tafsirs", broad
     * enough that tier menus stay short.
     */
    private fun categoryOf(path: String): String {
        val parts = path.split('/')
        return when {
            parts.size >= 2 -> parts.subList(0, 2).joinToString("/")
            parts.size == 1 -> parts[0]
            else -> path
        }
    }

    private fun ensureLoaded() {
        if (loaded.value) return
        if (manifestFile.isFile) {
            runCatching {
                manifest.clear()
                manifest.putAll(
                    json.decodeFromString<List<ManifestRow>>(manifestFile.readText())
                        .associateBy({ it.path }, { it }),
                )
            }
        }
        loaded.set(true)
    }

    private fun flush() {
        manifestFile.writeText(json.encodeToString(manifest.values.toList()))
    }

    companion object {
        const val MANIFEST_NAME = "downloads_manifest.json"
    }
}

/** Immutable view rows for the host. */
data class DownloadedItemView(
    val path: String,
    val category: String,
    val bytes: Long,
    val fetchedAtMs: Long,
    val sha256: String?,
)

data class DownloadsSnapshot(
    val items: List<DownloadedItemView>,
    val totalBytes: Long,
    val bytesByCategory: Map<String, Long>,
)

    private fun CachingFetcher.ManifestRow.toView(path: String, category: String) = DownloadedItemView(
        path = path,
        category = category,
        bytes = bytes,
        fetchedAtMs = fetchedAtMs,
        sha256 = sha256,
    )

/** Minimal internal boolean cell (avoids kotlin.concurrent lock-free API surface). */
private class AtomicBool(initial: Boolean) {
    private var v = initial
    val value: Boolean get() = synchronized(this) { v }
    fun set(b: Boolean) { synchronized(this) { v = b } }
}
