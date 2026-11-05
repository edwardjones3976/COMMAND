package com.command.reveille

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.command.domain.handshake.TryId
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the ring screen asks for, at large type and on a phone on its side, with text that is really measured (the default graphics mode of
 * the other screen tests measures every label at a few pixels, so nothing there could fail). Whatever is asked, Safety silence must be
 * entirely inside the window without scrolling and one tap, and the control that answers the challenge must be reachable by scrolling.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi")
class HandshakeLargeTypeTest {

    @get:Rule val compose = createComposeRule()
    private val shown = ShownDirection()

    private var silenced = 0
    private val typed = ArrayList<String>()
    private val tapped = ArrayList<Int>()
    private var indicated = 0
    private val assistive = ArrayList<TryId>()

    private fun actions() = ReveilleActions(
        onHoldCompleted = { _, _, _ -> }, onHoldReleasedEarly = { _, _, _ -> }, onAssistiveConfirmed = { assistive += it },
        onTypingSubmitted = { _, text -> typed += text }, onSequenceTapped = { _, number -> tapped += number },
        onTwoStepIndicated = { indicated++ }, onSilence = { silenced++ },
        onResume = {}, onStarted = {}, onDelay = {}, onSkip = {}, onClose = {},
    )

    private fun show(challenge: Challenge, progress: ChainProgress? = null, offerConfirm: Boolean = false, alternatives: List<Alternative> = emptyList()) {
        compose.setContent {
            shown.RecordDirection()
            CommandTheme(darkTheme = true) {
                ReveilleContent(ReveilleUiState.Firing("occ-1", "Write the report", challenge, progress, offerConfirm, alternatives), null, emptyList(), actions(), {}, {})
            }
        }
    }

    /** Whether the node, or anything above it, scrolls: a control that a scroll gesture must never take over has nothing above it that does. */
    private fun hasScrollingAncestor(tag: String): Boolean {
        var node: SemanticsNode? = compose.onNodeWithTag(tag).fetchSemanticsNode()
        while (node != null) {
            if (SemanticsProperties.VerticalScrollAxisRange in node.config || SemanticsActions.ScrollBy in node.config) return true
            node = node.parent
        }
        return false
    }

    private val typing get() = Challenge.Typing(TryId(0, 2), "Feet on the floor and moving now", triesLeft = 2, mistaken = true)
    private val sequence get() = Challenge.Sequence(TryId(0, 2), listOf(3, 1, 4, 2), tapped = 1, triesLeft = 2, mistaken = true)
    private val twoStep get() = Challenge.TwoStep(TryId(1, 1), "Open the report and write the first heading")
    private val hold get() = Challenge.Hold(TryId(0, 1), 3, earlyRelease = true, assistive = false, acknowledging = true)
    private val assistiveHold get() = Challenge.Hold(TryId(0, 1), 3, earlyRelease = false, assistive = true, acknowledging = false)

    /**
     * Safety silence is wholly inside the window, with nothing scrolled, as tall as a button is meant to be (a layout that runs out of
     * room does not push it out of the window, it squeezes it to a sliver that is still "inside"), and does what it says.
     */
    private fun assertSilenceWithinReach() {
        val window = compose.onRoot().fetchSemanticsNode().size
        val b = compose.onNodeWithTag(ReveilleTags.SILENCE).fetchSemanticsNode().boundsInRoot
        assertTrue("Safety silence starts inside the window: $b in $window", b.left >= 0f && b.top >= 0f)
        assertTrue("and ends inside it: $b in $window", b.right <= window.width && b.bottom <= window.height)
        compose.onNodeWithTag(ReveilleTags.SILENCE).assertIsDisplayed().assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(1, silenced)
    }

    /** The hold target is a real target, not what is left of one. */
    private fun assertHoldTargetIsBig() {
        compose.onNodeWithTag(ReveilleTags.HOLD).assertIsDisplayed().assertHeightIsAtLeast(160.dp).assertWidthIsAtLeast(160.dp)
    }

    private val ways = listOf(Alternative(1, com.command.domain.dials.HandshakeMode.CONFIRM_SEQUENCE, instead = true), Alternative(2, com.command.domain.dials.HandshakeMode.TWO_STEP, instead = true))

