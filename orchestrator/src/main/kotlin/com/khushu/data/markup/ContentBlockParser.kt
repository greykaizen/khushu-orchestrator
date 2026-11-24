package com.khushu.data.markup

import com.khushu.data.model.BlockType
import com.khushu.data.model.ContentBlock
import com.khushu.data.model.DuaBlocks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * blocks_json → typed [ContentBlock]s with parsed inline spans.
 * Unknown block types flow through as UNKNOWN with raw passthrough.
 */
object ContentBlockParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parseBlocks(blocksJson: String): List<ContentBlock> =
        json.parseToJsonElement(blocksJson).jsonArray.map { el -> parseBlock(el.jsonObject) }

    /** Parse the `blocks` object emitted by the dua canonicalizer. */
    fun parseDuaBlocks(blocksJson: String): DuaBlocks {
        if (blocksJson.isBlank()) return DuaBlocks()
        return runCatching {
            val root = json.parseToJsonElement(blocksJson).jsonObject
            val fields = root["fields"]?.jsonObject ?: return@runCatching DuaBlocks()
            fun field(name: String): List<ContentBlock> =
                fields[name]?.jsonArray?.map { parseBlock(it.jsonObject) } ?: emptyList()
            DuaBlocks(
                arabic = field("arabic"),
                translation = field("translation"),
                transliteration = field("transliteration"),
                virtue = field("virtue"),
                explanation = field("explanation"),
                reference = field("reference"),
            )
        }.getOrElse { DuaBlocks() }
    }

    private fun parseBlock(obj: kotlinx.serialization.json.JsonObject): ContentBlock {
        val rawType = obj["type"]?.jsonPrimitive?.content ?: "unknown"
        val text = obj["text"]?.jsonPrimitive?.content ?: ""
        return ContentBlock(
            type = classify(rawType),
            rawType = rawType,
            spans = ContentMarkup.parse(text),
            rawText = text,
            level = obj["level"]?.jsonPrimitive?.content?.toIntOrNull(),
            lang = obj["lang"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
        )
    }

    private fun classify(rawType: String): BlockType = when (rawType.lowercase()) {
        "matn" -> BlockType.MATN
        "sanad" -> BlockType.SANAD
        "narrator" -> BlockType.NARRATOR
        "note" -> BlockType.NOTE
        "paragraph" -> BlockType.PARAGRAPH
        "heading" -> BlockType.HEADING
        "bullet" -> BlockType.BULLET
        "quote" -> BlockType.QUOTE
        "raw_html" -> BlockType.RAW_HTML
        "bismillah" -> BlockType.BISMILLAH
        "arabic" -> BlockType.ARABIC
        else -> BlockType.UNKNOWN
    }
}
