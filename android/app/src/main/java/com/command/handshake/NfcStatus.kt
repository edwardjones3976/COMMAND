package com.command.handshake

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Whether this phone can read a tag now. */
enum class NfcAvailability {
    /** No NFC hardware, no adapter, or the phone would not say. */
    NONE,

    /** NFC is there but turned off. */
    OFF,

    /** Secure NFC is on and the keyguard is locked: tags are not read until the phone is unlocked. */
    SECURE_LOCKED,

    /** A tag touched now would be read. */
    READY,
}

/** The phone's NFC as the handshake needs it, behind a seam so that a test can say what the phone has. */
interface NfcStatus {
    fun availability(): NfcAvailability

    /** API 29+: Secure NFC is supported and on (tags are read only while unlocked). False below 29 or on any error. */
    fun secureNfcOn(): Boolean
}

/**
 * The phone as it is. Each platform call is guarded: a phone that throws is a phone that cannot read a tag (NONE), never a crash.
 *
 * The keyguard is read with `isKeyguardLocked`, not `isDeviceLocked`: it is the conservative one. With a swipe-only keyguard and Secure NFC on it
 * says SECURE_LOCKED where reading might have worked, which costs only a typed phrase; the opposite mistake would leave a reader that never fires.
 */
class AndroidNfcStatus @Inject constructor(@ApplicationContext private val context: Context) : NfcStatus {

    override fun availability(): NfcAvailability = runCatching {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)) return@runCatching NfcAvailability.NONE
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return@runCatching NfcAvailability.NONE
        when {
            !adapter.isEnabled -> NfcAvailability.OFF
            secureOn(adapter) && keyguardLocked() -> NfcAvailability.SECURE_LOCKED
            else -> NfcAvailability.READY
        }
    }.getOrDefault(NfcAvailability.NONE)

    override fun secureNfcOn(): Boolean = runCatching {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)) return@runCatching false
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return@runCatching false
        secureOn(adapter)
    }.getOrDefault(false)

    private fun secureOn(adapter: NfcAdapter): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && adapter.isSecureNfcSupported && adapter.isSecureNfcEnabled

    private fun keyguardLocked(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
}

/**
 * The phone's NFC, in a module of its own so that a test can put a fake in its place (`@UninstallModules(NfcStatusModule::class)` and a
 * `@BindValue`) without having to replace the other handshake bindings.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NfcStatusModule {
    @Binds
    abstract fun nfcStatus(impl: AndroidNfcStatus): NfcStatus
}
