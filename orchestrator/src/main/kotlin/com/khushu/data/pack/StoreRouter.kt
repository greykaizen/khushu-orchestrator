package com.khushu.data.pack

import com.khushu.data.store.SqlStore

/**
 * Resolves the [SqlStore] to use for a logical corpus: the local installed pack
 * (offline), else the remote Turso database (online), else null (unavailable →
 * caller may fall back to the JSON path or surface an error). This is the seam the
 * repositories consult so identical domain SQL runs offline, online, or not-at-all,
 * with no per-repo branching on storage.
 *
 * A domain maps to one or more packs; the host supplies the remote fallback per
 * logical DB (there are 6 remote DBs but many packs, e.g. every translation pack
 * shares the `khushu-quran` remote DB).
 */
class StoreRouter(
    private val api: PackApi,
    /** Map a pack id to its remote [SqlStore] (usually by family). */
    private val remoteFor: (packId: String) -> SqlStore?,
) {
    /** Local pack store if installed, else remote, else null. */
    suspend fun resolve(packId: String): SqlStore? =
        api.store(packId) ?: remoteFor(packId)

    fun isLocal(packId: String): Boolean = api.isInstalled(packId)
}
