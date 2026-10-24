package com.tercuman.keyboard

import com.tercuman.keyboard.engine.Direction
import com.tercuman.keyboard.engine.LlamaBridge
import com.tercuman.keyboard.engine.Prompts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class HostEngineTest {
    private val lib = System.getProperty("tercuman.hostLib")
    private val model = System.getProperty("tercuman.model")

    private fun withEngine(block: (Long) -> Unit) {
        assumeTrue("host library/model not given", lib != null && model != null && File(model).isFile)
        LlamaBridge.loadForHostTest(lib!!)
        val h = LlamaBridge.nativeLoad(model!!, Runtime.getRuntime().availableProcessors(), 2048)
        try {
            block(h)
        } finally {
            LlamaBridge.nativeFree(h)
        }
    }

    private fun translate(h: Long, text: String, dir: Direction, listener: LlamaBridge.PartialListener? = null): String {
        val prompt = Prompts.translateGemma(text, dir).toByteArray(Charsets.UTF_8)
        val out = String(LlamaBridge.nativeGenerate(h, prompt, Prompts.maxOutputTokens(text), listener), Charsets.UTF_8)
        return Prompts.cleanOutput(out)
    }

    @Test
    fun translatesBothWaysStreamsAndCancels() = withEngine { h ->
        val partials = mutableListOf<String>()
        val en = translate(h, "Yarın sabah doktora gideceğim, akşam seni ararım.", Direction.TR_TO_EN) {
            partials.add(String(it, Charsets.UTF_8)); true
        }
        println("TR->EN: $en")
        val lower = en.lowercase()
        assertTrue(en, lower.contains("doctor") && lower.contains("tomorrow"))
        assertTrue("streaming should deliver partial results", partials.size > 3)
        assertFalse("must not contain chat markup", en.contains("<"))

        val tr = translate(h, "Thank you, I will call you this evening.", Direction.EN_TO_TR)
        println("EN->TR: $tr")
        assertTrue(tr, tr.lowercase().contains("teşekkür"))

        var calls = 0
        val cancelled = try {
            translate(h, "Bu uzun bir cümle ve çevirisi de uzun olacak, çünkü birçok kelime içeriyor.", Direction.TR_TO_EN) {
                calls++; calls < 3
            }
            false
        } catch (e: CancellationException) {
            true
        }
        assertTrue("generation should have been cancelled", cancelled)
        assertTrue(calls == 3)

        assertTrue(translate(h, "Merhaba", Direction.TR_TO_EN).lowercase().contains("hello"))
    }
}