    private fun assertWaysReachable(list: List<Alternative> = ways) {
        for (alternative in list) {
            compose.onNodeWithTag(ReveilleTags.alternative(alternative.position)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
    }

    /** Where every step is needed: the longer header ("You can do these in any order. Do next:"), and the buttons are only done first. */
    private val doNext = listOf(Alternative(1, com.command.domain.dials.HandshakeMode.TYPING, instead = false))

    /** Three other ways at once (a chain of four), the last the three taps that stand in for a press: the longest names. */
    private val threeWays = listOf(
        Alternative(1, com.command.domain.dials.HandshakeMode.HOLD, instead = true, assistive = true),
        Alternative(2, com.command.domain.dials.HandshakeMode.CONFIRM_SEQUENCE, instead = true),
        Alternative(3, com.command.domain.dials.HandshakeMode.TWO_STEP, instead = true),
    )

    // --- upright at twice the type size ---------------------------------------------------------------------------------

    @Test
    @Config(fontScale = 2f)
    fun `other ways under a phrase at twice the type size are reachable and as big as a finger, with Safety silence in view`() {
        show(typing, ChainProgress(1, 2), alternatives = ways)
        assertWaysReachable()
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w400dp-h400dp-xxhdpi")
    fun `other ways with a hold in a short window at twice the type size are reachable, and neither the hold nor Safety silence is in a scroll`() {
        show(hold, ChainProgress(1, 1), alternatives = ways)
        assertWaysReachable()
        compose.onNodeWithTag(ReveilleTags.HOLD).assertIsDisplayed().assertHeightIsAtLeast(96.dp)
        assertFalse("a finger resting on the target must never be taken over by a scroll", hasScrollingAncestor(ReveilleTags.HOLD))
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "he-ldrtl-w360dp-h640dp-xxhdpi")
    fun `other ways under a phrase right to left at twice the type size are reachable and as big as a finger, with Safety silence in view`() {
        show(typing, ChainProgress(1, 2), alternatives = ways)
        shown.assertRtl()
        assertWaysReachable()
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f)
    fun `the other steps to do next, with the longer header, under the numbers at twice the type size are reachable and as big as a finger, with Safety silence in view`() {
        show(sequence, ChainProgress(1, 2), alternatives = doNext)
        compose.onNodeWithText("You can do these in any order. Do next:").performScrollTo().assertIsDisplayed()
        assertWaysReachable(doNext)
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "he-ldrtl-w360dp-h640dp-xxhdpi")
    fun `and the same under the numbers right to left`() {
        show(sequence, ChainProgress(1, 2), alternatives = doNext)
        shown.assertRtl()
        compose.onNodeWithText("You can do these in any order. Do next:").performScrollTo().assertIsDisplayed()
        assertWaysReachable(doNext)
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f)
    fun `three other ways under the two-step at twice the type size are all reachable and as big as a finger, with Safety silence in view`() {
        show(twoStep, ChainProgress(1, 4), alternatives = threeWays)
        compose.onNodeWithTag(ReveilleTags.alternative(1)).performScrollTo().assertIsDisplayed().assertTextEquals("Confirm with three taps")
        assertWaysReachable(threeWays)
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "he-ldrtl-w640dp-h360dp-land-xxhdpi")
    fun `three other ways beside a hold on a phone on its side right to left, at twice the type size, with the target and Safety silence whole`() {
        show(hold, ChainProgress(1, 4), alternatives = threeWays)
        shown.assertRtl()
        assertWaysReachable(threeWays)
        assertHoldTargetIsBig()
        assertFalse(hasScrollingAncestor(ReveilleTags.HOLD))
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `other ways beside a hold on a phone on its side at twice the type size are reachable, with the target and Safety silence whole`() {
        show(hold, ChainProgress(1, 1), alternatives = ways)
        assertWaysReachable()
        assertHoldTargetIsBig()
        assertFalse(hasScrollingAncestor(ReveilleTags.HOLD))
        assertSilenceWithinReach()
    }


    @Test
    @Config(fontScale = 2f)
    fun `typing at twice the type size keeps Safety silence in view and every control reachable`() {
        show(typing, ChainProgress(1, 2))
        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.MISTAKEN).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.TYPING_DONE).performScrollTo().assertIsDisplayed()
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f)
    fun `the numbers at twice the type size keep Safety silence in view and every number reachable`() {
        show(sequence, ChainProgress(1, 2))
        for (n in 1..4) compose.onNodeWithTag(ReveilleTags.sequenceTarget(n)).performScrollTo().assertIsDisplayed()
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f)
    fun `the two-step at twice the type size keeps Safety silence in view and its button reachable`() {
        show(twoStep, ChainProgress(2, 2))
        compose.onNodeWithTag(ReveilleTags.TWO_STEP_INDICATE).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, indicated)
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f)
    fun `the end of the road at twice the type size keeps Safety silence in view and the note readable`() {
        show(Challenge.OutOfTries)
        compose.onNodeWithTag(ReveilleTags.OUT_OF_TRIES).performScrollTo().assertIsDisplayed()
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f)
    fun `the hold at twice the type size keeps its target and Safety silence in view`() {
        show(hold, ChainProgress(1, 1))
        assertHoldTargetIsBig()
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f)
    fun `the three taps at twice the type size are reachable with Safety silence in view`() {
        show(assistiveHold)
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).assertIsDisplayed()
        assertSilenceWithinReach()
    }

