package com.khushu.data.pack

/** Outcome of an install request. */
sealed class PackInstallResult {
    data class Installed(val bytesFetched: Long) : PackInstallResult()
    data object AlreadyPresent : PackInstallResult()
    data class Failed(val reason: String) : PackInstallResult()
    /** sha256 mismatch — corrupt download; host should delete + retry. */
    data class ChecksumMismatch(val expected: String, val actual: String) : PackInstallResult()
}

/**
 * Host-provided storage for pack fragments. Deliberately abstract:
 * - The **host** owns where files live (Android: `filesDir/packs`, desktop: any dir),
 *   how they're fetched (HTTP, OkHttp, Ktor, file copy), and how they're opened
 *   (Android: `LibsqlSqlStore(File)`, JVM: `JdbcSqlStore(File)`).
 * - The **library** owns the manifest, tracking, tiering, and progress reporting.
 *
 * This is the pack-era analog of `CachingFetcher`: it persists a durable record so
 * "free up space" is first-class, and it never guesses at storage policy.
 */
interface PackStorage {
    fun isInstalled(packId: String): Boolean
    /** Bytes on disk for [packId] (0 if absent). */
    fun installedBytes(packId: String): Long
    fun installedIds(): Set<String>

    /**
     * Fetch + verify + persist the pack file for [info] from [url] into host storage.
     * MUST verify sha256 against [info].sha256 and NOT persist the file on mismatch.
     * [onProgress] may be called 0..n times with bytes-so-far.
     */
    suspend fun install(
        info: PackInfo,
        url: String,
        onProgress: (bytesDone: Long) -> Unit = {},
    ): PackInstallResult

    /** Delete the pack file (Storage-settings "clear" → routes queries to Turso remote). */
    fun delete(packId: String): Boolean

    /** Optional hook: the host returns a SqlStore for a pack id, or null if not installed. */
    fun storeFor(packId: String): com.khushu.data.store.SqlStore? = null
}
