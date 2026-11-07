package com.command.profiles

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.command.data.ProfileHandshake
import com.command.data.VoicePackChoice
import com.command.domain.coaching.TemplateOverrides
import com.command.domain.dials.Dial
import com.command.domain.dials.Dials
import com.command.domain.dials.Personality
import com.command.domain.dials.PresetOrigin
import com.command.domain.voice.BuiltInVoicePacks
import com.command.domain.voice.SpeechVoice
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The dials and the profiles at large type on a narrow phone, with text that is really measured: every control can be reached and is as big as a finger. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 2f)
class ProfilesLargeTypeTest {

    @get:Rule val compose = createComposeRule()

    private var fontScale = 0f
    private val profile = ProfileHandshake("p", "Reveille MAX with a long name", PresetOrigin.CUSTOM, Personality.DRILL_SERGEANT, 5, null, storedUnusable = false)

    private fun reachable(list: String, tag: String) {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `every setting of every dial can be reached and tapped`() {
        val state = DialsUiState.Ready(profile, Dials(5, 5, 5, 5, 5, 5, 5), inUse = true, overrides = TemplateOverrides())
        compose.setContent {
            fontScale = LocalDensity.current.fontScale
            CommandTheme { DialsScreen(state, onBack = {}, onSet = { _, _ -> }, onOpenHandshake = {}) }
        }
        assertEquals(2f, fontScale)

        for (dial in Dial.values()) for (value in Dials.RANGE) reachable(DialsTags.LIST, DialsTags.value(dial, value))
    }

    @Test
    fun `every control of the voice screen can be reached and tapped, down to the last voice and its trim`() {
        val packs = BuiltInVoicePacks.all.map { VoicePackChoice(it.id, it.name, Personality.DRILL_SERGEANT, 3f, SpeechVoice(it.languageTag, it.pitch, it.speechRate)) }
        val state = VoiceUiState.Ready(profile, Dials(5, 5, 5, 5, 5, 5, 5), packs.last().id, packs, inUse = true, overrides = TemplateOverrides())
        compose.setContent { CommandTheme { VoiceScreen(state, VoiceActions({}, {}, { _, _ -> }, { _, _, _ -> })) } }

        for (pack in packs) {
            reachable(VoiceTags.LIST, VoiceTags.choose(pack.id))
            reachable(VoiceTags.LIST, VoiceTags.hear(pack.id))
        }
        // Material's slider is 44 dp high, whatever is asked of it.
        compose.onNodeWithTag(VoiceTags.LIST).performScrollToNode(hasTestTag(VoiceTags.TRIM))
        compose.onNodeWithTag(VoiceTags.TRIM).assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `the buttons of the last profile can be reached and tapped`() {
        val others = PresetOrigin.values().map { profile.copy(id = it.name, origin = it) }
        val state = ProfilesUiState.Ready(others + profile, activeId = "p", inUse = setOf("p"))
        compose.setContent {
            CommandTheme { ProfilesScreen(state, ProfilesActions({}, {}, {}, {}, {}, {})) }
        }

        reachable(ProfilesTags.LIST, ProfilesTags.active("p"))
        reachable(ProfilesTags.LIST, ProfilesTags.dials("p"))
        reachable(ProfilesTags.LIST, ProfilesTags.handshake("p"))
        reachable(ProfilesTags.LIST, ProfilesTags.voice("p"))
        reachable(ProfilesTags.LIST, ProfilesTags.RULES)
    }
}
