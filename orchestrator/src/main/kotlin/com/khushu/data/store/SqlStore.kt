package com.khushu.data.store

/**
 * A single read-only result row, addressed by column label or position.
 * Transport-neutral so identical domain code runs against a local SQLite pack
 * ([JdbcSqlStore]) or a remote Turso query ([HttpSqlStore]) without either
 * leaking a driver/HTTP type upward.
 */
interface Row {
    val columns: List<String>
    fun isNull(column: String): Boolean
    fun string(column: String): String?
    fun int(column: String): Int?
    fun long(column: String): Long?
    fun double(column: String): Double?

    /** Generic accessor over the first column (label unknown to callers of [SqlStore.scalar]). */
    fun any(column: String = columns.firstOrNull().orEmpty()): Any?
}

/**
 * Query-level SQL abstraction over ONE logical database — a downloaded pack
 * fragment (offline) or a Turso database (online). Deliberately NOT a
 * `java.sql.Connection`: the orchestrator is JVM-only and bundles a desktop
 * sqlite-jdbc native that cannot load on Android, so hosts inject the engine
 * (Android `androidx.sqlite`, or HTTP to Turso) through this seam.
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
        queryOne(sql, args)?.any()

    override fun close() {}
}
