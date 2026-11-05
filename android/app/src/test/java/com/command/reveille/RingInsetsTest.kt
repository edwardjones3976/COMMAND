package com.command.reveille

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.command.domain.handshake.TryId
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * From Android 15 on an app that targets it is drawn behind the system bars, and the keyboard is an inset too. The ring is the one screen
 * where a control must never end up under a bar, a notch or a gesture area, or behind the keyboard: Safety silence is at the bottom of it.
 * The insets are dispatched to the content the way the platform does, and the layout's whole area (not a control that happens to be near
 * its edge) is what is measured against them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi")
class RingInsetsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 3 // xxhdpi: 3 px to a dp
    private val statusBar = 24 * density
    private val navigationBar = 48 * density
    private val keyboard = 300 * density

    private val typing = ReveilleUiState.Firing("occ-1", "Write the report", Challenge.Typing(TryId(0, 1), "Up and awake", 3, false))
    private val hold = ReveilleUiState.Firing("occ-1", "Write the report", Challenge.Hold(TryId(0, 1), 3, earlyRelease = false, assistive = false, acknowledging = false))
    private val direction = ReveilleUiState.Direction("Write the report", "Open the document", 5)

    private fun actions() = ReveilleActions(
        onHoldCompleted = { _, _, _ -> }, onHoldReleasedEarly = { _, _, _ -> }, onAssistiveConfirmed = {}, onSilence = {},
        onResume = {}, onStarted = {}, onDelay = {}, onSkip = {}, onClose = {},
    )

    /** What the platform reports: bars at the top and bottom, a cut-out (the notch) on any side, and the keyboard. */
    private class Window(
        val status: Int = 0,
        val navigation: Int = 0,
        val ime: Int = 0,
        val cutout: Insets = Insets.NONE,
        val sideBars: Insets = Insets.NONE,
    )

    private fun show(state: ReveilleUiState, window: Window) {
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        compose.setContent {
            CommandTheme(darkTheme = true) { ReveilleContent(state, null, emptyList(), actions(), {}, {}) }
        }
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, window.status, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.add(Insets.of(0, 0, 0, window.navigation), window.sideBars))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), window.cutout)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, window.ime))
            .setVisible(WindowInsetsCompat.Type.ime(), window.ime > 0)
            .build()
        compose.runOnUiThread {
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        compose.waitForIdle()
    }

    private fun silenceBounds() = compose.onNodeWithTag(ReveilleTags.SILENCE).fetchSemanticsNode().boundsInRoot
    private fun area() = compose.onNodeWithTag(ReveilleTags.RING_AREA).fetchSemanticsNode().boundsInRoot
    private fun root() = compose.onRoot().fetchSemanticsNode().size

    @Test
    fun `the ring's whole area clears the status bar and the navigation bar, and Safety silence is above the gesture area`() {
        show(typing, Window(status = statusBar, navigation = navigationBar))

        val area = area()
        assertTrue("below the status bar: ${area.top}", area.top >= statusBar)
        assertTrue("above the navigation bar: ${area.bottom} of ${root().height}", area.bottom <= root().height - navigationBar)
        assertTrue("Safety silence ends above it too: ${silenceBounds().bottom}", silenceBounds().bottom <= root().height - navigationBar)
    }

    @Test
    fun `a notch taller than the status bar pushes the whole area down below it`() {
        // Taller than the status bar (24 dp) and the margin under it (24 dp, 8 dp when tight) together: with a notch of only 48 dp the
        // margin alone put the area below it, and a screen that ignored the notch passed.
        val notch = 64 * density
        show(typing, Window(status = statusBar, navigation = navigationBar, cutout = Insets.of(0, notch, 0, 0)))

        assertTrue("below the notch, not only the status bar: ${area().top}", area().top >= notch)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `on its side the area keeps clear of a notch on one side and a navigation bar on the other, and the hold is not under either`() {
        val notch = 40 * density
        val bar = 48 * density
        show(hold, Window(cutout = Insets.of(notch, 0, 0, 0), sideBars = Insets.of(0, 0, bar, 0)))

        val area = area()
        assertTrue("clear of the notch on the left: ${area.left}", area.left >= notch)
        assertTrue("and of the bar on the right: ${area.right} of ${root().width}", area.right <= root().width - bar)
        val target = compose.onNodeWithTag(ReveilleTags.HOLD).fetchSemanticsNode().boundsInRoot
        assertTrue("the hold target is inside the area: $target in $area", target.left >= area.left && target.right <= area.right)
    }

    @Test
    @Config(qualifiers = "ldrtl-w360dp-h640dp-xxhdpi")
    fun `right to left the insets are the same physical sides`() {
        val notch = 36 * density
        show(typing, Window(status = statusBar, navigation = navigationBar, cutout = Insets.of(notch, 0, 0, 0)))

        assertTrue("a notch on the left is on the left in a right-to-left layout too: ${area().left}", area().left >= notch)
    }

    @Test
    fun `Safety silence stays in view above the keyboard`() {
        show(typing, Window(status = statusBar, navigation = navigationBar, ime = keyboard))

        val silence = silenceBounds()
        assertTrue("above the keyboard: ${silence.bottom} of ${root().height}", silence.bottom <= root().height - keyboard)
        assertTrue("and the whole area ends there", area().bottom <= root().height - keyboard)
    }

    @Test
    fun `the hold's Safety silence clears the navigation bar too`() {
        show(hold, Window(status = statusBar, navigation = navigationBar))

        assertTrue("above the navigation bar: ${silenceBounds().bottom} of ${root().height}", silenceBounds().bottom <= root().height - navigationBar)
    }

    @Test
    fun `the other screens of the flow give way to the bars as well`() {
        show(direction, Window(status = statusBar, navigation = navigationBar))

        val screen = compose.onNodeWithTag(ReveilleTags.SCREEN_AREA).fetchSemanticsNode().boundsInRoot
        assertTrue("the screen starts below the status bar: ${screen.top}", screen.top >= statusBar)
        assertTrue("and ends above the navigation bar: ${screen.bottom} of ${root().height}", screen.bottom <= root().height - navigationBar)
    }
}
