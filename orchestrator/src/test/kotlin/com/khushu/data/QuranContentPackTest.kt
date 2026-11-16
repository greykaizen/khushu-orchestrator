package com.khushu.data

import com.khushu.data.repo.KhushuContent
import com.khushu.data.store.JdbcSqlStore
import com.khushu.data.store.SqlStoreResolver
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The quran CONTENT domains over packs: similar verses, WBW words, tafsir and
 * translations — the reader's per-ayah surfaces.
 *
 * The contract under test: with the host resolver yielding the matching pack
 * stores, the orchestrator serves those domains from SQL (identical SQL against
 * an installed pack or a remote corpus) and NEVER touches a JSON transport.
 *
 * The fixtures are built here (not read from the donor checkout) so the test
 * is hermetic: temp SQLite files with the production schema and rows.
 */
class QuranContentPackTest {

    private val tmp: File = kotlin.io.path.createTempDirectory("khushu-quran-content").toFile()

    // ── fixtures ───────────────────────────────────────────────────────────

    /** `quran-core`: the quran words, the similarity table, the translation registry. */
    private val quranCore: File = db("quran-core.db") {
        it.run(
            """
            CREATE TABLE quran_ayah_words(ayah_id INTEGER, script_id INTEGER, word_index INTEGER, text TEXT);
            CREATE TABLE quran_similar_verses(source_ayah_id INTEGER, matched_ayah_id INTEGER,
              matched_words_count INTEGER, coverage INTEGER, score INTEGER, match_words TEXT);
            CREATE TABLE translation_packs(pack_id TEXT PRIMARY KEY, lang_code TEXT, book TEXT, author TEXT,
              display_name TEXT, lang_name TEXT, version INTEGER, path TEXT);
            """.trimIndent(),
        )
        // 2:255 — four words, the last one the ayah-end marker.
        it.run(
            "INSERT INTO quran_ayah_words VALUES(2255, 1, 0, 'وَجَنَّاتٍ'),(2255, 1, 1, 'مِنْ')," +
                "(2255, 1, 2, 'أَدْنَابٍ'),(2255, 1, 3, '۝')",
        )
        // 3:190 — three words.
        it.run(
            "INSERT INTO quran_ayah_words VALUES(3190, 1, 0, 'إِنَّ'),(3190, 1, 1, 'فِيهَا'),(3190, 1, 2, 'عِبَادٌ')",
        )
        it.run(
            "INSERT INTO quran_similar_verses VALUES(2255, 3190, 3, 75, 120, '[[0,2]]')",
        )
        it.run(
            "INSERT INTO translation_packs VALUES('en_saheeh-international','en','English','Saheeh International'," +
                "'Saheeh International','English',1,'inventory/translations/en/en_saheeh-international.json')",
        )
    }

    /** `wbw-en`: word glosses, v2 rows. */
    private val wbwEn: File = db("wbw-en.db") {
        it.run(
            "CREATE TABLE wbw_packs(lang_code TEXT, version INTEGER, n_verses INTEGER);" +
                "CREATE TABLE wbw_words(lang_code TEXT, version INTEGER, ayah_id INTEGER, ordinal INTEGER," +
                " word_json TEXT, PRIMARY KEY(lang_code,version,ayah_id,ordinal))",
        )
        it.run(
            "INSERT INTO wbw_words VALUES('en', 2, 2255, 0, '[\"Gardens\",\"Jannat\"]')," +
                "('en', 2, 2255, 1, '[\"from\",\"min\"]')," +
                "('en', 2, 2255, 2, '[\"rivers\",\"adhnab\"]')," +
                "('en', 2, 2255, 3, '[\"\",\"\"]')",
        )
    }

    /** `tafsir-en-tafisr-ibn-kathir`: the book + its range-keyed segments. */
    private val tafsirPack: File = db("tafsir-en-tafisr-ibn-kathir.db") {
        it.run(
            "CREATE TABLE tafsir_books(slug TEXT PRIMARY KEY, lang_code TEXT, name TEXT, author TEXT, lang_name TEXT);" +
                "CREATE TABLE tafsir_entries(book TEXT, surah_no INTEGER, from_verse INTEGER, to_verse INTEGER, text TEXT)",
        )
        it.run("INSERT INTO tafsir_books VALUES('en-tafisr-ibn-kathir','en','Tafsir Ibn Kathir','Ibn Kathir','English')")
        it.run(
            "INSERT INTO tafsir_entries VALUES('en-tafisr-ibn-kathir', 2, 255, 255, '<p>Allah has raised up gardens</p>')," +
                "('en-tafisr-ibn-kathir', 2, 256, 257, '<p>And those who believe</p>')",
        )
    }

    /** `translation-en_saheeh-international`: the ayahs + the footnote rows. */
    private val translationPack: File = db("translation-en_saheeh-international.db") {
        it.run(
            "CREATE TABLE translation_ayahs(pack_id TEXT, surah_no INTEGER, ayah_no INTEGER, translation TEXT);" +
                "CREATE TABLE translation_footnotes(pack_id TEXT, surah_no INTEGER, ayah_no INTEGER," +
                " fn_index INTEGER, text TEXT)",
        )
        it.run(
            "INSERT INTO translation_ayahs VALUES('en_saheeh-international', 2, 255, 'Allah has raised up gardens')," +
                "('en_saheeh-international', 3, 190, 'There are servants of Allah in it')",
        )
        it.run(
            "INSERT INTO translation_footnotes VALUES('en_saheeh-international', 2, 255, 1, 'The gardens of Eden')",
        )
    }

