package com.tercuman.keyboard

import com.tercuman.keyboard.ime.FieldTextReader
import com.tercuman.keyboard.ime.Target
import org.junit.Assert.assertEquals
import org.junit.Test

class FieldTextTest {
    @Test
    fun noSelectionTakesWholeField() {
        val f = FieldTextReader.pick("Merhaba nasılsın", 7, 7)
        assertEquals("Merhaba nasılsın", f.text)
        assertEquals(Target.Range(0, 16), f.target)
    }

    @Test
    fun selectionIsUsedAndOffsetsAreAbsolute() {
        val f = FieldTextReader.pick("Merhaba nasılsın", 8, 16, base = 100)
        assertEquals("nasılsın", f.text)
        assertEquals(Target.Range(108, 116), f.target)
    }

    @Test
    fun reversedSelectionIsNormalised() {
        assertEquals("Merhaba", FieldTextReader.pick("Merhaba nasılsın", 7, 0).text)
    }

    @Test
    fun blankSelectionFallsBackToWholeField() {
        assertEquals("a b", FieldTextReader.pick("a b", 1, 2).text)
    }

    @Test
    fun outOfRangeSelectionIsClamped() {
        assertEquals("abc", FieldTextReader.pick("abc", -1, 99).text)
    }
}
