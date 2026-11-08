package com.command.handshake

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.command.data.ConsentStore
import com.command.data.RegisterResult
import com.command.data.TokenRepository
import com.command.domain.dials.HandshakeMode
import com.command.domain.handshake.HandshakeCapability
import com.command.domain.model.ConsentKey
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The phone as the ring reads it: QR is ready only with the consent, the permission, a camera and a registered code, all four at once. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class HandshakeCapabilitySourceTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var source: HandshakeCapabilitySource
    @Inject lateinit var consents: ConsentStore
    @Inject lateinit var tokens: TokenRepository

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
}
