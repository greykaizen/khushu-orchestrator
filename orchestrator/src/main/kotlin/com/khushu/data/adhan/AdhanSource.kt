package com.khushu.data.adhan

import com.khushu.data.model.AdhanEntry
import com.khushu.data.model.AdhanReciter
import com.khushu.data.transport.ContentFetcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Adhan audio catalog over `assets/adhan/` — 178 donor-collected recordings,
 * per-reciter permissions (LICENSE-CONTENT.md). Audio is mono Opus at mixed
 * 16–48 kHz: Android Media3/ExoPlayer decodes Opus on every API 21+ device
 * (Khushu targets Android 12+; the legacy MediaPlayer's pre-10 Opus gap does
 * not apply). Playback stays host-side; this source only retrieves bytes.
 */
class AdhanSource(
    private val fetcher: ContentFetcher,
    private val baseDir: String = "assets/adhan",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: List<AdhanEntry>? = null

    /** The full catalog (cached after first read). */
    suspend fun entries(): List<AdhanEntry> = cache ?: run {
        val root = json.parseToJsonElement(fetcher.fetch("$baseDir/adhan_index.json").decodeToString()).jsonObject
        val out = root["entries"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            fun optStr(name: String): String? =
                (o[name] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content
            fun optInt(name: String): Int? =
                (o[name] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toIntOrNull()
            AdhanEntry(
                id = o["id"]!!.jsonPrimitive.content,
                reciter = o["reciter"]!!.jsonPrimitive.content,
                region = optStr("region"),
                style = optStr("style"),
                file = o["file"]!!.jsonPrimitive.content,
                format = optStr("format") ?: "opus",
                sampleRateHz = optInt("sampleRateHz"),
                channels = optInt("channels"),
                sizeBytes = o["sizeBytes"]!!.jsonPrimitive.long,
                sha256 = o["sha256"]!!.jsonPrimitive.content,
            )
        }
        cache = out
        out
    }

    /** Grouped by reciter — for picker UIs with per-reciter download sizing. */
    suspend fun reciters(): List<AdhanReciter> =
        entries().groupBy { it.reciter }
            .map { (name, list) -> AdhanReciter(name, list) }
            .sortedBy { it.name }

    suspend fun byReciter(name: String): List<AdhanEntry> =
        entries().filter { it.reciter == name }

    suspend fun entry(id: String): AdhanEntry? = entries().firstOrNull { it.id == id }

    /** Opus bytes for one catalog entry (host plays via Media3). */
    suspend fun audio(id: String): ByteArray? =
        entry(id)?.let { fetcher.fetch(it.file) }

    /** Standard-style entries only (exclude Fajr-only / Eid-Takbir variants). */
    suspend fun standard(): List<AdhanEntry> = entries().filter { it.style == null }
}
