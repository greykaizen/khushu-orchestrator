package com.khushu.data.store

import com.khushu.data.model.AsmaName
import com.khushu.data.model.AsmaPack
import com.khushu.data.model.WbwLangPack
import com.khushu.data.model.WbwWord
import com.khushu.data.repo.IslamicEventEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resolves a corpus `assets` row's blob to a downloadable URL on the GitHub asset
 * releases. `release_name` is the `asset_id` with path separators flattened to `__`;
 * the kind→release-tag mapping + URL template come from `data/manifest/assets.json`
 * (surfaced to the host as build config). Pure + injectable so remote vs CDN differ
 * by only the template.
 */
class AssetResolver(
    private val urlTemplate: String =
        "https://github.com/greykaizen/khushu-data-api/releases/download/assets-{kind}-v2026.09/{release_name}",
) {
    /** Flat GitHub release asset name for an asset id ("names/rahman.opus" -> "names__rahman.opus"). */
    fun releaseName(assetId: String): String = assetId.replace("/", "__")

    fun url(kind: String, assetId: String): String =
        urlTemplate.replace("{kind}", kind).replace("{release_name}", releaseName(assetId))

    /** Resolve an asset id by looking up its kind in the store's `assets` table, then URL. */
    suspend fun urlForAsset(store: SqlStore, assetId: String): String? {
        val kind = store.query("SELECT kind FROM assets WHERE asset_id=?", listOf(assetId))
            .firstOrNull()?.stringAt(0) ?: return null
        return url(kind, assetId)
    }
}

/**
 * SQL-backed 99 Names (Asma ul-Husna) over the `khushu-content` DB / `content` pack.
 * Returns the same [AsmaPack]/[AsmaName] models the JSON `AsmaSource` produced. Audio
 * is resolved to a remote URL via [AssetResolver] (names audio is a GH asset, not a DB blob).
 */
class SqlAsmaSource(private val content: SqlStore, private val assets: AssetResolver? = null) {

    suspend fun pack(lang: String): AsmaPack? {
        val meta = content.query(
            "SELECT lang_code, title, description, hadith, recitation_benefits, total FROM names_packs WHERE lang_code=?",
            listOf(lang),
        ).firstOrNull() ?: return null
        val names = content.query(
            "SELECT number, name, transliteration, translation, meaning, audio_asset_id FROM names_names WHERE lang_code=? ORDER BY number",
            listOf(lang),
        ).map { r ->
            val audioId = r.stringAt(5)
            AsmaName(
                number = r.intAt(0) ?: 0, name = r.stringAt(1) ?: "", transliteration = r.stringAt(2) ?: "",
                translation = r.stringAt(3) ?: "", meaning = r.stringAt(4) ?: "",
                audio = if (assets != null && audioId != null) assets.url("names-audio", audioId) else audioId,
            )
        }
        return AsmaPack(
            langCode = meta.stringAt(0) ?: lang, title = meta.stringAt(1) ?: "", description = meta.stringAt(2),
            hadith = meta.stringAt(3), recitationBenefits = meta.stringAt(4),
            total = meta.intAt(5) ?: names.size, names = names,
        )
    }

    suspend fun name(lang: String, number: Int): AsmaName? =
        content.query(
            "SELECT number, name, transliteration, translation, meaning, audio_asset_id FROM names_names WHERE lang_code=? AND number=?",
            listOf(lang, number),
        ).firstOrNull()?.let { r ->
            val audioId = r.stringAt(5)
            AsmaName(r.intAt(0) ?: number, r.stringAt(1) ?: "", r.stringAt(2) ?: "",
                r.stringAt(3) ?: "", r.stringAt(4) ?: "",
                audio = if (assets != null && audioId != null) assets.url("names-audio", audioId) else audioId)
        }
}

/** SQL-backed Islamic calendar events over `calendar_events`. */
class SqlEventsSource(private val content: SqlStore) {
    suspend fun all(): List<IslamicEventEntry> = content.query(
        "SELECT id, title, hijri_month, hijri_day, category, recurrence, source, confidence " +
            "FROM calendar_events ORDER BY hijri_month, hijri_day",
    ).map { r ->
        IslamicEventEntry(
            id = r.stringAt(0) ?: "", title = r.stringAt(1) ?: "", hijriMonth = r.intAt(2) ?: 0,
            hijriDay = r.intAt(3) ?: 0, category = r.stringAt(4) ?: "", recurrence = r.stringAt(5) ?: "",
            source = r.stringAt(6) ?: "", confidence = r.stringAt(7) ?: "",
        )
    }

    suspend fun forHijriMonth(month: Int): List<IslamicEventEntry> = content.query(
        "SELECT id, title, hijri_month, hijri_day, category, recurrence, source, confidence " +
            "FROM calendar_events WHERE hijri_month=? ORDER BY hijri_day", listOf(month),
    ).map { r ->
        IslamicEventEntry(r.stringAt(0) ?: "", r.stringAt(1) ?: "", r.intAt(2) ?: 0, r.intAt(3) ?: 0,
            r.stringAt(4) ?: "", r.stringAt(5) ?: "", r.stringAt(6) ?: "", r.stringAt(7) ?: "")
    }
}

/**
 * SQL-backed word-by-word over a `wbw-<lang>` pack. [WbwWord] is parsed from the pack's
 * `word_json` ([translation, transliteration] array). Keyed ayah_id -> ordinal -> word.
 */
class SqlWbwSource(private val pack: SqlStore, private val json: Json = Json { ignoreUnknownKeys = true }) {
    suspend fun langPack(lang: String, version: Int = 1): WbwLangPack {
        val verses = HashMap<Int, HashMap<Int, WbwWord>>()
        pack.query("SELECT ayah_id, ordinal, word_json FROM wbw_words WHERE lang_code=? AND version=? ORDER BY ayah_id, ordinal", listOf(lang, version))
            .forEach { r ->
                val ayahId = r.intAt(0) ?: return@forEach
                val ord = r.intAt(1) ?: return@forEach
                val w = parseWord(r.stringAt(2)) ?: return@forEach
                verses.getOrPut(ayahId) { HashMap() }[ord] = w
            }
        return WbwLangPack(lang, version, verses as Map<Int, Map<Int, WbwWord>>)
    }

    private fun parseWord(jsonStr: String?): WbwWord? {
        if (jsonStr.isNullOrBlank()) return null
        return try {
            val el = json.parseToJsonElement(jsonStr)
            if (el is kotlinx.serialization.json.JsonArray && el.size >= 1) {
                val tr = el[0].jsonPrimitive.contentOrNullSafe()
                val tr2 = el.getOrNull(1)?.jsonPrimitive?.contentOrNullSafe()
                WbwWord(translation = tr ?: "", transliteration = tr2)
            } else null
        } catch (_: Exception) { null }
    }
    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content
}
