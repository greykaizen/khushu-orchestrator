package com.khushu.data.adhan

import com.khushu.data.model.AdhanEntry
import com.khushu.data.model.AdhanReciter
import com.khushu.data.store.SqlStore

/**
 * SQL-backed adhan catalog over the `khushu-audio` DB / `audio` pack, joining the
 * `adhans` index to the embedded `assets` registry for size/sha/path. Returns the same
 * [AdhanEntry]/[AdhanReciter] models as the JSON `AdhanSource`.
 */
class SqlAdhanSource(private val audio: SqlStore) {

    private val cols = "a.adhan_id, a.reciter, a.region, a.style, s.relative_path, a.format, " +
        "a.sample_rate_hz, a.channels, s.size_bytes, s.sha256"

    suspend fun entries(): List<AdhanEntry> = audio.query(
        "SELECT $cols FROM adhans a JOIN assets s ON s.asset_id = a.asset_id " +
            "ORDER BY a.reciter, a.adhan_id",
    ).map { r -> mapEntry(r) }

    suspend fun reciters(): List<AdhanReciter> =
        entries().groupBy { it.reciter }.map { (name, es) -> AdhanReciter(name, es) }
            .sortedBy { it.name }

    suspend fun byReciter(name: String): List<AdhanEntry> = entries().filter { it.reciter == name }

    suspend fun entry(id: String): AdhanEntry? = audio.query(
        "SELECT $cols FROM adhans a JOIN assets s ON s.asset_id = a.asset_id WHERE a.adhan_id=?", listOf(id),
    ).firstOrNull()?.let { mapEntry(it) }

    /** Standard-style recordings only (excludes Fajr-only / Eid-Takbir variants). */
    suspend fun standard(): List<AdhanEntry> = entries().filter { it.style == null }

    private fun mapEntry(r: com.khushu.data.store.Row) = AdhanEntry(
        id = r.stringAt(0) ?: "", reciter = r.stringAt(1) ?: "", region = r.stringAt(2)?.ifBlank { null },
        style = r.stringAt(3)?.ifBlank { null }, file = r.stringAt(4) ?: "", format = r.stringAt(5) ?: "",
        sampleRateHz = r.intAt(6), channels = r.intAt(7), sizeBytes = r.longAt(8) ?: 0L, sha256 = r.stringAt(9) ?: "",
    )
}
