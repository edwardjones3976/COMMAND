package com.command.reveille

import com.command.domain.handshake.HandshakeCapability
import com.command.handshake.HandshakeCapabilitySource

/** The phone of a test: it can do what [now] says (nothing, unless a test says otherwise), and counts how often it was asked. */
internal class FixedCapability(@Volatile var now: HandshakeCapability = HandshakeCapability.NONE) : HandshakeCapabilitySource {
    @Volatile var reads = 0

    override suspend fun current(): HandshakeCapability {
        reads++
        return now
    }
}
