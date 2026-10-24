package com.tercuman.keyboard

import com.tercuman.keyboard.engine.Direction
import com.tercuman.keyboard.engine.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {
    @Test
    fun turkishToEnglishMatchesModelTemplate() {
        val expected = "<start_of_turn>user\n" +
            "You are a professional Turkish (tr) to English (en) translator. Your goal is to accurately convey " +
            "the meaning and nuances of the original Turkish text while adhering to English grammar, vocabulary, " +
            "and cultural sensitivities.\n" +
            "Produce only the English translation, without any additional explanations or commentary. " +
            "Please translate the following Turkish text into English:\n\n\n" +
            "Merhaba dünya<end_of_turn>\n<start_of_turn>model\n"
        assertEquals(expected, Prompts.translateGemma("  Merhaba dünya \n", Direction.TR_TO_EN))
    }

    @Test
    fun englishToTurkishSwapsLanguages() {
        val p = Prompts.translateGemma("Hello", Direction.EN_TO_TR)
        assertTrue(p.contains("professional English (en) to Turkish (tr) translator"))
        assertTrue(p.contains("translate the following English text into Turkish:\n\n\nHello<end_of_turn>"))
    }

    @Test
    fun outputBudgetScalesWithInput() {
        assertEquals(48, Prompts.maxOutputTokens(""))
        assertEquals(98, Prompts.maxOutputTokens("x".repeat(100)))
        assertEquals(1024, Prompts.maxOutputTokens("x".repeat(Prompts.MAX_INPUT_CHARS * 2)))
    }

    @Test
    fun cleanOutputRemovesTurnMarkers() {
        assertEquals("See you tomorrow.", Prompts.cleanOutput(" See you tomorrow.<end_of_turn>\n"))
    }
}
