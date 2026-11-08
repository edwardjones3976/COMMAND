package com.command.domain.handshake

import com.command.domain.dials.HandshakeMode

/**
 * What this phone can ask for right now, of the modes that need something of it ([HandshakeMode.needsDevice]): [ready] holds those it can.
 * A mode that needs nothing of the phone can always be run. It is read when a ring starts or is restored, and again when it may have
 * changed (the ring screen comes back to the front; a camera that fails is reported as [ChainEvent.Unavailable] instead); it is never saved and never decides whether a chain is valid.
 */
data class HandshakeCapability(val ready: Set<HandshakeMode>) {
    /** True when [mode] can be asked for on this phone now. */
    fun canRun(mode: HandshakeMode): Boolean = !mode.needsDevice || mode in ready

    companion object {
        /** A phone that can do none of the modes that need something of it. */
        val NONE = HandshakeCapability(emptySet())

        /** A phone that can do every mode. */
        val ALL = HandshakeCapability(HandshakeMode.values().filter { it.needsDevice }.toSet())
    }
}
