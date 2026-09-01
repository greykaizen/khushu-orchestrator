package com.khushu.orchestrator

import com.khushu.data.plans.CollectionPlan
import com.khushu.data.plans.PlanFactory
import com.khushu.data.repo.CollectionProgress
import com.khushu.data.repo.CollectionResult

/**
 * Download plans + batch execution — delegation over data-api's
 * [com.khushu.data.repo.DownloadsApi]. The CachingFetcher manifest is the
 * completion ledger: resume is automatic (manifest hits skip the network),
 * sha256 verification is on by default, progress is a manifest diff.
 *
 * DOCTRINE: delegation only, one line per call, never add logic here.
 */
class DownloadsNamespace internal constructor(private val o: KhushuOrchestrator) {

    /** Plan factories (null when the transport doesn't serve the downloads ledger). */
    suspend fun plans(): PlanFactory? = o.data.downloads.plans()

    /** Batch download: resume-safe, sha256-verified, bounded parallelism. */
    suspend fun download(
        plan: CollectionPlan,
        shaVerify: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): CollectionResult = o.data.downloads.download(plan, shaVerify, onProgress)

    /** Completion state of a plan against the current manifest (no network). */
    fun progress(plan: CollectionPlan): CollectionProgress =
        o.data.downloads.progress(plan)

    /** Delete every persisted file of a plan. */
    fun delete(plan: CollectionPlan): Int = o.data.downloads.delete(plan)

    // ── storage bookkeeping (host download-manager UI) ─────────────────────

    fun summary() = o.data.downloads.summary()
    fun totalBytes(): Long = o.data.downloads.totalBytes()
    fun clearAll(): Int = o.data.downloads.clearAll()
    fun reconcile(): Int = o.data.downloads.reconcile()
    fun deleteWhere(predicate: (com.khushu.data.transport.DownloadedItemView) -> Boolean): Int =
        o.data.downloads.deleteWhere(predicate)
}
