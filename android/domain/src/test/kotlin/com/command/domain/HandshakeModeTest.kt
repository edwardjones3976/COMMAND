package com.command.domain

import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.HandshakeMode.NFC
import com.command.domain.dials.HandshakeMode.QR
import com.command.domain.dials.HandshakeMode.TYPING
import com.command.domain.dials.HandshakeMode.VOICE
import com.command.domain.handshake.HandshakeCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M13b: NFC is built and needs the phone, and stays planned until the hardware sign-off; voice is unchanged. */
class HandshakeModeTest {

    @Test
    fun nfcBuiltNeedsDeviceStillPlannedOffersItself() {
        assertTrue(NFC.built)
        assertTrue(NFC.needsDevice)
        assertTrue("labelled planned until DC2", NFC.planned)
        assertEquals(NFC, NFC.offered)
        assertEquals(TYPING, NFC.fallback)
        assertEquals(setOf(QR, NFC), HandshakeMode.values().filter { it.needsDevice }.toSet())
    }

    @Test
    fun voiceUnchanged() {
        assertFalse(VOICE.built)
        assertFalse(VOICE.needsDevice)
        assertTrue(VOICE.planned)
        assertEquals(TYPING, VOICE.fallback)
        assertEquals(TYPING, VOICE.offered)
    }

    @Test
    fun capabilityAllHoldsQrAndNfc() {
        assertEquals(HandshakeCapability(setOf(QR, NFC)), HandshakeCapability.ALL)
        assertTrue(HandshakeCapability.ALL.canRun(NFC))
        assertFalse(HandshakeCapability.NONE.canRun(NFC))
        assertTrue(HandshakeCapability(setOf(QR)).canRun(QR))
        assertFalse(HandshakeCapability(setOf(QR)).canRun(NFC))
        assertTrue("voice needs nothing of the phone, since it runs as typing", HandshakeCapability.NONE.canRun(VOICE))
    }
}