    // --- on its side ------------------------------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `the hold on a phone on its side keeps its target and Safety silence in the window`() {
        show(hold)
        assertHoldTargetIsBig()
        assertSilenceWithinReach()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `the three taps on a phone on its side are reachable with Safety silence in the window`() {
        show(assistiveHold)
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).assertIsDisplayed()
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `typing on a phone on its side at twice the type size keeps Safety silence in the window and the button reachable`() {
        show(typing, ChainProgress(1, 2))
        compose.onNodeWithTag(ReveilleTags.TYPING_DONE).performScrollTo().assertIsDisplayed()
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `the numbers on a phone on its side at twice the type size keep Safety silence in the window and every number reachable`() {
        show(sequence, ChainProgress(1, 2))
        for (n in 1..4) compose.onNodeWithTag(ReveilleTags.sequenceTarget(n)).performScrollTo().assertIsDisplayed()
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `the two-step on a phone on its side at twice the type size keeps Safety silence in the window and its button reachable`() {
        show(twoStep, ChainProgress(2, 2))
        compose.onNodeWithTag(ReveilleTags.TWO_STEP_INDICATE).performScrollTo().assertIsDisplayed()
        assertSilenceWithinReach()
    }

    // --- short windows: the hold's target shrinks, and nothing else is squeezed --------------------------------------------

    @Test
    @Config(fontScale = 2f, qualifiers = "w360dp-h400dp-xxhdpi")
    fun `the hold in a short window at twice the type size shrinks its target and squeezes nothing, and neither it nor Safety silence is in a scroll`() {
        show(hold, ChainProgress(1, 1))
        compose.onNodeWithTag(ReveilleTags.HOLD).assertIsDisplayed().assertHeightIsAtLeast(96.dp).assertWidthIsAtLeast(96.dp)
        // The target gives way to the room it is given, and only the words give way to it in turn: they keep their minimum (128 dp, a little
        // less allowed for rounding). A target that took the size it asked for whatever the room would leave them a sliver.
        compose.onNodeWithTag(ReveilleTags.HOLD_WORDS).assertHeightIsAtLeast(120.dp)
        assertFalse("a finger resting on the target must never be taken over by a scroll", hasScrollingAncestor(ReveilleTags.HOLD))
        assertFalse(hasScrollingAncestor(ReveilleTags.SILENCE))
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w320dp-h480dp-xxhdpi")
    fun `the hold on a small phone at twice the type size keeps a big target, the hint, and Safety silence whole`() {
        show(hold, ChainProgress(1, 1))
        assertHoldTargetIsBig()
        compose.onNodeWithTag(ReveilleTags.ACKNOWLEDGING).performScrollTo().assertIsDisplayed()
        assertFalse(hasScrollingAncestor(ReveilleTags.HOLD))
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w360dp-h400dp-xxhdpi")
    fun `a screen reader's three taps under the hold in a short window at twice the type size are reachable with Safety silence whole`() {
        show(hold, ChainProgress(1, 1), offerConfirm = true)
        compose.onNodeWithTag(ReveilleTags.HOLD).assertIsDisplayed().assertHeightIsAtLeast(96.dp)
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `on its side at twice the type size the hold keeps its target, the hint and the three taps for a screen reader, and Safety silence`() {
        show(hold, ChainProgress(1, 1), offerConfirm = true)
        assertHoldTargetIsBig()
        compose.onNodeWithText("Released early. Hold the full 3 seconds.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).performScrollTo().assertIsDisplayed()
        assertFalse(hasScrollingAncestor(ReveilleTags.HOLD))
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `on its side at twice the type size the three taps that stand in for the press are not squeezed either`() {
        show(assistiveHold, offerConfirm = true)
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        assertSilenceWithinReach()
    }

    // --- right to left ----------------------------------------------------------------------------------------------------

    @Test
    @Config(fontScale = 2f, qualifiers = "he-ldrtl-w360dp-h640dp-xxhdpi")
    fun `typing right to left at twice the type size keeps Safety silence in view and the button reachable`() {
        show(typing, ChainProgress(1, 2))
        shown.assertRtl()
        compose.onNodeWithTag(ReveilleTags.TYPING_DONE).performScrollTo().assertIsDisplayed()
        assertSilenceWithinReach()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "he-ldrtl-w360dp-h640dp-xxhdpi")
    fun `the numbers right to left at twice the type size are all reachable, with Safety silence in view`() {
        show(sequence, ChainProgress(1, 2))
        shown.assertRtl()
        for (n in 1..4) compose.onNodeWithTag(ReveilleTags.sequenceTarget(n)).performScrollTo().assertIsDisplayed()
        assertSilenceWithinReach()
    }
}
