package com.khushu.data

import com.khushu.data.content.AyahRef
import com.khushu.data.content.ContentSelection
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentSelectionTest {

    @Test
    fun dailyPickIsDeterministic() {
        val pool = listOf("a", "b", "c", "d", "e")
        val date = LocalDate.of(2025, 6, 21)
        assertEquals(ContentSelection.dailyPick(pool, date), ContentSelection.dailyPick(pool, date))
        assertNull(ContentSelection.dailyPick(emptyList(), date))
    }

    @Test
    fun ayahRefParsing() {
        val refs = AyahRef.parse("68:51-52,2:153,3:173")
        assertEquals(3, refs.size)
        assertEquals(AyahRef(68, 51, 52), refs[0])
        assertEquals(AyahRef(2, 153, null), refs[1])
    }
}
