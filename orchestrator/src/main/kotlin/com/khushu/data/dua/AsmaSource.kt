package com.khushu.data.dua

import com.khushu.data.model.AsmaName
import com.khushu.data.model.AsmaPack
import com.khushu.data.transport.ContentFetcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The 99 Names of Allah over `assets/asma_ul_husna/asma_data_{lang}.json`
 * (11 language packs; pure structured JSON — no HTML anywhere).
 */
class AsmaSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "assets/asma_ul_husna",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Available language codes (from the 11 canonical `asma_data_{lang}.json` files). */
    val languages: List<String> = listOf(
        "ar", "bn", "de", "en", "es", "fa", "fr", "id", "ru", "tr", "ur",
    )

    private var cache: MutableMap<String, AsmaPack> = mutableMapOf()

    /** One language pack (99 names, cached per language). */
    suspend fun pack(lang: String): AsmaPack? {
        if (lang !in languages) return null
        cache[lang]?.let { return it }
        val root = json.parseToJsonElement(
            fetcher.fetch("$baseDir/asma_data_$lang.json").decodeToString(),
        ).jsonObject
        val data = root["data"]!!.jsonObject
        val names = data["names"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            AsmaName(
                number = o["number"]!!.jsonPrimitive.int,
                name = o["name"]!!.jsonPrimitive.content,
                transliteration = o["transliteration"]?.jsonPrimitive?.content ?: "",
                translation = o["translation"]?.jsonPrimitive?.content ?: "",
                meaning = o["meaning"]?.jsonPrimitive?.content ?: "",
                audio = o["audio"]?.let { if (it.jsonPrimitive.isString) it.jsonPrimitive.content else null },
            )
        }
        val out = AsmaPack(
            langCode = lang,
            title = data["title"]?.jsonPrimitive?.content ?: "",
            description = data["description"]?.jsonPrimitive?.content,
            hadith = data["hadith"]?.jsonPrimitive?.content,
            recitationBenefits = data["recitation_benefits"]?.jsonPrimitive?.content,
            total = data["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: names.size,
            names = names,
        )
        cache[lang] = out
        return out
    }

    suspend fun name(lang: String, number: Int): AsmaName? =
        pack(lang)?.names?.firstOrNull { it.number == number }
}
