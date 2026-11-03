package com.command.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** The person's words are only ever given to a voice that works on the phone. */
class OfflineVoicesTest {

    private class FakeEngine(var voices: List<EngineVoice>, var current: EngineVoice?) : TtsEngine {
        var selected: EngineVoice? = null
        override fun voices() = voices
        override fun currentVoice() = selected ?: current
        override fun setLanguage(locale: Locale) = Unit
        override fun selectVoice(voice: EngineVoice): Boolean { selected = voice; return true }
        override fun synthesizeToFile(text: String, file: File, utteranceId: String, finished: (Boolean) -> Unit) = true
        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    private fun voice(name: String, locale: Locale = Locale.US, quality: Int = 300, network: Boolean = false, installed: Boolean = true) =
        EngineVoice(name, locale, quality, network, installed)

    @Test
    fun `a default voice that works on the phone is kept as it is`() {
        val local = voice("local")
        val engine = FakeEngine(listOf(local, voice("better", quality = 500)), current = local)
        assertTrue(OfflineVoices.pin(engine, Locale.US))
        assertNull("nothing was switched", engine.selected)
    }

    @Test
    fun `a default voice that asks a server is replaced by the best installed offline voice of the language`() {
        val network = voice("network", network = true, quality = 500)
        val plain = voice("plain", quality = 200)
        val best = voice("best", quality = 400)
        val french = voice("french", locale = Locale.FRANCE, quality = 500)
        val engine = FakeEngine(listOf(network, plain, french, best), current = network)
        assertTrue(OfflineVoices.pin(engine, Locale.US))
        assertEquals(best, engine.selected)
    }

    @Test
    fun `a voice of the same country is preferred to a better one from another`() {
        val british = voice("british", locale = Locale.UK, quality = 500)
        val american = voice("american", locale = Locale.US, quality = 300)
        val engine = FakeEngine(listOf(british, american), current = voice("network", network = true))
        assertTrue(OfflineVoices.pin(engine, Locale.US))
        assertEquals(american, engine.selected)
    }

    @Test
    fun `a voice whose data is not installed is not used, because using it may start a download`() {
        val missing = voice("missing", installed = false)
        val engine = FakeEngine(listOf(missing), current = missing)
        assertFalse(OfflineVoices.pin(engine, Locale.US))
        assertNull(engine.selected)
    }

    @Test
    fun `with no voice that works on the phone nothing is said`() {
        val network = voice("network", network = true)
        assertFalse(OfflineVoices.pin(FakeEngine(listOf(network), current = network), Locale.US))
    }

    @Test
    fun `an offline voice of another language is no use`() {
        val network = voice("network", network = true)
        val french = voice("french", locale = Locale.FRANCE)
        assertFalse(OfflineVoices.pin(FakeEngine(listOf(network, french), current = network), Locale.US))
    }

    @Test
    fun `an engine that does not say which voices it has cannot be shown to work offline, so nothing is said`() {
        assertFalse(OfflineVoices.pin(FakeEngine(emptyList(), current = null), Locale.US))
    }
}
