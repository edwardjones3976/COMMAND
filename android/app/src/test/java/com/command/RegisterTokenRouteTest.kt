package com.command

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.command.domain.dials.HandshakeMode
import com.command.handshake.RegisterTokenViewModel
import com.command.nav.Routes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The route of the registered codes and tags, with the graph's own arguments: one screen, picked by its mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RegisterTokenRouteTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun bareRouteStillQr() {
        assertEquals("register-token?mode={mode}", Routes.REGISTER_TOKEN)
        assertEquals("register-token?mode=NFC", Routes.registerToken(HandshakeMode.NFC))
        assertEquals("register-token?mode=QR", Routes.registerToken(HandshakeMode.QR))

        // A bare route, as an older caller or a restored back stack has it, is the codes.
        val seen = ArrayList<String?>()
        var nav: NavHostController? = null
        compose.setContent {
            val controller = rememberNavController().also { nav = it }
            NavHost(controller, startDestination = "start") {
                composable("start") { Text("start") }
                composable(Routes.REGISTER_TOKEN, arguments = Routes.registerTokenArguments) { entry ->
                    val mode = entry.arguments?.getString(RegisterTokenViewModel.ARG_MODE)
                    if (seen.lastOrNull() != mode) seen += mode
                    Text(mode.orEmpty())
                }
            }
        }
        compose.runOnIdle { nav!!.navigate(Routes.REGISTER_TOKEN_BARE) }
        compose.waitForIdle()
        compose.runOnIdle { nav!!.navigate(Routes.registerToken(HandshakeMode.NFC)) }
        compose.waitForIdle()
        compose.runOnIdle { nav!!.navigate(Routes.registerToken(HandshakeMode.QR)) }
        compose.waitForIdle()

        assertEquals(listOf("QR", "NFC", "QR"), seen)
    }
}
