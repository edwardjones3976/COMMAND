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
 * The phone as it is: QR is ready when the person has given the camera consent, Android has given the camera permission, the phone has a
 * camera, and at least one code is registered. Each of them can change while the app is closed, so each is read every time.
 */
class AndroidHandshakeCapabilitySource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consents: ConsentStore,
    private val tokens: TokenRepository,
) : HandshakeCapabilitySource {
    override suspend fun current(): HandshakeCapability {
        val qr = consents.isGranted(ConsentKey.CAMERA_QR) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
            tokens.count(HandshakeMode.QR) > 0
        return if (qr) HandshakeCapability(setOf(HandshakeMode.QR)) else HandshakeCapability.NONE
    }
}
