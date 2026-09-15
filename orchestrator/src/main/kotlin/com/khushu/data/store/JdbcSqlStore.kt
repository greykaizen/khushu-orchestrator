package com.khushu.data.store

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JVM/test/local [SqlStore] over a read-only SQLite file via `org.xerial:sqlite-jdbc`
 * (open `mode=ro&immutable=1`; corpora are never mutated). This is the reference
 * engine used by unit tests and any JVM host — the Android host supplies an
 * `androidx.sqlite`-backed [SqlStore] instead, because sqlite-jdbc's native does
 * not load there.
 *
 * FTS5 works because the fragments carry their own external-content / contentless
 * indexes (see khushu-data-api `deployment/build_packs.py`).
 */
class JdbcSqlStore(private val dbFile: File) : SqlStore {

    init {
        if (!dbFile.isFile) throw IllegalArgumentException("no such SQLite file: $dbFile")
        Class.forName("org.sqlite.JDBC")
    }

    @Volatile private var connection: Connection? = null

    private fun conn(): Connection = synchronized(this) {
        connection?.takeIf { !it.isClosed } ?: DriverManager
            .getConnection("jdbc:sqlite:file:${dbFile.absolutePath}?mode=ro&immutable=1")
            .also { connection = it }
    }

    override suspend fun query(sql: String, args: List<Any?>): List<Row> = withContext(Dispatchers.IO) {
        conn().prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> ps.setNull(i + 1, java.sql.Types.NULL)
                    is Boolean -> ps.setLong(i + 1, if (a) 1 else 0)
                    else -> ps.setObject(i + 1, a)
                }
            }
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                val cols = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                buildList {
                    while (rs.next()) {
                        val values = HashMap<String, Any?>(cols.size)
                        cols.forEachIndexed { idx, name -> values[name] = rs.getObject(idx + 1) }
                        add(JdbcRow(cols, values))
                    }
                }
            }
        }
    }

    override fun close() {
        synchronized(this) { connection?.let { runCatching { it.close() } }; connection = null }
    }
}

/** Row backed by materialised column→value pairs (cursor-independent, ordered). */
internal class JdbcRow(override val columns: List<String>, private val v: Map<String, Any?>) : Row {
    // Positional: values map keyed by column name → look up by name at index.
    private fun at(index: Int): Any? {
        val name = columns.getOrNull(index) ?: return null
        return v[name]
    }
    override fun isNullAt(index: Int) = at(index) == null
    override fun stringAt(index: Int): String? = at(index)?.toString()
    override fun intAt(index: Int): Int? = (at(index) as? Number)?.toInt() ?: at(index)?.toString()?.toIntOrNull()
    override fun longAt(index: Int): Long? = (at(index) as? Number)?.toLong() ?: at(index)?.toString()?.toLongOrNull()
    override fun doubleAt(index: Int): Double? = (at(index) as? Number)?.toDouble() ?: at(index)?.toString()?.toDoubleOrNull()
    override fun anyAt(index: Int): Any? = at(index)
}
