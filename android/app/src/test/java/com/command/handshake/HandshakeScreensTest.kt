package com.command.handshake

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.command.data.ProfileHandshake
import com.command.domain.dials.ChainIssue
import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainProblem
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.Personality
import com.command.domain.dials.PresetOrigin
import com.command.domain.dials.StepSpec
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The handshake screens with no view model: what is on each, and what each control reports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class HandshakeScreensTest {

    @get:Rule val compose = createComposeRule()

    private class Calls {
        var back = 0
        val edited = ArrayList<String>()
        val ops = ArrayList<ChainOp>()
        var added = 0
        val removed = ArrayList<Int>()
        val moved = ArrayList<Pair<Int, Boolean>>()
        val modes = ArrayList<Pair<Int, HandshakeMode>>()
        val tries = ArrayList<Pair<Int, Int>>()
        val times = ArrayList<Pair<Int, Int>>()
        val holds = ArrayList<Pair<Int, Int>>()
        var saved = 0
        var standard = 0

        fun actions(onOpenCodes: (HandshakeMode) -> Unit = {}) = HandshakeEditorActions(
            onBack = { back++ },
            onSetOp = { ops += it },
            onAddStep = { added++ },
            onRemoveStep = { removed += it },
            onMoveStep = { key, up -> moved += key to up },
            onSetMode = { key, mode -> modes += key to mode },
            onChangeTries = { key, by -> tries += key to by },
            onChangeTimeout = { key, by -> times += key to by },
            onChangeHold = { key, by -> holds += key to by },
            onSave = { saved++ },
            onUseStandard = { standard++ },
            onOpenCodes = onOpenCodes,
        )
    }

    private fun step(key: Int, mode: HandshakeMode, tries: Int = 3, timeout: Int = 90, hold: Int = 0) = StepDraft(key, mode, tries, timeout, hold)

    private fun editing(
        steps: List<StepDraft>,
        difficulty: Int = 2,
        op: ChainOp = ChainOp.OR,
        issues: List<ChainIssue> = emptyList(),
        changed: Boolean = false,
        usingStandard: Boolean = true,
        storedUnusable: Boolean = false,
        saved: Boolean = false,
        inUse: Boolean = true,
        restored: Boolean = false,
        name: String = "Steady",
    ) = HandshakeEditorUiState.Editing(name, difficulty, op, steps, issues, changed, usingStandard, storedUnusable, saved, restored, inUse)

    private fun showEditor(state: HandshakeEditorUiState, calls: Calls = Calls()): Calls {
        compose.setContent { CommandTheme(darkTheme = true) { HandshakeEditorScreen(state, calls.actions()) } }
        return calls
    }

    /** Shows [state] with its Back dispatcher in hand, and returns what presses Back. */
    private fun showEditorWithBack(state: HandshakeEditorUiState, calls: Calls = Calls()): () -> Unit {
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CommandTheme(darkTheme = true) { HandshakeEditorScreen(state, calls.actions()) }
        }
        return { compose.runOnIdle { dispatcher!!.onBackPressed() } }
    }

    private fun <T> SemanticsNodeInteraction.property(key: SemanticsPropertyKey<T>): T? = fetchSemanticsNode().config.let { if (it.contains(key)) it[key] else null }
    private fun SemanticsNodeInteraction.liveRegion() = property(SemanticsProperties.LiveRegion)
    private fun SemanticsNodeInteraction.stateDescription() = property(SemanticsProperties.StateDescription)
    private fun SemanticsNodeInteraction.role() = property(SemanticsProperties.Role)
    private fun SemanticsNodeInteraction.isHeading() = fetchSemanticsNode().config.contains(SemanticsProperties.Heading)

    private fun profile(id: String, name: String, difficulty: Int, own: ChainSpec? = null, unusable: Boolean = false) =
        ProfileHandshake(id, name, PresetOrigin.CUSTOM, Personality.MINIMALIST, difficulty, own, unusable)

    // --- the list of profiles ----------------------------------------------------------------------------------------

    @Test
    fun `each profile says what it asks for, and whether that is the standard handshake or its own`() {
        val own = ChainSpec(ChainOp.AND, listOf(StepSpec(HandshakeMode.HOLD, 3, 90, 5), StepSpec(HandshakeMode.TYPING, 3, 90)))
        compose.setContent {
            CommandTheme(darkTheme = true) {
                HandshakeProfilesScreen(
                    HandshakeProfilesUiState.Ready(
                        listOf(
                            profile("a", "Steady", 2),
                            profile("b", "Mine", 3, own = own),
                            profile("c", "Broken", 3, unusable = true),
                        ),
                        inUse = emptySet(),
                    ),
                    onBack = {},
                    onEdit = {},
                )
            }
        }

        compose.onNodeWithText("Hold the button for 3 seconds").assertIsDisplayed()
        compose.onNodeWithText("The standard handshake for difficulty 2").assertIsDisplayed()
        compose.onNodeWithText("All of these, in any order: Hold the button for 5 seconds, Type a phrase").assertIsDisplayed()
        compose.onNodeWithText("A handshake of your own").assertIsDisplayed()
        compose.onNodeWithText("What was saved for this profile could not be used, so the standard handshake is in force.").assertIsDisplayed()
    }

    @Test
    fun `each profile says whether alarms are fired with it, and the ones none uses say that nothing asks for them yet`() {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                HandshakeProfilesScreen(
                    HandshakeProfilesUiState.Ready(listOf(profile("a", "Steady", 2), profile("b", "Command", 3), profile("c", "Reveille", 4)), inUse = setOf("a", "c")),
                    onBack = {},
                    onEdit = {},
                )
            }
        }

        compose.onNodeWithTag(HandshakeTags.inUse("a")).assertTextEquals("Your alarms use this profile.")
        compose.onNodeWithTag(HandshakeTags.inUse("b")).assertTextEquals("No alarm uses this profile yet.")
        compose.onNodeWithTag(HandshakeTags.inUse("c")).assertTextEquals("Your alarms use this profile.")
    }

    @Test
    fun `a chain that names a way that is only planned says what the ring asks for in its place`() {
        val planned = ChainSpec(ChainOp.OR, listOf(StepSpec(HandshakeMode.VOICE, 3, 90), StepSpec(HandshakeMode.HOLD, 3, 90, 4)))
        compose.setContent {
            CommandTheme(darkTheme = true) {
                HandshakeProfilesScreen(HandshakeProfilesUiState.Ready(listOf(profile("p", "Planned", 3, own = planned)), inUse = emptySet()), onBack = {}, onEdit = {})
            }
        }

        compose.onNodeWithText("Any one of: Say a phrase (planned; for now: Type a phrase), Hold the button for 4 seconds").assertIsDisplayed()
    }

    @Test
    fun `a chain with a code to scan says that it asks for typing when the phone cannot scan`() {
        val qr = ChainSpec(ChainOp.SEQUENCE, listOf(StepSpec(HandshakeMode.QR, 3, 90), StepSpec(HandshakeMode.TYPING, 3, 90)))
        compose.setContent {
            CommandTheme(darkTheme = true) {
                HandshakeProfilesScreen(HandshakeProfilesUiState.Ready(listOf(profile("p", "Scan", 3, own = qr)), inUse = emptySet()), onBack = {}, onEdit = {})
            }
        }

        compose.onNodeWithText("One after another: Scan a code (or Type a phrase when the phone cannot), Type a phrase").assertIsDisplayed()
    }

    @Test
    fun `the list of profiles leads to the registered codes`() {
        var codes = 0
        compose.setContent {
            CommandTheme(darkTheme = true) {
                HandshakeProfilesScreen(HandshakeProfilesUiState.Ready(listOf(profile("a", "Steady", 2)), inUse = emptySet()), onBack = {}, onEdit = {}, onOpenCodes = { codes++ })
            }
        }

        compose.onNodeWithTag(HandshakeTags.CODES).assertTextEquals("Registered codes").assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(1, codes)
    }

    @Test
    fun `the arrow of the list of profiles goes back`() {
        var back = 0
        compose.setContent { CommandTheme(darkTheme = true) { HandshakeProfilesScreen(HandshakeProfilesUiState.Ready(listOf(profile("a", "Steady", 2)), inUse = emptySet()), { back++ }, {}) } }

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, back)
    }

    @Test
    fun `Edit reports the profile it is on`() {
        val edited = ArrayList<String>()
        compose.setContent {
            CommandTheme(darkTheme = true) {
                HandshakeProfilesScreen(HandshakeProfilesUiState.Ready(listOf(profile("a", "Steady", 2), profile("b", "Command", 3)), inUse = emptySet()), {}, { edited += it })
            }
        }

        compose.onNodeWithTag(HandshakeTags.edit("b")).performClick()
        compose.onNodeWithTag(HandshakeTags.edit("a")).performClick()

        assertEquals(listOf("b", "a"), edited)
    }

    @Test
    fun `while the profiles are being read there is a spinner and no list`() {
        compose.setContent { CommandTheme(darkTheme = true) { HandshakeProfilesScreen(HandshakeProfilesUiState.Loading, {}, {}) } }
        compose.onNodeWithTag(HandshakeTags.LIST).assertDoesNotExistFor()
    }

    // --- the editor: how the steps go together ---------------------------------------------------------------------

    @Test
    fun `one step has nothing to combine with, so how steps go together is not asked`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3))))
        compose.onNodeWithTag(HandshakeTags.OP_OR).assertDoesNotExistFor()
    }

    @Test
    fun `with two steps the three ways to combine them are offered, the one in force is selected, and a choice reports itself`() {
        val calls = showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3), step(1, HandshakeMode.TYPING)), op = ChainOp.AND))

        compose.onNodeWithText("How the steps go together").assertIsDisplayed()
        compose.onNodeWithTag(HandshakeTags.OP_AND).assertIsSelected()
        compose.onNodeWithTag(HandshakeTags.OP_OR).assertIsNotSelected()
        compose.onNodeWithTag(HandshakeTags.OP_SEQUENCE).assertIsNotSelected()

        compose.onNodeWithTag(HandshakeTags.OP_SEQUENCE).performClick()
        compose.onNodeWithTag(HandshakeTags.OP_OR).performClick()

        assertEquals(listOf(ChainOp.SEQUENCE, ChainOp.OR), calls.ops)
    }

    // --- the editor: a step -----------------------------------------------------------------------------------------

    @Test
    fun `every way is a choice, the one in use is selected, planned ones are disabled and say so, and so is one another step uses`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3), step(1, HandshakeMode.TYPING))))

        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.HOLD)).assertIsSelected().assertIsEnabled()
        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.CONFIRM_SEQUENCE)).assertIsNotSelected().assertIsEnabled()
        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.TYPING)).assertIsNotEnabled() // the second step has it
        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.TWO_STEP)).assertIsEnabled()
        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.VOICE)).assertIsNotEnabled()
        compose.onAllNodesWithTextCount("Say a phrase (planned)", 2)
        // A tag to touch is built (M13b) and can be chosen, and still says that it has not been checked on a phone.
        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.NFC)).assertIsEnabled().assertIsNotSelected()
        compose.onAllNodesWithTextCount("Touch a tag (not yet checked on a phone)", 2)
        compose.onAllNodesWithTextCount("Touch a tag (planned)", 0)
        // A code to scan is built and can be chosen, and says that it has not been checked on a phone yet.
        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.QR)).assertIsEnabled().assertIsNotSelected()
        compose.onAllNodesWithTextCount("Scan a code (not yet checked on a phone)", 2)
        compose.onAllNodesWithTextCount("Scan a code (planned)", 0)
    }

    @Test
    fun `choosing a code to scan reports the step and the way`() {
        val calls = showEditor(editing(listOf(step(4, HandshakeMode.HOLD, hold = 3), step(5, HandshakeMode.TYPING))))

        compose.onNodeWithTag(HandshakeTags.mode(1, HandshakeMode.QR)).performClick()

        assertEquals(listOf(5 to HandshakeMode.QR), calls.modes)
    }

    @Test
    fun `a step that scans a code says what it needs and leads to the registered codes`() {
        var codes = 0
        val opened = ArrayList<HandshakeMode>()
        compose.setContent {
            CommandTheme(darkTheme = true) {
                HandshakeEditorScreen(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3), step(1, HandshakeMode.QR))), Calls().actions(onOpenCodes = { codes++; opened += it }))
            }
        }

        compose.onNodeWithTag(HandshakeTags.qrNeeds(1))
            .assertTextEquals("Needs the camera, the camera consent and a registered code; without them this step asks you to type a phrase.")
        compose.onNodeWithTag(HandshakeTags.codes(1)).assertTextEquals("Registered codes").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, codes)
        assertEquals("the codes, not the tags", listOf(HandshakeMode.QR), opened)
        compose.onNodeWithTag(HandshakeTags.tags(1)).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.qrNeeds(0)).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.codes(0)).assertDoesNotExistFor()
        compose.onNodeWithText("This way is not built yet. For now the alarm asks for this instead: Type a phrase.").assertDoesNotExistFor()
    }

    @Test
    fun `a code to scan and typing are not the same way, so a step can scan while another types`() {
        showEditor(editing(listOf(step(0, HandshakeMode.QR), step(1, HandshakeMode.HOLD, hold = 3))))

        compose.onNodeWithTag(HandshakeTags.mode(1, HandshakeMode.TYPING)).assertIsEnabled()
        assertEquals(null, compose.onNodeWithTag(HandshakeTags.mode(1, HandshakeMode.TYPING)).stateDescription())
        compose.onNodeWithTag(HandshakeTags.mode(1, HandshakeMode.QR)).assertIsNotEnabled()
        assertEquals("Used in step 1", compose.onNodeWithTag(HandshakeTags.mode(1, HandshakeMode.QR)).stateDescription())
    }

    @Test
    fun `choosing a way reports the step and the way`() {
        val calls = showEditor(editing(listOf(step(7, HandshakeMode.HOLD, hold = 3), step(9, HandshakeMode.TYPING))))

        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.CONFIRM_SEQUENCE)).performClick()
        compose.onNodeWithTag(HandshakeTags.mode(1, HandshakeMode.TWO_STEP)).performClick()

        assertEquals(listOf(7 to HandshakeMode.CONFIRM_SEQUENCE, 9 to HandshakeMode.TWO_STEP), calls.modes)
    }

    @Test
    fun `tries, the time for a try and the length of a press are said in words and changed a step at a time`() {
        val calls = showEditor(editing(listOf(step(4, HandshakeMode.HOLD, tries = 4, timeout = 90, hold = 3))))

        compose.onNodeWithText("4 tries to get it right").assertIsDisplayed()
        compose.onNodeWithText("If you cannot hold, the three confirming taps may take 90 seconds").assertIsDisplayed() // a hold has no clock of its own: the time is for the taps that stand in for it
        compose.onNodeWithText("Hold for 3 seconds").assertIsDisplayed()
        compose.onNodeWithTag(HandshakeTags.triesMore(0)).performClick()
        compose.onNodeWithTag(HandshakeTags.triesLess(0)).performClick()
        compose.onNodeWithTag(HandshakeTags.timeMore(0)).performClick()
        compose.onNodeWithTag(HandshakeTags.timeLess(0)).performClick()
        compose.onNodeWithTag(HandshakeTags.holdMore(0)).performClick()
        compose.onNodeWithTag(HandshakeTags.holdLess(0)).performClick()

        assertEquals(listOf(4 to 1, 4 to -1), calls.tries)
        assertEquals(listOf(4 to 1, 4 to -1), calls.times)
        assertEquals(listOf(4 to 1, 4 to -1), calls.holds)
    }

    @Test
    fun `each stepper is wired to its own value, which is told by pressing them a different number of times`() {
        val calls = showEditor(editing(listOf(step(4, HandshakeMode.HOLD, tries = 4, timeout = 90, hold = 3))))

        compose.onNodeWithTag(HandshakeTags.triesMore(0)).performClick()
        repeat(2) { compose.onNodeWithTag(HandshakeTags.timeMore(0)).performClick() }
        repeat(3) { compose.onNodeWithTag(HandshakeTags.holdMore(0)).performClick() }
        compose.onNodeWithTag(HandshakeTags.holdLess(0)).performClick()

        assertEquals(listOf(4 to 1), calls.tries)
        assertEquals(listOf(4 to 1, 4 to 1), calls.times)
        assertEquals(listOf(4 to 1, 4 to 1, 4 to 1, 4 to -1), calls.holds)
    }

    @Test
    fun `at the least a stepper cannot go lower and says why, and the other way is open`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, tries = 2, timeout = 10, hold = 1))))

        for ((less, more) in listOf(
            HandshakeTags.triesLess(0) to HandshakeTags.triesMore(0),
            HandshakeTags.timeLess(0) to HandshakeTags.timeMore(0),
            HandshakeTags.holdLess(0) to HandshakeTags.holdMore(0),
        )) {
            compose.onNodeWithTag(less).assertIsNotEnabled()
            assertEquals("This is the least", compose.onNodeWithTag(less).stateDescription())
            compose.onNodeWithTag(more).assertIsEnabled()
            assertEquals(null, compose.onNodeWithTag(more).stateDescription())
        }
    }

    @Test
    fun `at the most a stepper cannot go higher and says why, and the other way is open`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, tries = 9, timeout = 300, hold = 10))))

        for ((less, more) in listOf(
            HandshakeTags.triesLess(0) to HandshakeTags.triesMore(0),
            HandshakeTags.timeLess(0) to HandshakeTags.timeMore(0),
            HandshakeTags.holdLess(0) to HandshakeTags.holdMore(0),
        )) {
            compose.onNodeWithTag(more).assertIsNotEnabled()
            assertEquals("This is the most", compose.onNodeWithTag(more).stateDescription())
            compose.onNodeWithTag(less).assertIsEnabled()
            assertEquals(null, compose.onNodeWithTag(less).stateDescription())
        }
    }

    private val tapsTime = "If you cannot hold, the three confirming taps may take 90 seconds"

    @Test
    fun `the time is for a try of typing or of the numbers, and for the three taps that stand in for a press or for the hold that a two-step begins with`() {
        showEditor(
            editing(
                listOf(
                    step(0, HandshakeMode.HOLD, hold = 3),
                    step(1, HandshakeMode.CONFIRM_SEQUENCE),
                    step(2, HandshakeMode.TYPING),
                    step(3, HandshakeMode.TWO_STEP),
                ),
            ),
        )

        compose.onNodeWithTag(HandshakeTags.time(0)).assertTextEquals(tapsTime)
        compose.onNodeWithTag(HandshakeTags.time(1)).assertTextEquals("A try may take 90 seconds")
        compose.onNodeWithTag(HandshakeTags.time(2)).assertTextEquals("A try may take 90 seconds")
        compose.onNodeWithTag(HandshakeTags.time(3)).assertTextEquals(tapsTime)
        compose.onNodeWithTag(HandshakeTags.onlyQuestion(3)).assertDoesNotExistFor()
    }

    private val beginsPlain = "It begins with a hold of 3 seconds."
    private val beginsUnless = "It begins with a hold of 3 seconds, unless another step has shown that you are awake first."

    @Test
    fun `in a chain of alternatives a two-step says that it begins with a hold, and nothing can spare it the hold`() {
        showEditor(editing(listOf(step(0, HandshakeMode.TYPING), step(1, HandshakeMode.TWO_STEP)), op = ChainOp.OR))

        compose.onNodeWithTag(HandshakeTags.beginsWithHold(1)).assertTextEquals(beginsPlain)
        compose.onNodeWithTag(HandshakeTags.beginsWithHold(0)).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.onlyQuestion(1)).assertDoesNotExistFor()
    }

    @Test
    fun `where every step is needed another step can spare it the hold, and the card says so`() {
        showEditor(editing(listOf(step(0, HandshakeMode.TYPING), step(1, HandshakeMode.TWO_STEP)), op = ChainOp.AND))

        compose.onNodeWithTag(HandshakeTags.beginsWithHold(1)).assertTextEquals(beginsUnless)
    }

    @Test
    fun `and so it can when the two-step comes first, in the same chain`() {
        showEditor(editing(listOf(step(0, HandshakeMode.TWO_STEP), step(1, HandshakeMode.TYPING)), op = ChainOp.AND))

        compose.onNodeWithTag(HandshakeTags.beginsWithHold(0)).assertTextEquals(beginsUnless)
    }

    @Test
    fun `a two-step that comes first in an ordered chain begins with a hold, and nothing comes before it to spare it`() {
        showEditor(editing(listOf(step(0, HandshakeMode.TWO_STEP), step(1, HandshakeMode.TYPING)), op = ChainOp.SEQUENCE))

        compose.onNodeWithTag(HandshakeTags.beginsWithHold(0)).assertTextEquals(beginsPlain)
    }

    @Test
    fun `a two-step that stands alone begins with a hold, and there is no other step to spare it`() {
        showEditor(editing(listOf(step(0, HandshakeMode.TWO_STEP)), op = ChainOp.AND))

        compose.onNodeWithTag(HandshakeTags.beginsWithHold(0)).assertTextEquals(beginsPlain)
    }

    @Test
    fun `a two-step later in an ordered chain does not say it, because the step before it has acknowledged the alarm`() {
        showEditor(editing(listOf(step(0, HandshakeMode.TYPING), step(1, HandshakeMode.TWO_STEP)), op = ChainOp.SEQUENCE))

        compose.onNodeWithTag(HandshakeTags.beginsWithHold(1)).assertDoesNotExistFor()
    }

    @Test
    fun `no other kind of step says that it begins with a hold`() {
        showEditor(
            editing(
                listOf(step(0, HandshakeMode.HOLD, hold = 3), step(1, HandshakeMode.CONFIRM_SEQUENCE), step(2, HandshakeMode.TYPING), step(3, HandshakeMode.TWO_STEP)),
                op = ChainOp.OR,
            ),
        )

        for (index in 0..2) compose.onNodeWithTag(HandshakeTags.beginsWithHold(index)).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.beginsWithHold(3)).assertTextEquals(beginsPlain)
    }

    @Test
    fun `the length of the hold is the one the step carries, one second in the singular`() {
        showEditor(editing(listOf(step(0, HandshakeMode.TWO_STEP, hold = 1), step(1, HandshakeMode.TYPING)), op = ChainOp.OR))

        compose.onNodeWithTag(HandshakeTags.beginsWithHold(0)).assertTextEquals("It begins with a hold of 1 second.")
    }

    @Test
    fun `a two-step later in an ordered chain is only the question, because the step before it has acknowledged the alarm, so it has no tries and no clock to set`() {
        showEditor(editing(listOf(step(0, HandshakeMode.TYPING), step(1, HandshakeMode.TWO_STEP)), op = ChainOp.SEQUENCE))

        compose.onNodeWithTag(HandshakeTags.onlyQuestion(1))
            .assertTextEquals("This step only asks what you will do first, after the step before it. It has no tries and no clock.")
        compose.onNodeWithTag(HandshakeTags.tries(1)).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.time(1)).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.triesMore(1)).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.timeMore(1)).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.tries(0)).assertIsDisplayed()
        compose.onNodeWithTag(HandshakeTags.time(0)).assertTextEquals("A try may take 90 seconds")
    }

    @Test
    fun `a two-step that comes first in an ordered chain begins with a hold, so it has its tries and the time of the three taps`() {
        showEditor(editing(listOf(step(0, HandshakeMode.TWO_STEP), step(1, HandshakeMode.TYPING)), op = ChainOp.SEQUENCE))

        compose.onNodeWithTag(HandshakeTags.onlyQuestion(0)).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.tries(0)).assertIsDisplayed()
        compose.onNodeWithTag(HandshakeTags.time(0)).assertTextEquals(tapsTime)
        compose.onNodeWithTag(HandshakeTags.time(1)).assertTextEquals("A try may take 90 seconds")
    }

    @Test
    fun `in a chain of alternatives a two-step can be turned to first wherever it stands, so it begins with a hold`() {
        showEditor(editing(listOf(step(0, HandshakeMode.TYPING), step(1, HandshakeMode.TWO_STEP)), op = ChainOp.OR))

        compose.onNodeWithTag(HandshakeTags.onlyQuestion(1)).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.time(1)).assertTextEquals(tapsTime)
    }

    @Test
    fun `and so it can in a chain where every step is needed, in any order`() {
        showEditor(editing(listOf(step(0, HandshakeMode.TYPING), step(1, HandshakeMode.TWO_STEP)), op = ChainOp.AND))

        compose.onNodeWithTag(HandshakeTags.onlyQuestion(1)).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.time(1)).assertTextEquals(tapsTime)
    }

    @Test
    fun `the length of a press that was stored as nothing is the short default in the editor, and it can be made shorter or longer`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 0))))

        compose.onNodeWithTag(HandshakeTags.hold(0)).assertTextEquals("Hold for 3 seconds")
        compose.onNodeWithTag(HandshakeTags.holdLess(0)).assertIsEnabled()
        compose.onNodeWithTag(HandshakeTags.holdMore(0)).assertIsEnabled()
    }

    @Test
    fun `a screen reader is told what each stepper button does, with the number of the step where there is more than one`() {
        showEditor(editing(listOf(step(1, HandshakeMode.HOLD, hold = 3), step(2, HandshakeMode.TYPING), step(3, HandshakeMode.CONFIRM_SEQUENCE))))

        for ((tag, words) in listOf(
            HandshakeTags.triesLess(0) to "Fewer tries", HandshakeTags.triesMore(0) to "More tries",
            HandshakeTags.timeLess(0) to "Shorter time for a try", HandshakeTags.timeMore(0) to "Longer time for a try",
            HandshakeTags.holdLess(0) to "Shorter hold", HandshakeTags.holdMore(0) to "Longer hold",
            HandshakeTags.triesLess(1) to "Fewer tries", HandshakeTags.triesMore(2) to "More tries",
            HandshakeTags.up(1) to "Move step 2 up", HandshakeTags.down(1) to "Move step 2 down", HandshakeTags.remove(1) to "Remove step 2",
            HandshakeTags.up(2) to "Move step 3 up", HandshakeTags.down(0) to "Move step 1 down", HandshakeTags.remove(2) to "Remove step 3",
        )) {
            compose.onNode(androidx.compose.ui.test.hasTestTag(tag) and hasContentDescription(words)).assertExists()
        }
        compose.onNodeWithContentDescription("Back").assertExists()
    }

    @Test
    fun `a screen reader hears the value of a stepper and the note of a save when they change, and the titles as headings`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3), step(1, HandshakeMode.TYPING)), saved = true, usingStandard = false))

        for (tag in listOf(HandshakeTags.tries(0), HandshakeTags.time(0), HandshakeTags.hold(0), HandshakeTags.tries(1), HandshakeTags.time(1), HandshakeTags.SAVED)) {
            assertEquals(tag, LiveRegionMode.Polite, compose.onNodeWithTag(tag).liveRegion())
        }
        assertTrue(compose.onNodeWithText("Step 1").isHeading())
        assertTrue(compose.onNodeWithText("Step 2").isHeading())
        assertTrue(compose.onNodeWithText("How the steps go together").isHeading())
    }

    @Test
    fun `how the steps go together is a group of radio buttons`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3), step(1, HandshakeMode.TYPING))))

        for (tag in listOf(HandshakeTags.OP_OR, HandshakeTags.OP_AND, HandshakeTags.OP_SEQUENCE)) {
            assertEquals(tag, Role.RadioButton, compose.onNodeWithTag(tag).role())
        }
    }

    @Test
    fun `a way that another step uses says which step, to a screen reader as well as by being dimmed`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3), step(1, HandshakeMode.TYPING))))

        assertEquals("Used in step 2", compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.TYPING)).stateDescription())
        assertEquals("Used in step 1", compose.onNodeWithTag(HandshakeTags.mode(1, HandshakeMode.HOLD)).stateDescription())
        assertEquals("the way a step has is not dimmed by itself", null, compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.HOLD)).stateDescription())
        assertEquals(null, compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.CONFIRM_SEQUENCE)).stateDescription())
    }

    @Test
    fun `Remove is apart from the arrows, so that a graze while moving a step does not delete it`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3), step(1, HandshakeMode.TYPING))))

        val down = compose.onNodeWithTag(HandshakeTags.down(0)).getBoundsInRoot()
        val remove = compose.onNodeWithTag(HandshakeTags.remove(0)).getBoundsInRoot()

        assertTrue("a gap of ${remove.left - down.right}", remove.left - down.right >= 8.dp)
    }

    @Test
    fun `the way back to the standard handshake is apart from Save`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = false, changed = true))

        val save = compose.onNodeWithTag(HandshakeTags.SAVE).getBoundsInRoot()
        val standard = compose.onNodeWithTag(HandshakeTags.STANDARD).getBoundsInRoot()

        assertTrue("a gap of ${standard.top - save.bottom}", standard.top - save.bottom >= 16.dp)
    }

    @Test
    fun `every problem is said in words with the numbers of the limits themselves`() {
        showEditor(
            editing(
                steps = listOf(step(0, HandshakeMode.TYPING, tries = 1, timeout = 5, hold = 0), step(1, HandshakeMode.HOLD, hold = 11)),
                issues = listOf(
                    ChainIssue(ChainProblem.NO_STEPS),
                    ChainIssue(ChainProblem.TOO_MANY_STEPS),
                    ChainIssue(ChainProblem.TRIES_OUT_OF_RANGE, 0),
                    ChainIssue(ChainProblem.TIME_OUT_OF_RANGE, 0),
                    ChainIssue(ChainProblem.HOLD_OUT_OF_RANGE, 1),
                    ChainIssue(ChainProblem.SAME_WAY_TWICE, 1),
                ),
                changed = true,
            ),
        )

        compose.onNodeWithText("A handshake needs at least one step.").assertIsDisplayed()
        compose.onNodeWithText("A handshake has at most 4 steps.").assertIsDisplayed()
        compose.onNodeWithText("The tries must be between 2 and 9.").assertIsDisplayed()
        compose.onNodeWithText("A try may take between 10 and 300 seconds.").assertIsDisplayed()
        compose.onNodeWithText("A press lasts between 1 and 10 seconds.").assertIsDisplayed()
        compose.onNodeWithText("The same way twice in one handshake asks for the same thing twice.").assertIsDisplayed()
    }

    @Test
    fun `only a hold has a length of press`() {
        showEditor(editing(listOf(step(0, HandshakeMode.TYPING))))
        compose.onNodeWithTag(HandshakeTags.hold(0)).assertDoesNotExistFor()
    }

    @Test
    fun `a single try is said in the singular`() {
        showEditor(editing(listOf(step(0, HandshakeMode.TYPING, tries = 1, timeout = 10))))
        compose.onNodeWithText("1 try to get it right").assertIsDisplayed()
        compose.onNodeWithText("A try may take 10 seconds").assertIsDisplayed()
    }

    @Test
    fun `a step can move up and down, except past the ends, and be removed, except the last`() {
        val calls = showEditor(editing(listOf(step(1, HandshakeMode.HOLD, hold = 3), step(2, HandshakeMode.TYPING), step(3, HandshakeMode.TWO_STEP))))

        compose.onNodeWithTag(HandshakeTags.up(0)).assertIsNotEnabled()
        compose.onNodeWithTag(HandshakeTags.down(2)).assertIsNotEnabled()
        compose.onNodeWithTag(HandshakeTags.up(1)).performClick()
        compose.onNodeWithTag(HandshakeTags.down(1)).performClick()
        compose.onNodeWithTag(HandshakeTags.remove(2)).performClick()

        assertEquals(listOf(2 to true, 2 to false), calls.moved)
        assertEquals(listOf(3), calls.removed)
    }

    @Test
    fun `the last step cannot be removed`() {
        showEditor(editing(listOf(step(1, HandshakeMode.HOLD, hold = 3))))
        compose.onNodeWithTag(HandshakeTags.remove(0)).assertIsNotEnabled()
    }

    @Test
    fun nfcBlockShowsNeedsAndTagsButton() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3), step(1, HandshakeMode.NFC))))

        compose.onNodeWithTag(HandshakeTags.nfcNeeds(1)).assertTextEquals(
            "Needs the tag consent, NFC turned on, and at least one registered tag. Some phones read tags only when unlocked. " +
                "Otherwise this step asks for a typed phrase.",
        )
        compose.onNodeWithTag(HandshakeTags.tags(1)).assertTextEquals("Registered tags").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(HandshakeTags.nfcNeeds(0)).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.tags(0)).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.codes(1)).assertDoesNotExistFor()
        compose.onNodeWithText("This way is not built yet. For now the alarm asks for this instead: Type a phrase.").assertDoesNotExistFor()
    }

    @Test
    fun tagsButtonOpensNfc() {
        val opened = ArrayList<HandshakeMode>()
        compose.setContent {
            CommandTheme(darkTheme = true) {
                HandshakeEditorScreen(editing(listOf(step(0, HandshakeMode.NFC))), Calls().actions(onOpenCodes = { opened += it }))
            }
        }

        compose.onNodeWithTag(HandshakeTags.tags(0)).performClick()

        assertEquals(listOf(HandshakeMode.NFC), opened)
    }

    @Test
    fun chipStillSaysNotYetChecked() {
        showEditor(editing(listOf(step(0, HandshakeMode.NFC))))

        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.NFC)).assertIsSelected().assertIsEnabled()
        compose.onAllNodesWithTextCount("Touch a tag (not yet checked on a phone)", 1)
        compose.onAllNodesWithTextCount("Touch a tag (planned)", 0)
    }

    @Test
    fun `a planned way that is in a chain says that it runs as typing`() {
        showEditor(editing(listOf(step(0, HandshakeMode.VOICE))))
        compose.onNodeWithText("This way is not built yet. For now the alarm asks for this instead: Type a phrase.").assertIsDisplayed()
        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.VOICE)).assertIsSelected()
        compose.onNodeWithTag(HandshakeTags.tags(0)).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.codes(0)).assertDoesNotExistFor()
    }

    // --- the editor: adding, saving, the problems -------------------------------------------------------------------

    @Test
    fun `adding a step reports, until there are four`() {
        val three = listOf(step(0, HandshakeMode.HOLD, hold = 3), step(1, HandshakeMode.CONFIRM_SEQUENCE), step(2, HandshakeMode.TYPING))
        val calls = showEditor(editing(three))
        compose.onNodeWithTag(HandshakeTags.ADD).assertIsEnabled().performClick()
        assertEquals(1, calls.added)
    }

    @Test
    fun `four steps are the most, and the button to add one is disabled`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3), step(1, HandshakeMode.CONFIRM_SEQUENCE), step(2, HandshakeMode.TYPING), step(3, HandshakeMode.TWO_STEP))))
        compose.onNodeWithTag(HandshakeTags.ADD).assertIsNotEnabled()
        compose.onNodeWithTag(HandshakeTags.ADD_LIMIT).assertTextEquals("A handshake has at most 4 steps.")
    }

    @Test
    fun `with room for another step there is no note about the limit`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3))))
        compose.onNodeWithTag(HandshakeTags.ADD_LIMIT).assertDoesNotExistFor()
    }

    @Test
    fun `Save is for a change that can be saved, and reports itself`() {
        val calls = showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), changed = true))
        compose.onNodeWithTag(HandshakeTags.SAVE).assertIsEnabled().performClick()
        assertEquals(1, calls.saved)
    }

    @Test
    fun `with nothing changed, or with a problem, Save is disabled`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), changed = false))
        compose.onNodeWithTag(HandshakeTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun `a problem with the chain is said in words, with the step it is about, and Save waits`() {
        showEditor(
            editing(
                steps = listOf(step(0, HandshakeMode.TYPING), step(1, HandshakeMode.TYPING)),
                issues = listOf(ChainIssue(ChainProblem.SAME_WAY_TWICE, 1), ChainIssue(ChainProblem.TOO_MANY_STEPS)),
                changed = true,
            ),
        )

        compose.onNodeWithText("The same way twice in one handshake asks for the same thing twice.").assertIsDisplayed()
        compose.onNodeWithText("A handshake has at most 4 steps.").assertIsDisplayed()
        compose.onNodeWithTag(HandshakeTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun `without a save there is no note that one was made`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = false, saved = false, changed = true))
        compose.onNodeWithTag(HandshakeTags.SAVED).assertDoesNotExistFor()
    }

    @Test
    fun `after a save of a profile that alarms use it says that the next alarm asks for this`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = false, saved = true, inUse = true))
        compose.onNodeWithTag(HandshakeTags.SAVED).assertIsDisplayed()
        compose.onNodeWithText("Saved. The next alarm asks for this.").assertIsDisplayed()
    }

    @Test
    fun `after a save of a profile that no alarm uses it says that no alarm asks for this yet`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = false, saved = true, inUse = false))
        compose.onNodeWithTag(HandshakeTags.SAVED).assertTextEquals("Saved. No alarm uses this profile yet, so no alarm asks for this yet.")
        compose.onNodeWithText("Saved. The next alarm asks for this.").assertDoesNotExistFor()
    }

    @Test
    fun `the editor says whether alarms are fired with the profile`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), inUse = true))
        compose.onNodeWithTag(HandshakeTags.IN_USE).assertTextEquals("Your alarms use this profile.")
    }

    @Test
    fun `and says so when none is`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), inUse = false))
        compose.onNodeWithTag(HandshakeTags.IN_USE).assertTextEquals("No alarm uses this profile yet.")
    }

    @Test
    fun `the standard handshake can be gone back to when the profile has one of its own, after a question, and not when it does not`() {
        val calls = showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = false))

        compose.onNodeWithTag(HandshakeTags.STANDARD).assertIsDisplayed().assertTextEquals("Use the standard handshake for difficulty 2").performClick()

        assertEquals("nothing is deleted by the tap itself", 0, calls.standard)
        compose.onNodeWithTag(HandshakeTags.STANDARD_DIALOG).assertIsDisplayed()
        compose.onNodeWithText("Use the standard handshake?").assertIsDisplayed()
        compose.onNodeWithText(
            "This replaces the handshake you saved for this profile with the standard one for difficulty 2. Changes you have not saved are lost, and this cannot be undone.",
        ).assertIsDisplayed()
        compose.onNodeWithTag(HandshakeTags.STANDARD_CONFIRM).performClick()
        assertEquals(1, calls.standard)
        compose.onNodeWithTag(HandshakeTags.STANDARD_DIALOG).assertDoesNotExistFor()
    }

    @Test
    fun `keeping the saved handshake closes the question and deletes nothing`() {
        val calls = showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = false))

        compose.onNodeWithTag(HandshakeTags.STANDARD).performClick()
        compose.onNodeWithTag(HandshakeTags.STANDARD_KEEP).assertIsDisplayed().performClick()
        compose.onNodeWithTag(HandshakeTags.STANDARD_DIALOG).assertDoesNotExistFor()

        assertEquals(0, calls.standard)
        assertEquals(0, calls.back)
    }

    private fun openStandardQuestion() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = false))
        compose.onNodeWithTag(HandshakeTags.STANDARD).performClick()
    }

    @Test
    fun `the title of the question about the standard handshake is a heading above the answers`() {
        openStandardQuestion()

        val title = compose.onNodeWithText("Use the standard handshake?")
        assertTrue("the title is a heading", title.isHeading())
        val keep = compose.onNodeWithTag(HandshakeTags.STANDARD_KEEP).getBoundsInRoot()
        assertTrue("and it is above the answers: ${title.getBoundsInRoot()}, $keep", title.getBoundsInRoot().bottom <= keep.top)
    }

    @Test
    fun `the answer that keeps the saved handshake comes first, and both answers are as big as a finger`() {
        openStandardQuestion()

        compose.onNodeWithTag(HandshakeTags.STANDARD_KEEP).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(HandshakeTags.STANDARD_CONFIRM).assertHeightIsAtLeast(56.dp)
        val keep = compose.onNodeWithTag(HandshakeTags.STANDARD_KEEP).getBoundsInRoot()
        val confirm = compose.onNodeWithTag(HandshakeTags.STANDARD_CONFIRM).getBoundsInRoot()
        assertTrue("the answer that keeps is above the one that deletes: $keep, $confirm", keep.bottom <= confirm.top)
    }

    @Test
    fun `the answers of the question about the standard handshake have room between them, so that a graze does not reach the wrong one`() {
        openStandardQuestion()

        val keep = compose.onNodeWithTag(HandshakeTags.STANDARD_KEEP).getBoundsInRoot()
        val confirm = compose.onNodeWithTag(HandshakeTags.STANDARD_CONFIRM).getBoundsInRoot()
        assertTrue("at least 8 dp between: $keep, $confirm", confirm.top - keep.bottom >= 8.dp)
    }

    @Test
    fun `each answer of the question about the standard handshake is as wide as the dialog's content`() {
        openStandardQuestion()

        val box = compose.onNodeWithTag(HandshakeTags.STANDARD_DIALOG).getBoundsInRoot()
        val inner = (box.right - box.left) - 48.dp // the dialog pads its content by 24 dp on each side
        for (tag in listOf(HandshakeTags.STANDARD_KEEP, HandshakeTags.STANDARD_CONFIRM)) {
            val answer = compose.onNodeWithTag(tag).getBoundsInRoot()
            assertEquals(tag, inner.value, (answer.right - answer.left).value, 1f)
        }
    }

    @Test
    fun `the dialog of the question about the standard handshake wraps its content and does not fill the window`() {
        openStandardQuestion()

        val box = compose.onNodeWithTag(HandshakeTags.STANDARD_DIALOG).getBoundsInRoot()
        assertTrue("it is far shorter than the window of 4000 dp: ${box.bottom - box.top}", box.bottom - box.top < 1_000.dp)
    }

    @Test
    fun `the question before leaving is laid out the same way, title first, the answer that keeps next, room between, each as wide as the content`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), changed = true))
        compose.onNodeWithContentDescription("Back").performClick()

        val title = compose.onNodeWithText("Leave without saving?")
        assertTrue("the title is a heading", title.isHeading())
        val keep = compose.onNodeWithTag(HandshakeTags.LEAVE_KEEP).getBoundsInRoot()
        val confirm = compose.onNodeWithTag(HandshakeTags.LEAVE_CONFIRM).getBoundsInRoot()
        assertTrue("the title is above the answers", title.getBoundsInRoot().bottom <= keep.top)
        assertTrue("the answer that keeps is above the one that throws away, with room between: $keep, $confirm", confirm.top - keep.bottom >= 8.dp)
        val box = compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).getBoundsInRoot()
        val inner = (box.right - box.left) - 48.dp
        assertEquals(inner.value, (keep.right - keep.left).value, 1f)
        assertEquals(inner.value, (confirm.right - confirm.left).value, 1f)
    }

    @Test
    fun `the question about the standard handshake survives the screen being recreated`() {
        val restoration = StateRestorationTester(compose)
        val calls = Calls()
        restoration.setContent {
            CommandTheme(darkTheme = true) { HandshakeEditorScreen(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = false), calls.actions()) }
        }
        compose.onNodeWithTag(HandshakeTags.STANDARD).performClick()
        compose.onNodeWithTag(HandshakeTags.STANDARD_DIALOG).assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag(HandshakeTags.STANDARD_DIALOG).assertIsDisplayed()
        assertEquals("recreating the screen answers nothing", 0, calls.standard)
    }

    @Test
    fun `the button for the standard handshake names the difficulty of the profile, whichever it is`() {
        showEditor(editing(listOf(step(0, HandshakeMode.TYPING)), difficulty = 4, usingStandard = false))

        compose.onNodeWithTag(HandshakeTags.STANDARD).assertTextEquals("Use the standard handshake for difficulty 4")
    }

    // --- leaving with changes that were not saved --------------------------------------------------------------------

    @Test
    fun `with nothing changed the arrow goes back at once`() {
        val calls = showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), changed = false))

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, calls.back)
        compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).assertDoesNotExistFor()
    }

    @Test
    fun `with changes that were not saved the arrow asks first, and keep editing stays`() {
        val calls = showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), changed = true))

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals("nothing is thrown away by the tap", 0, calls.back)
        compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).assertIsDisplayed()
        compose.onNodeWithText("Leave without saving?").assertIsDisplayed()
        compose.onNodeWithText("You changed this handshake and have not saved it. If you leave now, the changes are lost.").assertIsDisplayed()
        compose.onNodeWithTag(HandshakeTags.LEAVE_KEEP).performClick()
        compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).assertDoesNotExistFor()
        assertEquals(0, calls.back)
        compose.onNodeWithTag(HandshakeTags.SAVE).assertIsDisplayed()
    }

    @Test
    fun `Back asks too, and leaving without saving goes back`() {
        val calls = Calls()
        val pressBack = showEditorWithBack(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), changed = true), calls)

        pressBack()

        compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).assertIsDisplayed()
        assertEquals(0, calls.back)
        compose.onNodeWithTag(HandshakeTags.LEAVE_CONFIRM).performClick()
        assertEquals(1, calls.back)
        compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).assertDoesNotExistFor()
    }

    @Test
    fun `changes that are saved while the question is up close it, and it does not come back by itself`() {
        val calls = Calls()
        var state by mutableStateOf<HandshakeEditorUiState>(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), changed = true))
        compose.setContent { CommandTheme(darkTheme = true) { HandshakeEditorScreen(state, calls.actions()) } }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).assertIsDisplayed()

        state = editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), changed = false, saved = true, usingStandard = false)
        compose.waitForIdle()
        compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).assertDoesNotExistFor()
        state = editing(listOf(step(0, HandshakeMode.HOLD, hold = 4)), changed = true, usingStandard = false)
        compose.waitForIdle()

        compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).assertDoesNotExistFor()
        assertEquals(0, calls.back)
    }

    @Test
    fun `the question survives the screen being recreated`() {
        val restoration = StateRestorationTester(compose)
        val calls = Calls()
        restoration.setContent { CommandTheme(darkTheme = true) { HandshakeEditorScreen(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), changed = true), calls.actions()) } }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).assertIsDisplayed()
    }

    @Test
    fun `the unusable text says what to do about it`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = true, storedUnusable = true))
        compose.onNodeWithTag(HandshakeTags.UNUSABLE).assertTextContains("Change something and save to replace it", substring = true)
    }

    @Test
    fun `a profile on the standard handshake has nothing to go back to`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = true))
        compose.onNodeWithTag(HandshakeTags.STANDARD).assertDoesNotExistFor()
    }

    @Test
    fun `something stored that cannot be used is said, and the way back to the standard is there`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = true, storedUnusable = true))
        compose.onNodeWithTag(HandshakeTags.UNUSABLE).assertIsDisplayed()
        compose.onNodeWithTag(HandshakeTags.STANDARD).assertIsDisplayed()
    }

    @Test
    fun `a profile that is gone says so`() {
        showEditor(HandshakeEditorUiState.NotFound)
        compose.onNodeWithText("That profile is gone.").assertIsDisplayed()
    }

    @Test
    fun `a press that was saved as nothing is told as the short default in the list`() {
        val own = ChainSpec(ChainOp.OR, listOf(StepSpec(HandshakeMode.HOLD, 3, 90, 0)))
        compose.setContent {
            CommandTheme(darkTheme = true) {
                HandshakeProfilesScreen(HandshakeProfilesUiState.Ready(listOf(profile("a", "Mine", 3, own = own)), inUse = emptySet()), onBack = {}, onEdit = {})
            }
        }

        compose.onNodeWithText("Hold the button for 3 seconds").assertIsDisplayed()
    }

    @Test
    fun `each Edit button is named for its profile to a screen reader, and still says Edit`() {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                HandshakeProfilesScreen(
                    HandshakeProfilesUiState.Ready(listOf(profile("a", "Steady", 2), profile("b", "Command", 3)), inUse = emptySet()),
                    onBack = {},
                    onEdit = {},
                )
            }
        }

        compose.onNode(androidx.compose.ui.test.hasTestTag(HandshakeTags.edit("a")) and hasContentDescription("Edit Steady")).assertExists()
        compose.onNode(androidx.compose.ui.test.hasTestTag(HandshakeTags.edit("b")) and hasContentDescription("Edit Command")).assertExists()
        compose.onNodeWithTag(HandshakeTags.edit("a")).assertTextEquals("Edit")
    }

    @Test
    fun `which profile is edited is a heading at the top of the list, and the bar says one short word, so that a long name cannot be cut off`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), name = "Reveille MAX with a long name"))

        compose.onNodeWithTag(HandshakeTags.PROFILE_NAME).assertIsDisplayed().assertTextEquals("Reveille MAX with a long name")
        assertTrue(compose.onNodeWithTag(HandshakeTags.PROFILE_NAME).isHeading())
        val intro = "Choose what the alarm asks of you. When a way does not work out after its tries, another way is offered. Silence, a delay or a skip are never behind a step."
        assertTrue(
            "the name comes before the intro",
            compose.onNodeWithTag(HandshakeTags.PROFILE_NAME).getBoundsInRoot().top < compose.onNodeWithText(intro).getBoundsInRoot().top,
        )
        compose.onNodeWithText("Handshake").assertIsDisplayed()
    }

    @Test
    fun `Save says why it is off to a screen reader when nothing has changed`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), changed = false))
        compose.onNodeWithTag(HandshakeTags.SAVE).assertIsNotEnabled()
        assertEquals("Nothing has changed", compose.onNodeWithTag(HandshakeTags.SAVE).stateDescription())
    }

    @Test
    fun `right after a save Save says that it is saved, and not that nothing has changed`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), changed = false, usingStandard = false, saved = true))

        compose.onNodeWithTag(HandshakeTags.SAVE).assertIsNotEnabled()
        assertEquals("Saved", compose.onNodeWithTag(HandshakeTags.SAVE).stateDescription())
    }

    @Test
    fun `or that the problems come first`() {
        showEditor(
            editing(
                steps = listOf(step(0, HandshakeMode.TYPING), step(1, HandshakeMode.TYPING)),
                issues = listOf(ChainIssue(ChainProblem.SAME_WAY_TWICE, 1)),
                changed = true,
            ),
        )
        assertEquals("Fix the problems first", compose.onNodeWithTag(HandshakeTags.SAVE).stateDescription())
    }

    @Test
    fun `and when it can be used it says nothing, because there is nothing in its way`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), changed = true))
        compose.onNodeWithTag(HandshakeTags.SAVE).assertIsEnabled()
        assertEquals(null, compose.onNodeWithTag(HandshakeTags.SAVE).stateDescription())
    }

    @Test
    fun `after going back to the standard handshake the screen says so, and a screen reader hears it`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = true, restored = true))

        compose.onNodeWithTag(HandshakeTags.RESTORED).assertIsDisplayed().assertTextEquals("Back on the standard handshake for difficulty 2.")
        assertEquals(LiveRegionMode.Polite, compose.onNodeWithTag(HandshakeTags.RESTORED).liveRegion())
        compose.onNodeWithTag(HandshakeTags.SAVED).assertDoesNotExistFor()
    }

    @Test
    fun `without it there is no such note, and a save is not the same note`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = false, saved = true))

        compose.onNodeWithTag(HandshakeTags.RESTORED).assertDoesNotExistFor()
        compose.onNodeWithTag(HandshakeTags.SAVED).assertIsDisplayed()
    }

    @Test
    fun `a planned way does not say that a step uses it, because no step asks for it, even when the way it runs as is used`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3), step(1, HandshakeMode.TYPING))))

        for (index in 0..1) {
            val chip = compose.onNodeWithTag(HandshakeTags.mode(index, HandshakeMode.VOICE))
            chip.assertIsNotEnabled()
            assertEquals("step ${index + 1}, VOICE", null, chip.stateDescription())
        }
        assertEquals("while the way that is built still says which step has it", "Used in step 2", compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.TYPING)).stateDescription())
    }

    @Test
    fun `a planned way that runs as typing takes typing, so no other step can choose it, and the chip says which step has it`() {
        showEditor(editing(listOf(step(0, HandshakeMode.VOICE), step(1, HandshakeMode.HOLD, hold = 3))))

        compose.onNodeWithTag(HandshakeTags.mode(1, HandshakeMode.TYPING)).assertIsNotEnabled()
        assertEquals("Used in step 1", compose.onNodeWithTag(HandshakeTags.mode(1, HandshakeMode.TYPING)).stateDescription())
        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.TYPING)).assertIsEnabled()
        assertEquals("the step that has it can still choose it itself", null, compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.TYPING)).stateDescription())
    }

    // --- dismissing the questions: nothing is thrown away by a graze -------------------------------------------------------

    private fun latestDialog(): android.app.Dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()

    private fun pressBackInTheDialog() {
        compose.runOnUiThread { latestDialog().onBackPressed() }
        compose.waitForIdle()
    }

    /** A finger goes down and comes up outside the dialog's window: Compose's dialog dismisses on the release. */
    private fun tapOutsideTheDialog() {
        compose.runOnUiThread {
            val dialog = latestDialog()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val outside = android.view.MotionEvent.obtain(0L, 0L, action, -200f, -200f, 0)
                dialog.onTouchEvent(outside)
                outside.recycle()
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `Back while the question about leaving is up keeps the changes`() {
        val calls = showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), changed = true))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).assertIsDisplayed()

        pressBackInTheDialog()

        compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).assertDoesNotExistFor()
        assertEquals("nothing was thrown away", 0, calls.back)
        compose.onNodeWithTag(HandshakeTags.SAVE).assertIsDisplayed()
    }

    @Test
    fun `a tap outside the question about leaving keeps the changes`() {
        val calls = showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), changed = true))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).assertIsDisplayed()

        tapOutsideTheDialog()

        compose.onNodeWithTag(HandshakeTags.LEAVE_DIALOG).assertDoesNotExistFor()
        assertEquals("nothing was thrown away", 0, calls.back)
    }

    @Test
    fun `Back while the question about the standard handshake is up keeps the saved one`() {
        val calls = showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = false))
        compose.onNodeWithTag(HandshakeTags.STANDARD).performClick()
        compose.onNodeWithTag(HandshakeTags.STANDARD_DIALOG).assertIsDisplayed()

        pressBackInTheDialog()

        compose.onNodeWithTag(HandshakeTags.STANDARD_DIALOG).assertDoesNotExistFor()
        assertEquals("nothing was deleted", 0, calls.standard)
    }

    @Test
    fun `a tap outside the question about the standard handshake keeps the saved one`() {
        val calls = showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3)), usingStandard = false))
        compose.onNodeWithTag(HandshakeTags.STANDARD).performClick()
        compose.onNodeWithTag(HandshakeTags.STANDARD_DIALOG).assertIsDisplayed()

        tapOutsideTheDialog()

        compose.onNodeWithTag(HandshakeTags.STANDARD_DIALOG).assertDoesNotExistFor()
        assertEquals("nothing was deleted", 0, calls.standard)
    }

    @Test
    fun `Safety silence is promised on the screen`() {
        showEditor(editing(listOf(step(0, HandshakeMode.HOLD, hold = 3))))
        compose.onNodeWithText("Safety silence is always one tap away, whatever you choose here.").assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistFor() = assertDoesNotExist()

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String, expected: Int) {
    assertEquals(expected, onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size)
}
