package com.command.handshake

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import com.command.domain.dials.ChainIssue
import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainProblem
import com.command.domain.dials.HandshakeMode
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The handshake editor at large type, on a narrow phone, on its side and right to left, with text that is really measured: a person who needs
 * the type large must still reach every control, and every control must be as big as a finger. Each test says what it was shown at (the type
 * size and the direction are read from inside the composition, so that a qualifier that did not take cannot pass for the thing it names).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi")
class HandshakeEditorLargeTypeTest {

    @get:Rule val compose = createComposeRule()

    private var saved = 0
    private var added = 0
    private var back = 0
    private val tries = ArrayList<Pair<Int, Int>>()
    private var fontScale = 0f
    private var direction: LayoutDirection? = null

    private fun actions() = HandshakeEditorActions(
        onBack = { back++ }, onSetOp = {}, onAddStep = { added++ }, onRemoveStep = {}, onMoveStep = { _, _ -> }, onSetMode = { _, _ -> },
        onChangeTries = { key, by -> tries += key to by }, onChangeTimeout = { _, _ -> }, onChangeHold = { _, _ -> },
        onSave = { saved++ }, onUseStandard = {},
    )

    private fun step(key: Int, mode: HandshakeMode, hold: Int = 0) = StepDraft(key, mode, 3, 90, hold)

    private val twoSteps = HandshakeEditorUiState.Editing(
        profileName = "Reveille MAX with a long name",
        difficulty = 4,
        op = ChainOp.AND,
        steps = listOf(step(0, HandshakeMode.HOLD, hold = 5), step(1, HandshakeMode.CONFIRM_SEQUENCE)),
        issues = emptyList(),
        changed = true,
        usingStandard = false,
        storedUnusable = false,
        saved = false,
        restored = false,
        inUse = true,
    )

    /** Everything the screen can say at once: four steps, a problem in a step and one in the chain, something unusable that was stored, a save, and a profile no alarm uses. */
    private val everything = HandshakeEditorUiState.Editing(
        profileName = "Reveille MAX with a long name",
        difficulty = 4,
        op = ChainOp.AND,
        steps = listOf(step(0, HandshakeMode.HOLD, hold = 5), step(1, HandshakeMode.CONFIRM_SEQUENCE), step(2, HandshakeMode.TYPING), step(3, HandshakeMode.TWO_STEP)),
        issues = listOf(ChainIssue(ChainProblem.TRIES_OUT_OF_RANGE, 1), ChainIssue(ChainProblem.TIME_OUT_OF_RANGE, 1), ChainIssue(ChainProblem.TOO_MANY_STEPS)),
        changed = true,
        usingStandard = false,
        storedUnusable = true,
        saved = true,
        restored = false,
        inUse = false,
    )

    private fun show(state: HandshakeEditorUiState = twoSteps) {
        compose.setContent {
            fontScale = LocalDensity.current.fontScale
            direction = LocalLayoutDirection.current
            CommandTheme(darkTheme = true) { HandshakeEditorScreen(state, actions()) }
        }
    }

    private fun assertShownAt(scale: Float, rtl: Boolean = false) {
        assertEquals("the type size it was shown at", scale, fontScale)
        assertEquals("the direction it was shown in", if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr, direction)
    }

