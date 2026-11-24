package com.khushu.data.markup

import com.khushu.data.model.BlockType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuaBlocksParserTest {

    @Test
    fun parsesTypedDuaBlocks() {
        val json = """
            {"schema":1,"fields":{
              "arabic":[
                {"type":"bismillah","text":"بِسْمِ اللّٰهِ"},
                {"type":"arabic","text":"قُلْ هُوَ اللّٰهُ أَحَدٌ"}
              ],
              "translation":[{"type":"paragraph","text":"Say <hon kind=\"saw\">ﷺ</hon>"}],
              "transliteration":[],
              "virtue":[{"type":"paragraph","text":"A narration."}],
              "explanation":[
                {"type":"heading","level":1,"text":"Brief Commentary"},
                {"type":"heading","level":2,"text":"The Greatest Ayah"},
                {"type":"bullet","text":"One"},
                {"type":"quote","text":"A long quote."}
              ],
              "reference":[{"type":"paragraph","text":"Bukhārī 1"}]
            }}
        """.trimIndent()

        val blocks = ContentBlockParser.parseDuaBlocks(json)
        assertEquals(1, blocks.translation.size)
        assertEquals(BlockType.PARAGRAPH, blocks.translation[0].type)
        assertTrue(blocks.translation[0].spans.filterIsInstance<ContentSpan.Honorific>().single().kind == HonorificKind.SAW)

        assertEquals(listOf(BlockType.HEADING, BlockType.HEADING, BlockType.BULLET, BlockType.QUOTE),
            blocks.explanation.map { it.type })
        assertEquals(1, blocks.explanation[0].level)
        assertEquals(2, blocks.explanation[1].level)
        assertNull(blocks.explanation[2].level)
        assertEquals("The Greatest Ayah", blocks.explanation[1].rawText)

        assertEquals("Bukhārī 1", blocks.reference.single().rawText)
        assertEquals(listOf(BlockType.BISMILLAH, BlockType.ARABIC), blocks.arabic.map { it.type })
        assertTrue(blocks.arabic[0].spans.filterIsInstance<ContentSpan.Text>().isNotEmpty())
        assertTrue(blocks.transliteration.isEmpty())
    }

    @Test
    fun missingOrBadBlocksFallBackEmpty() {
        assertTrue(ContentBlockParser.parseDuaBlocks("").isEmpty)
        assertTrue(ContentBlockParser.parseDuaBlocks("{}").isEmpty)
        assertTrue(ContentBlockParser.parseDuaBlocks("not json").isEmpty)
    }
}
