package com.khushu.data.sunnah

import com.khushu.data.transport.ContentFetcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Per-book hadith reading over the SLICED corpus
 * (`inventory/hadiths/{collection}/books/{lang}/{collection}_bNN.json` —
 * verified 97 files / 15.3 MB for Bukhari; ~158 KB avg per book).
 *
 * This is the ONLINE read path (streams through the injected [ContentFetcher],
 * cached by CachingFetcher when present) — the fast-load path the per-book
 * slicing exists for. Offline = the same files prefetched via download plans;
 * the consolidated .db corpora (attachSunnah/LocalHadithRepository) remain the
 * search-heavy path. One read path, no divergence.
 */
class SunnahBookSource(private val fetcher: ContentFetcher) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Corpus layout: English books live in `books/`, translations in `books_{lang}/`. */
    private fun booksDir(collectionId: String, lang: String): String =
        if (lang.equals("en", ignoreCase = true)) "inventory/hadiths/$collectionId/books"
        else "inventory/hadiths/$collectionId/books_${lang.lowercase()}"


    /** Book index: id/number/title/intro/hadith_count per collection. */
    suspend fun bookIndex(collectionId: String): List<BookIndexEntry> {
        val root = json.parseToJsonElement(
            fetcher.fetch("inventory/hadiths/$collectionId/metadata.json").decodeToString(),
        ).jsonObject
        return root["books"]!!.jsonArray.map { o ->
            val b = o.jsonObject
            BookIndexEntry(
                id = b["id"]!!.jsonPrimitive.content,
                collectionId = b["collection_id"]?.jsonPrimitive?.content ?: collectionId,
                number = b["number"]?.jsonPrimitive?.intOrNull ?: 0,
                title = b["title"]?.jsonPrimitive?.contentOrNull ?: "",
                intro = b["intro"]?.jsonPrimitive?.contentOrNull ?: "",
                notes = b["notes"]?.jsonPrimitive?.contentOrNull ?: "",
                hadithCount = b["hadith_count"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }

    /** One full book — every hadith, in book order. */
    suspend fun book(collectionId: String, bookId: String, lang: String = "en"): List<SunnahBookHadith> {
        val path = "${booksDir(collectionId, lang)}/$bookId.json"
        val arr = json.parseToJsonElement(fetcher.fetch(path).decodeToString()).jsonArray
        return arr.map { it.parseHadith() }
    }

    private fun JsonElement.parseHadith(): SunnahBookHadith {
        val o = this.jsonObject
        return SunnahBookHadith(
            id = o["id"]!!.jsonPrimitive.content,
            urn = o["urn"]?.jsonPrimitive?.long,
            collectionId = o["collection_id"]?.jsonPrimitive?.content ?: "",
            bookId = o["book_id"]?.jsonPrimitive?.content ?: "",
            chapterId = (o["chapter_id"] as? JsonNull)?.let { null } ?: o["chapter_id"]?.jsonPrimitive?.contentOrNull,
            number = o["number"]?.jsonPrimitive?.contentOrNull,
            arabicText = o["arabic_text"]?.jsonPrimitive?.contentOrNull ?: "",
            translationText = o["translation_text"]?.jsonPrimitive?.contentOrNull ?: "",
            grades = (o["grades"] as? JsonArray)?.map { g ->
                val go = g.jsonObject
                SunnahBookGrade(
                    name = go["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    grade = go["grade"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            } ?: emptyList(),
            references = (o["references"] as? JsonArray)?.map { r ->
                val ro = r.jsonObject
                SunnahBookReference(
                    type = ro["type"]?.jsonPrimitive?.contentOrNull ?: "",
                    value = ro["value"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            } ?: emptyList(),
            narratorIds = (o["narrators"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList(),
        )
    }

}

/** One book row from the collection's metadata.json. */
data class BookIndexEntry(
    val id: String,
    val collectionId: String,
    val number: Int,
    val title: String,
    val intro: String,
    val notes: String,
    val hadithCount: Int,
)

/** One hadith from the sliced per-book corpus — structured fields, no parsing required. */
data class SunnahBookHadith(
    val id: String,
    val urn: Long?,
    val collectionId: String,
    val bookId: String,
    val chapterId: String?,
    val number: String?,
    val arabicText: String,
    val translationText: String,
    val grades: List<SunnahBookGrade>,
    val references: List<SunnahBookReference>,
    /** Scholar ids (join via scholars_info.db when narrators are displayed). */
    val narratorIds: List<Int>,
)

data class SunnahBookGrade(val name: String, val grade: String)

data class SunnahBookReference(val type: String, val value: String)
