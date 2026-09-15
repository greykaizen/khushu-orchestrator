package com.khushu.data.store

/**
 * Host-provided resolver: given a logical pack id (e.g. "quran-core",
 * "translation-en_saheeh-international", "content"), return a [SqlStore] — the
 * installed local pack, or a remote Turso store, or null when neither is available.
 *
 * The orchestrator (JVM) cannot construct the Android engine itself, so the host
 * (Khushu app) injects this, wired to `KhushuCorpus.store(packId)`. Domain
 * repositories take a resolver and resolve the store they need per call, so the
 * identical SQL runs offline (pack), online (Turso), or falls back to the JSON
 * path (null). Read-only; there is no sync.
 */
fun interface SqlStoreResolver {
    suspend fun resolve(packId: String): SqlStore?
}
