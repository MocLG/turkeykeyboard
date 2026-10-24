package com.tercuman.keyboard

import com.tercuman.keyboard.ime.KeyType
import com.tercuman.keyboard.ime.Layouts
import com.tercuman.keyboard.ime.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {
    @Test
    fun everyRowFitsTheGrid() {
        for (page in Page.values()) for (numbers in listOf(true, false)) {
            for (row in Layouts.rows(page, numbers)) {
                val w = Layouts.rowWidth(row)
                assertTrue("$page row ${row.map { it.text }} is $w wide", w <= Layouts.COLUMNS + 0.001f && w >= 11.4f)
            }
        }
    }

    @Test
    fun allTurkishLettersPresent() {
        val letters = Layouts.rows(Page.LETTERS, false).flatten().filter { it.type == KeyType.CHAR }.map { it.text }.toSet()
        for (c in "abcçdefgğhıijklmnoöprsştuüvyz") assertTrue("missing $c", c.toString() in letters)
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
