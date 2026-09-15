package com.khushu.data.content

import com.khushu.data.store.SqlStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SQL-backed curated verse sets over the `content` pack (`curated_sets` + `curated_titles`).
 * `refs` is stored verbatim as a range string (e.g. "2:153,3:173"); parsed through the same
 * [AyahRef.parse] the JSON path used, so results are byte-for-byte equivalent. Titles are
 * per-(kind,entry,lang) with English fallback, mirroring CuratedContentSource.
 */
class SqlCuratedSource(private val content: SqlStore) {
    suspend fun entries(kind: String, lang: String = "en"): List<CuratedVerseEntry> {
        val fallback = titleMap(kind, "en")
        val local = if (lang == "en") fallback else titleMap(kind, lang)
        return content.query("SELECT entry_id, refs FROM curated_sets WHERE kind=? ORDER BY CAST(entry_id AS INTEGER)", listOf(kind))
            .map { r ->
                val id = r.stringAt(0) ?: return@map null
                CuratedVerseEntry(id, AyahRef.parse(r.stringAt(1) ?: ""), local[id], fallback[id])
            }.filterNotNull()
    }

    suspend fun entry(kind: String, id: String, lang: String = "en"): CuratedVerseEntry? =
        entries(kind, lang).firstOrNull { it.id == id }

    private suspend fun titleMap(kind: String, lang: String): Map<String, String> = content.query(
        "SELECT entry_id, title FROM curated_titles WHERE kind=? AND lang=?", listOf(kind, lang),
    ).associate { it.stringAt(0)!! to (it.stringAt(1) ?: "") }
}

/** SQL-backed recommended recitation rules + texts over the `content` pack. */
class SqlRecommendedSource(private val content: SqlStore, private val json: Json = Json { ignoreUnknownKeys = true }) {
    suspend fun rules(): List<RecommendedRule> = content.query(
        "SELECT id, priority, when_json, segments_json FROM recommended_rules ORDER BY priority DESC",
    ).map { r ->
        RecommendedRule(
            id = r.stringAt(0) ?: "", priority = r.intAt(1) ?: 0,
            clauses = parseClauses(r.stringAt(2)), segments = parseSegments(r.stringAt(3)),
        )
    }

    suspend fun rule(id: String): RecommendedRule? = rules().firstOrNull { it.id == id }

    suspend fun defaults(): Map<String, String> = content.query("SELECT key, value FROM recommended_defaults")
        .associate { it.stringAt(0)!! to (it.stringAt(1) ?: "") }

    suspend fun texts(lang: String = "en"): Map<String, RecommendedText> = content.query(
        "SELECT id, title, description FROM recommended_texts WHERE lang=?", listOf(lang),
    ).associate { it.stringAt(0)!! to RecommendedText(it.stringAt(1) ?: "", it.stringAt(2)) }

    private fun parseClauses(whenJson: String?): List<WhenClause> {
        val arr = whenJson?.let { json.parseToJsonElement(it).jsonObject["clauses"]?.jsonArray } ?: return emptyList()
        return arr.map { c ->
            val co = c.jsonObject
            WhenClause(
                weekdays = co["weekdays"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList(),
                hourRanges = co["hourRanges"]?.jsonArray?.map { h ->
                    val p = h.jsonArray; p[0].jsonPrimitive.int to p[1].jsonPrimitive.int
                } ?: emptyList(),
            )
        }
    }

    private fun parseSegments(segmentsJson: String?): List<RecommendedSegment> {
        val arr = segmentsJson?.let { json.parseToJsonElement(it).jsonArray } ?: return emptyList()
        return arr.map { s ->
            if (s is kotlinx.serialization.json.JsonPrimitive) RecommendedSegment(AyahRef.parse(s.content), null)
            else {
                val so = s.jsonObject
                RecommendedSegment(
                    verseRefs = so["verseRef"]?.jsonPrimitive?.content?.let { AyahRef.parse(it) } ?: emptyList(),
                    langKey = (so["langKey"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                )
            }
        }
    }
}

/** SQL-backed Quran-science topics over the `content` pack (localized titles via translations). */
class SqlScienceSource(private val content: SqlStore, private val json: Json = Json { ignoreUnknownKeys = true }) {
    suspend fun topics(): List<ScienceTopic> = content.query(
        "SELECT id, title, references_count, path, translations_json FROM curated_science_topics ORDER BY id",
    ).map { r ->
        val tr = runCatching {
            r.stringAt(4)?.let { json.parseToJsonElement(it).jsonObject.mapValues { e -> e.value.jsonPrimitive.content } }
        }.getOrNull().orEmpty()
        ScienceTopic(r.stringAt(0) ?: "", r.stringAt(1) ?: "", r.intAt(2) ?: 0, r.stringAt(3) ?: "", tr)
    }

    suspend fun topic(id: String): ScienceTopic? = topics().firstOrNull { it.id == id }
}
