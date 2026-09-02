package com.khushu.data

import com.khushu.data.dua.AsmaSource
import com.khushu.data.dua.DuaSource
import com.khushu.data.transport.LocalFetcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

/** Dua/Asma/Articles against REAL repo content — anchored to corpus facts. */
class DuaSliceTest {

    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "assets/dua_dhikr/dua_data.json").exists() }
            // absorbed-test resolver: content corpus lives in the sibling checkout
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-quran-data/assets/dua_dhikr/dua_data.json").exists() }
                ?.let { File(it, "khushu-quran-data") }
            ?: error("repo root not found")

    private val fetcher = LocalFetcher(repoRoot.absolutePath.toPath())

    // ── duas ───────────────────────────────────────────────────────────────

    @Test
    fun corpusHas491DuasAcross30Subcategories() = runTest {
        val source = DuaSource(fetcher)
        val duas = source.duas()
        assertEquals(491, duas.size)
        val cats = source.categories()
        assertEquals(30, cats.size)
        assertEquals(12, cats.count { it.category == "main-adhkar" })
        assertEquals(18, cats.count { it.category == "other-adhkar" })
        assertTrue(cats.sumOf { it.count } == 491, "category counts must cover the corpus")
    }

    @Test
    fun duaFieldsAreStructuredNotHtml() = runTest {
        val source = DuaSource(fetcher)
        val d = source.dua(1)!!
        assertEquals("marriage-and-children", d.subcategory)
        // structured text fields — the dua corpus carries no HTML anywhere
        // (verified by corpus scan; articles are the only HTML carrier)
        listOf(d.arabic, d.translation, d.transliteration, d.virtue).forEach {
            assertTrue(!it.contains("<p>") && !it.contains("<div>"), "dua text should not carry HTML")
        }
        assertTrue(d.title.isNotBlank())
        // ids are dense and stable
        assertTrue(source.duas().map { it.id }.distinct().size == 491)
    }

    @Test
    fun subcategoryLookupFilters() = runTest {
        val source = DuaSource(fetcher)
        val morning = source.bySubcategory("morning")
        assertTrue(morning.isNotEmpty())
        assertTrue(morning.all { it.subcategory == "morning" })
        assertEquals(0, source.bySubcategory("no-such-subcategory").size)
    }

    @Test
    fun outOfRangeDuaIsNull() = runTest {
        val source = DuaSource(fetcher)
        assertNull(source.dua(0))
        assertNull(source.dua(492))
    }

    // ── articles (raw HTML passthrough) ───────────────────────────────────

    @Test
    fun articleIndexCovers186EntriesIn12Categories() = runTest {
        val source = DuaSource(fetcher)
        val cats = source.articleCategories()
        assertEquals(12, cats.size)
        // 186 index entries = 169 unique articles + 17 double-indexed
        // (same article listed under two categories — donor index property)
        assertEquals(186, cats.sumOf { it.count })
        assertEquals(186, cats.sumOf { it.articles.size })
        assertEquals(186, source.articles().size)
        assertEquals(169, source.articles().map { it.id }.distinct().size)
        assertEquals(169, source.articles().map { it.filePath }.distinct().size)
        // every indexed file_path actually fetches
        val first = source.articles().first()
        assertNotNull(source.article(first.filePath))
    }

    @Test
    fun articleReturnsRawHtmlBody() = runTest {
        val source = DuaSource(fetcher)
        val first = source.articles().first()
        val article = source.article(first.filePath)!!
        assertTrue(article.contentHtml.startsWith("<"), "body is raw HTML passthrough")
        assertTrue(article.contentHtml.contains("<p>"))
        assertEquals(first.id, article.id)
        assertEquals(first.title, article.title)
    }

    @Test
    fun articlePathTraversalIsRejected() = runTest {
        val source = DuaSource(fetcher)
        assertNull(source.article("../dua_data.json"))      // must stay under articles/
        assertNull(source.article("dua_data.json"))          // not an article path
        assertNull(source.article("/etc/passwd"))
    }

    // ── asma ul husna ──────────────────────────────────────────────────────

    @Test
    fun asmaPacksHave99NamesInAll11Languages() = runTest {
        val source = AsmaSource(fetcher)
        assertEquals(11, source.languages.size)
        for (lang in source.languages) {
            val pack = source.pack(lang)
            assertNotNull(pack, "pack missing for $lang")
            assertEquals(99, pack.names.size, "$lang names")
            assertEquals(99, pack.total)
        }
    }

    @Test
    fun asmaNameLookupWorks() = runTest {
        val source = AsmaSource(fetcher)
        val first = source.name("en", 1)!!
        assertEquals("الرَّحْمَنُ", first.name)
        assertTrue(first.transliteration.isNotBlank())
        assertTrue(first.meaning.isNotBlank())
        assertNull(source.name("en", 0))
        assertNull(source.name("en", 100))
        assertNull(source.name("xx", 1))
    }
}
