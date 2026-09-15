package com.khushu.data.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One offline pack fragment from `packs.json`. */
data class PackInfo(
    val id: String,
    val family: String,
    /** Consumption tier: "prebundle" | "first_run" | "on_demand". */
    val tier: String,
    val file: String,
    val bytes: Long,
    val sha256: String,
    val tables: List<String>,
    val fts: List<String>,
    val version: String,
)

/**
 * Offline-pack registry parsed from `data/manifest/packs.json` (khushu-data-api).
 * The host fetches it from GitHub raw (versioned) and injects into [PackApi]. Pure
 * data — no storage or network access; [PackApi] composes it with a host [PackStorage].
 */
class PackManifest private constructor(
    val version: String,
    private val byId: Map<String, PackInfo>,
    val packUrlTemplate: String,
    val tierIds: Map<String, List<String>>,
    val familyIds: Map<String, List<String>>,
) {
    fun all(): List<PackInfo> = byId.values.sortedBy { it.id }
    fun byId(id: String): PackInfo? = byId[id]
    fun byFamily(family: String): List<PackInfo> = (familyIds[family] ?: emptyList()).mapNotNull { byId[it] }
    fun byTier(tier: String): List<PackInfo> = (tierIds[tier] ?: emptyList()).mapNotNull { byId[it] }

    /** Absolute download URL for a pack from the host template ("packs-{family}-{rev}/{file}"). */
    fun url(info: PackInfo): String = packUrlTemplate
        .replace("{family}", info.family)
        .replace("{file}", info.file)
        .replace("{version}", info.version)
        .replace("{rev}", info.version)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): PackManifest {
            val o = json.parseToJsonElement(text).jsonObject
            val version = o["version"]!!.jsonPrimitive.content
            val template = o["pack_url_template"]?.jsonPrimitive?.contentOrNull
                ?: "https://github.com/greykaizen/khushu-data-api/releases/download/packs-{family}-{version}/{file}"
            val packs = o["packs"]!!.jsonArray.map { e ->
                val x = e.jsonObject
                PackInfo(
                    id = x["id"]!!.jsonPrimitive.content,
                    family = x["family"]!!.jsonPrimitive.content,
                    tier = x["tier"]!!.jsonPrimitive.content,
                    file = x["file"]!!.jsonPrimitive.content,
                    bytes = x["bytes"]!!.jsonPrimitive.long,
                    sha256 = x["sha256"]!!.jsonPrimitive.content,
                    tables = x["tables"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    fts = x["fts"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    version = x["version"]?.jsonPrimitive?.contentOrNull ?: version,
                )
            }
            return PackManifest(
                version = version,
                byId = packs.associateBy { it.id },
                packUrlTemplate = template,
                tierIds = packs.groupBy { it.tier }.mapValues { (_, v) -> v.map { it.id } },
                familyIds = packs.groupBy { it.family }.mapValues { (_, v) -> v.map { it.id } },
            )
        }
    }
}
