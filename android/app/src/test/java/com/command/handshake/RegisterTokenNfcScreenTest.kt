package com.command.handshake

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.command.data.Token
import com.command.domain.dials.HandshakeMode
import com.command.domain.handshake.TagId
import com.command.domain.handshake.TagRead
import com.command.domain.handshake.TokenHash
import com.command.reveille.FakeNfcReader
import com.command.reveille.LocalNfcReader
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The registered tags (the codes screen with mode NFC) with no view model and no phone: a fake reader reads what the test says it reads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class RegisterTokenNfcScreenTest {

    @get:Rule val compose = createComposeRule()

    private class Calls {
        val consent = ArrayList<Boolean>()
        var askCamera = 0
        val tagReads = ArrayList<Pair<String, TagRead>>()
        var cancelled = 0
        var readerOff = 0
        var nfcSettings = 0
        var make = 0
        var dismissed = 0

        fun actions() = RegisterTokenActions(
            onBack = {},
            onSetConsent = { consent += it },
            onAskCamera = { askCamera++ },
            onOpenAppSettings = {},
            onRegister = { _, _ -> error("a tag is never registered as a code") },
            onScanFailed = {},
            onRename = { _, _ -> },
            onDelete = {},
            onMakeCode = { make++ },
            onCloseMadeCode = {},
            onDismissOutcome = { dismissed++ },
            onTagRead = { label, read -> tagReads += label to read },
            onCancelTag = { cancelled++ },
            onTagReaderOff = { readerOff++ },
            onOpenNfcSettings = { nfcSettings++ },
        )
    }

    private val kitchen = Token("k", "Kitchen", 1L)
    private val seen = TagRead.Seen(TokenHash.ofTag(TagId(byteArrayOf(0x04, 0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte(), 0xe5.toByte(), 0xf6.toByte()))))

    private fun ready(codes: List<Token> = listOf(kitchen), consent: Boolean = true, outcome: CodeOutcome? = null) =
        RegisterTokenUiState.Ready(codes, consent, outcome, madeCode = null, mode = HandshakeMode.NFC)

    private var state by mutableStateOf<RegisterTokenUiState>(RegisterTokenUiState.Loading)
    private var reader by mutableStateOf(ReaderAccess.READY)

    private fun show(
        initial: RegisterTokenUiState = ready(),
        access: ReaderAccess = ReaderAccess.READY,
        secure: Boolean = false,
        calls: Calls = Calls(),
        fake: FakeNfcReader = FakeNfcReader(),
    ): Calls {
        state = initial
        reader = access
        compose.setContent {
            CommandTheme(darkTheme = true) {
                CompositionLocalProvider(LocalNfcReader provides fake) {
                    RegisterTokenScreen(state, CameraAccess.NONE, calls.actions(), reader, secure, mode = HandshakeMode.NFC)
                }
            }
        }
        return calls
    }

    private fun reach(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(RegisterTokenTags.LIST).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    /** What the name field holds now, without its label. */
    private fun fieldText(): String =
        reach(RegisterTokenTags.LABEL_FIELD).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun startTouching(label: String) {
        reach(RegisterTokenTags.ADD).performClick()
        reach(RegisterTokenTags.LABEL_FIELD).performTextInput(label)
        reach(RegisterTokenTags.SCAN).performClick()
        compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertIsDisplayed()
    }

    @Test
    fun `the tags screen has its own words, the tag consent, and the advice on which tags work`() {
        show()
        compose.onNodeWithText("Registered tags").assertIsDisplayed()
        compose.onNodeWithText("Name a tag, then touch it to the phone twice. Only a fingerprint of its id is kept.").assertIsDisplayed()
        compose.onNodeWithText("Use a sticker or key-ring tag. Phones and bank cards change their id each time and cannot be used.").assertIsDisplayed()
        compose.onNodeWithText("Touch a tag").assertIsDisplayed() // the consent's title
        compose.onNodeWithText("Never reads tags in the background", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Camera for the QR handshake").assertDoesNotExist()
        reach(RegisterTokenTags.ADD).assertTextEquals("Add a tag")
    }

    @Test
    fun `while the tags are loading the screen already has the words of tags`() {
        show(initial = RegisterTokenUiState.Loading)
        compose.onNodeWithText("Registered tags").assertIsDisplayed()
        compose.onNodeWithText("Registered codes").assertDoesNotExist()
    }

    @Test
    fun nfcOffShowsSettingsButton() {
        val calls = show(access = ReaderAccess.OFF)
        reach(RegisterTokenTags.NFC_OFF).assertTextEquals("NFC is off.")
        reach(RegisterTokenTags.NFC_SETTINGS).assertTextEquals("Open NFC settings").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.nfcSettings)
        reach(RegisterTokenTags.ADD).assertIsNotEnabled()
    }

    @Test
    fun nfcNoneHidesAdd() {
        show(access = ReaderAccess.NONE)
        reach(RegisterTokenTags.NFC_NONE).assertTextEquals("This phone cannot read tags.")
        compose.onNodeWithTag(RegisterTokenTags.ADD).assertDoesNotExist()
        compose.onNodeWithTag(RegisterTokenTags.CONSENT).assertDoesNotExist()
        compose.onNodeWithTag(RegisterTokenTags.NFC_SETTINGS).assertDoesNotExist()
        // The tags already registered can still be renamed and deleted.
        reach(RegisterTokenTags.rename("k")).assertIsDisplayed()
    }

    @Test
    fun noMakeButtonForNfc() {
        show()
        reach(RegisterTokenTags.ADD).assertIsEnabled()
        compose.onNodeWithTag(RegisterTokenTags.MAKE).assertDoesNotExist()
        compose.onNodeWithTag(RegisterTokenTags.CAMERA_ASK).assertDoesNotExist()
        compose.onNodeWithTag(RegisterTokenTags.NO_CAMERA).assertDoesNotExist()
    }

    @Test
    fun noCameraPermissionAskedForNfc() {
        val calls = show(ready(consent = false))
        reach(RegisterTokenTags.CONSENT).performClick()
        assertEquals(listOf(true), calls.consent)
        assertEquals("the screen asks Android for nothing", 0, calls.askCamera)
        reach(RegisterTokenTags.NEED_CONSENT).assertTextEquals("Turn on the tag consent above to register a tag.")
    }

    @Test
    fun secureNoteShownWhenSecureNfcOn() {
        show(secure = true)
        reach(RegisterTokenTags.SECURE_NOTE).assertTextEquals(
            "This phone reads tags only when it is unlocked. When an alarm rings over the lock screen, it will ask you to type a phrase instead.",
        )
    }

    @Test
    fun `without Secure NFC there is no such note`() {
        show(secure = false)
        compose.onNodeWithTag(RegisterTokenTags.SECURE_NOTE).assertDoesNotExist()
    }

    @Test
    fun `two touches are handed on with the name, one per touch, and the pane asks for the second`() {
        val fake = FakeNfcReader()
        val calls = show(fake = fake)
        startTouching("Kitchen")
        assertEquals("the reader listens only while the pane is shown", 1, fake.active)
        compose.onNodeWithTag(RegisterTokenTags.TAG_INSTRUCTION).assertTextEquals("Hold the tag to the back of the phone.")

        compose.runOnIdle {
            fake.touch(seen)
            fake.touch(seen) // a tag resting on the phone, or a bounce: one touch per session
        }
        compose.waitForIdle()
        assertEquals(listOf("Kitchen" to (seen as TagRead)), calls.tagReads)

        state = ready(outcome = CodeOutcome.TouchAgain)
        compose.waitForIdle()
        compose.onNodeWithTag(RegisterTokenTags.TAG_AGAIN)
            .assertTextEquals("Got it. Take the tag away, then touch it once more to check.")
        assertEquals(
            LiveRegionMode.Polite,
            compose.onNodeWithTag(RegisterTokenTags.TAG_AGAIN).fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )
        compose.runOnIdle { fake.touch(seen) }
        compose.waitForIdle()
        assertEquals("the second touch is its own session", 2, calls.tagReads.size)

        state = ready(codes = listOf(kitchen, Token("n", "Hall", 2L)), outcome = CodeOutcome.Registered("Hall"))
        compose.waitForIdle()
        compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertDoesNotExist()
        assertEquals("the reader is off once the pane goes", 0, fake.active)
        compose.onNodeWithTag(RegisterTokenTags.OUTCOME).assertTextEquals("Registered “Hall”. A step that asks for a tag accepts it now.")
    }

    @Test
    fun `a touch that cannot be used ends the pane with its own words`() {
        val words = mapOf(
            CodeOutcome.TagChanged to "That was a different id. Touch the same tag twice.",
            CodeOutcome.RandomTag to "This tag changes its id every time, so it cannot be used. Use a sticker or key-ring tag.",
            CodeOutcome.NoTagId to "This tag gave no id, so it cannot be used.",
            CodeOutcome.Duplicate("Kitchen") to "That tag is already registered, as “Kitchen”.",
            CodeOutcome.NoConsent to "The tag consent is off, so the tag was not registered.",
        )
        val fake = FakeNfcReader()
        show(fake = fake)
        var first = true
        for ((outcome, said) in words) {
            state = ready()
            compose.waitForIdle()
            if (first) startTouching("Kitchen") else reach(RegisterTokenTags.SCAN).performClick() // the name stays until a tag is registered
            first = false
            compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertIsDisplayed()
            compose.runOnIdle { fake.touch(TagRead.NoId) }
            state = ready(outcome = outcome)
            compose.waitForIdle()
            compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertDoesNotExist()
            compose.onNodeWithTag(RegisterTokenTags.OUTCOME).assertTextEquals(said)
        }
    }

    @Test
    fun readerUnavailableShowsReaderOff() {
        val fake = FakeNfcReader()
        val calls = show(fake = fake)
        startTouching("Kitchen")

        compose.runOnIdle { fake.fail() }
        compose.waitForIdle()

        assertEquals(1, calls.readerOff)
        compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertDoesNotExist()
        assertTrue("no tag was handed on", calls.tagReads.isEmpty())
        state = ready(outcome = CodeOutcome.ReaderOff)
        compose.waitForIdle()
        compose.onNodeWithTag(RegisterTokenTags.OUTCOME).assertTextEquals("The phone stopped reading tags. Check that NFC is on and try again.")
    }

    @Test
    fun `cancel ends the pane, forgets the first touch, and a touch after it is dropped`() {
        val fake = FakeNfcReader()
        val calls = show(fake = fake)
        startTouching("Kitchen")
        compose.onNodeWithTag(RegisterTokenTags.TAG_CANCEL).assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(1, calls.cancelled)
        compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertDoesNotExist()
        compose.runOnIdle { fake.touch(seen) }
        assertTrue(calls.tagReads.isEmpty())
        assertEquals(0, fake.active)
    }

    @Test
    fun `a touch that arrives after Cancel, before the pane has gone, is dropped`() {
        val fake = FakeNfcReader()
        val calls = show(fake = fake)
        startTouching("Kitchen")

        // No frame runs after the click: the pane, and the reader in it, are still composed when a read already on its way arrives.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(RegisterTokenTags.TAG_CANCEL).performClick()
        assertEquals(1, calls.cancelled)
        assertEquals("the reader is still composed", 1, fake.active)
        compose.runOnIdle {
            fake.touch(seen)
            fake.fail()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertTrue("no touch is handed on after Cancel", calls.tagReads.isEmpty())
        assertEquals("nor a reader that stopped", 0, calls.readerOff)
        compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertDoesNotExist()
    }

    @Test
    fun `a tag saved after Cancel still puts the name away`() {
        val fake = FakeNfcReader()
        val calls = show(fake = fake)
        startTouching("Hall")
        compose.runOnIdle { fake.touch(seen) }
        state = ready(outcome = CodeOutcome.TouchAgain)
        compose.waitForIdle()
        compose.runOnIdle { fake.touch(seen) }
        state = ready(outcome = null) // the second touch matched: the save is on its way
        compose.waitForIdle()
        assertEquals(2, calls.tagReads.size)

        compose.onNodeWithTag(RegisterTokenTags.TAG_CANCEL).performClick()
        compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertDoesNotExist()
        state = ready(codes = listOf(kitchen, Token("n", "Hall", 2L)), outcome = CodeOutcome.Registered("Hall"))
        compose.waitForIdle()

        compose.onNodeWithTag(RegisterTokenTags.OUTCOME).assertTextEquals("Registered “Hall”. A step that asks for a tag accepts it now.")
        compose.onNodeWithTag(RegisterTokenTags.LABEL_FIELD).assertDoesNotExist()
        reach(RegisterTokenTags.ADD).performClick()
        assertEquals("", fieldText())
    }

    @Test
    fun `after Cancel with no save on its way, the name stays`() {
        val fake = FakeNfcReader()
        show(fake = fake)
        startTouching("Hall")
        compose.onNodeWithTag(RegisterTokenTags.TAG_CANCEL).performClick()
        state = ready(outcome = CodeOutcome.Registered("Kitchen")) // an answer that is not this touch's: a rename elsewhere, or one from before
        compose.waitForIdle()
        assertEquals("Hall", fieldText())
    }

    @Test
    fun `Back while the pane is shown ends it and stays here`() {
        var dispatcher: OnBackPressedDispatcher? = null
        val calls = Calls()
        state = ready()
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                CompositionLocalProvider(LocalNfcReader provides FakeNfcReader()) { RegisterTokenScreen(state, CameraAccess.NONE, calls.actions(), ReaderAccess.READY, false) }
            }
        }
        startTouching("Kitchen")

        compose.runOnIdle { dispatcher!!.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertDoesNotExist()
        compose.onNodeWithTag(RegisterTokenTags.LIST).assertExists()
        assertEquals(1, calls.cancelled)
    }

    @Test
    fun `the pane ends when NFC goes off or the consent is withdrawn`() {
        val fake = FakeNfcReader()
        val calls = show(fake = fake)
        startTouching("Kitchen")
        reader = ReaderAccess.OFF
        compose.waitForIdle()
        compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertDoesNotExist()
        assertEquals(0, fake.active)
        assertEquals(1, calls.cancelled)

        reader = ReaderAccess.READY
        compose.waitForIdle()
        compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertDoesNotExist() // it does not come back by itself
        reach(RegisterTokenTags.SCAN).performClick()
        compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertIsDisplayed()
        state = ready(consent = false)
        compose.waitForIdle()
        compose.onNodeWithTag(RegisterTokenTags.TAG_PANE).assertDoesNotExist()
        compose.runOnIdle { fake.touch(seen) }
        assertTrue(calls.tagReads.isEmpty())
    }

    @Test
    fun savedStateHoldsNoTagData() {
        // Android writes saved state into a Bundle that can outlive the screen; a tag's fingerprint is never in it.
        val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
        val fake = FakeNfcReader()
        state = ready()
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry, LocalNfcReader provides fake) {
                CommandTheme(darkTheme = true) { RegisterTokenScreen(state, CameraAccess.NONE, Calls().actions(), ReaderAccess.READY, false) }
            }
        }
        startTouching("Kitchen")
        compose.runOnIdle { fake.touch(seen) }
        state = ready(outcome = CodeOutcome.TouchAgain)
        compose.waitForIdle()

        val saved = compose.runOnIdle { registry.performSave() }
        assertFalse("the saved state holds the fingerprint: $saved", saved.toString().contains(seen.hash))
        assertFalse("or a read", saved.values.flatten().any { it is TagRead })
    }
}
