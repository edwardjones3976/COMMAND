package com.command.profiles

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import com.command.data.ProfileDials
import com.command.data.ProfileRepository
import com.command.data.TemplateOverrideRepository
import com.command.data.db.CommandDb
import com.command.domain.coaching.CoachingPhase
import com.command.domain.dials.Dial
import com.command.domain.dials.DialEffects
import com.command.domain.dials.Personality
import com.command.testing.android.awaitUntil
import com.command.ui.theme.CommandTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The dials of one profile, on the screen, through the view model and the real database. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class DialsTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var templates: TemplateOverrideRepository
    @Inject lateinit var db: CommandDb

    private lateinit var vm: DialsViewModel
    private val openedHandshake = ArrayList<String>()

    @Before
    fun setUp() {
        hilt.inject()
        vm = DialsViewModel(profiles, templates)
    }

    private fun show(id: String = "builtin_steady") {
        compose.setContent { CommandTheme { DialsRoute(id, onBack = {}, onOpenHandshake = { openedHandshake += it }, viewModel = vm) } }
        // A condition has to go through the semantics tree or waitForIdle: waitUntil does not run the main looper's queue by itself under Robolectric.
        compose.waitUntil(timeoutMillis = 45_000) { exists(DialsTags.NAME) || exists(DialsTags.NOT_FOUND) }
    }

    private fun dials(id: String = "builtin_steady"): ProfileDials = runBlocking { profiles.observeDials(id).first()!! }

    private fun tap(dial: Dial, value: Int) = compose.onNodeWithTag(DialsTags.value(dial, value)).performClick()

    private fun previewText(): String {
        compose.waitForIdle()
        return compose.onNodeWithTag(DialsTags.PREVIEW).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
    }

    private fun awaitDial(dial: Dial, value: Int) = compose.waitUntil(timeoutMillis = 45_000) {
        compose.waitForIdle()
        dials().dials[dial] == value && (vm.state.value as? DialsUiState.Ready)?.dials?.get(dial) == value
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** A view model as the destination holds it: clearing the store is what leaving the screen does to it. */
    private inline fun <reified T : ViewModel> ViewModelStore.holding(noinline make: () -> T): T =
        ViewModelProvider(this, viewModelFactory { initializer { make() } })[T::class.java]

    @Test
    fun `leaving the screen does not lose a tap that is still waiting for the one before it`() {
        val store = ViewModelStore()
        val held = store.holding { DialsViewModel(profiles, templates) }
        val gate = CompletableDeferred<Unit>()
        held.beforeWrite = { gate.await() }
        held.open("builtin_steady")

        held.set(Dial.ALERT_INTENSITY, 4)
        held.set(Dial.ALERT_INTENSITY, 5)
        store.clear()
        gate.complete(Unit)

        awaitUntil(message = "the last tap to be written") { dials().dials[Dial.ALERT_INTENSITY] == 5 }
    }

    @Test
    fun `a dial that is tapped is written at once, and the screen shows it from the row`() {
        show()

        tap(Dial.ALERT_INTENSITY, 5)
        awaitDial(Dial.ALERT_INTENSITY, 5)

        compose.onNodeWithTag(DialsTags.value(Dial.ALERT_INTENSITY, 5)).assertIsSelected()
        compose.onNodeWithTag(DialsTags.effect(Dial.ALERT_INTENSITY)).assertTextEquals("Rings 3 times, for 3 minutes each, with 30 seconds between. The voice is at its loudest.")
    }

    @Test
    fun `the preview line follows firmness, and it is the line the ring would make`() {
        show()
        val gentle = previewText()
        assertTrue("firmness 3 closes with Begin: $gentle", gentle.endsWith("Begin.") || gentle.endsWith("Get going."))

        tap(Dial.FIRMNESS, 5)
        awaitDial(Dial.FIRMNESS, 5)

        val firm = previewText()
        assertNotEquals(gentle, firm)
        val ready = vm.state.value as DialsUiState.Ready
        assertEquals(previewLine(ready, "Morning run", "Put on your shoes"), firm)
        assertTrue("firmness 5 closes with an order: $firm", firm.endsWith("Do it now.") || firm.endsWith("Do it now!"))
    }

    @Test
    fun `the preview line follows obnoxiousness, with more said at each step up`() {
        show()
        val quiet = previewText()

        tap(Dial.OBNOXIOUSNESS, 3)
        awaitDial(Dial.OBNOXIOUSNESS, 3)
        val louder = previewText()
        tap(Dial.OBNOXIOUSNESS, 5)
        awaitDial(Dial.OBNOXIOUSNESS, 5)
        val loudest = previewText()

        assertTrue("$quiet / $louder", louder.length > quiet.length)
        assertTrue("$louder / $loudest", loudest.length > louder.length)
    }

    @Test
    fun `a dial that does not change the words leaves the preview line as it was`() {
        show()
        val before = previewText()

        tap(Dial.HANDSHAKE_DIFFICULTY, 5)
        awaitDial(Dial.HANDSHAKE_DIFFICULTY, 5)

        assertEquals(before, previewText())
    }

    @Test
    fun `the preview line says the person's own words where a ring would`() {
        runBlocking {
            val personality = dials().handshake.personality
            for (variant in 0 until 4) templates.save(personality, CoachingPhase.REVEILLE, variant, "Lace up and step outside.")
        }
        show()

        assertTrue(previewText(), previewText().contains("Lace up and step outside."))
    }

    @Test
    fun `the minimalist's cap is said on the screen, and only for the minimalist`() {
        show("builtin_steady")
        assertFalse("Steady is not a minimalist", exists(DialsTags.PREVIEW_CAP))
        runBlocking { db.profiles().upsert(db.profiles().get("builtin_steady")!!.copy(personality = Personality.MINIMALIST)) }

        compose.waitUntil(timeoutMillis = 45_000) { exists(DialsTags.PREVIEW_CAP) }
    }

    @Test
    fun `the difficulty dial says it changes nothing while the profile has a handshake of its own, and the way there opens it`() {
        show()
        assertFalse("no chain of its own yet", exists(DialsTags.OWN_CHAIN))
        runBlocking { profiles.saveChain("builtin_steady", DialEffects.defaultChain(4)) }

        compose.waitUntil(timeoutMillis = 45_000) { exists(DialsTags.OWN_CHAIN) }
        compose.onNodeWithTag(DialsTags.OPEN_HANDSHAKE).performClick()

        assertEquals(listOf("builtin_steady"), openedHandshake)
    }

    @Test
    fun `a profile that goes while its dials are open says so`() {
        runBlocking { db.profiles().upsert(db.profiles().get("builtin_steady")!!.copy(id = "mine", isBuiltin = false)) }
        show("mine")
        compose.waitUntil(timeoutMillis = 45_000) { exists(DialsTags.NAME) }

        runBlocking(Dispatchers.IO) { db.runInTransaction { db.openHelper.writableDatabase.execSQL("DELETE FROM execution_profiles WHERE id = 'mine'") } }

        compose.waitUntil(timeoutMillis = 45_000) { exists(DialsTags.NOT_FOUND) }
    }

    @Test
    fun `two taps on one dial leave the one tapped last, however slow the first write`() {
        show()
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        vm.beforeWrite = { if (writes++ == 0) gate.await() }

        vm.set(Dial.FIRMNESS, 1)
        vm.set(Dial.FIRMNESS, 5)
        gate.complete(Unit)

        awaitDial(Dial.FIRMNESS, 5)
        awaitUntil(message = "both started") { writes == 2 }
        Thread.sleep(500) // a first write that had waited for nothing would land by now, over the second
        compose.waitForIdle()
        assertEquals(5, dials().dials.firmness)
    }

    @Test
    fun `the dials that are not at the top can be reached`() {
        show()
        compose.onNodeWithTag(DialsTags.LIST).performScrollToNode(hasTestTag(DialsTags.value(Dial.RECOVERY_STRUCTURE, 5)))
        compose.onNodeWithTag(DialsTags.value(Dial.RECOVERY_STRUCTURE, 5)).performClick()
        awaitDial(Dial.RECOVERY_STRUCTURE, 5)
    }
}

