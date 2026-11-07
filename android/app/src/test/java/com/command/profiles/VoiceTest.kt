package com.command.profiles

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.command.R
import com.command.alarm.RingSession
import com.command.alarm.VoicePreview
import com.command.data.AppSettings
import com.command.data.ProfileRepository
import com.command.data.TemplateOverrideRepository
import com.command.data.VoicePackChoice
import com.command.data.db.CommandDb
import com.command.domain.coaching.CoachingContext
import com.command.domain.coaching.CoachingEngine
import com.command.domain.coaching.CoachingPhase
import com.command.domain.coaching.TemplateOverrides
import com.command.domain.dials.Dial
import com.command.domain.dials.Personality
import com.command.domain.voice.BuiltInVoicePacks
import com.command.domain.voice.LoudnessMeter
import com.command.domain.voice.RoutePolicy
import com.command.domain.voice.SpeakResult
import com.command.domain.voice.SpeechLevel
import com.command.domain.voice.SpeechProblem
import com.command.domain.voice.SpeechUsage
import com.command.domain.voice.SpeechVoice
import com.command.testing.android.FakePreviewSpeaker
import com.command.testing.android.FakeSpeaker
import com.command.testing.android.awaitUntil
import com.command.ui.theme.CommandTheme
import com.command.voice.LocalTtsSpeaker
import com.command.voice.PcmPlayer
import com.command.voice.SpeechSynthesizer
import com.command.voice.SpokenAudio
import com.command.voice.Synthesis
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject
import kotlin.math.sin

