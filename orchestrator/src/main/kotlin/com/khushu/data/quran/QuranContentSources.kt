package com.khushu.data.quran

import com.khushu.data.model.ChapterInfo
import com.khushu.data.model.ChapterInfoVariant
import com.khushu.data.model.ChapterTimings
import com.khushu.data.model.ReciterInfo
import com.khushu.data.model.TafsirSegment
import com.khushu.data.model.VerseTiming
import com.khushu.data.model.WbwLangPack
import com.khushu.data.model.WbwWord
import com.khushu.data.model.WordAudioSegment
import com.khushu.data.transport.ContentFetcher
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Surah background info (`inventory/chapters/info/{lang}/{N}.json`,
 * `{N}_58.json` variants). See [ChapterInfoVariant] for the shipped variants.
 */
class ChapterInfoSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "inventory/chapters/info",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, ChapterInfo?>()

    suspend fun info(
        surahNo: Int,
        lang: String = "en",
        variant: ChapterInfoVariant = ChapterInfoVariant.DEFAULT,
    ): ChapterInfo? {
        val key = "$lang/${surahNo}${variant.suffix}"
        if (cache.containsKey(key)) return cache[key]
        val path = "$baseDir/$lang/$surahNo${variant.suffix}.json"
        val text = runCatching { fetcher.fetch(path).decodeToString() }.getOrNull()
        val info = text?.let { parse(surahNo, lang, variant, it) }
        cache[key] = info
        return info
    }

    /** Which variants exist for [surahNo] in [lang] (probes the two shipped files). */
    suspend fun availableVariants(surahNo: Int, lang: String = "en"): List<ChapterInfoVariant> =
        ChapterInfoVariant.entries.filter { info(surahNo, lang, it) != null }

    private fun parse(surahNo: Int, lang: String, variant: ChapterInfoVariant, text: String): ChapterInfo {
        val o = json.parseToJsonElement(text).jsonObject
        return ChapterInfo(
            surahNo = o["chapter_id"]?.jsonPrimitive?.int ?: surahNo,
            langCode = o["language_code"]?.jsonPrimitive?.content ?: lang,
            variant = variant,
            source = o["source"].strSafe(),
            shortText = o["short_text"].strSafe(),
            textHtml = o["text"]?.jsonPrimitive?.content ?: "",
        )
    }

    private fun JsonElement?.strSafe(): String? {
        val v = this ?: return null
        if (v is JsonNull) return null
        val c = (v as? JsonPrimitive)?.content ?: return null
        return if (c == "null" || c.isBlank()) null else c
    }
}

/**
 * Word-by-word translation/transliteration packs
 * (`inventory/wbw/packs/wbw_{lang}.json.gz`,
 * `packs_v2/` — same shape, version 3).
 *
 * Pack shape: `{lang_code, version, verses: {ayah_id: {word_index:
 * [translation, transliteration]}}}` — word indices are 0-based and include
 * the trailing ayah-end marker entry.
 */
class WbwSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "inventory/wbw",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, WbwLangPack>()

    /** Languages with a shipped pack. [v2] selects `packs_v2/` over `packs/`. */
    suspend fun availableLanguages(v2: Boolean = true): List<String> =
        PACK_LANGS.filter { runCatching { pack(it, v2) }.isSuccess }

    suspend fun pack(lang: String, v2: Boolean = true): WbwLangPack {
        val dir = if (v2) "packs_v2" else "packs"
        val key = "$dir/$lang"
        cache[key]?.let { return it }
        val bytes = fetcher.fetch("$baseDir/$dir/wbw_$lang.json.gz")
        val text = GZIPInputStream(bytes.inputStream()).readBytes().decodeToString()
        val root = json.parseToJsonElement(text).jsonObject
        val verses = HashMap<Int, Map<Int, WbwWord>>()
        for ((ayahId, wordsEl) in root["verses"]!!.jsonObject) {
            val words = wordsEl.jsonObject.entries.associate { (idx, el) ->
                val a = el.jsonArray
                idx.toInt() to WbwWord(
                    translation = a[0].jsonPrimitive.content,
                    transliteration = a.getOrNull(1)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                )
            }
            verses[ayahId.toInt()] = words
        }
        return WbwLangPack(
            langCode = root["lang_code"]?.jsonPrimitive?.content ?: lang,
            version = root["version"]?.jsonPrimitive?.int ?: 1,
            verses = verses,
        ).also { cache[key] = it }
    }

    suspend fun forSurah(surahNo: Int, lang: String = "en", v2: Boolean = true): Map<Int, Map<Int, WbwWord>> =
        pack(lang, v2).forSurah(surahNo)

    suspend fun word(surahNo: Int, ayahNo: Int, wordIndex: Int, lang: String = "en", v2: Boolean = true): WbwWord? =
        pack(lang, v2).word(surahNo, ayahNo, wordIndex)

    fun clearCache() = cache.clear()

    companion object {
        /** Languages mirrored under inventory/wbw (catalog: available_wbw_info*.json). */
        val PACK_LANGS = listOf(
            "bn", "de", "en", "fa", "fr", "hi", "id", "ml", "ru", "sd",
            "ta", "tr", "ur", "zh",
        )
    }
}

/**
 * Tafsir content reader. Per-book per-surah splits for lazy loading
 * (`inventory/tafsirs/{lang}/{slug}/split/{NNN}.json`), falling back to the
 * monolithic `tafsir.json.gz` when a split is absent. Segment bodies are
 * HTML — render via the host's markup stack.
 */
class TafsirSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "inventory/tafsirs",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val monolithicCache = HashMap<String, List<TafsirSegment>>()

    /** Tafsir segments covering [surahNo] of [slug] (catalog id, e.g. `en-tafisr-ibn-kathir`). */
    suspend fun forSurah(slug: String, surahNo: Int, lang: String = "en"): List<TafsirSegment> {
        val splitText = listOf("$surahNo.json", "${surahNo.toString().padStart(3, '0')}.json")
            .firstNotNullOfOrNull { name ->
                runCatching {
                    fetcher.fetch("$baseDir/$lang/$slug/split/$name").decodeToString()
                }.getOrNull()
            }
        if (splitText != null) {
            // split files are one-per-surah and omit the `chapter` field
            return parseSegments(splitText).map { if (it.chapter == 0) it.copy(chapter = surahNo) else it }
        }
        // monolithic fallback (cached per book)
        val all = monolithic(slug, lang)
        return all.filter { it.chapter == surahNo }
    }

    /** Everything for one tafsir book (monolithic pack; large — prefer [forSurah]). */
    suspend fun monolithic(slug: String, lang: String = "en"): List<TafsirSegment> {
        val key = "$lang/$slug"
        monolithicCache[key]?.let { return it }
        val bytes = fetcher.fetch("$baseDir/$lang/$slug/tafsir.json.gz")
        val text = GZIPInputStream(bytes.inputStream()).readBytes().decodeToString()
        return parseSegments(text).also { monolithicCache[key] = it }
    }

    private fun parseSegments(text: String): List<TafsirSegment> {
        val root = json.parseToJsonElement(text)
        val arr = when (root) {
            is kotlinx.serialization.json.JsonArray -> root
            is kotlinx.serialization.json.JsonObject -> root["segments"]?.jsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return arr.map { el ->
            val o = el.jsonObject
            TafsirSegment(
                chapter = o["chapter"]?.jsonPrimitive?.int ?: 0,
                fromVerse = o["fromVerse"]?.jsonPrimitive?.int ?: 0,
                toVerse = o["toVerse"]?.jsonPrimitive?.int ?: 0,
                textHtml = o["text"]?.jsonPrimitive?.content ?: "",
            )
        }
    }
}

/**
 * Recitation catalog + per-ayah/per-word audio timing anchors.
 *
 * Timings are repo-local (`inventory/recitations/timings/{reciter}.json.gz`);
 * AUDIO stays on the external host via the catalog's `urlTemplate` (the
 * repo does not redistribute copyrighted audio).
 */
class RecitationSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "inventory/recitations",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var recitersCache: List<ReciterInfo>? = null
    private val timingsCache = HashMap<String, List<ChapterTimings>>()

    suspend fun reciters(): List<ReciterInfo> = recitersCache ?: run {
        val root = json.parseToJsonElement(
            fetcher.fetch("$baseDir/available_recitations_info_v2.json").decodeToString(),
        ).jsonObject
        val out = root["reciters"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            ReciterInfo(
                id = o["id"]!!.jsonPrimitive.content,
                name = o["reciter"]!!.jsonPrimitive.content,
                style = o["style"]?.jsonPrimitive?.content,
                urlTemplate = o["url_template"]?.jsonPrimitive?.content,
                timingUrl = o["timing_url"]?.jsonPrimitive?.content,
                timingVersion = o["timing_version"]?.jsonPrimitive?.int ?: 1,
                audioVersion = o["audio_version"]?.jsonPrimitive?.int ?: 1,
                translations = o["translations"]?.jsonObject
                    ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
            )
        }
        recitersCache = out
        out
    }

    suspend fun reciter(id: String): ReciterInfo? = reciters().firstOrNull { it.id == id }

    /** All chapter timings of one reciter (one gz pack per reciter). */
    suspend fun timings(reciterId: String): List<ChapterTimings> =
        timingsCache[reciterId] ?: run {
            val info = reciter(reciterId) ?: error("unknown reciter $reciterId")
            val url = info.timingUrl ?: error("reciter $reciterId has no timing pack")
            val bytes = fetcher.fetch(url)
            val text = GZIPInputStream(bytes.inputStream()).readBytes().decodeToString()
            val root = json.parseToJsonElement(text).jsonObject
            val out = root["chapters"]!!.jsonArray.map { el ->
                val c = el.jsonObject
                ChapterTimings(
                    reciterId = reciterId,
                    chapter = c["chapter"]!!.jsonPrimitive.int,
                    durationMs = c["duration_ms"]?.jsonPrimitive?.int ?: 0,
                    verses = c["verses"]!!.jsonArray.map { vel ->
                        val v = vel.jsonObject
                        VerseTiming(
                            verse = v["verse"]!!.jsonPrimitive.int,
                            startMs = v["start_ms"]!!.jsonPrimitive.int,
                            endMs = v["end_ms"]!!.jsonPrimitive.int,
                            segments = v["segments"]?.jsonArray?.map { sel ->
                                val s = sel.jsonArray
                                WordAudioSegment(
                                    wordIndex = s[0].jsonPrimitive.int,
                                    startMs = s[1].jsonPrimitive.int,
                                    endMs = s[2].jsonPrimitive.int,
                                )
                            } ?: emptyList(),
                        )
                    },
                )
            }
            timingsCache[reciterId] = out
            out
        }

    suspend fun chapterTimings(reciterId: String, surahNo: Int): ChapterTimings? =
        timings(reciterId).firstOrNull { it.chapter == surahNo }

    /** Surah length check for callers that only need anchors. */
    suspend fun verseTimings(reciterId: String, surahNo: Int): List<VerseTiming> =
        chapterTimings(reciterId, surahNo)?.verses ?: emptyList()

    fun clearCache() {
        timingsCache.clear()
        recitersCache = null
    }
}
