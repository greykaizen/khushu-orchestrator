package com.khushu.data.pack

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Aggregate snapshot for a Storage settings screen. */
data class PackSummary(
    val installed: Set<String>,
    val totalBytes: Long,
    val bytesByFamily: Map<String, Long>,
    val bytesByTier: Map<String, Long>,
)

/**
 * Orchestrator-side pack manager — the pack-era sibling of [com.khushu.data.repo.DownloadsApi].
 * Delegates storage to a host [PackStorage] and reasons only over the [PackManifest].
 * It never touches the filesystem or network itself; that's the host's job (which is why
 * it's portable across Android/JVM/desktop consumers).
 */
class PackApi(
    val manifest: PackManifest,
    private val storage: PackStorage,
    private val parallelism: Int = 4,
) {
    val version: String get() = manifest.version

    fun info(id: String): PackInfo? = manifest.byId(id)
    fun all(): List<PackInfo> = manifest.all()
    fun prebundle(): List<PackInfo> = manifest.byTier("prebundle")
    fun firstRun(): List<PackInfo> = manifest.byTier("first_run")

    fun isInstalled(id: String): Boolean = storage.isInstalled(id)
    fun installedIds(): Set<String> = storage.installedIds().filter { manifest.byId(it) != null }.toSet()

    /** Which packs of [family] are installed vs missing (a picker UI state). */
    fun familyStatus(family: String): Map<String, Boolean> =
        manifest.byFamily(family).associate { it.id to isInstalled(it.id) }

    suspend fun install(id: String, onProgress: (bytesDone: Long) -> Unit = {}): PackInstallResult {
        val info = manifest.byId(id) ?: return PackInstallResult.Failed("unknown pack: $id")
        return if (storage.isInstalled(id)) PackInstallResult.AlreadyPresent
        else storage.install(info, manifest.url(info), onProgress)
    }

    /** Install a whole family (e.g. all tafsir books, all hadith collections). */
    suspend fun installFamily(
        family: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Pair<String, PackInstallResult>> = installMany(manifest.byFamily(family), onProgress)

    suspend fun installTier(
        tier: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Pair<String, PackInstallResult>> = installMany(manifest.byTier(tier), onProgress)

    private suspend fun installMany(
        infos: List<PackInfo>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<Pair<String, PackInstallResult>> {
        if (infos.isEmpty()) return emptyList()
        val sem = Semaphore(parallelism)
        var done = 0
        val total = infos.size
        return coroutineScope {
            infos.map { info ->
                async {
                    sem.withPermit {
                        val r = if (storage.isInstalled(info.id)) PackInstallResult.AlreadyPresent
                        else storage.install(info, manifest.url(info))
                        done++
                        onProgress(done, total)
                        info.id to r
                    }
                }
            }.awaitAll()
        }
    }

    fun delete(id: String): Boolean = storage.delete(id)

    /** Delete an entire family; returns the number of packs removed. */
    fun deleteFamily(family: String): Int =
        manifest.byFamily(family).count { storage.delete(it.id) }

    /** Open a store for an installed pack (host decides engine). Null if not installed. */
    fun store(id: String): com.khushu.data.store.SqlStore? =
        if (storage.isInstalled(id)) storage.storeFor(id) else null

    fun summary(): PackSummary {
        val installed = installedIds()
        val infos = installed.mapNotNull { manifest.byId(it) }
        return PackSummary(
            installed = installed,
            totalBytes = infos.sumOf { it.bytes },
            bytesByFamily = infos.groupBy { it.family }.mapValues { (_, v) -> v.sumOf { it.bytes } },
            bytesByTier = infos.groupBy { it.tier }.mapValues { (_, v) -> v.sumOf { it.bytes } },
        )
    }

    /** Bytes a plan would add to the device (for a "this will use X MB" prompt). */
    fun plannedBytes(ids: Collection<String>): Long = ids.mapNotNull { manifest.byId(it) }.sumOf { it.bytes }
}
