package com.command.handshake

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.command.data.Token
import com.command.reveille.CameraXQrScanner
import com.command.reveille.LocalQrScanner
import com.command.reveille.QrScanner
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The registered codes with no view model and no camera: a fake scanner reads what the test says it reads. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class RegisterTokenScreenTest {

    @get:Rule val compose = createComposeRule()

    /** A viewfinder that is an empty box with a tag: the test reads codes through it and fails its camera when it wants. */
    private class FakeScanner(private val consent: (() -> Boolean)? = null) : QrScanner {
        var onDecoded: (String) -> Unit = {}
        var onError: () -> Unit = {}

        /** Set when the viewfinder was drawn, even for one frame, while [consent] (read as state, so a change redraws it) said no. */
        var drawnWithoutConsent = false

        @Composable
        override fun Viewfinder(modifier: Modifier, onDecoded: (String) -> Unit, onError: () -> Unit) {
            this.onDecoded = onDecoded
            this.onError = onError
            if (consent?.invoke() == false) drawnWithoutConsent = true
            Box(modifier.testTag(FAKE_VIEWFINDER))
        }
    }

    private class Calls {
        var back = 0
        val consent = ArrayList<Boolean>()
        var askCamera = 0
        var appSettings = 0
        val registered = ArrayList<Pair<String, String>>()
        var scanFailed = 0
        val renamed = ArrayList<Pair<String, String>>()
        val deleted = ArrayList<String>()
        var make = 0
        var closeMade = 0
        var dismissed = 0

        fun actions() = RegisterTokenActions(
            onBack = { back++ },
            onSetConsent = { consent += it },
            onAskCamera = { askCamera++ },
            onOpenAppSettings = { appSettings++ },
            onRegister = { label, payload -> registered += label to payload },
            onScanFailed = { scanFailed++ },
            onRename = { id, label -> renamed += id to label },
            onDelete = { deleted += it },
            onMakeCode = { make++ },
            onCloseMadeCode = { closeMade++ },
            onDismissOutcome = { dismissed++ },
        )
    }

    private val kitchen = Token("k", "Kitchen", 1L)
    private val hall = Token("h", "Hall", 2L)
    private val desk = Token("d", "Desk by the window", 3L)

    private fun ready(
        codes: List<Token> = listOf(kitchen, hall),
        consent: Boolean = true,
        outcome: CodeOutcome? = null,
        made: String? = null,
    ) = RegisterTokenUiState.Ready(codes, consent, outcome, made)

    private var fontScale = 0f

    private fun show(state: RegisterTokenUiState, camera: CameraAccess = CameraAccess.GRANTED, calls: Calls = Calls(), scanner: FakeScanner = FakeScanner()): Calls {
        compose.setContent {
            fontScale = LocalDensity.current.fontScale
            CommandTheme(darkTheme = true) {
                CompositionLocalProvider(LocalQrScanner provides scanner) { RegisterTokenScreen(state, camera, calls.actions()) }
            }
        }
        return calls
    }

    /** The screen is a lazy list: what is below the window does not exist until the list has been scrolled to it. */
    private fun reach(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(RegisterTokenTags.LIST).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun reachAsBigAsAFinger(tag: String, least: Dp = 48.dp) {
        reach(tag).assertIsDisplayed().assertHeightIsAtLeast(least).assertWidthIsAtLeast(48.dp)
    }

    // --- the list --------------------------------------------------------------------------------------------------------

    @Test
    fun `the codes are listed by their labels, each with a Rename and a Delete named for it`() {
        show(ready())

        compose.onNodeWithTag(RegisterTokenTags.code("k")).assertTextContains("Kitchen")
        compose.onNodeWithTag(RegisterTokenTags.code("h")).assertTextContains("Hall")
        reach(RegisterTokenTags.rename("k")).assertContentDescriptionEquals("Rename Kitchen")
        reach(RegisterTokenTags.delete("h")).assertContentDescriptionEquals("Delete Hall")
        compose.onNodeWithTag(RegisterTokenTags.NONE).assertDoesNotExist()
    }

    @Test
    fun `with no codes it says so`() {
        show(ready(codes = emptyList()))
        reach(RegisterTokenTags.NONE).assertTextEquals("No codes are registered yet.")
    }

    @Test
    fun `the arrow goes back`() {
        val calls = show(ready())
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, calls.back)
    }

    @Test
    fun `while the codes are read there is no list`() {
        show(RegisterTokenUiState.Loading)
        compose.onNodeWithTag(RegisterTokenTags.LIST).assertDoesNotExist()
    }

    // --- the consent and the camera --------------------------------------------------------------------------------------

    @Test
    fun `the camera consent says what it allows and what it does not, and its switch reports both ways`() {
        val calls = show(ready(consent = false))

        reach(RegisterTokenTags.CONSENT).assertIsOff()
        compose.onNodeWithText("Camera for the QR handshake").assertIsDisplayed()
        compose.onNodeWithText("Scanning a code you registered when an alarm asks for it. Frames are read on the phone and never saved or sent.").assertIsDisplayed()
        compose.onNodeWithText("It does not take photos or video.").assertIsDisplayed()
        assertEquals(Role.Switch, compose.onNodeWithTag(RegisterTokenTags.CONSENT).fetchSemanticsNode().config[SemanticsProperties.Role])
        compose.onNodeWithTag(RegisterTokenTags.CONSENT).performClick()
        assertEquals(listOf(true), calls.consent)
    }

    @Test
    fun `a consent that is on is shown on, and turning it off reports that`() {
        val calls = show(ready(consent = true))
        reach(RegisterTokenTags.CONSENT).assertIsOn().performClick()
        assertEquals(listOf(false), calls.consent)
    }

    @Test
    fun `without the consent a code cannot be added, and it says why`() {
        show(ready(consent = false))

        reach(RegisterTokenTags.ADD).assertIsNotEnabled()
        reach(RegisterTokenTags.NEED_CONSENT).assertTextEquals("Turn on the camera consent above to add a code.")
    }

    @Test
    fun `with the consent and the camera a code can be added`() {
        show(ready(consent = true))

        reach(RegisterTokenTags.ADD).assertIsEnabled()
        compose.onNodeWithTag(RegisterTokenTags.NEED_CONSENT).assertDoesNotExist()
        compose.onNodeWithTag(RegisterTokenTags.CAMERA_REFUSED).assertDoesNotExist()
    }

    @Test
    fun `with the consent but the camera not asked for yet, it asks Android`() {
        val calls = show(ready(consent = true), camera = CameraAccess.NOT_ASKED)

        reach(RegisterTokenTags.ADD).assertIsNotEnabled()
        reach(RegisterTokenTags.CAMERA_ASK).assertTextEquals("Allow the camera").performClick()
        assertEquals(1, calls.askCamera)
    }

    @Test
    fun `a camera that Android was refused says so, with a button to the app's settings`() {
        val calls = show(ready(consent = true), camera = CameraAccess.REFUSED)

        reach(RegisterTokenTags.CAMERA_REFUSED)
            .assertTextEquals("Android has not given COMMAND the camera, so a code cannot be scanned. You can allow it in the app's settings.")
        reach(RegisterTokenTags.APP_SETTINGS).assertTextEquals("Open the app's settings").performClick()
        assertEquals(1, calls.appSettings)
        reach(RegisterTokenTags.ADD).assertIsNotEnabled()
    }

    @Test
    fun `on a phone with no camera it says that QR cannot be used here, and offers nothing to scan or make`() {
        show(ready(consent = true), camera = CameraAccess.NONE)

        reach(RegisterTokenTags.NO_CAMERA)
            .assertTextEquals("This phone has no camera, so QR codes cannot be used on it. A step that asks for a code asks you to type a phrase instead.")
        for (tag in listOf(RegisterTokenTags.ADD, RegisterTokenTags.MAKE, RegisterTokenTags.CONSENT, RegisterTokenTags.CAMERA_ASK)) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
        reach(RegisterTokenTags.delete("k")).assertIsEnabled() // codes registered earlier can still be deleted
    }

    // --- adding a code -----------------------------------------------------------------------------------------------------

    private fun startAdding(label: String) {
        reach(RegisterTokenTags.ADD).performClick()
        reach(RegisterTokenTags.LABEL_FIELD).performTextInput(label)
    }

    @Test
    fun `adding a code asks for a name first, and the scan cannot start without one`() {
        show(ready())

        reach(RegisterTokenTags.ADD).performClick()

        reach(RegisterTokenTags.SCAN).assertIsNotEnabled()
        reach(RegisterTokenTags.LABEL_FIELD).performTextInput("   ")
        reach(RegisterTokenTags.SCAN).assertIsNotEnabled()
        reach(RegisterTokenTags.LABEL_FIELD).performTextInput("Kitchen")
        reach(RegisterTokenTags.SCAN).assertIsEnabled()
    }

    @Test
    fun `a name that is too long says so, and the scan cannot start`() {
        show(ready())
        startAdding("x".repeat(41))

        reach(RegisterTokenTags.SCAN).assertIsNotEnabled()
        compose.onNodeWithText("A name needs 1 to 40 characters.").assertExists()
    }

    @Test
    fun `the code that is scanned is registered once under the name, however often the camera reads it`() {
        val scanner = FakeScanner()
        val calls = show(ready(), scanner = scanner)
        startAdding("Kitchen")

        reach(RegisterTokenTags.SCAN).performClick()
        compose.onNodeWithTag(FAKE_VIEWFINDER).assertIsDisplayed()
        compose.onNodeWithTag(RegisterTokenTags.SCAN_INSTRUCTION).assertTextEquals("Point the camera at the code you want to register as “Kitchen”.")
        compose.runOnIdle {
            scanner.onDecoded("command-qr:kitchen")
            scanner.onDecoded("command-qr:kitchen")
        }
        compose.waitForIdle()

        assertEquals(listOf("Kitchen" to "command-qr:kitchen"), calls.registered)
        compose.onNodeWithTag(FAKE_VIEWFINDER).assertDoesNotExist()
        compose.onNodeWithTag(RegisterTokenTags.LIST).assertExists()
    }

    @Test
    fun `a code that reads as nothing is still handed on, so that its own words say what is wrong`() {
        val scanner = FakeScanner()
        val calls = show(ready(), scanner = scanner)
        startAdding("Kitchen")
        reach(RegisterTokenTags.SCAN).performClick()

        compose.runOnIdle { scanner.onDecoded("  ") }
        compose.waitForIdle()

        assertEquals(listOf("Kitchen" to "  "), calls.registered)
    }

    @Test
    fun `a camera that fails while scanning is reported once, and the scan ends`() {
        val scanner = FakeScanner()
        val calls = show(ready(), scanner = scanner)
        startAdding("Kitchen")
        reach(RegisterTokenTags.SCAN).performClick()

        compose.runOnIdle {
            scanner.onError()
            scanner.onError()
        }
        compose.waitForIdle()

        assertEquals(1, calls.scanFailed)
        assertTrue(calls.registered.isEmpty())
        compose.onNodeWithTag(FAKE_VIEWFINDER).assertDoesNotExist()
    }

    @Test
    fun `a scan can be cancelled, and nothing is registered`() {
        val calls = show(ready())
        startAdding("Kitchen")
        reach(RegisterTokenTags.SCAN).performClick()

        compose.onNodeWithTag(RegisterTokenTags.SCAN_CANCEL).assertHeightIsAtLeast(48.dp).performClick()

        compose.onNodeWithTag(FAKE_VIEWFINDER).assertDoesNotExist()
        assertTrue(calls.registered.isEmpty())
    }

    @Test
    fun `a scan that is refused keeps the name that was typed, and only a registration clears it`() {
        val scanner = FakeScanner()
        val (calls, answer) = showAnswering(scanner)
        startAdding("Kitchen")

        for (refused in listOf(CodeOutcome.Duplicate("Hall"), CodeOutcome.Full, CodeOutcome.Blank)) {
            reach(RegisterTokenTags.SCAN).performClick()
            compose.runOnIdle { scanner.onDecoded("command-qr:k") }
            compose.waitForIdle()
            answer(refused)
            reach(RegisterTokenTags.LABEL_FIELD).assertTextContains("Kitchen")
            reach(RegisterTokenTags.SCAN).assertIsEnabled()
        }

        reach(RegisterTokenTags.SCAN).performClick()
        compose.runOnIdle { scanner.onDecoded("command-qr:k") }
        compose.waitForIdle()
        answer(CodeOutcome.Registered("Kitchen"))
        compose.onNodeWithTag(RegisterTokenTags.LABEL_FIELD).assertDoesNotExist()
        reach(RegisterTokenTags.ADD).performClick()
        assertEquals("the next code starts with no name", "", reach(RegisterTokenTags.LABEL_FIELD).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(4, calls.registered.size)
    }

    /** The screen with a consent and a camera the test changes, under a Back of its own that counts what reaches it. */
    private class Changing {
        var consent by mutableStateOf(true)
        var camera by mutableStateOf(CameraAccess.GRANTED)
        var backsBelow = 0
        var dispatcher: OnBackPressedDispatcher? = null
    }

    private fun showChanging(scanner: FakeScanner): Pair<Calls, Changing> {
        val calls = Calls()
        val changing = Changing()
        compose.setContent {
            BackHandler { changing.backsBelow++ }
            changing.dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                CompositionLocalProvider(LocalQrScanner provides scanner) {
                    RegisterTokenScreen(ready(consent = changing.consent), changing.camera, calls.actions())
                }
            }
        }
        return calls to changing
    }

    @Test
    fun `a scan whose consent is withdrawn ends, Back and the arrow leave, and the camera does not come back by itself`() {
        val scanner = FakeScanner()
        val (calls, changing) = showChanging(scanner)
        startAdding("Kitchen")
        reach(RegisterTokenTags.SCAN).performClick()
        compose.onNodeWithTag(FAKE_VIEWFINDER).assertIsDisplayed()

        changing.consent = false
        compose.waitForIdle()
        compose.onNodeWithTag(FAKE_VIEWFINDER).assertDoesNotExist()

        compose.runOnIdle { changing.dispatcher!!.onBackPressed() }
        assertEquals("Back is not swallowed by a scan that is not shown", 1, changing.backsBelow)
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals("the arrow goes back", 1, calls.back)

        changing.consent = true
        compose.waitForIdle()
        compose.onNodeWithTag(FAKE_VIEWFINDER).assertDoesNotExist()
        compose.onNodeWithTag(RegisterTokenTags.LIST).assertExists()
    }

    @Test
    fun `a scan ends in the very frame its consent is withdrawn, so the camera is never drawn without it`() {
        lateinit var changing: Changing
        val scanner = FakeScanner { changing.consent }
        changing = showChanging(scanner).second
        startAdding("Kitchen")
        reach(RegisterTokenTags.SCAN).performClick()
        compose.onNodeWithTag(FAKE_VIEWFINDER).assertIsDisplayed()

        changing.consent = false
        compose.waitForIdle()

        compose.onNodeWithTag(FAKE_VIEWFINDER).assertDoesNotExist()
        assertTrue("the viewfinder was drawn after the consent was withdrawn", !scanner.drawnWithoutConsent)
    }

    @Test
    fun `a scan whose camera Android takes away ends, and does not come back when it is given again`() {
        val scanner = FakeScanner()
        val (calls, changing) = showChanging(scanner)
        startAdding("Kitchen")
        reach(RegisterTokenTags.SCAN).performClick()

        changing.camera = CameraAccess.REFUSED
        compose.waitForIdle()
        compose.runOnIdle { changing.dispatcher!!.onBackPressed() }
        assertEquals(1, changing.backsBelow)

        changing.camera = CameraAccess.GRANTED
        compose.waitForIdle()
        compose.onNodeWithTag(FAKE_VIEWFINDER).assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, calls.back)
    }

    @Test
    fun `Back while a scan is shown ends the scan and stays here`() {
        val scanner = FakeScanner()
        val (calls, changing) = showChanging(scanner)
        startAdding("Kitchen")
        reach(RegisterTokenTags.SCAN).performClick()

        compose.runOnIdle { changing.dispatcher!!.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithTag(FAKE_VIEWFINDER).assertDoesNotExist()
        assertEquals(0, changing.backsBelow)
        assertEquals(0, calls.back)
    }

    @Test
    fun `adding can be cancelled before the scan`() {
        show(ready())
        startAdding("Kitchen")

        reach(RegisterTokenTags.ADD_CANCEL).performClick()

        compose.onNodeWithTag(RegisterTokenTags.LABEL_FIELD).assertDoesNotExist()
        reach(RegisterTokenTags.ADD).assertIsEnabled()
    }

    @Test
    fun `the viewfinder is the real camera unless a test gives another`() {
        var scanner: QrScanner? = null
        compose.setContent { scanner = LocalQrScanner.current }
        compose.waitForIdle()
        assertEquals(CameraXQrScanner, scanner)
    }

    // --- what each answer says -----------------------------------------------------------------------------------------------

    @Test
    fun `every answer has its own words`() {
        val words = mapOf(
            CodeOutcome.Registered("Kitchen") to "Registered “Kitchen”. A step that asks for a code accepts it now.",
            CodeOutcome.Blank to "That code holds no text, so it cannot be used. Try another code.",
            CodeOutcome.BadLabel to "A name needs 1 to 40 characters.",
            CodeOutcome.Duplicate("Hall") to "That code is already registered, as “Hall”.",
            CodeOutcome.Full to "20 codes are registered, the most there can be. Delete one to add another.",
            CodeOutcome.Renamed to "The name is changed.",
            CodeOutcome.Gone to "That code has been deleted.",
            CodeOutcome.CameraFailed to "The camera could not be used. If another app is using it, close that app and try again.",
        )
        assertEquals("every outcome is here", 8, words.size)
        var outcome by mutableStateOf<CodeOutcome?>(null)
        val calls = Calls()
        compose.setContent {
            CommandTheme(darkTheme = true) { RegisterTokenScreen(ready(outcome = outcome), CameraAccess.GRANTED, calls.actions()) }
        }
        compose.onNodeWithTag(RegisterTokenTags.OUTCOME).assertDoesNotExist()
        for ((answer, said) in words) {
            outcome = answer
            compose.waitForIdle()
            compose.onNodeWithTag(RegisterTokenTags.OUTCOME).assertIsDisplayed().assertTextContains(said)
            assertEquals(
                "$answer is said when it appears",
                androidx.compose.ui.semantics.LiveRegionMode.Polite,
                compose.onNodeWithTag(RegisterTokenTags.OUTCOME).fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
            )
        }
        compose.onNodeWithTag(RegisterTokenTags.OUTCOME_DISMISS).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.dismissed)
    }

    // --- rename and delete -------------------------------------------------------------------------------------------------

    /** The screen with an answer the test changes, as the view model would: a registration or a rename puts the last answer away at once. */
    private fun showAnswering(scanner: FakeScanner = FakeScanner(), camera: CameraAccess = CameraAccess.GRANTED): Pair<Calls, (CodeOutcome?) -> Unit> {
        var outcome by mutableStateOf<CodeOutcome?>(null)
        val calls = Calls()
        val actions = calls.actions()
        val answering = RegisterTokenActions(
            onBack = actions.onBack,
            onSetConsent = actions.onSetConsent,
            onAskCamera = actions.onAskCamera,
            onOpenAppSettings = actions.onOpenAppSettings,
            onRegister = { label, payload -> outcome = null; actions.onRegister(label, payload) },
            onScanFailed = actions.onScanFailed,
            onRename = { id, label -> outcome = null; actions.onRename(id, label) },
            onDelete = actions.onDelete,
            onMakeCode = actions.onMakeCode,
            onCloseMadeCode = actions.onCloseMadeCode,
            onDismissOutcome = actions.onDismissOutcome,
        )
        compose.setContent {
            CommandTheme(darkTheme = true) {
                CompositionLocalProvider(LocalQrScanner provides scanner) { RegisterTokenScreen(ready(outcome = outcome), camera, answering) }
            }
        }
        return calls to { answer: CodeOutcome? -> outcome = answer; compose.waitForIdle() }
    }

    @Test
    fun `rename starts from the label, reports the code and the new name, and closes when the name is changed`() {
        val (calls, answer) = showAnswering()

        reach(RegisterTokenTags.rename("k")).performClick()
        reach(RegisterTokenTags.renameField("k")).assertTextContains("Kitchen").performTextClearance()
        reach(RegisterTokenTags.renameField("k")).performTextInput("  Fridge ")
        reach(RegisterTokenTags.renameSave("k")).performClick()

        assertEquals("the name is trimmed", listOf("k" to "Fridge"), calls.renamed)
        answer(CodeOutcome.Renamed)
        compose.onNodeWithTag(RegisterTokenTags.renameField("k")).assertDoesNotExist()
    }

    @Test
    fun `a rename that is refused keeps the field open with what was typed`() {
        val (calls, answer) = showAnswering()

        reach(RegisterTokenTags.rename("k")).performClick()
        reach(RegisterTokenTags.renameField("k")).performTextClearance()
        reach(RegisterTokenTags.renameField("k")).performTextInput("Fridge")
        reach(RegisterTokenTags.renameSave("k")).performClick()
        answer(CodeOutcome.BadLabel)

        reach(RegisterTokenTags.renameField("k")).assertTextContains("Fridge")
        reach(RegisterTokenTags.renameSave("k")).performClick()
        answer(CodeOutcome.BadLabel)
        reach(RegisterTokenTags.renameField("k")).assertTextContains("Fridge")
        assertEquals(listOf("k" to "Fridge", "k" to "Fridge"), calls.renamed)

        reach(RegisterTokenTags.renameSave("k")).performClick()
        answer(CodeOutcome.Renamed)
        compose.onNodeWithTag(RegisterTokenTags.renameField("k")).assertDoesNotExist()
    }

    @Test
    fun `a rename says the limit as it is typed, and an empty or too long name cannot be saved`() {
        val calls = show(ready())

        reach(RegisterTokenTags.rename("k")).performClick()
        reach(RegisterTokenTags.renameField("k")).performTextClearance()
        reach(RegisterTokenTags.renameSave("k")).assertIsNotEnabled()
        reach(RegisterTokenTags.renameField("k")).performTextInput("   ")
        reach(RegisterTokenTags.renameSave("k")).assertIsNotEnabled()

        reach(RegisterTokenTags.renameField("k")).performTextClearance()
        reach(RegisterTokenTags.renameField("k")).performTextInput("x".repeat(41))
        reach(RegisterTokenTags.renameSave("k")).assertIsNotEnabled()
        compose.onNodeWithText("A name needs 1 to 40 characters.").assertExists()

        // Forty faces are eighty chars but forty characters, so the name fits.
        reach(RegisterTokenTags.renameField("k")).performTextClearance()
        reach(RegisterTokenTags.renameField("k")).performTextInput("😀".repeat(40))
        reach(RegisterTokenTags.renameSave("k")).assertIsEnabled()
        compose.onNodeWithText("A name needs 1 to 40 characters.").assertDoesNotExist()
        assertTrue(calls.renamed.isEmpty())
    }

    @Test
    fun `a rename can be cancelled`() {
        val calls = show(ready())

        reach(RegisterTokenTags.rename("h")).performClick()
        reach(RegisterTokenTags.renameCancel("h")).performClick()

        assertTrue(calls.renamed.isEmpty())
        compose.onNodeWithTag(RegisterTokenTags.renameField("h")).assertDoesNotExist()
    }

    /** The answer that keeps the code is the upper of the two, with room between them, as in the editor's questions. */
    private fun assertKeepComesFirst() {
        val title = compose.onNode(hasText("Delete this code?")).fetchSemanticsNode()
        assertTrue("the title is a heading", title.config.contains(SemanticsProperties.Heading))
        val k = compose.onNodeWithTag(RegisterTokenTags.DELETE_KEEP).fetchSemanticsNode().boundsInRoot
        val d = compose.onNodeWithTag(RegisterTokenTags.DELETE_CONFIRM).fetchSemanticsNode().boundsInRoot
        assertTrue("the title is above the answers", title.boundsInRoot.bottom <= k.top)
        assertTrue("keeping is above deleting: $k, $d", k.bottom <= d.top)
        assertTrue("with room between them: $k, $d", d.top - k.bottom >= with(compose.density) { 8.dp.toPx() })
    }

    @Test
    fun `delete asks first, keeping is the first answer and deletes nothing, and the other answer deletes that code`() {
        val calls = show(ready())

        reach(RegisterTokenTags.delete("k")).performClick()
        compose.onNodeWithTag(RegisterTokenTags.DELETE_DIALOG).assertExists()
        assertKeepComesFirst()
        compose.onNodeWithTag(RegisterTokenTags.DELETE_KEEP).assertTextEquals("Keep the code").performClick()
        compose.onNodeWithTag(RegisterTokenTags.DELETE_DIALOG).assertDoesNotExist()
        assertTrue(calls.deleted.isEmpty())

        reach(RegisterTokenTags.delete("k")).performClick()
        compose.onNodeWithTag(RegisterTokenTags.DELETE_CONFIRM).assertTextEquals("Delete the code").performClick()

        assertEquals(listOf("k"), calls.deleted)
        compose.onNodeWithTag(RegisterTokenTags.DELETE_DIALOG).assertDoesNotExist()
    }

    @Test
    fun `the question names the code it deletes`() {
        show(ready())
        reach(RegisterTokenTags.delete("h")).performClick()
        compose.onNode(hasText("“Hall” is deleted from COMMAND, and a step that asks for a code no longer accepts it. The printed code itself does not change. This cannot be undone.")).assertExists()
    }

    // --- making a new code -------------------------------------------------------------------------------------------------

    @Test
    fun `Make a new code reports`() {
        val calls = show(ready())
        reach(RegisterTokenTags.MAKE).assertTextEquals("Make a new code").performClick()
        assertEquals(1, calls.make)
    }

    @Test
    fun `a code that was made is shown large with what to do with it, and Done puts it away`() {
        val made = show(ready(made = "command-qr:AAAAAAAAAAAAAAAAAAAAAA"))
        reach(RegisterTokenTags.MADE_IMAGE).assertIsDisplayed().assertContentDescriptionEquals("A new QR code to print or show on another screen")
            .assertWidthIsAtLeast(240.dp).assertHeightIsAtLeast(240.dp)
        compose.onNodeWithText("Print this or show it on another screen, then scan it to register it").assertExists()
        reach(RegisterTokenTags.MADE_CLOSE).performClick()
        assertEquals(1, made.closeMade)
        assertTrue("nothing is registered by making a code", made.registered.isEmpty())
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun `a code that is made is brought into view, and its words are said`() {
        var made by mutableStateOf<String?>(null)
        compose.setContent {
            CommandTheme(darkTheme = true) {
                RegisterTokenScreen(ready(codes = listOf(kitchen, hall, desk), made = made), CameraAccess.GRANTED, Calls().actions())
            }
        }
        compose.onNodeWithTag(RegisterTokenTags.MADE_IMAGE).assertDoesNotExist()

        made = "command-qr:AAAAAAAAAAAAAAAAAAAAAA"
        compose.waitForIdle()

        compose.onNodeWithTag(RegisterTokenTags.MADE_IMAGE).assertIsDisplayed()
        val words = compose.onNodeWithTag(RegisterTokenTags.MADE_WORDS)
        words.assertIsDisplayed().assertTextEquals("Print this or show it on another screen, then scan it to register it")
        assertEquals(
            androidx.compose.ui.semantics.LiveRegionMode.Polite,
            words.fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )
    }

    // --- large type ---------------------------------------------------------------------------------------------------------

    private fun assertListReachable() {
        reachAsBigAsAFinger(RegisterTokenTags.CONSENT, 56.dp)
        for (code in listOf(kitchen, hall, desk)) {
            reachAsBigAsAFinger(RegisterTokenTags.rename(code.id))
            reachAsBigAsAFinger(RegisterTokenTags.delete(code.id))
        }
        reachAsBigAsAFinger(RegisterTokenTags.ADD, 56.dp)
        reachAsBigAsAFinger(RegisterTokenTags.MAKE, 56.dp)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w360dp-h640dp-xxhdpi")
    fun `at twice the type size on a narrow phone every control is reachable and as big as a finger`() {
        show(ready(codes = listOf(kitchen, hall, desk), outcome = CodeOutcome.Duplicate("Desk by the window")))
        assertEquals(2f, fontScale)

        assertListReachable()
        reach(RegisterTokenTags.ADD).performClick()
        reachAsBigAsAFinger(RegisterTokenTags.LABEL_FIELD)
        reach(RegisterTokenTags.LABEL_FIELD).performTextInput("Kitchen")
        reachAsBigAsAFinger(RegisterTokenTags.SCAN, 56.dp)
        reachAsBigAsAFinger(RegisterTokenTags.ADD_CANCEL, 56.dp)
        compose.onNodeWithTag(RegisterTokenTags.OUTCOME_DISMISS).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w360dp-h640dp-xxhdpi")
    fun `and the way to the app's settings and a code that was made`() {
        show(ready(codes = listOf(kitchen, hall, desk), made = "command-qr:AAAAAAAAAAAAAAAAAAAAAA"), camera = CameraAccess.REFUSED)
        assertEquals(2f, fontScale)
        reachAsBigAsAFinger(RegisterTokenTags.APP_SETTINGS, 56.dp)
        reachAsBigAsAFinger(RegisterTokenTags.MADE_CLOSE, 56.dp)
        reach(RegisterTokenTags.MADE_IMAGE).assertIsDisplayed()
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w360dp-h640dp-xxhdpi")
    fun `and the request for the camera`() {
        show(ready(codes = listOf(kitchen)), camera = CameraAccess.NOT_ASKED)
        assertEquals(2f, fontScale)
        reachAsBigAsAFinger(RegisterTokenTags.CAMERA_ASK, 56.dp)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w360dp-h640dp-xxhdpi")
    fun `and while scanning, the viewfinder has room and Cancel is in view`() {
        show(ready())
        startAdding("Desk by the window")
        reach(RegisterTokenTags.SCAN).performClick()

        compose.onNodeWithTag(RegisterTokenTags.SCAN_CANCEL).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(FAKE_VIEWFINDER).assertIsDisplayed().assertHeightIsAtLeast(120.dp)
    }

    @Test
    @Config(fontScale = 2f, qualifiers = "w360dp-h640dp-xxhdpi")
    fun `and the question before a delete keeps its safe answer first`() {
        show(ready(codes = listOf(desk)))
        reach(RegisterTokenTags.delete("d")).performClick()
        assertKeepComesFirst()
        compose.onNodeWithTag(RegisterTokenTags.DELETE_KEEP).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(RegisterTokenTags.DELETE_CONFIRM).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
    }

    private companion object {
        const val FAKE_VIEWFINDER = "fake_viewfinder"
    }
}