/** The plain words of every setting of every dial, read as a person reads them. A change of what a dial does has to change these. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DialWordsTest {

    @get:Rule val compose = createComposeRule()

    private fun words(dial: Dial): List<String> {
        val out = ArrayList<String>()
        compose.setContent { Words(dial, out) }
        compose.waitForIdle()
        return out.take(5)
    }

    @Composable
    private fun Words(dial: Dial, out: MutableList<String>) {
        CommandTheme { for (value in 1..5) out += dialEffectWords(dial, value) }
    }

    @Test
    fun `firmness`() = assertEquals(
        listOf(
            "Lines end as a suggestion: when you are ready.",
            "Lines end as an invitation to begin.",
            "Lines end by telling you to begin.",
            "Lines end by telling you to begin now.",
            "Lines end with an order to do it now.",
        ),
        words(Dial.FIRMNESS),
    )

    @Test
    fun `obnoxiousness`() = assertEquals(
        listOf(
            "No extra words. Never an insult, at any setting.",
            "Up to 1 shout in a line. Never an insult.",
            "Up to 2 shouts in a line. Never an insult. The last one is said 1 more time.",
            "Up to 3 shouts in a line. Never an insult. The last one is said 1 more time.",
            "Up to 4 shouts in a line. Never an insult. The last one is said 2 more times.",
        ),
        words(Dial.OBNOXIOUSNESS),
    )

    @Test
    fun `alert intensity`() = assertEquals(
        listOf(
            "Rings once, for 1 minute. The voice is loud.",
            "Rings once, for 2 minutes. The voice is loud.",
            "Rings 2 times, for 2 minutes each, with 1 minute between. The voice is loud.",
            "Rings 3 times, for 2 minutes each, with 1 minute between. The voice is louder.",
            "Rings 3 times, for 3 minutes each, with 30 seconds between. The voice is at its loudest.",
        ),
        words(Dial.ALERT_INTENSITY),
    )

    @Test
    fun `follow-up persistence`() = assertEquals(
        listOf(
            "Asks how it is going once, 10 minutes later.",
            "Asks how it is going up to 2 times, 10 minutes apart.",
            "Asks how it is going up to 2 times, 7 minutes apart.",
            "Asks how it is going up to 3 times, 5 minutes apart.",
            "Asks how it is going up to 4 times, 5 minutes apart.",
        ),
        words(Dial.FOLLOW_UP_PERSISTENCE),
    )

    @Test
    fun `handshake difficulty names the standard chain and its tries`() {
        val said = words(Dial.HANDSHAKE_DIFFICULTY)
        assertTrue(said[0], said[0].contains("2 seconds") && said[0].endsWith("5 tries for each way."))
        assertTrue(said[1], said[1].contains("3 seconds") && said[1].endsWith("4 tries for each way."))
        assertTrue(said[4], said[4].endsWith("2 tries for each way."))
        assertEquals("every level says something of its own", 5, said.toSet().size)
    }

    @Test
    fun `schedule rigidity`() {
        val none = "A fixed mission can be delayed or skipped with one tap. A flexible one always can."
        assertEquals(
            listOf(
                none,
                none,
                "A fixed mission asks you to confirm before a delay or a skip. A flexible one never asks.",
                "A fixed mission asks for a reason before a delay or a skip. A flexible one never asks.",
                "A fixed mission offers a way to start first, before a delay or a skip. A flexible one never asks.",
            ),
            words(Dial.SCHEDULE_RIGIDITY),
        )
    }

    @Test
    fun `recovery structure`() = assertEquals(
        listOf(
            "When a start does not happen, offers once: start again.",
            "When a start does not happen, offers once: start again, try later (in 30, 60 minutes).",
            "When a start does not happen, offers up to 2 times: start again, a smaller step, if the mission has one, try later (in 15, 30, 60 minutes).",
            "When a start does not happen, offers up to 2 times: start again, a smaller step, if the mission has one, try later (in 15, 30, 60 minutes, or tomorrow).",
            "When a start does not happen, offers up to 2 times: a smaller step, if the mission has one, start again, try later (in 15, 30, 60 minutes, or tomorrow).",
        ),
        words(Dial.RECOVERY_STRUCTURE),
    )
}