/** Who speaks for a profile, how loud, and hearing it: on the screen, through the view model and the real database. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w411dp-h8000dp-xhdpi")
class VoiceTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var templates: TemplateOverrideRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var session: RingSession
    @Inject lateinit var preview: VoicePreview
    @Inject lateinit var previewSpeaker: FakePreviewSpeaker
    @Inject lateinit var ringSpeaker: FakeSpeaker

    private val speaker: FakeSpeaker get() = previewSpeaker.speaker
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val steady = "builtin_steady"

    @Before fun setUp() = hilt.inject()

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun show(vm: VoiceViewModel = VoiceViewModel(profiles, templates, preview), id: String = steady): VoiceViewModel {
        compose.setContent { CommandTheme { VoiceRoute(id, onBack = {}, viewModel = vm) } }
        compose.waitUntil(timeoutMillis = 45_000) { exists(VoiceTags.NAME) || exists(VoiceTags.NOT_FOUND) }
        return vm
    }

    private fun waitFor(what: () -> Boolean) = compose.waitUntil(timeoutMillis = 45_000) {
        compose.waitForIdle()
        what()
    }

    private fun row() = runBlocking { db.profiles().get(steady)!! }
    private fun trimOf(pack: String) = runBlocking { db.voicePacks().get(pack)!!.loudnessTrimDb }
    private fun hear(pack: String) = compose.onNodeWithTag(VoiceTags.hear(pack)).performClick()
    private fun slideTo(db: Float) = compose.onNodeWithTag(VoiceTags.TRIM).performSemanticsAction(SemanticsActions.SetProgress) { it(db) }

    private inline fun <reified T : ViewModel> ViewModelStore.holding(noinline make: () -> T): T =
        ViewModelProvider(this, viewModelFactory { initializer { make() } })[T::class.java]

    // --- what it says about itself ---------------------------------------------------------------------------------------------------

    @Test
    fun `it says on screen that the voices are the phone's own and differ by pitch, rate and installed voice`() {
        show()

        val words = context.getString(R.string.voice_honest)
        compose.onNodeWithTag(VoiceTags.HONEST).assertTextEquals(words)
        for (expected in listOf("phone's own", "pitch", "speaking rate", "installed voice")) assertTrue(expected, expected in words)
    }

    @Test
    fun `nothing it says, and no pack's name, claims a licence, a human, a recording or a studio voice`() {
        val banned = Regex("licen[cs]e|licensed|human|recorded|recording|studio|neural|gemini|\\bactor\\b|real voice|cloud", RegexOption.IGNORE_CASE)
        val strings = R.string::class.java.fields.filter { it.name.startsWith("voice_") }.map { it.name to context.getString(it.getInt(null)) }
        assertTrue("the screen's words were found", strings.size > 10)

        for ((name, words) in strings) assertFalse("$name: $words", banned.containsMatchIn(words))
        for (pack in BuiltInVoicePacks.all) assertFalse(pack.name, banned.containsMatchIn(pack.name))
    }

    @Test
    fun `a profile that is gone is said to be gone`() {
        show(id = "nobody")
        assertTrue(exists(VoiceTags.NOT_FOUND))
    }

    // --- choosing --------------------------------------------------------------------------------------------------------------------

    @Test
    fun `all ten packs are there, each with its pitch, its rate and whose words it goes with`() {
        show()

        for (pack in BuiltInVoicePacks.all) {
            compose.onNodeWithTag(VoiceTags.LIST).performScrollToNode(hasTestTag(VoiceTags.choose(pack.id)))
        }
        val stone = compose.onNodeWithTag(VoiceTags.facts("pack_major_stone"))
        stone.assertTextContains("0.86", substring = true)
        // The pack says 1.28, and the engine is asked for what it may be asked for.
        stone.assertTextContains("rate 1.20", substring = true)
        stone.assertTextContains("drill sergeant", substring = true)
        stone.assertTextContains("+4.5 dB", substring = true)
    }

    @Test
    fun `choosing a voice sets the pack and its personality together, of this profile and of nothing else`() {
        val others = runBlocking { db.profiles().all().filter { it.id != steady } }
        val before = row()
        show()

        compose.onNodeWithTag(VoiceTags.choose("pack_major_stone")).performClick()

        waitFor { row().voicePackId == "pack_major_stone" }
        assertEquals(before.copy(personality = Personality.DRILL_SERGEANT, voicePackId = "pack_major_stone"), row())
        assertEquals(others, runBlocking { db.profiles().all().filter { it.id != steady } })
        waitFor { compose.onAllNodes(hasTestTag(VoiceTags.choose("pack_major_stone")) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        before.voicePackId?.let { compose.onNodeWithTag(VoiceTags.choose(it)).assertIsNotSelected() }
    }

    @Test
    fun `leaving the screen does not lose a choice just made`() {
        val store = ViewModelStore()
        val held = store.holding { VoiceViewModel(profiles, templates, preview) }
        held.open(steady)

        held.choose("pack_iron_sentinel")
        store.clear()

        awaitUntil(message = "the choice is written") { row().voicePackId == "pack_iron_sentinel" }
    }

    // --- the trim --------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the trim slider runs from nothing to eight decibels, writes the chosen pack's trim and shows it`() {
        val chosen = row().voicePackId!!
        val others = runBlocking { db.voicePacks().all().filter { it.id != chosen } }
        show()

        slideTo(6f)
        waitFor { trimOf(chosen) == 6f }
        compose.onNodeWithTag(VoiceTags.TRIM_VALUE).assertTextEquals("+6.0 dB")

        slideTo(0f)
        waitFor { trimOf(chosen) == 0f }
        compose.onNodeWithTag(VoiceTags.TRIM_VALUE).assertTextEquals("+0.0 dB")

        slideTo(8f)
        waitFor { trimOf(chosen) == 8f }
        compose.onNodeWithTag(VoiceTags.TRIM_VALUE).assertTextEquals("+8.0 dB")

        assertEquals(others, runBlocking { db.voicePacks().all().filter { it.id != chosen } })
    }

    @Test
    fun `the slider's position becomes the nearest half decibel, inside the range`() {
        assertEquals(6.5f, snappedTrim(6.3f), 0f)
        assertEquals(6.0f, snappedTrim(6.2f), 0f)
        assertEquals(0f, snappedTrim(-1f), 0f)
        assertEquals(8f, snappedTrim(9f), 0f)
    }

    @Test
    fun `only the chosen voice has a slider, and a profile with no voice says what its alarms use`() {
        show()
        assertEquals(1, compose.onAllNodesWithTag(VoiceTags.TRIM).fetchSemanticsNodes().size)
        assertFalse(exists(VoiceTags.NONE_CHOSEN))

        runBlocking { db.profiles().upsert(row().copy(voicePackId = null)) }
        waitFor { exists(VoiceTags.NONE_CHOSEN) }
        assertFalse(exists(VoiceTags.TRIM))
    }

    @Test
    fun `a trim that cannot be saved is said, the slider goes back to what is stored, and the next that can be saved clears it`() {
        val pack = choiceOf("pack_coach_maya")
        val state = mutableStateOf(readyState(pack, failedWrites = 0))
        compose.setContent { CommandTheme { VoiceScreen(state.value, VoiceActions({}, {}, { _, _ -> }, { _, _, _ -> })) } }

        slideTo(7f)
        compose.onNodeWithTag(VoiceTags.TRIM_VALUE).assertTextEquals("+7.0 dB")

        state.value = readyState(pack, failedWrites = 1)
        compose.waitForIdle()

        compose.onNodeWithTag(VoiceTags.TRIM_VALUE).assertTextEquals("+3.5 dB")
        assertTrue(exists(VoiceTags.WRITE_FAILED))
    }

    @Test
    fun `an earlier write coming back does not move the slider off a later value, and the later one coming back settles it`() {
        val pack = choiceOf("pack_coach_maya")
        val state = mutableStateOf(readyState(pack, failedWrites = 0))
        compose.setContent { CommandTheme { VoiceScreen(state.value, VoiceActions({}, {}, { _, _ -> }, { _, _, _ -> })) } }

        slideTo(7f)
        state.value = readyState(pack.copy(trimDb = 6f), failedWrites = 0)
        compose.waitForIdle()
        compose.onNodeWithTag(VoiceTags.TRIM_VALUE).assertTextEquals("+7.0 dB")

        state.value = readyState(pack.copy(trimDb = 7f), failedWrites = 0)
        compose.waitForIdle()
        compose.onNodeWithTag(VoiceTags.TRIM_VALUE).assertTextEquals("+7.0 dB")

        state.value = readyState(pack.copy(trimDb = 2f), failedWrites = 0)
        compose.waitForIdle()
        compose.onNodeWithTag(VoiceTags.TRIM_VALUE).assertTextEquals("+2.0 dB")
    }

    @Test
    fun `a movement that ends on what the row still says is written all the same, so that it lands after the write before it`() {
        val pack = choiceOf("pack_coach_maya").copy(trimDb = 0f)
        val written = ArrayList<Float>()
        val ready = readyState(pack, failedWrites = 0)
        compose.setContent { CommandTheme { VoiceScreen(ready, VoiceActions({}, {}, { _, db -> written += db }, { _, _, _ -> })) } }
        slideTo(7f)
        assertEquals(listOf(7f), written)

        // The row still says 0, as the write of 7 has not come back. The finger goes back to 0, and the screen has time to draw it on the way.
        compose.onNodeWithTag(VoiceTags.TRIM).performTouchInput { down(center) }
        compose.waitForIdle()
        compose.onNodeWithTag(VoiceTags.TRIM).performTouchInput { moveTo(centerLeft) }
        compose.waitForIdle()
        compose.onNodeWithTag(VoiceTags.TRIM).performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(0f, written.last(), 0f)
    }

    @Test
    fun `the view model says a write failed, and says no more once one has worked`() {
        val vm = show()

        vm.setTrim("pack_nobody", 2f)
        waitFor { exists(VoiceTags.WRITE_FAILED) }

        slideTo(5f)
        waitFor { !exists(VoiceTags.WRITE_FAILED) }
    }

    private fun choiceOf(id: String) = BuiltInVoicePacks.all.single { it.id == id }.let {
        VoicePackChoice(it.id, it.name, BuiltInVoicePacks.personalityOf(it.id)!!, 3.5f, SpeechVoice(it.languageTag, it.pitch, it.speechRate))
    }

    private fun readyState(pack: VoicePackChoice, failedWrites: Int) = runBlocking {
        val voice = profiles.observeVoice(steady).first()!!
        VoiceUiState.Ready(voice.dials.handshake, voice.dials.dials, pack.id, listOf(pack), inUse = true, overrides = TemplateOverrides(), failedWrites = failedWrites)
    }

    // --- the preview -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a preview goes the ring's way, on the alarm usage, at the profile's intensity and the pack's trim, in the pack's voice and words`() {
        val pack = BuiltInVoicePacks.all.single { it.id == "pack_major_stone" }
        runBlocking {
            profiles.setDial(steady, Dial.ALERT_INTENSITY, 4)
            profiles.setVoiceTrim(pack.id, 6.5f)
        }
        val before = row()
        show()

        hear(pack.id)
        waitFor { speaker.spoken.isNotEmpty() }

        val (line, usage) = speaker.spoken.single()
        assertEquals(SpeechUsage.ALARM, usage)
        assertEquals(SpeechLevel(4, 6.5f), speaker.levels.single())
        assertEquals(SpeechVoice("en-US", 0.86f, 1.28f, false), speaker.voices.single())
        assertEquals(RoutePolicy.SPEAKER_AND_HEADSET, speaker.routes.single())
        val words = CoachingEngine.compose(
            Personality.DRILL_SERGEANT, CoachingPhase.REVEILLE, runBlocking { profiles.observeDials(steady).first()!!.dials },
            CoachingContext(context.getString(R.string.dials_preview_mission), context.getString(R.string.dials_preview_first_action)), attempt = 0, seed = steady,
        )
        assertEquals(words.text, line.text)

        assertTrue("the ring's speaker is not the preview's", ringSpeaker.spoken.isEmpty())
        assertEquals("hearing a voice chooses nothing", before, row())
    }

    @Test
    fun `a preview keeps to the route the person set for the ring's speech`() {
        runBlocking { settings.setRoutePolicy(RoutePolicy.HEADSET_ONLY) }
        show()

        hear("pack_coach_ray")
        waitFor { speaker.routes.isNotEmpty() }

        assertEquals(RoutePolicy.HEADSET_ONLY, speaker.routes.single())
    }

    @Test
    fun `while it plays its button stops it, and another voice takes its place`() {
        show()
        speaker.result = SpeakResult.Interrupted
        speaker.hold = true

        hear("pack_coach_ray")
        waitFor { speaker.spoken.size == 1 }
        compose.onNodeWithTag(VoiceTags.hear("pack_coach_ray")).assertTextEquals(context.getString(R.string.voice_stop))

        hear("pack_coach_maya")
        waitFor { speaker.spoken.size == 2 }
        assertTrue("the first was stopped", speaker.stops >= 1)
        compose.onNodeWithTag(VoiceTags.hear("pack_coach_ray")).assertTextEquals(context.getString(R.string.voice_hear))
        compose.onNodeWithTag(VoiceTags.hear("pack_coach_maya")).assertTextEquals(context.getString(R.string.voice_stop))

        val stops = speaker.stops
        hear("pack_coach_maya")
        waitFor { speaker.stops > stops }
        compose.waitForIdle()
        compose.onNodeWithTag(VoiceTags.hear("pack_coach_maya")).assertTextEquals(context.getString(R.string.voice_hear))
        assertEquals("stopping is not asking again", 2, speaker.spoken.size)
    }

    @Test
    fun `when it has played, its button is ready again and nothing is said to be wrong`() {
        show()

        hear("pack_coach_ray")
        waitFor { speaker.spoken.size == 1 }
        compose.waitForIdle()

        compose.onNodeWithTag(VoiceTags.hear("pack_coach_ray")).assertTextEquals(context.getString(R.string.voice_hear))
        assertFalse(exists(VoiceTags.NOT_HEARD))
    }

    @Test
    fun `leaving the screen ends the sound and lets the engine go`() {
        val store = ViewModelStore()
        val held = store.holding { VoiceViewModel(profiles, templates, preview) }
        show(held)
        speaker.result = SpeakResult.Interrupted
        speaker.hold = true
        hear("pack_coach_ray")
        waitFor { speaker.spoken.size == 1 }

        store.clear()

        assertTrue(speaker.stops >= 1)
        assertEquals(1, speaker.releases)
        assertEquals("the ring's speaker is left alone", 0, ringSpeaker.stops)
        assertEquals("the ring's speaker is left alone", 0, ringSpeaker.releases)
    }

    @Test
    fun `no preview starts while a ring is sounding, and the screen says why`() {
        val ringing = object : RingSession() {
            override val isBusy: Boolean get() = true
        }
        show(VoiceViewModel(profiles, templates, VoicePreview(previewSpeaker.speaker, ringing, settings)))

        hear("pack_coach_ray")
        waitFor { exists(VoiceTags.NOT_HEARD) }

        assertTrue(speaker.spoken.isEmpty())
        compose.onNodeWithTag(VoiceTags.NOT_HEARD).assertTextEquals(context.getString(R.string.voice_not_heard_ring))
    }

    @Test
    fun `a preview that could not be played says why, in the words for each reason`() {
        val expected = mapOf(
            SpeechProblem.NO_OFFLINE_VOICE to R.string.voice_not_heard_no_voice,
            SpeechProblem.HEADSET_REQUIRED to R.string.voice_not_heard_headset,
            SpeechProblem.NO_ENGINE to R.string.voice_not_heard_engine,
            SpeechProblem.PLAYBACK_FAILED to R.string.voice_not_heard_failed,
        )
        show()
        for ((reason, words) in expected) {
            speaker.result = SpeakResult.Unavailable(reason)
            val asked = speaker.spoken.size
            hear("pack_coach_ray")
            waitFor { speaker.spoken.size > asked && exists(VoiceTags.NOT_HEARD) }
            compose.waitForIdle()
            compose.onNodeWithTag(VoiceTags.NOT_HEARD).assertTextEquals(context.getString(words))
        }
    }

    @Test
    fun `a speaker that throws costs the preview and nothing else`() {
        show()
        speaker.failWith = IllegalStateException("broken engine")

        hear("pack_coach_ray")
        waitFor { exists(VoiceTags.NOT_HEARD) }

        compose.onNodeWithTag(VoiceTags.hear("pack_coach_ray")).assertTextEquals(context.getString(R.string.voice_hear))
    }

    // --- what comes out -------------------------------------------------------------------------------------------------------------

    /** The real speaker, mastering included, with made-up speech in and the audio out where a test can measure it. */
    private inner class Rendered {
        val rate = 24_000
        val plays = ArrayList<Pair<ShortArray, SpeechUsage>>()
        private val synth = object : SpeechSynthesizer {
            override suspend fun synthesize(text: String, voice: SpeechVoice): Synthesis =
                Synthesis.Audio(SpokenAudio(ShortArray(rate * 3) { i -> (if ((i / (rate / 4)) % 2 == 0) sin(i * 2 * Math.PI * 440 / rate) * 2_000 else 0.0).toInt().toShort() }, rate))

            override fun shutdown() = Unit
        }
        private val player = object : PcmPlayer {
            override suspend fun play(pcm: ShortArray, sampleRate: Int, usage: SpeechUsage, route: RoutePolicy): Long {
                plays += pcm to usage
                return 1_000
            }

            override fun stop() = Unit
        }
        val vm = VoiceViewModel(profiles, templates, VoicePreview(LocalTtsSpeaker(synth, player), session, settings))
        fun lufs(pcm: ShortArray) = LoudnessMeter.integratedLufs(FloatArray(pcm.size) { pcm[it] / 32768f }, rate)
    }

    @Test
    fun `the trim set on the slider changes the render the preview plays, at the ring's loudness and on the alarm usage`() {
        runBlocking { profiles.setDial(steady, Dial.ALERT_INTENSITY, 2) }
        val chosen = row().voicePackId!!
        val rendered = Rendered()
        show(rendered.vm)

        fun played(): ShortArray {
            val asked = rendered.plays.size
            hear(chosen)
            waitFor { rendered.plays.size > asked }
            compose.waitForIdle()
            return rendered.plays.last().first
        }

        fun shows(words: String) = compose.onAllNodes(hasTestTag(VoiceTags.facts(chosen)) and hasText(words, substring = true)).fetchSemanticsNodes().isNotEmpty()

        slideTo(0f)
        waitFor { shows("+0.0 dB") }
        val none = played()

        slideTo(2.5f)
        waitFor { shows("+2.5 dB") }
        val some = played()

        slideTo(8f)
        waitFor { shows("+8.0 dB") }
        val most = played()

        assertEquals("the ring's loudness at intensity 2", -16.0, rendered.lufs(none), 1.0)
        assertEquals("a small trim is all heard", 2.5, rendered.lufs(some) - rendered.lufs(none), 0.5)
        // On a signal this dense the limiters take part of the largest trims (amendment 28): less, never nothing, and the screen says so.
        assertTrue("more trim is louder", rendered.lufs(most) > rendered.lufs(some) + 0.25)
        assertTrue(rendered.plays.all { it.second == SpeechUsage.ALARM })
    }
}
