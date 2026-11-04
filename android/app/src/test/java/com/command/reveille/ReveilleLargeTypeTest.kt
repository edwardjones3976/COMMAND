package com.command.reveille

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.SkipReasonCode
import com.command.domain.handshake.TryId
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
 * The way out at large type on a narrow phone. These need text that is really measured, which the default (legacy) graphics mode of the
 * other screen tests does not do: there a label is a few pixels wide whatever its size, so nothing here could fail. And the type is scaled
 * the way the phone scales it, in the configuration of the whole window: a dialog is a window of its own and does not take the scale of
 * a composition around it, so every test checks that the scale it asked for is the one the text was measured at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi")
class ReveilleLargeTypeTest {

    @get:Rule val compose = createComposeRule()

    private val firing = ReveilleUiState.Firing("occ-1", "Write the report", Challenge.Hold(TryId(0, 1), 3, earlyRelease = false, assistive = false, acknowledging = false))

    private fun actions() = ReveilleActions(
        onHoldCompleted = { _, _, _ -> }, onHoldReleasedEarly = { _, _, _ -> }, onAssistiveConfirmed = {}, onSilence = {},
        onResume = {}, onStarted = {}, onDelay = {}, onSkip = {}, onClose = {},
    )

    /** Shows the ring screen with its way out open, and a Mission Lock question over it if [friction] is given. */
    private fun showWayOut(friction: FrictionPrompt? = null) {
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                ReveilleContent(firing, friction, SkipReasonCode.values().toList(), actions(), {}, {})
            }
        }
        compose.runOnIdle { dispatcher!!.onBackPressed() }
    }

    /** The lines the label of the [minutes]-minute delay is broken into on screen; the text was measured at [fontScale]. */
    private fun delayLabelLines(minutes: Int, fontScale: Float): List<String> {
        val label = compose
            .onNode(hasText("$minutes min") and hasAnyAncestor(hasTestTag(ReveilleTags.escapeDelay(minutes))), useUnmergedTree = true)
            .fetchSemanticsNode()
        val layouts = ArrayList<TextLayoutResult>()
        compose.runOnIdle { label.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts) }
        val layout = layouts.single()
        assertTrue("the label was not really measured (${layout.size.width}px wide)", layout.size.width >= 40)
        assertEquals("the text was measured at the type size that was asked for", fontScale, layout.layoutInput.density.fontScale, 0.001f)
        val text = layout.layoutInput.text.text
        return (0 until layout.lineCount).map { text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim() }
    }

    private fun assertRead(fontScale: Float) {
        for (minutes in listOf(10, 30, 60)) {
            val lines = delayLabelLines(minutes, fontScale)
            assertEquals("the label of the $minutes-minute delay is on one line at $fontScale times", listOf("$minutes min"), lines)
        }
    }

    @Test
    fun `the delay choices are read whole at the normal type size`() {
        showWayOut()
        assertRead(1f)
    }

    @Test
    @Config(fontScale = 1.5f)
    fun `the delay choices are read whole at one and a half times the type size`() {
        showWayOut()
        assertRead(1.5f)
    }

    @Test
    @Config(fontScale = 2f)
    fun `the delay choices are read whole at twice the type size`() {
        showWayOut()
        assertRead(2f)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w320dp-h568dp-xxhdpi")
    fun `the delay choices are read whole at twice the type size on the narrowest phone`() {
        showWayOut()
        assertRead(2f)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "ldrtl-w360dp-h640dp-xxhdpi")
    fun `the delay choices are read whole at twice the type size when the text runs from right to left`() {
        showWayOut()
        assertRead(2f)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `with the phone on its side at twice the type size every way out can still be reached`() {
        showWayOut()

        compose.onNodeWithTag(ReveilleTags.ESCAPE_SILENCE).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE_KEEP).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE_SKIP).performScrollTo().assertIsDisplayed()
        for (minutes in listOf(10, 30, 60)) compose.onNodeWithTag(ReveilleTags.escapeDelay(minutes)).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(fontScale = 2f)
    fun `at twice the type size every way out can still be reached`() {
        showWayOut()

        compose.onNodeWithTag(ReveilleTags.ESCAPE_SILENCE).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE_SKIP).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE_KEEP).assertIsDisplayed()
        for (minutes in listOf(10, 30, 60)) compose.onNodeWithTag(ReveilleTags.escapeDelay(minutes)).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(fontScale = 2f)
    fun `at twice the type size a question that asks for a reason keeps its silence in view and every reason can be reached`() {
        showWayOut(friction = FrictionPrompt(LockPolicy.REASON) { })

        compose.onNodeWithTag(ReveilleTags.FRICTION_SILENCE).assertIsDisplayed()
        compose.onNodeWithText("Other").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.FRICTION_SILENCE).assertIsDisplayed() // and after the reasons have been scrolled to the end
    }

    @Test
    @Config(fontScale = 2f)
    fun `at twice the type size the silence of the way out is still in view when the choices have been scrolled to the end`() {
        showWayOut()

        compose.onNode(hasText("Choose what is true", substring = true)).performScrollTo().assertIsDisplayed() // the last thing among the choices
        compose.onNodeWithTag(ReveilleTags.ESCAPE_SILENCE).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE_KEEP).assertIsDisplayed()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "ldrtl-w360dp-h640dp-xxhdpi")
    fun `at twice the type size with the text running from right to left the silence is still in view when the choices have been scrolled to the end`() {
        showWayOut()

        compose.onNode(hasText("Choose what is true", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE_SILENCE).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE_KEEP).assertIsDisplayed()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "ldrtl-w360dp-h640dp-xxhdpi")
    fun `at twice the type size with the text running from right to left the silence of a question is still in view when the reasons have been scrolled to the end`() {
        showWayOut(friction = FrictionPrompt(LockPolicy.REASON) { })

        compose.onNodeWithText("Other").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.FRICTION_SILENCE).assertIsDisplayed()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `with the phone on its side at twice the type size the silence is still in view when the choices have been scrolled to the end`() {
        showWayOut()

        compose.onNode(hasText("Choose what is true", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE_SILENCE).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE_KEEP).assertIsDisplayed()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `with the phone on its side at twice the type size the question that asks for a reason keeps its silence in view and every reason can be reached`() {
        showWayOut(friction = FrictionPrompt(LockPolicy.REASON) { })

        compose.onNodeWithTag(ReveilleTags.FRICTION_SILENCE).assertIsDisplayed()
        compose.onNodeWithText("Other").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.FRICTION_SILENCE).assertIsDisplayed()
    }
}