    /** The editor is a lazy list: what is below the window does not exist until the list has been scrolled to it, and then it is scrolled into full view. */
    private fun reach(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(HandshakeTags.EDITOR).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    /** In view and as big as a finger, in both directions: a dimmed control is a target a finger finds too, so it is measured as well. */
    private fun reachAsBigAsAFinger(tag: String, least: Dp = 48.dp) {
        reach(tag).assertIsDisplayed().assertHeightIsAtLeast(least).assertWidthIsAtLeast(48.dp)
    }

    private fun lineCount(tag: String): Int {
        val results = ArrayList<TextLayoutResult>()
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().lineCount
    }

    /** The lines a message takes, found by its words (the messages of the questions have no tag of their own). */
    private fun lineCountOfText(text: String): Int {
        val results = ArrayList<TextLayoutResult>()
        compose.onNode(hasText(text)).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().lineCount
    }

    private fun stepControls(index: Int, hold: Boolean) {
        for (tag in listOf(HandshakeTags.up(index), HandshakeTags.down(index), HandshakeTags.remove(index))) reachAsBigAsAFinger(tag)
        for (mode in HandshakeMode.values()) reachAsBigAsAFinger(HandshakeTags.mode(index, mode))
        for (tag in listOf(HandshakeTags.triesLess(index), HandshakeTags.triesMore(index), HandshakeTags.timeLess(index), HandshakeTags.timeMore(index))) reachAsBigAsAFinger(tag)
        if (hold) for (tag in listOf(HandshakeTags.holdLess(index), HandshakeTags.holdMore(index))) reachAsBigAsAFinger(tag)
    }

    private fun assertReachable() {
        reach(HandshakeTags.OP_SEQUENCE).assertIsDisplayed()
        for (tag in listOf(HandshakeTags.OP_OR, HandshakeTags.OP_AND, HandshakeTags.OP_SEQUENCE)) reachAsBigAsAFinger(tag, 56.dp)
        stepControls(0, hold = true)
        stepControls(1, hold = false)
        reach(HandshakeTags.hold(0)).assertIsDisplayed()
        reachAsBigAsAFinger(HandshakeTags.STANDARD, 56.dp)
        reach(HandshakeTags.ADD).assertIsDisplayed().assertHeightIsAtLeast(56.dp).performClick()
        reach(HandshakeTags.SAVE).assertIsDisplayed().assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(1, added)
        assertEquals(1, saved)
        reach(HandshakeTags.triesMore(1)).performClick()
        assertEquals(listOf(1 to 1), tries)
    }

    @Test
    fun `every control of the editor is reachable and as big as a finger at the normal type size`() {
        show()
        assertShownAt(1f)
        assertReachable()
    }

    @Test
    @Config(fontScale = 2f)
    fun `and at twice the type size`() {
        show()
        assertShownAt(2f)
        assertReachable()
        reach(HandshakeTags.tries(0))
        assertTrue("the words really wrap, so the measuring is real", lineCount(HandshakeTags.tries(0)) > 1)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w320dp-h568dp-xxhdpi")
    fun `and at twice the type size on the narrowest phone`() {
        show()
        assertShownAt(2f)
        assertReachable()
        reach(HandshakeTags.tries(0))
        assertTrue(lineCount(HandshakeTags.tries(0)) > 1)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `and on a phone on its side at twice the type size`() {
        show()
        assertShownAt(2f)
        assertReachable()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "he-ldrtl-w360dp-h640dp-xxhdpi")
    fun `and right to left at twice the type size`() {
        show()
        assertShownAt(2f, rtl = true)
        assertReachable()
    }

    // --- everything the screen can say at once ----------------------------------------------------------------------

    private fun assertEverythingIsReachable() {
        for (tag in listOf(HandshakeTags.IN_USE, HandshakeTags.UNUSABLE, HandshakeTags.ISSUES, HandshakeTags.ADD_LIMIT)) {
            reach(tag).assertIsDisplayed()
        }
        // The note of the save is not in the list: it is under it, and in view without scrolling.
        compose.onNodeWithTag(HandshakeTags.SAVED).assertIsDisplayed()
        for (index in 0..3) stepControls(index, hold = index == 0)
        reachAsBigAsAFinger(HandshakeTags.ADD, 56.dp)
        reachAsBigAsAFinger(HandshakeTags.SAVE, 56.dp)
        reachAsBigAsAFinger(HandshakeTags.STANDARD, 56.dp)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w320dp-h568dp-xxhdpi")
    fun `with four steps, problems, a note of unusable text and a save all at once, at twice the type size on the narrowest phone, all of it is reachable`() {
        show(everything)
        assertShownAt(2f)
        assertEverythingIsReachable()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "he-ldrtl-w360dp-h640dp-xxhdpi")
    fun `and right to left`() {
        show(everything)
        assertShownAt(2f, rtl = true)
        assertEverythingIsReachable()
    }

    // --- what the last save, or the way back to the standard, said --------------------------------------------------------

    private fun assertWhollyInTheWindow(tag: String) {
        val window = compose.onRoot().fetchSemanticsNode().size
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag is wholly inside the window: $b in $window", b.left >= 0f && b.top >= 0f && b.right <= window.width && b.bottom <= window.height)
        assertTrue("and has a body: $b", b.height > 0f && b.width > 0f)
    }

    /**
     * The person scrolls to the end of a long list, where Save is, and saves: the note is in view at once, and is not a thing they would have to
     * scroll to, and the end of the list (what follows Save, or Save itself when nothing does) is still in view above it.
     */
    private fun showThenChange(before: HandshakeEditorUiState.Editing, after: HandshakeEditorUiState.Editing, tag: String, words: String, stillInView: String) {
        var state by mutableStateOf<HandshakeEditorUiState>(before)
        compose.setContent {
            fontScale = LocalDensity.current.fontScale
            direction = LocalLayoutDirection.current
            CommandTheme(darkTheme = true) { HandshakeEditorScreen(state, actions()) }
        }
        reach(HandshakeTags.SAVE).assertIsDisplayed()
        compose.onNodeWithTag(tag).assertDoesNotExist()

        state = after
        compose.waitForIdle()

        compose.onNodeWithTag(tag).assertIsDisplayed().assertTextEquals(words)
        assertWhollyInTheWindow(tag)
        compose.onNodeWithTag(stillInView).assertIsDisplayed()

        // And wherever the list is scrolled to, the note stays under it: at the top of the list the note is still in view, and it is not part of the list.
        compose.onNodeWithTag(HandshakeTags.EDITOR).performScrollToNode(hasTestTag(HandshakeTags.PROFILE_NAME))
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val list = compose.onNodeWithTag(HandshakeTags.EDITOR).fetchSemanticsNode().boundsInRoot
        val note = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("the note is under the list and not in it: $note, $list", note.top >= list.bottom - 1f)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w320dp-h568dp-xxhdpi")
    fun `after a save from the end of the list the note is in view without scrolling, at twice the type size on the narrowest phone`() {
        // The first save of a profile: it was on the standard handshake, so Save is the last item, and the way back to the standard comes after it.
        val before = everything.copy(saved = false, issues = emptyList(), storedUnusable = false, changed = true, usingStandard = true)
        showThenChange(
            before, before.copy(saved = true, changed = false, usingStandard = false), HandshakeTags.SAVED,
            "Saved. No alarm uses this profile yet, so no alarm asks for this yet.", HandshakeTags.STANDARD,
        )
        assertShownAt(2f)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "he-ldrtl-w360dp-h640dp-xxhdpi")
    fun `after a save from the end of the list the note is in view right to left too`() {
        val before = everything.copy(saved = false, issues = emptyList(), storedUnusable = false, changed = true, inUse = true, usingStandard = true)
        showThenChange(
            before, before.copy(saved = true, changed = false, usingStandard = false), HandshakeTags.SAVED, "Saved. The next alarm asks for this.", HandshakeTags.STANDARD,
        )
        assertShownAt(2f, rtl = true)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `after a save from the end of the list the note is in view on a phone on its side too`() {
        val before = everything.copy(saved = false, issues = emptyList(), storedUnusable = false, changed = true, inUse = true, usingStandard = true)
        showThenChange(
            before, before.copy(saved = true, changed = false, usingStandard = false), HandshakeTags.SAVED, "Saved. The next alarm asks for this.", HandshakeTags.STANDARD,
        )
        assertShownAt(2f)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w320dp-h568dp-xxhdpi")
    fun `after going back to the standard handshake the note is in view without scrolling, at twice the type size on the narrowest phone`() {
        val before = everything.copy(saved = false, issues = emptyList(), storedUnusable = false, changed = false)
        showThenChange(before, before.copy(usingStandard = true, restored = true), HandshakeTags.RESTORED, "Back on the standard handshake for difficulty 4.", HandshakeTags.SAVE)
        assertShownAt(2f)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w320dp-h568dp-xxhdpi")
    fun `a two-step that is only the question says so in words that fit, at twice the type size on the narrowest phone`() {
        show(twoSteps.copy(op = ChainOp.SEQUENCE, steps = listOf(step(0, HandshakeMode.TYPING), step(1, HandshakeMode.TWO_STEP))))
        assertShownAt(2f)

        reach(HandshakeTags.onlyQuestion(1)).assertIsDisplayed()
        assertTrue("the words really wrap, so the measuring is real", lineCount(HandshakeTags.onlyQuestion(1)) > 1)
        compose.onNodeWithTag(HandshakeTags.tries(1)).assertDoesNotExist()
        for (tag in listOf(HandshakeTags.up(1), HandshakeTags.down(1), HandshakeTags.remove(1))) reachAsBigAsAFinger(tag)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w320dp-h568dp-xxhdpi")
    fun `the line that a two-step begins with a hold fits at twice the type size on the narrowest phone`() {
        show(everything) // every step is needed, and the two-step is the fourth: the longer form of the line
        assertShownAt(2f)

        reach(HandshakeTags.beginsWithHold(3)).assertIsDisplayed()
        assertTrue("the words really wrap, so the measuring is real", lineCount(HandshakeTags.beginsWithHold(3)) > 1)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w320dp-h568dp-xxhdpi")
    fun `the profile is named at the top of the list, in words that fit, and the bar has one short word`() {
        show(twoSteps)
        assertShownAt(2f)

        compose.onNodeWithTag(HandshakeTags.PROFILE_NAME).assertIsDisplayed().assertTextEquals("Reveille MAX with a long name")
        assertTrue("a long name wraps in the list, where there is room, and is not cut off", lineCount(HandshakeTags.PROFILE_NAME) > 1)
        compose.onNode(hasText("Handshake")).assertIsDisplayed()
    }

    // --- the questions ------------------------------------------------------------------------------------------------

    /**
     * The answer that keeps what the person has is the upper of the two, which are one under the other and each as wide as the dialog: it is
     * first in reading order whatever the width, the type size or the direction.
     */
    private fun assertSafeAnswerComesFirst(title: String, keep: String, risky: String) {
        val head = compose.onNode(hasText(title)).fetchSemanticsNode()
        assertTrue("the title is a heading", head.config.contains(SemanticsProperties.Heading))
        val k = compose.onNodeWithTag(keep).fetchSemanticsNode().boundsInRoot
        val r = compose.onNodeWithTag(risky).fetchSemanticsNode().boundsInRoot
        assertTrue("the title is above the answers: ${head.boundsInRoot}, $k", head.boundsInRoot.bottom <= k.top)
        assertTrue("the safe answer is above the other: $k, $r", k.bottom <= r.top)
        assertTrue("with room between them, so that a graze does not reach the wrong one: $k, $r", r.top - k.bottom >= with(compose.density) { 8.dp.toPx() })
    }

    @Test
    fun `the answer that keeps the changes comes first when the question before leaving opens`() {
        show()
        assertShownAt(1f)

        compose.onNodeWithContentDescription("Back").performClick()

        assertSafeAnswerComesFirst("Leave without saving?", HandshakeTags.LEAVE_KEEP, HandshakeTags.LEAVE_CONFIRM)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w320dp-h568dp-xxhdpi")
    fun `and at twice the type size on the narrowest phone, where they are still one under the other`() {
        show()
        assertShownAt(2f)

        compose.onNodeWithContentDescription("Back").performClick()

        assertSafeAnswerComesFirst("Leave without saving?", HandshakeTags.LEAVE_KEEP, HandshakeTags.LEAVE_CONFIRM)
    }

    @Test
    @Config(qualifiers = "he-ldrtl-w360dp-h640dp-xxhdpi")
    fun `and right to left, where it is still the upper`() {
        show()
        assertShownAt(1f, rtl = true)

        compose.onNodeWithContentDescription("Back").performClick()

        assertSafeAnswerComesFirst("Leave without saving?", HandshakeTags.LEAVE_KEEP, HandshakeTags.LEAVE_CONFIRM)
    }

    @Test
    fun `the answer that keeps the saved handshake comes first when the question about the standard opens`() {
        show()
        assertShownAt(1f)

        reach(HandshakeTags.STANDARD).performClick()

        assertSafeAnswerComesFirst("Use the standard handshake?", HandshakeTags.STANDARD_KEEP, HandshakeTags.STANDARD_CONFIRM)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "he-ldrtl-w320dp-h568dp-xxhdpi")
    fun `and right to left at twice the type size on the narrowest phone`() {
        show()
        assertShownAt(2f, rtl = true)

        reach(HandshakeTags.STANDARD).performClick()

        assertSafeAnswerComesFirst("Use the standard handshake?", HandshakeTags.STANDARD_KEEP, HandshakeTags.STANDARD_CONFIRM)
    }

    private val leaveBody = "You changed this handshake and have not saved it. If you leave now, the changes are lost."
    private val standardBody = "This replaces the handshake you saved for this profile with the standard one for difficulty 4. Changes you have not saved are lost, and this cannot be undone."

    @Test
    @Config(fontScale = 2f, qualifiers = "w320dp-h568dp-xxhdpi")
    fun `the message of the question before leaving scrolls to its end, at twice the type size on the narrowest phone`() {
        show()
        assertShownAt(2f)
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNode(hasText(leaveBody)).performScrollTo().assertIsDisplayed()
        assertTrue("the message wraps", lineCountOfText(leaveBody) > 1)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `and on its side, where the message of the question about the standard handshake is the longer`() {
        show()
        assertShownAt(2f)
        reach(HandshakeTags.STANDARD).performClick()

        compose.onNode(hasText(standardBody)).performScrollTo().assertIsDisplayed()
    }

    private fun assertBothAnswersAreInReach(keep: String, confirm: String) {
        compose.onNodeWithTag(keep).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(confirm).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w320dp-h568dp-xxhdpi")
    fun `the question before leaving has both answers in reach at twice the type size on the narrowest phone, and leaving goes back`() {
        show()
        assertShownAt(2f)

        compose.onNodeWithContentDescription("Back").performClick()

        assertBothAnswersAreInReach(HandshakeTags.LEAVE_KEEP, HandshakeTags.LEAVE_CONFIRM)
        compose.onNodeWithTag(HandshakeTags.LEAVE_CONFIRM).performClick()
        assertEquals(1, back)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `and on a phone on its side`() {
        show()
        assertShownAt(2f)

        compose.onNodeWithContentDescription("Back").performClick()

        assertBothAnswersAreInReach(HandshakeTags.LEAVE_KEEP, HandshakeTags.LEAVE_CONFIRM)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w320dp-h568dp-xxhdpi")
    fun `the question before the standard handshake has both answers in reach at twice the type size on the narrowest phone`() {
        show()
        assertShownAt(2f)

        reach(HandshakeTags.STANDARD).performClick()

        assertBothAnswersAreInReach(HandshakeTags.STANDARD_KEEP, HandshakeTags.STANDARD_CONFIRM)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "he-ldrtl-w640dp-h360dp-land-xxhdpi")
    fun `and on a phone on its side right to left`() {
        show()
        assertShownAt(2f, rtl = true)

        reach(HandshakeTags.STANDARD).performClick()

        assertBothAnswersAreInReach(HandshakeTags.STANDARD_KEEP, HandshakeTags.STANDARD_CONFIRM)
    }
}
