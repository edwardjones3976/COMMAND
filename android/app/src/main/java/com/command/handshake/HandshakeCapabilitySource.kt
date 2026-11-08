package com.command.handshake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.command.data.ConsentStore
import com.command.data.TokenRepository
import com.command.domain.dials.HandshakeMode
import com.command.domain.handshake.HandshakeCapability
import com.command.domain.model.ConsentKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * What this phone can ask for now, of the ways that need something of it. It is read when a ring is shown and whenever the ring screen comes
 * back to the front, never saved, and never decides whether a chain is valid.
 */
interface HandshakeCapabilitySource {
    suspend fun current(): HandshakeCapability
}

/**
 * The phone as it is. Each condition can change while the app is closed (or while the ring screen is away), so each is read every time.
 * - QR is ready when the person has given the camera consent, Android has given the camera permission, the phone has a camera, and at least
 *   one code is registered.
 * - NFC is ready when the person has given the tag consent, the phone's reader can read a tag now ([NfcStatus]: it has NFC, NFC is on, and it
 *   is not Secure NFC with the keyguard locked), and at least one tag is registered.
 * The two are independent: either, both or neither.
 */
class AndroidHandshakeCapabilitySource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consents: ConsentStore,
    private val tokens: TokenRepository,
    private val nfc: NfcStatus,
) : HandshakeCapabilitySource {
    override suspend fun current(): HandshakeCapability {
        val ready = buildSet {
            if (qrReady()) add(HandshakeMode.QR)
            if (nfcReady()) add(HandshakeMode.NFC)
        }
        return if (ready.isEmpty()) HandshakeCapability.NONE else HandshakeCapability(ready)
    }

    private suspend fun qrReady(): Boolean =
        consents.isGranted(ConsentKey.CAMERA_QR) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
            tokens.count(HandshakeMode.QR) > 0

    private suspend fun nfcReady(): Boolean =
        consents.isGranted(ConsentKey.NFC) &&
            nfc.availability() == NfcAvailability.READY &&
            tokens.count(HandshakeMode.NFC) > 0
}
