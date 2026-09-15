package com.khushu.data.content

import com.khushu.data.model.MutashabihatOccurrence
import com.khushu.data.model.MutashabihatPhrase
import com.khushu.data.model.SimilarVerse
import com.khushu.data.model.Topic
import com.khushu.data.model.TopicRelation
import com.khushu.data.store.SqlStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * SQL-backed topics (taxonomy + localized titles + ayah links + relations) over the
 * `quran-core` pack (or the `khushu-quran` DB). Returns the same [Topic]/[TopicRelation]
 * models the JSON `TopicsSource` produced. `image_asset_id` maps to Topic.imageUrl
 * (topic images are a GH asset kind; resolve via [AssetResolver] upstream if desired).
 */
class SqlTopicsSource(private val quran: SqlStore) {
    suspend fun topics(type: String? = null): List<Topic> {
        val locs = quran.query("SELECT topic_id, lang_code, title FROM quran_topic_localizations")
            .groupBy({ it.intAt(0)!! }, { it.stringAt(1)!! to (it.stringAt(2) ?: "") })
        val ayahs = quran.query("SELECT topic_id, ayah_id FROM quran_topic_ayahs ORDER BY rowid")
            .groupBy({ it.intAt(0)!! }, { it.intAt(1)!! })
        val rows = if (type == null)
            quran.query("SELECT id, slug, type, image_asset_id, icon, flags FROM quran_topics ORDER BY id")
        else
            quran.query("SELECT id, slug, type, image_asset_id, icon, flags FROM quran_topics WHERE type=? ORDER BY id", listOf(type))
        return rows.map { r ->
            val id = r.intAt(0) ?: return@map null
            val byLang = locs[id].orEmpty().toMap()
            Topic(
                id = id, slug = r.stringAt(1) ?: "", type = r.stringAt(2) ?: "",
                imageUrl = r.stringAt(3)?.ifBlank { null }, icon = r.stringAt(4)?.ifBlank { null },
                flags = r.intAt(5) ?: 0, titleEn = byLang["en"], titleAr = byLang["ar"],
                ayahIds = ayahs[id].orEmpty(),
            )
        }.filterNotNull()
    }

    suspend fun topic(id: Int): Topic? = topics().firstOrNull { it.id == id }
    suspend fun topicBySlug(slug: String): Topic? = topics().firstOrNull { it.slug == slug }
    suspend fun topicsForAyah(ayahId: Int): List<Topic> = topics().filter { ayahId in it.ayahIds }

    suspend fun relations(topicId: Int, type: String? = null): List<TopicRelation> = quran.query(
        "SELECT src_topic_id, tgt_topic_id, type, sort_order FROM quran_relationships WHERE src_topic_id=? " +
            (if (type != null) "AND type=? " else "") + "ORDER BY sort_order",
        if (type != null) listOf(topicId, type) else listOf(topicId),
    ).map { TopicRelation(it.intAt(0) ?: 0, it.intAt(1) ?: 0, it.stringAt(2) ?: "", it.intAt(3) ?: 0) }
}

/**
 * SQL-backed similar verses + mutashabihat (ambiguous/parallel phrases) over the
 * `quran-core` pack. Word-range JSON ([ [from,to], … ]) parsed to [Pair]s, matching
 * the JSON `ContentSelection` output.
 */
class SqlSelectionSource(private val quran: SqlStore, private val json: Json = Json { ignoreUnknownKeys = true }) {
    suspend fun similarTo(ayahId: Int): List<SimilarVerse> = quran.query(
        "SELECT matched_ayah_id, matched_words_count, coverage, score, match_words FROM quran_similar_verses WHERE source_ayah_id=? ORDER BY score DESC",
        listOf(ayahId),
    ).map { r ->
        SimilarVerse(r.intAt(0) ?: 0, r.intAt(1) ?: 0, r.intAt(2) ?: 0, r.intAt(3) ?: 0, pairs(r.stringAt(4)))
    }

    suspend fun phrases(): List<MutashabihatPhrase> = quran.query(
        "SELECT phrase_id, surahs_count, ayahs_count, occurrence_count, source_ayah_id, source_word_from, source_word_to " +
            "FROM quran_mutashabihat_phrases ORDER BY phrase_id",
    ).map { r ->
        MutashabihatPhrase(r.intAt(0) ?: 0, r.intAt(1) ?: 0, r.intAt(2) ?: 0, r.intAt(3) ?: 0, r.intAt(4) ?: 0, r.intAt(5) ?: 0, r.intAt(6) ?: 0)
    }

    suspend fun phrase(id: Int): MutashabihatPhrase? = phrases().firstOrNull { it.phraseId == id }

    suspend fun occurrencesOf(phraseId: Int): List<MutashabihatOccurrence> = quran.query(
        "SELECT ayah_id, word_ranges, in_ayah_order FROM quran_mutashabihat_phrase_ayah WHERE phrase_id=? ORDER BY in_ayah_order",
        listOf(phraseId),
    ).map { r -> MutashabihatOccurrence(r.intAt(0) ?: 0, pairs(r.stringAt(1)), r.intAt(2) ?: 0) }

    private fun pairs(s: String?): List<Pair<Int, Int>> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(s).jsonArray.map { p ->
                val a = p.jsonArray; (a[0].jsonPrimitive.intOrNull ?: 0) to (a[1].jsonPrimitive.intOrNull ?: 0)
            }
        }.getOrDefault(emptyList())
    }
}
