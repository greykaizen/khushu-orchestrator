package com.khushu.data.sunnah

import com.khushu.data.model.Hadith
import com.khushu.data.store.JdbcSqlStore
import com.khushu.data.store.Row
import com.khushu.data.store.SqlStore
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * [SqlHadithSource.byIds] is a pure fast-path: same models as repeated [SqlHadithSource.byId]
 * calls, but one query per table regardless of page size. This builds a tiny pack so the
 * query-count guarantee can be asserted without the large hadith corpus.
 */
class SqlHadithBatchTest {

    private class CountingStore(private val delegate: SqlStore) : SqlStore {
        var queries = 0
            private set

        fun reset() { queries = 0 }

        override suspend fun query(sql: String, args: List<Any?>): List<Row> {
            queries++
            return delegate.query(sql, args)
        }

        override fun close() = delegate.close()
    }

    private fun createPack(): File {
        val file = File.createTempFile("hadith-batch-", ".db").apply { deleteOnExit() }
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE bukhari_hadiths(id TEXT PRIMARY KEY, urn TEXT, collection_id TEXT, book_id TEXT, chapter_id TEXT, number TEXT)")
                st.executeUpdate("CREATE TABLE bukhari_hadith_contents(hadith_id TEXT, lang TEXT, blocks_json TEXT)")
                st.executeUpdate("CREATE TABLE bukhari_hadith_references(hadith_id TEXT, type TEXT, value TEXT)")
                st.executeUpdate("CREATE TABLE bukhari_hadith_related(hadith_id TEXT, related_hadith_id TEXT)")
                st.executeUpdate("CREATE TABLE bukhari_hadith_grades(hadith_id TEXT, grade_id TEXT, label TEXT, lang TEXT)")
                st.executeUpdate("CREATE TABLE bukhari_hadith_narrators(hadith_id TEXT, source TEXT, narrator_id TEXT, position INTEGER)")
                for (i in 1..3) {
                    val id = "h$i"
                    st.executeUpdate("INSERT INTO bukhari_hadiths VALUES('$id','urn$i','bukhari','b1','c1','$i')")
                    st.executeUpdate(
                        "INSERT INTO bukhari_hadith_contents VALUES('$id','en','[{\"type\":\"matn\",\"text\":\"en $i\"}]')",
                    )
                    st.executeUpdate(
                        "INSERT INTO bukhari_hadith_contents VALUES('$id','ar','[{\"type\":\"matn\",\"text\":\"ar $i\"}]')",
                    )
                    st.executeUpdate("INSERT INTO bukhari_hadith_references VALUES('$id','quran','2:$i')")
                    st.executeUpdate("INSERT INTO bukhari_hadith_related VALUES('$id','h${(i % 3) + 1}')")
                    st.executeUpdate("INSERT INTO bukhari_hadith_grades VALUES('$id','sahih','Sahih','en')")
                    st.executeUpdate("INSERT INTO bukhari_hadith_narrators VALUES('$id','scholars','${100 + i}',1)")
                }
            }
        }
        return file
    }

    @Test
    fun byIdsMatchesByidAndBatches() = runTest {
        val file = createPack()
        JdbcSqlStore(file).use { single ->
            JdbcSqlStore(file).use { batch ->
                val one = SqlHadithSource("bukhari", single)
                val many = SqlHadithSource("bukhari", batch)

                // Duplicate ids are preserved (the host can legitimately request one twice),
                // and order follows the request, not the pack.
                val ids = listOf("h2", "missing", "h1", "h2")
                // contentLangs row order is engine-dependent; compare it sorted.
                fun Hadith.normalized() = copy(contentLangs = contentLangs.sorted())
                val expected = ids.mapNotNull { one.byId(it, "en") }.map { it.normalized() }
                val actual = many.byIds(ids, "en").map { it.normalized() }

                assertEquals(expected, actual)
                assertEquals(listOf("h2", "h1", "h2"), actual.map { it.id })
                assertEquals("en 2", actual.first().blocks.single().rawText)
                assertEquals(listOf("ar", "en"), actual.first().contentLangs.sorted())
            }
        }
    }

    @Test
    fun byIdsUsesOneQueryPerTable() = runTest {
        val file = createPack()
        JdbcSqlStore(file).use { delegate ->
            val counting = CountingStore(delegate)
            val src = SqlHadithSource("bukhari", counting)

            val ids = listOf("h1", "h2", "h3")
            src.byIds(ids, "en")
            assertEquals(6, counting.queries, "base + contents + references + related + grades + narrators")

            counting.reset()
            ids.forEach { src.byId(it, "en") }
            assertEquals(18, counting.queries, "the per-id path it replaces")
        }
    }

    @Test
    fun byIdsOverParameterLimitChunks() = runTest {
        val file = createPack()
        JdbcSqlStore(file).use { delegate ->
            val counting = CountingStore(delegate)
            val src = SqlHadithSource("bukhari", counting)
            val ids = (1..501).map { "h$it" }
            val result = src.byIds(ids, "en")
            assertEquals(3, result.size, "only h1..h3 exist; the rest are dropped")
            assertEquals(12, counting.queries, "two chunks × six tables")
        }
    }
}
