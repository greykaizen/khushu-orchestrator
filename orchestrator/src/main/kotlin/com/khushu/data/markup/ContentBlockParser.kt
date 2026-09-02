package com.khushu.data.markup

import com.khushu.data.model.BlockType
import com.khushu.data.model.ContentBlock
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
        json.parseToJsonElement(blocksJson).jsonArray.map { el ->
            val obj = el.jsonObject
            val rawType = obj["type"]?.jsonPrimitive?.content ?: "unknown"
            val text = obj["text"]?.jsonPrimitive?.content ?: ""
            ContentBlock(
                type = classify(rawType),
                rawType = rawType,
                spans = ContentMarkup.parse(text),
                rawText = text,
            )
        }

    private fun classify(rawType: String): BlockType = when (rawType.lowercase()) {
        "matn" -> BlockType.MATN
        "sanad" -> BlockType.SANAD
        "narrator" -> BlockType.NARRATOR
        "note" -> BlockType.NOTE
        else -> BlockType.UNKNOWN
    }
}
