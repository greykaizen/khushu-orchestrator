package com.khushu.data.store

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * PR#1: the query-level [SqlStore] contract, exercised against the REAL pack
 * fragments (offline/local [JdbcSqlStore]) and against recorded Turso `/v2/pipeline`
 * payloads (online [HttpSqlStore], no network). Same SQL, same schema, two engines —
 * the whole point of the seam.
 */
class SqlStoreTest {

    private val packsRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/packs/quran-core.db").exists() }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-data-api/data/packs/quran-core.db").exists() }
                ?.let { File(it, "khushu-data-api/data/packs") }
            ?: File("missing")

    private val havePacks: Boolean get() = File(packsRoot, "quran-core.db").isFile

    // ── offline / local: external-content FTS5 + rowid-join + params ───────────
    @Test fun quranCoreReadAndExternalContentFts() = runTest {
        assumeTrue(havePacks, "khushu-data-api/data/packs not present")
        JdbcSqlStore(File(packsRoot, "quran-core.db")).use { s ->
            assertEquals(7, s.queryOne("SELECT ayah_count FROM quran_surahs WHERE surah_no=?", listOf(1))!!.int("ayah_count"))
            assertEquals(86, s.queryOne("SELECT COUNT(*) AS n FROM quran_surahs WHERE revelation_type=?", listOf("meccan"))!!.long("n"))
            // 1:1 has ayah_id 1001; uthmani = script_id 1
            val words = s.query("SELECT text FROM quran_ayah_words WHERE ayah_id=? AND script_id=? ORDER BY word_index", listOf(1001, 1))
            assertEquals(5, words.size)
            assertTrue(words.first().string("text")!!.startsWith("بِس"), "first uthmani word: ${words.first().string("text")}")
            // FTS5 external-content MATCH through the java.sql engine
            assertEquals(45, s.queryOne("SELECT COUNT(*) AS n FROM quran_arabic_fts WHERE quran_arabic_fts MATCH ?", listOf("الرحمن"))!!.long("n"))
        }
    }

    @Test fun translationContentlessFtsAndRowidJoin() = runTest {
        assumeTrue(havePacks, "khushu-data-api/data/packs not present")
        JdbcSqlStore(File(packsRoot, "translation-en_saheeh-international.db")).use { s ->
            // contentless fts: COUNT works, columns are not selectable
            assertEquals(172, s.queryOne("SELECT COUNT(*) AS n FROM translation_fts WHERE translation_fts MATCH ?", listOf("merciful"))!!.long("n"))
            // the app's actual pattern: join back to the base table by rowid
            val r = s.queryOne(
                "SELECT ta.surah_no AS sn, ta.ayah_no AS an FROM translation_fts " +
                    "JOIN translation_ayahs ta ON ta.rowid=translation_fts.rowid WHERE translation_fts MATCH ? " +
                    "ORDER BY ta.surah_no, ta.ayah_no LIMIT 1", listOf("merciful"))
            assertNotNull(r); assertEquals(1, r!!.int("sn")); assertEquals(1, r.int("an"))
        }
    }

    // ── online / remote: request shape + response parse, no network ─────────────
    @Test fun httpBuildsReadOnlyBatchRequest() {
        val store = HttpSqlStore("https://khushu-quran.aws.example") { _, _ -> "{}" }
        val body = store.buildRequest("SELECT * FROM quran_surahs WHERE surah_no=?", listOf(7))
        assertTrue("\"type\":\"batch\"" in body && "\"type\":\"execute\"" in body, "batch/execute shape: $body")
        assertTrue("\"sql\":\"SELECT * FROM quran_surahs WHERE surah_no=?\"" in body, "sql field name: $body")
        assertTrue("""{"type":"integer","value":"7"}""" in body, "typed arg: $body")
    }

    @Test fun httpParsesReadRows() = runTest {
        val read = """
        {"baton":null,"base_url":null,"results":[{"type":"ok","response":{"type":"batch",
        "result":{"step_results":[{"cols":[{"name":"n","decltype":null}],
        "rows":[[{"type":"integer","value":"1089"}]]}],"step_errors":[null]}}}]}
        """
        var sentUrl = ""; var sentBody = ""
        val store = HttpSqlStore("https://khushu-content.aws.example") { url, body -> sentUrl = url; sentBody = body; read }
        val rows = store.query("SELECT COUNT(*) AS n FROM names_names")
        assertEquals(1, rows.size)
        assertEquals(1089L, rows.first().long("n"))
        assertEquals(1089L, store.scalar("SELECT COUNT(*) AS n FROM names_names"))
        assertTrue(sentUrl.endsWith("/v2/pipeline"), sentUrl)
        assertTrue("\"type\":\"batch\"" in sentBody)
    }

    @Test fun httpSurfacesReadOnlyWriteRejection() = runTest {
        val denied = """
        {"baton":null,"base_url":null,"results":[{"type":"ok","response":{"type":"batch",
        "result":{"step_results":[null],"step_errors":[{"message":"Operation was blocked: SQL write operations are forbidden (current session does not have write permission)","code":"BLOCKED"}]}}}]}
        """
        val store = HttpSqlStore("https://khushu-content.aws.example") { _, _ -> denied }
        val e = assertFailsWith<TursoQueryException> { store.query("CREATE TABLE _x(y INT)") }
        assertTrue("SQL write operations are forbidden" in (e.message ?: ""), e.message ?: "")
    }
}
