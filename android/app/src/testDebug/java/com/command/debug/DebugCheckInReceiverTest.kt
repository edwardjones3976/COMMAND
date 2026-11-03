package com.command.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/** The debug receiver's default question is the next one of the current session. Compiled for the debug build only. */
class DebugCheckInReceiverTest {

    @Test
    fun `the next question is the number asked in the current session`() {
        assertEquals(0, DebugCheckInReceiver.askedSoFar("{}"))
        assertEquals(0, DebugCheckInReceiver.askedSoFar(""))
        assertEquals(2, DebugCheckInReceiver.askedSoFar("{\"recoveryOffers\":1,\"checkInsAsked\":2,\"phase\":null}"))
    }

    @Test
    fun `after a reset the session starts again at the first question, whatever the table holds`() {
        assertEquals(0, DebugCheckInReceiver.askedSoFar("{\"recoveryOffers\":1,\"checkInsAsked\":0}"))
    }
}
