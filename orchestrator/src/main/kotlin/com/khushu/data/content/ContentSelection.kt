package com.khushu.data.content

import com.khushu.data.transport.ContentFetcher
import com.khushu.data.model.MutashabihatOccurrence
import com.khushu.data.model.MutashabihatPhrase
import com.khushu.data.model.SimilarVerse
import com.khushu.data.model.Topic
import com.khushu.data.model.TopicRelation
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Curated-content capabilities: deterministic daily picks + ayah-ref parsing.
 * Pure functions over explicit inputs — no Clock, no randomness.
 */
object ContentSelection {

    /** Same [date] + same pool always yields the same element. */
    fun <T> dailyPick(pool: List<T>, date: LocalDate): T? {
        if (pool.isEmpty()) return null
        val seed = date.toEpochDay()
        val idx = ((seed xor (seed shr 32)) % pool.size).let {
            if (it < 0) it + pool.size else it
        }.toInt()
        return pool[idx]
    }
}

/** A resolved ayah reference ("2:255" or "2:285-286"). */
data class AyahRef(val surah: Int, val ayahFrom: Int, val ayahTo: Int?) {
    companion object {
        private val RANGE_RE = Regex("""(\d+):(\d+)(?:-(\d+))?""")

        fun parse(raw: String): List<AyahRef> =
            raw.split(",").mapNotNull { part ->
                val m = RANGE_RE.matchEntire(part.trim()) ?: return@mapNotNull null
                AyahRef(
                    surah = m.groupValues[1].toInt(),
                    ayahFrom = m.groupValues[2].toInt(),
                    ayahTo = m.groupValues[3].toIntOrNull(),
                )
            }

        /** Compact serializer: "2:285-286". */
        fun format(refs: List<AyahRef>): String =
            refs.joinToString(",") { r ->
                "${r.surah}:${r.ayahFrom}" + (r.ayahTo?.let { "-$it" } ?: "")
            }
    }
}

/** Reads the `inventory/similar/` exports (similar verses + mutashabihat). */
class SimilarVersesSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "inventory/similar",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var pairs: Map<Int, List<SimilarVerse>>? = null
    private var phrases: List<MutashabihatPhrase>? = null
    private var occurrences: Map<Int, List<MutashabihatOccurrence>>? = null

    suspend fun similarTo(ayahId: Int): List<SimilarVerse> = pairs() [ayahId] ?: emptyList()

    suspend fun pairs(): Map<Int, List<SimilarVerse>> = pairs ?: run {
        val root = json.parseToJsonElement(fetcher.fetch("$baseDir/similar_verses.json").decodeToString()).jsonObject
        val out = root["pairs"]!!.jsonObject.mapValues { (_, arr) ->
            arr.jsonArray.map { el ->
                val a = el.jsonArray
                SimilarVerse(
                    matchedAyahId = a[0].jsonPrimitive.int,
                    matchedWordsCount = a[1].jsonPrimitive.int,
                    coverage = a[2].jsonPrimitive.int,
                    score = a[3].jsonPrimitive.int,
                    matchWords = a[4].jsonArray.map { r ->
                        val p = r.jsonArray
                        p[0].jsonPrimitive.int to p[1].jsonPrimitive.int
                    },
                )
            }
        }.mapKeys { it.key.toInt() }
        pairs = out
        out
    }

    suspend fun phrases(): List<MutashabihatPhrase> = phrases ?: run {
        val root = json.parseToJsonElement(fetcher.fetch("$baseDir/mutashabihat.json").decodeToString()).jsonObject
        val out = root["phrases"]!!.jsonArray.map { el ->
            val a = el.jsonArray
            MutashabihatPhrase(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int, a[2].jsonPrimitive.int,
                a[3].jsonPrimitive.int, a[4].jsonPrimitive.int, a[5].jsonPrimitive.int, a[6].jsonPrimitive.int)
        }
        phrases = out
        out
    }

    suspend fun phrase(id: Int): MutashabihatPhrase? = phrases().firstOrNull { it.phraseId == id }

    suspend fun occurrencesOf(phraseId: Int): List<MutashabihatOccurrence> = occ()[phraseId] ?: emptyList()

    private suspend fun occ(): Map<Int, List<MutashabihatOccurrence>> = occurrences ?: run {
        val root = json.parseToJsonElement(fetcher.fetch("$baseDir/mutashabihat.json").decodeToString()).jsonObject
        val out = root["occurrences"]!!.jsonObject.mapValues { (_, arr) ->
            arr.jsonArray.map { el ->
                val a = el.jsonArray
                MutashabihatOccurrence(
                    ayahId = a[0].jsonPrimitive.int,
                    wordRanges = a[1].jsonArray.map { r ->
                        val p = r.jsonArray
                        p[0].jsonPrimitive.int to p[1].jsonPrimitive.int
                    },
                    inAyahOrder = a[2].jsonPrimitive.int,
                )
            }
        }.mapKeys { it.key.toInt() }
        occurrences = out
        out
    }
}

/** Reads `inventory/topics/topics.json` (taxonomy + ayah links + relations). */
class TopicsSource(
    private val fetcher: ContentFetcher,
    private val path: String = "inventory/topics/topics.json",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var topicsCache: List<Topic>? = null
    private var relationsCache: List<TopicRelation>? = null

    suspend fun topics(type: String? = null, langForTitle: String = "en"): List<Topic> =
        load().let { all -> if (type == null) all else all.filter { it.type == type } }

    suspend fun topic(id: Int): Topic? = load().firstOrNull { it.id == id }

    suspend fun topicBySlug(slug: String): Topic? = load().firstOrNull { it.slug == slug }

    suspend fun topicsForAyah(ayahId: Int): List<Topic> = load().filter { ayahId in it.ayahIds }

    suspend fun relations(topicId: Int, type: String? = null): List<TopicRelation> =
        (relationsCache ?: loadRelations()).filter { it.sourceTopicId == topicId && (type == null || it.type == type) }

    private suspend fun load(): List<Topic> = topicsCache ?: run {
        val root = json.parseToJsonElement(fetcher.fetch(path).decodeToString()).jsonObject
        val localizations = root["localizations"]!!.jsonObject
        val ayahs = root["ayahs"]!!.jsonObject
        val out = root["topics"]!!.jsonArray.map { el ->
            val t = el.jsonArray
            val id = t[0].jsonPrimitive.int
            val loc = localizations[id.toString()]?.jsonObject
            Topic(
                id = id,
                slug = t[1].jsonPrimitive.content,
                type = t[2].jsonPrimitive.content,
                imageUrl = t[3].jsonPrimitive.contentOrNullSafe(),
                icon = t[4].jsonPrimitive.contentOrNullSafe(),
                flags = t[5].jsonPrimitive.int,
                titleEn = loc?.get("en")?.jsonArray?.get(0)?.jsonPrimitive?.contentOrNullSafe(),
                titleAr = loc?.get("ar")?.jsonArray?.get(0)?.jsonPrimitive?.contentOrNullSafe(),
                ayahIds = ayahs[id.toString()]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList(),
            )
        }
        topicsCache = out
        out
    }

    private suspend fun loadRelations(): List<TopicRelation> {
        val root = json.parseToJsonElement(fetcher.fetch(path).decodeToString()).jsonObject
        val out = root["relationships"]!!.jsonArray.map { el ->
            val a = el.jsonArray
            TopicRelation(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int,
                a[2].jsonPrimitive.content, a[3].jsonPrimitive.int)
        }
        relationsCache = out
        return out
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        content.takeIf { it != "null" && it.isNotBlank() }
}
