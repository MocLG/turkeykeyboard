package com.tercuman.keyboard

import com.tercuman.keyboard.ime.Predictor
import com.tercuman.keyboard.ime.TextFold
import com.tercuman.keyboard.ime.UserWords
import com.tercuman.keyboard.ime.WordContext
import com.tercuman.keyboard.ime.WordDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

class PredictionTest {

    private val turkish = Locale.forLanguageTag("tr")

    private fun dictionary(lang: String): WordDictionary =
        File("src/main/assets/dict/$lang.txt").reader(Charsets.UTF_8).use { WordDictionary.parse(it) }

    private val tr by lazy { dictionary("tr") }

    @Test
    fun foldsTurkishLetters() {
        assertEquals("gorusuruz", TextFold.fold("Görüşürüz"))
        assertEquals("isik", TextFold.fold("IŞIK"))
        assertEquals("cicek", TextFold.fold("çiçek"))
    }

    @Test
    fun readsContextBeforeCursor() {
        assertEquals(WordContext("gör", "yarın"), Predictor.context("Selam, yarın gör"))
        assertEquals(WordContext("", "yarın"), Predictor.context("Selam, yarın "))
        assertEquals(WordContext("Mer", null), Predictor.context("Mer"))
        assertEquals(WordContext("", null), Predictor.context("Tamam. "))
    }

    @Test
    fun dictionaryIsLoaded() {
        assertTrue(tr.size > 40_000)
    }

    @Test
    fun completesWithAndWithoutTurkishLetters() {
        val p = Predictor(tr, UserWords(null), turkish)
        assertTrue(p.suggest(WordContext("görüş", null)).contains("görüşürüz"))
        val folded = p.suggest(WordContext("gorusur", null))
        assertTrue(folded.toString(), folded.contains("görüşürüz"))
        assertTrue(p.suggest(WordContext("musa", null)).contains("müsait"))
    }

    @Test
    fun keepsTheCaseTheUserTyped() {
        val p = Predictor(tr, UserWords(null), turkish)
        val s = p.suggest(WordContext("Mer", null))
        assertTrue(s.toString(), s.contains("Merhaba"))
        assertTrue(p.suggest(WordContext("İST", null)).all { it == it.uppercase(turkish) })
    }

    @Test
    fun learnsWordsAndNextWords() {
        val file = Files.createTempFile("user", ".tsv").toFile()
        file.delete()
        val user = UserWords(file)
        val p = Predictor(tr, user, turkish)
        assertEquals(emptyList<String>(), p.suggest(WordContext("", "yarın")))
        p.learn("yarın", "Görüşürüz")
        p.learn("yarın", "görüşürüz")
        p.learn(null, "zxqmerhabam")
        assertEquals("görüşürüz", p.suggest(WordContext("", "Yarın")).first())
        assertEquals("zxqmerhabam", p.suggest(WordContext("zxq", null)).first())
        p.save()

        val reloaded = Predictor(tr, UserWords(file), turkish)
        assertEquals("görüşürüz", reloaded.suggest(WordContext("", "yarın")).first())
        reloaded.forget("zxqmerhabam")
        assertTrue(reloaded.suggest(WordContext("zxq", null)).isEmpty())
        file.delete()
    }

    @Test
    fun englishListWorks() {
        val p = Predictor(dictionary("en"), UserWords(null), Locale.ENGLISH)
        assertTrue(p.suggest(WordContext("tomor", null)).contains("tomorrow"))
    }
}
