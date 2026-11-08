package com.command.handshake

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import androidx.test.core.app.ApplicationProvider
import com.command.data.ConsentStore
import com.command.data.RegisterResult
import com.command.data.TokenRepository
import com.command.domain.dials.HandshakeMode
import com.command.domain.handshake.HandshakeCapability
import com.command.domain.handshake.TagRead
import com.command.domain.model.ConsentKey
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNfcAdapter
import javax.inject.Inject

/**
 * The phone as the ring reads it: QR is ready only with the consent, the permission, a camera and a registered code, all four at once; NFC
 * only with its consent, a reader that is ready now ([NfcStatus]) and a registered tag, all three at once. The two never depend on each other.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class HandshakeCapabilitySourceTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var source: HandshakeCapabilitySource
    @Inject lateinit var consents: ConsentStore
    @Inject lateinit var tokens: TokenRepository
    @Inject lateinit var boundNfc: NfcStatus

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        hilt.inject()
    }

    private fun ready() = runBlocking { source.current() }

    private fun everything() = runBlocking {
        consents.grant(ConsentKey.CAMERA_QR)
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        check(tokens.register(HandshakeMode.QR, "Kitchen", "command-qr:one") is RegisterResult.Saved)
    }

    @Test
    fun `the app binds the phone's own reading`() {
        assertEquals(AndroidHandshakeCapabilitySource::class.java, source::class.java)
    }

    @Test
    fun `with the consent, the permission, a camera and a code, QR is ready`() {
        everything()
        assertEquals(HandshakeCapability(setOf(HandshakeMode.QR)), ready())
    }

    @Test
    fun `without the camera consent it is not`() {
        everything()
        runBlocking { consents.revoke(ConsentKey.CAMERA_QR) }
        assertEquals(HandshakeCapability.NONE, ready())
    }

    @Test
    fun `without the camera permission it is not`() {
        everything()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        assertEquals(HandshakeCapability.NONE, ready())
    }

    @Test
    fun `on a phone with no camera it is not`() {
        everything()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)
        assertEquals(HandshakeCapability.NONE, ready())
    }

    @Test
    fun `with no code registered it is not`() {
        everything()
        runBlocking { tokens.observeOnce().forEach { tokens.delete(it.id) } }
        assertEquals(HandshakeCapability.NONE, ready())
    }

    private suspend fun TokenRepository.observeOnce() = observe(HandshakeMode.QR).first()

    // --- NFC ---------------------------------------------------------------------------------------------------

    private val nfc = FakeNfcStatus()

    /** The phone's own reading, with NFC as [nfc] says. */
    private fun withNfc() = AndroidHandshakeCapabilitySource(app, consents, tokens, nfc)

    private fun nfcReady(source: AndroidHandshakeCapabilitySource = withNfc()) = runBlocking { source.current() }

    private fun everythingNfc() = runBlocking {
        consents.grant(ConsentKey.NFC)
        nfc.now = NfcAvailability.READY
        val seen = TagRead.of(byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)) as TagRead.Seen
        check(tokens.registerTag("Bathroom", seen.hash) is RegisterResult.Saved)
    }

    @Test
    fun nfcReadyWhenConsentReadyAndTag() {
        everythingNfc()
        assertEquals(HandshakeCapability(setOf(HandshakeMode.NFC)), nfcReady())
        assertTrue("the reader was asked", nfc.reads > 0)
    }

    @Test
    fun eachNfcConditionMissingRemovesNfc() {
        val missing: List<Pair<String, () -> Unit>> = listOf(
            "no consent" to { runBlocking { consents.revoke(ConsentKey.NFC) } },
            "no NFC on the phone" to { nfc.now = NfcAvailability.NONE },
            "NFC turned off" to { nfc.now = NfcAvailability.OFF },
            "Secure NFC with the keyguard locked" to { nfc.now = NfcAvailability.SECURE_LOCKED },
            "no tag registered" to { runBlocking { tokens.observe(HandshakeMode.NFC).first().forEach { tokens.delete(it.id) } } },
        )
        for ((why, take) in missing) {
            everythingNfc()
            take()
            assertEquals(why, HandshakeCapability.NONE, nfcReady())
            // Put back what was taken, and clear the tags so that the next row starts from nothing.
            runBlocking { tokens.observe(HandshakeMode.NFC).first().forEach { tokens.delete(it.id) } }
        }
    }

    @Test
    fun qrAndNfcIndependent() {
        everything()
        nfc.now = NfcAvailability.NONE
        assertEquals("a code alone is QR alone", HandshakeCapability(setOf(HandshakeMode.QR)), nfcReady())

        runBlocking { consents.revoke(ConsentKey.CAMERA_QR) }
        everythingNfc()
        assertEquals("a tag alone is NFC alone", HandshakeCapability(setOf(HandshakeMode.NFC)), nfcReady())
    }

    @Test
    fun bothReadyGivesBoth() {
        everything()
        everythingNfc()
        assertEquals(HandshakeCapability(setOf(HandshakeMode.QR, HandshakeMode.NFC)), nfcReady())
        assertEquals(HandshakeCapability.ALL, nfcReady())
    }

    @Test
    fun `the app binds the phone's own NFC reading, which adds nothing without NFC and adds NFC with it on`() {
        everythingNfc()
        assertEquals(AndroidNfcStatus::class.java, boundNfc::class.java)
        assertEquals("Robolectric's phone has no NFC feature", HandshakeCapability.NONE, ready())

        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        ShadowNfcAdapter.setNfcHardwareExists(true)
        Shadow.extract<ShadowNfcAdapter>(NfcAdapter.getDefaultAdapter(app)).setEnabled(true)
        assertEquals("the bound source reads the phone's own NFC", HandshakeCapability(setOf(HandshakeMode.NFC)), ready())
    }
}
