package com.command.handshake

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import com.command.data.ConsentStore
import com.command.data.TokenRepository
import com.command.domain.model.ConsentKey
import com.command.domain.model.ConsentSource
import com.command.reveille.FakeNfcReader
import com.command.reveille.LocalNfcReader
import com.command.testing.android.awaitUntil
import com.command.ui.theme.CommandTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * The tags screen through its route and a real view model, with a phone whose NFC the test sets ([FakeNfcStatus]) and a fake reader: the
 * phone's NFC is read again when the reader says it stopped, and not only when the screen comes back.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class RegisterTokenNfcRouteTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Inject lateinit var tokens: TokenRepository
    @Inject lateinit var consents: ConsentStore

    @Before
    fun setUp() {
        hilt.inject()
    }

    private fun reach(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(RegisterTokenTags.LIST).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    @Test
    fun `NFC turned off while the pane is open shows NFC off and its settings, with the screen still in front`() {
        runBlocking { consents.grant(ConsentKey.NFC, ConsentSource.USER) }
        val nfc = FakeNfcStatus()
        val vm = RegisterTokenViewModel(tokens, consents, nfc, SavedStateHandle(mapOf(RegisterTokenViewModel.ARG_MODE to "NFC")))
        awaitUntil(message = "the tags are shown") { (vm.state.value as? RegisterTokenUiState.Ready)?.consentGranted == true }
        val fake = FakeNfcReader()
        compose.setContent {
            CommandTheme(darkTheme = true) {
                CompositionLocalProvider(LocalNfcReader provides fake) { RegisterTokenRoute(onBack = {}, viewModel = vm) }
            }
        }
        reach(RegisterTokenTags.ADD).performClick()
        reach(RegisterTokenTags.LABEL_FIELD).performTextInput("Kitchen")
        reach(RegisterTokenTags.SCAN).performClick()
        compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertIsDisplayed()

        // Quick Settings: NFC goes off and the activity is never paused, so only the reader's report can tell the screen.
        nfc.now = NfcAvailability.OFF
        compose.runOnIdle { fake.fail() }
        compose.waitForIdle()

        compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertDoesNotExist()
        reach(RegisterTokenTags.NFC_OFF).assertTextEquals("NFC is off.")
        reach(RegisterTokenTags.NFC_SETTINGS).assertIsDisplayed()
        reach(RegisterTokenTags.ADD).assertIsNotEnabled()
    }
}
