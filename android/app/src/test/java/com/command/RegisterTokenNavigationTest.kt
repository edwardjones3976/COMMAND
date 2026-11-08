package com.command

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import android.nfc.NfcAdapter
import com.command.data.AppSettings
import com.command.data.ConsentStore
import com.command.domain.model.ConsentKey
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNfcAdapter
import com.command.data.ProfileRepository
import com.command.domain.dials.HandshakeMode
import com.command.handshake.HandshakeTags
import com.command.handshake.RegisterTokenTags
import com.command.schedule.ScheduleTags
import com.command.settings.SettingsTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The way to the registered codes, through the real activity: from Settings by the handshakes, and from a step of the editor that scans a code. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class RegisterTokenNavigationTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var settings: AppSettings
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var consents: ConsentStore

    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking { settings.markOnboardingDone() }
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
    }

    private fun open(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java, Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)).setup().also { controller = it }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.waitUntil(timeoutMillis = 30_000) { exists(tag) }
    private fun waitGone(tag: String) = compose.waitUntil(timeoutMillis = 30_000) { !exists(tag) }

    private fun tap(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    private fun back(controller: ActivityController<MainActivity>) = controller.get().onBackPressedDispatcher.onBackPressed()

    @Test
    fun `from Settings by the handshakes to the registered codes, and back to the handshakes`() {
        val controller = open()
        tap(ScheduleTags.LINK_SETTINGS)
        tap(SettingsTags.LINK_HANDSHAKES)
        waitFor(HandshakeTags.LIST)

        tap(HandshakeTags.CODES)
        waitFor(RegisterTokenTags.LIST)
        waitFor(RegisterTokenTags.NONE)

        back(controller)
        waitFor(HandshakeTags.LIST)
        assertTrue("the codes are gone", !exists(RegisterTokenTags.LIST))
    }

    @Test
    fun `the arrow of the registered codes goes back`() {
        open()
        tap(ScheduleTags.LINK_SETTINGS)
        tap(SettingsTags.LINK_HANDSHAKES)
        tap(HandshakeTags.CODES)
        waitFor(RegisterTokenTags.LIST)
        waitGone(HandshakeTags.LIST) // the list has gone with its transition: its own arrow is not a second "Back" on screen

        compose.onNodeWithContentDescription("Back").performClick()

        waitFor(HandshakeTags.LIST)
    }

    @Test
    fun `from a step of the editor that scans a code to the registered codes, and back to the editor with the draft as it was`() {
        val controller = open()
        tap(ScheduleTags.LINK_SETTINGS)
        tap(SettingsTags.LINK_HANDSHAKES)
        tap(HandshakeTags.edit("builtin_steady"))
        waitFor(HandshakeTags.EDITOR)
        tap(HandshakeTags.mode(0, HandshakeMode.QR))

        tap(HandshakeTags.codes(0))
        waitFor(RegisterTokenTags.LIST)

        back(controller)
        waitFor(HandshakeTags.EDITOR)
        waitFor(HandshakeTags.codes(0))
        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.QR)).assertIsSelected()
        assertNull("nothing was saved on the way", runBlocking { profiles.get("builtin_steady")!!.own })
    }

    // --- the tags (M13b): the same screen, with mode=NFC --------------------------------------------------------------

    @Test
    fun profilesCodesStillQr() {
        open()
        tap(ScheduleTags.LINK_SETTINGS)
        tap(SettingsTags.LINK_HANDSHAKES)
        tap(HandshakeTags.CODES)
        waitFor(RegisterTokenTags.LIST)
        compose.onNodeWithText("Registered codes").assertIsDisplayed()
        compose.onNodeWithText("Registered tags").assertDoesNotExist()
        compose.onNodeWithText("A code is any QR code you print or already own", substring = true).assertIsDisplayed()
    }

    @Test
    fun editorNfcOpensTags() {
        val controller = open()
        tap(ScheduleTags.LINK_SETTINGS)
        tap(SettingsTags.LINK_HANDSHAKES)
        tap(HandshakeTags.edit("builtin_steady"))
        waitFor(HandshakeTags.EDITOR)
        tap(HandshakeTags.mode(0, HandshakeMode.NFC))

        tap(HandshakeTags.tags(0))
        waitFor(RegisterTokenTags.LIST)
        compose.onNodeWithText("Registered tags").assertIsDisplayed()
        compose.onNodeWithText("Name a tag, then touch it to the phone twice", substring = true).assertIsDisplayed()
        assertTrue("there is no code to make for a tag", !exists(RegisterTokenTags.MAKE))

        back(controller)
        waitFor(HandshakeTags.EDITOR)
        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.NFC)).assertIsSelected()
        assertNull("nothing was saved on the way", runBlocking { profiles.get("builtin_steady")!!.own })
    }

    @Test
    fun `turning the tag consent on asks Android for nothing`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        ShadowNfcAdapter.setNfcHardwareExists(true)
        Shadow.extract<ShadowNfcAdapter>(NfcAdapter.getDefaultAdapter(app)).setEnabled(true)
        val controller = open()
        tap(ScheduleTags.LINK_SETTINGS)
        tap(SettingsTags.LINK_HANDSHAKES)
        tap(HandshakeTags.edit("builtin_steady"))
        tap(HandshakeTags.mode(0, HandshakeMode.NFC))
        tap(HandshakeTags.tags(0))
        waitFor(RegisterTokenTags.CONSENT)
        compose.waitForIdle()
        assertNull("opening the tags asks Android for nothing", shadowOf(controller.get()).lastRequestedPermission)

        compose.onNodeWithTag(RegisterTokenTags.CONSENT).performClick()

        compose.waitUntil(timeoutMillis = 30_000) { runBlocking { consents.isGranted(ConsentKey.NFC) } }
        compose.waitForIdle()
        assertNull("the tag consent asks Android for nothing: NFC is granted at install", shadowOf(controller.get()).lastRequestedPermission)
        assertTrue("and the camera consent is untouched", !runBlocking { consents.isGranted(ConsentKey.CAMERA_QR) })
    }

    @Test
    fun `turning the camera consent on asks Android for the camera, and opening the codes asks nothing`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>().packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        val controller = open()
        tap(ScheduleTags.LINK_SETTINGS)
        tap(SettingsTags.LINK_HANDSHAKES)
        tap(HandshakeTags.CODES)
        waitFor(RegisterTokenTags.CONSENT)
        compose.waitForIdle()
        assertNull("opening the codes asks Android for nothing", shadowOf(controller.get()).lastRequestedPermission)

        compose.onNodeWithTag(RegisterTokenTags.CONSENT).performClick()

        compose.waitUntil(timeoutMillis = 30_000) { shadowOf(controller.get()).lastRequestedPermission != null }
        assertEquals(listOf(Manifest.permission.CAMERA), shadowOf(controller.get()).lastRequestedPermission!!.requestedPermissions.toList())
    }
}
