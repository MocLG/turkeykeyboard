package com.tercuman.keyboard

import com.tercuman.keyboard.ime.KeyType
import com.tercuman.keyboard.ime.LetterLayout
import com.tercuman.keyboard.ime.Layouts
import com.tercuman.keyboard.ime.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {

    @Test
    fun everyRowFitsTheGrid() {
        for (layout in LetterLayout.values()) for (page in Page.values()) for (numbers in listOf(true, false)) {
            for (row in Layouts.rows(page, numbers, layout)) {
                val w = Layouts.rowWidth(row)
                assertTrue("$layout $page row ${row.map { it.text }} is $w wide", w <= Layouts.COLUMNS + 0.001f && w >= 10.7f)
            }
        }
    }

    @Test
    fun allTurkishLettersReachableInBothLayouts() {
        for (layout in LetterLayout.values()) {
            val keys = Layouts.rows(Page.LETTERS, false, layout).flatten().filter { it.type == KeyType.CHAR }
            val reachable = keys.flatMap { listOf(it.text) + it.alternates }.toSet()
            for (c in "abcçdefgğhıijklmnoöprsştuüvyz") assertTrue("$layout missing $c", c.toString() in reachable)
        }
    }

    @Test
    fun qwertyHasTurkishLettersOnLongPress() {
        val keys = Layouts.rows(Page.LETTERS, false, LetterLayout.QWERTY).flatten().associateBy { it.text }
        assertEquals("ü", keys.getValue("u").alternates.first())
        assertEquals("ş", keys.getValue("s").alternates.first())
        assertEquals("ı", keys.getValue("i").alternates.first())
        assertEquals("ö", keys.getValue("o").alternates.first())
        assertEquals("ç", keys.getValue("c").alternates.first())
        assertEquals("ğ", keys.getValue("g").alternates.first())
        assertEquals(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            Layouts.rows(Page.LETTERS, false, LetterLayout.QWERTY).first().map { it.text })
    }

    @Test
    fun turkishUpperCase() {
        assertEquals("İ", Layouts.upper("i"))
        assertEquals("I", Layouts.upper("ı"))
        assertEquals("Ş", Layouts.upper("ş"))
    }

    @Test
    fun bottomRowHasSpaceAndEnter() {
        for (page in Page.values()) {
            val bottom = Layouts.rows(page, true).last().map { it.type }
            assertTrue(KeyType.SPACE in bottom && KeyType.ENTER in bottom && KeyType.GLOBE in bottom)
        }
    }
}
