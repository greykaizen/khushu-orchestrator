package com.khushu.data.store

/**
 * A single read-only result row, addressable **by column label or by position**.
 * Label access is the ergonomic path; positional access is mandatory because the
 * Android local engine (libSQL `tech.turso.libsql:libsql`) returns rows as bare
 * `List<Any?>` without column metadata — so every store must supply both.
 *
 * Transport-neutral by contract: identical domain code runs against a local
 * SQLite pack ([JdbcSqlStore] on JVM / `LibsqlSqlStore` on Android) or a remote
 * Turso query ([HttpSqlStore]) without leaking a driver/HTTP type upward.
 */
interface Row {
    /** Column labels in result order; may be empty when the engine doesn't expose names. */
    val columns: List<String>

    // ── positional (always supported; `index` is 0-based) ────────────────────────
    fun isNullAt(index: Int): Boolean
    fun stringAt(index: Int): String?
    fun intAt(index: Int): Int?
    fun longAt(index: Int): Long?
    fun doubleAt(index: Int): Double?
    fun anyAt(index: Int): Any?
    /** BLOB value (e.g. gzipped recitation timings) as bytes; engines return byte[] or a Blob. */
    fun bytesAt(index: Int): ByteArray? = when (val v = anyAt(index)) {
        is ByteArray -> v
        is java.sql.Blob -> try { v.getBytes(1, v.length().toInt()) } catch (e: Exception) { null }
        else -> null
    }

    // ── by label (convenience; default impls resolve via [columns]) ─────────────
    fun indexOf(column: String): Int = columns.indexOf(column)
    fun isNull(column: String): Boolean = isNullAt(indexOf(column))
    fun string(column: String): String? = stringAt(indexOf(column))
    fun int(column: String): Int? = intAt(indexOf(column))
    fun long(column: String): Long? = longAt(indexOf(column))
    fun double(column: String): Double? = doubleAt(indexOf(column))

    /** First-column value, when the caller doesn't care about the label (COUNT, MAX, …). */
    fun any(column: String = columns.firstOrNull().orEmpty()): Any? = anyAt(indexOf(column))
}

/**
 * Query-level SQL abstraction over ONE logical database — a downloaded pack
 * fragment (offline) or a Turso database (online). Deliberately NOT a
 * `java.sql.Connection`: the orchestrator is JVM-only, and on Android neither
 * `sqlite-jdbc` (desktop native) nor `androidx.sqlite` (framework SQLite — has
 * **NO** FTS5, validated on API-37) can read our FTS5-bearing packs. Hosts
 * inject the engine (Android: libSQL bundled native; JVM/tests: sqlite-jdbc;
 * remote: HTTP `/v2/pipeline`) through this seam.
 *
 * Read-only by contract — canonical content is admin-updated, never written by
 * the client (there is NO sync). Implementations run the SAME SQL against the
 * identical namespaced schema (khushu-data-api `data/db` + `data/packs`).
 */
interface SqlStore : AutoCloseable {
    /** Run a read query, binding positional [args] (Int/Long/Double/String/Boolean/null). */
    suspend fun query(sql: String, args: List<Any?> = emptyList()): List<Row>

    suspend fun queryOne(sql: String, args: List<Any?> = emptyList()): Row? =
        query(sql, args).firstOrNull()

    /** First column of the first row, or null — the common COUNT / single-value read. */
    suspend fun scalar(sql: String, args: List<Any?> = emptyList()): Any? =
        queryOne(sql, args)?.anyAt(0)

    override fun close() {}
}
