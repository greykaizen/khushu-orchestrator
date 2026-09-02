package com.khushu.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import com.khushu.data.markup.ContentMarkup
import com.khushu.data.markup.ContentSpan
import kotlin.test.Test
import kotlin.test.assertTrue

/** Full-corpus parser invariant: every hadith_contents row parses losslessly. */
class MarkupCorpusTest {

    @Test
    fun allCollectionsParseWithoutLoss() {
        val dbs = java.io.File("../inventory/hadiths")
            .listFiles { f -> f.extension == "db" && f.name != "scholars_info.db" } ?: return
        assertTrue(dbs.isNotEmpty(), "no corpora found — run from repo checkout")

        var rows = 0L
        for (db in dbs) {
            Class.forName("org.sqlite.JDBC")
            java.sql.DriverManager.getConnection("jdbc:sqlite:file:${db.absolutePath}?mode=ro&immutable=1").use { conn ->
                conn.createStatement().use { st ->
                    val rs = st.executeQuery("SELECT blocks_json FROM hadith_contents")
                    while (rs.next()) {
                        val arr = Json.parseToJsonElement(rs.getString(1)).jsonArray
                        for (el in arr) {
                            val obj = el.jsonObject
                            val type = obj["type"]?.jsonPrimitive?.content ?: continue
                            val raw = obj["text"]?.jsonPrimitive?.content ?: ""
                            val spans = ContentMarkup.parse(raw)
                            val plain = ContentMarkup.plainText(spans)
                            rows++
                            // Lossless: stripping markup tags must keep all visible text.
                            val expected = raw.replace(
                                Regex("</?(br|b|i|sup|qref|ref)[^>]*/?>", RegexOption.IGNORE_CASE), "",
                            )
                            assertEqualsNormalized(expected, plain, "$db.name [$type]")
                        }
                    }
                }
            }
        }
        println("parsed $rows content rows across ${dbs.size} collections")
        assertTrue(rows > 100_000, "corpus unexpectedly small: $rows")
    }

    private fun assertEqualsNormalized(expected: String, actual: String, where: String) {
        // Plain text may differ from stripped-raw ONLY by whitespace normalization
        // around removed tags. Compare collapsed whitespace.
        // Whitespace-free comparison: <br> projects to \n while tag-stripping
        // removes it entirely; character CONTENT must match, whitespace layout
        // around removed tags is not part of the contract.
        val norm = { s: String -> s.replace(Regex("\\s+"), "") }
        kotlin.test.assertEquals(norm(expected), norm(actual), "lossy parse in $where")
    }

    @Test
    fun knownSpansResolve() {
        val spans = ContentMarkup.parse("A <b>bold <i>nested</i></b> line<br/><qref>2:255</qref> tail")
        assertTrue(spans.contains(ContentSpan.LineBreak))
        assertTrue(spans.any { it is ContentSpan.Bold })
        assertTrue(spans.any { it is ContentSpan.QuranRef && it.raw == "2:255" })
        val plain = ContentMarkup.plainText(spans)
        assertTrue(plain.contains("bold nested"))
        assertTrue(!plain.contains("<b>"))
    }
}
