package com.command.handshake

import android.app.Application
import android.app.KeyguardManager
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNfcAdapter
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** When the phone can read a tag now, through Robolectric's NFC adapter and keyguard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidNfcStatusTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val status get() = AndroidNfcStatus(app)

    /** A phone with NFC hardware and the feature, turned on or off. */
    private fun phone(on: Boolean = true): NfcAdapter {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        ShadowNfcAdapter.setNfcHardwareExists(true)
        val adapter = NfcAdapter.getDefaultAdapter(app)
        Shadow.extract<ShadowNfcAdapter>(adapter).setEnabled(on)
        return adapter
    }

    private fun secure(adapter: NfcAdapter, supported: Boolean = true, enabled: Boolean = true) {
        Shadow.extract<ShadowNfcAdapter>(adapter).setSecureNfcSupported(supported)
        if (supported) ReflectionHelpers.callInstanceMethod<Any>(adapter, "enableSecureNfc", ClassParameter.from(Boolean::class.javaPrimitiveType, enabled))
    }

    private fun locked(locked: Boolean) = shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(locked)

    @Test
    fun noFeatureNone() {
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, false)
        assertEquals(NfcAvailability.NONE, status.availability())
        assertFalse(status.secureNfcOn())
    }

    @Test
    fun noHardwareNone() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        ShadowNfcAdapter.setNfcHardwareExists(false)
        assertEquals(NfcAvailability.NONE, status.availability())
        assertFalse(status.secureNfcOn())
    }

    @Test
    fun offOff() {
        phone(on = false)
        assertEquals(NfcAvailability.OFF, status.availability())
    }

    @Test
    fun onAndUnlockedReady() {
        phone()
        locked(false)
        assertEquals(NfcAvailability.READY, status.availability())
    }

    @Test
    fun secureEnabledLockedSecureLocked() {
        secure(phone())
        locked(true)
        assertEquals(NfcAvailability.SECURE_LOCKED, status.availability())
    }

    @Test
    fun secureEnabledUnlockedReady() {
        secure(phone())
        locked(false)
        assertEquals(NfcAvailability.READY, status.availability())
    }

    @Test
    fun secureSupportedNotEnabledLockedReady() {
        secure(phone(), enabled = false)
        locked(true)
        assertEquals(NfcAvailability.READY, status.availability())
    }

    @Test
    fun secureNotSupportedLockedReady() {
        secure(phone(), supported = false)
        locked(true)
        assertEquals(NfcAvailability.READY, status.availability())
    }

    @Test
    @Config(sdk = [28])
    fun sdk28IgnoresSecure() {
        phone()
        locked(true)
        assertEquals("Secure NFC does not exist before API 29: a locked phone still reads", NfcAvailability.READY, status.availability())
        assertFalse(status.secureNfcOn())
    }

    @Test
    fun adapterThrowsNone() {
        val broken = object : ContextWrapper(app) {
            override fun getPackageManager(): PackageManager = throw SecurityException("no")
        }
        assertEquals(NfcAvailability.NONE, AndroidNfcStatus(broken).availability())
        assertFalse(AndroidNfcStatus(broken).secureNfcOn())
    }

    @Test
    fun secureNfcOnReflectsState() {
        val adapter = phone()
        assertFalse(status.secureNfcOn())
        secure(adapter)
        assertTrue(status.secureNfcOn())
        secure(adapter, enabled = false)
        assertFalse(status.secureNfcOn())
    }
}
