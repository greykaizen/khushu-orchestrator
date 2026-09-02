package com.khushu.data.content

import com.khushu.data.transport.ContentFetcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The four curated verse collections shipped as `verses/{set}/`. */
enum class VerseSet(val dir: String, val file: String) {
    /** Situational remedies ("against anxiety", "for patience", ...). */
    SOLUTION("type0", "type0.json"),

    /** Prophetic and Quranic supplications. */
    DUA("type1", "type1.json"),

    /** Quranic etiquettes and character guidance. */
    ETIQUETTE("type2", "type2.json"),

    /** Major sins with their evidence verses. */
    MAJOR_SINS("major_sins", "major_sins.json"),
}

/** One curated entry: id + verse refs + localized title. */
data class CuratedVerseEntry(
    val id: String,
    val ayahRefs: List<AyahRef>,
    val title: String?,
    /** English fallback title when [title]'s locale is unavailable. */
    val fallbackTitle: String?,
)

/** Reads `inventory/curated/verses/` (map.json refs + per-lang titles). */
class CuratedContentSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "inventory/curated/verses",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val titleCache = HashMap<String, Map<String, String>>()
    private val mapCache = HashMap<String, Map<String, List<AyahRef>>>()

    /** All entries of [set] with titles for [lang] (English fallback). */
    suspend fun entries(set: VerseSet, lang: String = "en"): List<CuratedVerseEntry> {
        val refs = refs(set)
        val titles = titles(set, lang)
        val fallback = if (lang == "en") titles else titles(set, "en")
        return refs.map { (id, list) ->
            CuratedVerseEntry(id = id, ayahRefs = list, title = titles[id], fallbackTitle = fallback[id])
        }
    }

    suspend fun entry(set: VerseSet, id: String, lang: String = "en"): CuratedVerseEntry? =
        entries(set, lang).firstOrNull { it.id == id }

    /** Available localization languages for a set's titles. */
    suspend fun availableLanguages(set: VerseSet): List<String> =
        languagesCache.getOrPut(set.dir) {
            // discovered by trying common codes against the fetcher — catalogs
            // don't enumerate them, so we scan the title files lazily once.
            KNOWN_LANGS.filter { lang ->
                runCatching { fetcher.fetch("$baseDir/${set.dir}/$lang/${set.file}") }.isSuccess
            }
        }

    suspend fun refs(set: VerseSet): Map<String, List<AyahRef>> = mapCache.getOrPut(set.dir) {
        val obj = json.parseToJsonElement(fetcher.fetch("$baseDir/${set.dir}/map.json").decodeToString()).jsonObject
        obj.mapValues { (_, v) -> AyahRef.parse(v.jsonPrimitive.content) }
    }

    suspend fun titles(set: VerseSet, lang: String): Map<String, String> =
        titleCache.getOrPut("${set.dir}/$lang") {
            val obj = json.parseToJsonElement(fetcher.fetch("$baseDir/${set.dir}/$lang/${set.file}").decodeToString()).jsonObject
            obj.mapValues { (_, v) -> titleOf(v) }
        }

    private fun titleOf(el: JsonElement): String = when (el) {
        is JsonPrimitive -> el.content
        is JsonObject -> (el["title"] as? JsonPrimitive)?.content ?: el.toString()
        else -> el.toString()
    }

    private val languagesCache = HashMap<String, List<String>>()

    companion object {
        private val KNOWN_LANGS = listOf(
            "ar", "bn", "ckb", "de", "en", "es", "fa", "fil", "fr", "gu", "hi", "id",
            "it", "ky", "ml", "pt", "ru", "sd", "ta", "tr", "ur", "zh-rCN", "zh-rTW",
        )
    }
}

/** Recommended-recitation scheduling rule (verses/recommended/rules.json). */
data class RecommendedSegment(val verseRefs: List<AyahRef>, val langKey: String?)

data class WhenClause(val weekdays: List<Int>, val hourRanges: List<Pair<Int, Int>>)

data class RecommendedRule(
    val id: String,
    val priority: Int,
    val clauses: List<WhenClause>,
    val segments: List<RecommendedSegment>,
)

/** Localized display texts keyed by langKey (recommendation title/description). */
data class RecommendedText(val title: String, val description: String?)

/** Reads `inventory/curated/verses/recommended/` (rules + lang_* texts). */
class RecommendedSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "inventory/curated/verses/recommended",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var rulesCache: List<RecommendedRule>? = null
    private val textCache = HashMap<String, Map<String, RecommendedText>>()

    suspend fun rules(): List<RecommendedRule> = rulesCache ?: run {
        val root = json.parseToJsonElement(fetcher.fetch("$baseDir/rules.json").decodeToString()).jsonObject
        val out = root["rules"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            RecommendedRule(
                id = o["id"]!!.jsonPrimitive.content,
                priority = o["priority"]?.jsonPrimitive?.int ?: 0,
                clauses = o["when"]?.jsonObject?.get("clauses")?.jsonArray?.map { c ->
                    val co = c.jsonObject
                    WhenClause(
                        weekdays = co["weekdays"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList(),
                        hourRanges = co["hourRanges"]?.jsonArray?.map { h ->
                            val p = h.jsonArray
                            p[0].jsonPrimitive.int to p[1].jsonPrimitive.int
                        } ?: emptyList(),
                    )
                } ?: emptyList(),
                segments = o["ref"]?.jsonObject?.get("segments")?.jsonArray?.map { s ->
                    when (s) {
                        is JsonPrimitive -> RecommendedSegment(AyahRef.parse(s.content), null)
                        else -> {
                            val so = s.jsonObject
                            RecommendedSegment(
                                verseRefs = so["verseRef"]?.jsonPrimitive?.content?.let { AyahRef.parse(it) } ?: emptyList(),
                                langKey = (so["langKey"] as? JsonPrimitive)?.content,
                            )
                        }
                    }
                } ?: emptyList(),
            )
        }
        rulesCache = out
        out
    }

    suspend fun texts(lang: String = "en"): Map<String, RecommendedText> = textCache.getOrPut(lang) {
        val obj = json.parseToJsonElement(fetcher.fetch("$baseDir/lang_$lang.json").decodeToString()).jsonObject
        obj.mapValues { (_, v) ->
            val o = v.jsonObject
            RecommendedText(
                title = o["title"]!!.jsonPrimitive.content,
                description = (o["description"] as? JsonPrimitive)?.content,
            )
        }
    }
}

/** Quran-science topical entry (`inventory/curated/science/index.json`). */
data class ScienceTopic(
    val id: String,
    val title: String,
    val referencesCount: Int,
    /** Relative asset path of the content page (webview html in the pack). */
    val path: String,
    val translations: Map<String, String>,
)

/** Reads `inventory/curated/science/index.json`. */
class ScienceTopicsSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "inventory/curated/science",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: List<ScienceTopic>? = null

    suspend fun topics(): List<ScienceTopic> = cache ?: run {
        val arr = json.parseToJsonElement(fetcher.fetch("$baseDir/index.json").decodeToString()).jsonArray
        val out = arr.map { el ->
            val o = el.jsonObject
            ScienceTopic(
                id = o["id"]!!.jsonPrimitive.content,
                title = o["title"]!!.jsonPrimitive.content,
                referencesCount = o["referencesCount"]?.jsonPrimitive?.int ?: 0,
                path = o["path"]!!.jsonPrimitive.content,
                translations = o["translations"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
            )
        }
        cache = out
        out
    }
}
