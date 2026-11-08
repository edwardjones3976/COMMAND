package com.command.handshake

/** The phone's NFC as a test says it is: ready unless [now] says otherwise. Counts how often it was read. */
class FakeNfcStatus(
    @Volatile var now: NfcAvailability = NfcAvailability.READY,
    @Volatile var secure: Boolean = false,
) : NfcStatus {
    @Volatile var reads = 0

    override fun availability(): NfcAvailability {
        reads++
        return now
    }

    override fun secureNfcOn(): Boolean = secure
}
