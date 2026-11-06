package com.command.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.command.domain.voice.BuiltInVoicePacks
import com.command.domain.voice.QuietWindowPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The table of the ten and the voice engine must agree: both decide who a pack sounds like, and :data cannot see the engine. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class BuiltInVoicePacksTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun end() = scope.cancel()

    @Test
    fun `the table gives each pack the kind of voice and the Studio voice the engine gives it`() {
        val engine = VoiceDirectorEngine(context, scope, GeminiSpeechSource { _, _ -> error("not asked") }, QuietWindowPolicy { false }) { 0L }
        try {
            assertEquals(10, BuiltInVoicePacks.all.size)
            for (pack in BuiltInVoicePacks.all) {
                val director = engine.resolveDirectorVoice(pack)
                assertEquals(pack.id, director.isFemale, BuiltInVoicePacks.isFemale(pack.id))
                assertEquals(pack.id, director.geminiVoice, BuiltInVoicePacks.geminiVoiceOf(pack.id))
            }
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun `the trim of a pack is the boost the engine gives it`() {
        for (pack in BuiltInVoicePacks.all) {
            assertEquals(pack.id, VoiceDirectorEngine.boostDbFor(pack.volumeBoostPercent), BuiltInVoicePacks.trimDb(pack), 0f)
        }
        assertEquals(VoiceMastering.MAX_BOOST_DB, BuiltInVoicePacks.MAX_TRIM_DB, 0f)
    }

    @Test
    fun `a pack that is not one of the ten is not said to be anything`() {
        assertNull(BuiltInVoicePacks.isFemale("mine"))
        assertNull(BuiltInVoicePacks.personalityOf("mine"))
        assertNull(BuiltInVoicePacks.geminiVoiceOf("mine"))
    }
}
