package com.command.handshake

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.command.data.Token
import com.command.reveille.LocalQrScanner
import com.command.reveille.QrScanner
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The codes screen runs edge to edge in MainActivity, and typing a name opens the keyboard. The list and the answer under it must end above
 * the keyboard, or the field's Scan and Save, and the answer, are behind it at the end of the scroll. The insets are dispatched the way the
 * platform does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi")
class RegisterTokenInsetsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 3 // xxhdpi: 3 px to a dp
    private val statusBar = 24 * density
    private val navigationBar = 48 * density
    private val keyboard = 300 * density

    private val noScanner = object : QrScanner {
        @androidx.compose.runtime.Composable
        override fun Viewfinder(modifier: androidx.compose.ui.Modifier, onDecoded: (String) -> Unit, onError: () -> Unit) = Unit
    }

    private fun actions() = RegisterTokenActions(
        onBack = {}, onSetConsent = {}, onAskCamera = {}, onOpenAppSettings = {}, onRegister = { _, _ -> }, onScanFailed = {},
        onRename = { _, _ -> }, onDelete = {}, onMakeCode = {}, onCloseMadeCode = {}, onDismissOutcome = {},
    )

    private fun show(ime: Int) {
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        val state = RegisterTokenUiState.Ready(
            listOf(Token("k", "Kitchen", 1L), Token("h", "Hall", 2L)), consentGranted = true, outcome = CodeOutcome.Renamed, madeCode = null,
        )
        compose.setContent {
            CommandTheme(darkTheme = true) {
                CompositionLocalProvider(LocalQrScanner provides noScanner) { RegisterTokenScreen(state, CameraAccess.GRANTED, actions()) }
            }
        }
        dispatch(ime)
    }

    private fun dispatch(ime: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBar, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBar))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
            .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
            .build()
        compose.runOnUiThread {
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        compose.waitForIdle()
    }

    private fun bottomOf(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.bottom
    private fun height() = compose.onRoot().fetchSemanticsNode().size.height

    @Test
    fun `MainActivity, which shows the codes screen, resizes for the keyboard, so the keyboard is an inset before Android 11 too`() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val main = Regex("""<activity\b[^>]*android:name="com\.command\.MainActivity"[^>]*>""").find(manifest)?.value
        assertTrue("MainActivity is declared", main != null)
        assertTrue("MainActivity asks for adjustResize: $main", main!!.contains("android:windowSoftInputMode=\"adjustResize\""))
    }

    @Test
    fun `with the keyboard open the list and the answer under it end above the keyboard`() {
        show(ime = keyboard)

        assertTrue("the list ends above the keyboard: ${bottomOf(RegisterTokenTags.LIST)} of ${height()}", bottomOf(RegisterTokenTags.LIST) <= height() - keyboard)
        assertTrue("so does the answer: ${bottomOf(RegisterTokenTags.OUTCOME)}", bottomOf(RegisterTokenTags.OUTCOME) <= height() - keyboard)
    }

    @Test
    fun `opening the keyboard lifts the answer by the keyboard less the navigation bar, so the bar is not counted twice`() {
        show(ime = 0)
        val closed = bottomOf(RegisterTokenTags.OUTCOME)
        assertTrue("above the navigation bar: $closed of ${height()}", closed <= height() - navigationBar)

        dispatch(ime = keyboard)
        val lifted = closed - bottomOf(RegisterTokenTags.OUTCOME)
        assertTrue("lifted by ${keyboard - navigationBar}, not $lifted", kotlin.math.abs(lifted - (keyboard - navigationBar)) <= 1f)
    }
}
