package com.command.handshake

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.command.data.ProfileHandshake
import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.Personality
import com.command.domain.dials.PresetOrigin
import com.command.domain.dials.StepSpec
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The list of handshakes at large type, on a narrow phone, on its side and right to left, with text that is really measured: the Edit button
 * of every card is reachable, as big as a finger, and named for its profile. Each test says what it was shown at (read from inside the
 * composition, so that a qualifier that did not take cannot pass for the thing it names).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi")
class HandshakeProfilesLargeTypeTest {

    @get:Rule val compose = createComposeRule()

    private var fontScale = 0f
    private var direction: LayoutDirection? = null
    private var back = 0
    private val edited = ArrayList<String>()

    private fun profile(id: String, name: String, difficulty: Int, own: ChainSpec? = null) =
        ProfileHandshake(id, name, PresetOrigin.CUSTOM, Personality.MINIMALIST, difficulty, own, storedUnusable = false)

    /** Four profiles, the last with a chain of its own that is as long as a sentence can be. */
    private val four = listOf(
        profile("nudge", "Nudge", 1),
        profile("steady", "Steady", 2),
        profile("command", "Command", 3),
        profile(
            "max", "Reveille MAX with a long name", 4,
            own = ChainSpec(
                ChainOp.AND,
                listOf(
                    StepSpec(HandshakeMode.HOLD, 3, 90, 5), StepSpec(HandshakeMode.CONFIRM_SEQUENCE, 3, 90),
                    StepSpec(HandshakeMode.TYPING, 3, 90), StepSpec(HandshakeMode.TWO_STEP, 3, 90),
                ),
            ),
        ),
    )

    private fun show() {
        compose.setContent {
            fontScale = LocalDensity.current.fontScale
            direction = LocalLayoutDirection.current
            CommandTheme(darkTheme = true) {
                HandshakeProfilesScreen(HandshakeProfilesUiState.Ready(four, inUse = setOf("steady")), onBack = { back++ }, onEdit = { edited += it })
            }
        }
    }

    private fun assertShownAt(scale: Float, rtl: Boolean = false) {
        assertEquals("the type size it was shown at", scale, fontScale)
        assertEquals("the direction it was shown in", if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr, direction)
    }

    private fun reachEdit(profile: ProfileHandshake) {
        compose.onNodeWithTag(HandshakeTags.LIST).performScrollToNode(hasTestTag(HandshakeTags.edit(profile.id)))
        compose.onNodeWithTag(HandshakeTags.edit(profile.id)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(48.dp)
        compose.onNode(hasTestTag(HandshakeTags.edit(profile.id)) and hasContentDescription("Edit ${profile.name}")).assertExists()
    }

    private fun assertEveryCardIsReachable() {
        for (p in four) reachEdit(p)
        compose.onNodeWithTag(HandshakeTags.edit("max")).performClick()
        assertEquals(listOf("max"), edited)
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }

    @Test
    fun `every card can be edited at the normal type size`() {
        show()
        assertShownAt(1f)
        assertEveryCardIsReachable()
    }

    @Test
    @Config(fontScale = 2f)
    fun `and at twice the type size`() {
        show()
        assertShownAt(2f)
        assertEveryCardIsReachable()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w320dp-h568dp-xxhdpi")
    fun `and at twice the type size on the narrowest phone`() {
        show()
        assertShownAt(2f)
        assertEveryCardIsReachable()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `and on a phone on its side at twice the type size`() {
        show()
        assertShownAt(2f)
        assertEveryCardIsReachable()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "he-ldrtl-w360dp-h640dp-xxhdpi")
    fun `and right to left at twice the type size`() {
        show()
        assertShownAt(2f, rtl = true)
        assertEveryCardIsReachable()
    }
}
