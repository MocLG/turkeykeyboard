package com.tercuman.keyboard

import com.tercuman.keyboard.ime.AutoCorrector
import com.tercuman.keyboard.ime.KnownWords
import com.tercuman.keyboard.ime.Predictor
import com.tercuman.keyboard.ime.UserWords
import com.tercuman.keyboard.ime.WordDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class AutoCorrectTest {

    private val turkish = Locale.forLanguageTag("tr")

    private fun predictor(lang: String, locale: Locale, user: UserWords = UserWords(null)): Predictor {
        val dict = File("src/main/assets/dict/$lang.txt").reader(Charsets.UTF_8).use { WordDictionary.parse(it) }
        val known = KnownWords.of(File("src/main/assets/dict/${lang}_known.txt").readBytes())
        return Predictor(dict, user, locale, AutoCorrector(dict, known, user, locale))
    }

    private val tr by lazy { predictor("tr", turkish) }
    private val en by lazy { predictor("en", Locale.ENGLISH) }

    @Test
    fun knownWordsLookup() {
        val known = KnownWords.of(File("src/main/assets/dict/tr_known.txt").readBytes())
        assertTrue(known.size > 300_000)
        assertTrue(known.count("merhaba") > 100_000)
        assertTrue(known.count("gelemeyeceğimi") > 0)
        assertEquals(0L, known.count("zzzqqq"))
    }

    @Test
    fun editDistance() {
        assertTrue(AutoCorrector.withinOneEdit("merhaba", "merhabe"))
        assertTrue(AutoCorrector.withinOneEdit("merhaba", "mrehaba"))
        assertTrue(AutoCorrector.withinOneEdit("merhaba", "merhba"))
        assertTrue(AutoCorrector.withinOneEdit("merhaba", "merhabaa"))
        assertFalse(AutoCorrector.withinOneEdit("merhaba", "mrhabe"))
    }

    @Test
    fun correctsTurkishTypos() {
        assertEquals("merhaba", tr.autocorrect("merhabe"))
        assertEquals("Merhaba", tr.autocorrect("Merhabe"))
        assertEquals("teşekkürler", tr.autocorrect("teşekkürker"))
        assertEquals("yarın", tr.autocorrect("yarın") ?: "yarın")
    }

    @Test
    fun restoresTurkishLetters() {
        assertEquals("müsait", tr.autocorrect("musait"))
        assertEquals("görüşürüz", tr.autocorrect("gorusuruz"))
        assertEquals("teşekkürler", tr.autocorrect("tesekkurler"))
    }

    @Test
    fun leavesCorrectWordsAlone() {
        for (w in listOf("yarın", "geldin", "geldim", "sevgilin", "gelemeyeceğimi", "çalışamayacakmışsın", "ve", "bu", "abi", "Ahmet")) {
            assertNull(w, tr.autocorrect(w))
        }
        assertNull(tr.autocorrect("açı"))
        assertNull(tr.autocorrect("ölürdü"))
        assertNull(tr.autocorrect("pastaneye"))
        assertNull(tr.autocorrect("NATO"))
        assertNull(tr.autocorrect("x"))
    }

    @Test
    fun respectsLearnedWords() {
        val user = UserWords(null)
        val p = predictor("tr", turkish, user)
        assertEquals("merhaba", p.autocorrect("merhabe"))
        p.learn(null, "merhabe")
        assertNull(p.autocorrect("merhabe"))
    }

    @Test
    fun correctsEnglish() {
        assertEquals("because", en.autocorrect("becuase"))
        assertEquals("I", en.autocorrect("i"))
        assertNull(en.autocorrect("hello"))
    }
}