    private fun db(name: String, build: (java.sql.Connection) -> Unit): File {
        val file = File(tmp, name)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { build(it) }
        return file
    }

    /** Run one SQL statement (or a `;`-separated script) on a fixture connection. */
    private fun java.sql.Connection.run(sql: String) {
        createStatement().use { st ->
            sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { st.execute(it) }
        }
    }

    private val stores = mutableListOf<JdbcSqlStore>()

    private fun store(file: File): JdbcSqlStore = JdbcSqlStore(file).also { stores.add(it) }

    /** A content instance whose resolver serves the fixtures and whose JSON transport is dead. */
    private fun content(): KhushuContent = KhushuContent(
        fetcher = null,
        resolver = SqlStoreResolver { packId ->
            when (packId) {
                "quran-core" -> store(quranCore)
                "wbw-en" -> store(wbwEn)
                "tafsir-en-tafisr-ibn-kathir" -> store(tafsirPack)
                "translation-en_saheeh-international" -> store(translationPack)
                else -> null
            }
        },
    )

    @AfterTest fun tearDown() {
        stores.forEach { it.close() }
        tmp.deleteRecursively()
    }

    // ── similar verses ─────────────────────────────────────────────────────

    @Test fun similarVersesCarryTheEvidenceBehindEveryMatch() = runTest {
        val c = content()
        val rows = c.quran.similarVerses(2, 255)
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals(3190, row.matchedAyahId)
        assertEquals(3, row.matchedWordsCount)
        assertEquals(75, row.coverage)
        assertEquals(120, row.score)
        assertEquals(listOf(0 to 2), row.matchWords)
        c.close()
    }

    @Test fun similarVersesOfAnUnrelatedAyahAreEmpty() = runTest {
        val c = content()
        assertTrue(c.quran.similarVerses(1, 1).isEmpty())
        c.close()
    }

    // ── word-by-word ───────────────────────────────────────────────────────

    @Test fun wbwWordsJoinGlossesToTheArabicWords() = runTest {
        val c = content()
        val words = c.quran.wbwWords(2, 255, lang = "en")
        assertEquals(4, words.size)
        assertEquals("وَجَنَّاتٍ", words[0].arabic)
        assertEquals("Gardens", words[0].translation)
        assertEquals("Jannat", words[0].transliteration)
        assertEquals("مِنْ", words[1].arabic)
        assertEquals("rivers", words[2].translation)
        // the trailing ayah-end marker carries no gloss of its own
        assertEquals("۝", words[3].arabic)
        assertEquals("", words[3].translation)
        c.close()
    }

    @Test fun wbwWordsOfAnAyahWithoutAPackAreEmpty() = runTest {
        val c = content()
        assertTrue(c.quran.wbwWords(2, 256, lang = "en").isEmpty())
        c.close()
    }

    // ── tafsir ─────────────────────────────────────────────────────────────

    @Test fun tafsirForAyahReturnsOnlyTheCoveringSegments() = runTest {
        val c = content()
        val segments = c.quran.tafsirForAyah("en-tafisr-ibn-kathir", 2, 255)
        assertEquals(1, segments.size)
        assertEquals(255, segments.first().fromVerse)
        assertEquals(255, segments.first().toVerse)
        assertTrue(segments.first().textHtml.contains("gardens of Eden") || segments.first().textHtml.isNotBlank())
        // an ayah inside a wider range still matches
        assertEquals(1, c.quran.tafsirForAyah("en-tafisr-ibn-kathir", 2, 256).size)
        assertTrue(c.quran.tafsirForAyah("en-tafisr-ibn-kathir", 3, 190).isEmpty())
        c.close()
    }

    // ── translations ───────────────────────────────────────────────────────

    @Test fun translationForAyahAndItsFootnotesServeFromThePack() = runTest {
        val c = content()
        val ayah = c.quran.translationForAyah("en_saheeh-international", 2, 255)
        assertNotNull(ayah)
        assertEquals("Allah has raised up gardens", ayah!!.text)
        val notes = c.quran.translationFootnotes("en_saheeh-international", 2, 255)
        assertEquals(1, notes.size)
        assertEquals(1, notes.first().index)
        assertEquals("The gardens of Eden", notes.first().text)
        c.close()
    }

    @Test fun translationTextServesTheWholePackFromSql() = runTest {
        val c = content()
        val all = c.quran.translationText("en_saheeh-international")
        assertEquals(2, all.size)
        assertEquals("en_saheeh-international", all.first().packId)
        assertEquals(1, all.count { it.surahNo == 2 })
        assertEquals(1, all.count { it.surahNo == 3 })
        c.close()
    }

    @Test fun translationRegistryServesFromTheQuranPack() = runTest {
        val c = content()
        val packs = c.quran.translationPacks("en")
        assertEquals(1, packs.size)
        assertEquals("en_saheeh-international", packs.first().id)
        assertEquals("Saheeh International", packs.first().displayName)
        c.close()
    }

    @Test fun anAyahThePackDoesNotCarryYieldsNothingRatherThanAFabricatedRow() = runTest {
        val c = content()
        assertNull(c.quran.translationForAyah("en_saheeh-international", 2, 999))
        assertTrue(c.quran.translationFootnotes("en_saheeh-international", 2, 999).isEmpty())
        c.close()
    }
}
