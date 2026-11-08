package com.command.data

import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.DialEffects
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.StepSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChainJsonTest {

    @Test
    fun `every default chain survives being stored and read back, step by step`() {
        for (difficulty in 1..5) {
            val chain = DialEffects.defaultChain(difficulty)
            assertEquals("difficulty $difficulty", chain, ChainJson.decode(ChainJson.encode(chain)))
        }
    }

    @Test
    fun `a chain of the person's own keeps its way of combining, its order, tries, times and hold`() {
        val chain = ChainSpec(
            ChainOp.SEQUENCE,
            listOf(
                StepSpec(HandshakeMode.HOLD, maxRetries = 2, timeoutSec = 45, holdSeconds = 6),
                StepSpec(HandshakeMode.CONFIRM_SEQUENCE, maxRetries = 9, timeoutSec = 300),
                StepSpec(HandshakeMode.TWO_STEP, maxRetries = 2, timeoutSec = 10),
            ),
        )
        assertEquals(chain, ChainJson.decode(ChainJson.encode(chain)))
    }

    @Test
    fun `nothing stored, or nothing readable, is no chain and never an exception`() {
        for (text in listOf(null, "", "   ", "not json", "{", "[]", "null", "{\"op\":\"OR\"}", "{\"steps\":[]}", "\u0000", "{\"op\":1,\"steps\":2}")) {
            assertNull("[$text]", ChainJson.decode(text))
        }
    }

    @Test
    fun `a way or a combination this version does not know is no chain, so that a newer version's profile falls back to the default`() {
        val valid = "{\"op\":\"OR\",\"steps\":[{\"mode\":\"TYPING\",\"tries\":3,\"timeoutSeconds\":90}]}"
        assertNotNull(ChainJson.decode(valid))
        assertNull(ChainJson.decode(valid.replace("TYPING", "RETINA_SCAN")))
        assertNull(ChainJson.decode(valid.replace("\"OR\"", "\"XOR\"")))
    }

    @Test
    fun `a chain that breaks a limit is no chain`() {
        fun chain(step: String) = "{\"op\":\"OR\",\"steps\":[$step]}"
        assertNull("no steps", ChainJson.decode(chain("")))
        assertNull("no tries", ChainJson.decode(chain("{\"mode\":\"TYPING\",\"tries\":0,\"timeoutSeconds\":90}")))
        assertNull("too many tries", ChainJson.decode(chain("{\"mode\":\"TYPING\",\"tries\":10,\"timeoutSeconds\":90}")))
        assertNull("too short a time", ChainJson.decode(chain("{\"mode\":\"TYPING\",\"tries\":3,\"timeoutSeconds\":9}")))
        assertNull("a press too long", ChainJson.decode(chain("{\"mode\":\"HOLD\",\"tries\":3,\"timeoutSeconds\":90,\"holdSeconds\":11}")))
        assertNull("the same way twice", ChainJson.decode(chain("{\"mode\":\"TYPING\",\"tries\":3,\"timeoutSeconds\":90},{\"mode\":\"TYPING\",\"tries\":3,\"timeoutSeconds\":90}")))
    }

    @Test
    fun `one way this version does not know in a chain of several makes it no chain, and is not left out to leave a weaker one`() {
        val text = "{\"op\":\"AND\",\"steps\":[{\"mode\":\"HOLD\",\"tries\":3,\"timeoutSeconds\":90,\"holdSeconds\":3},{\"mode\":\"RETINA_SCAN\",\"tries\":3,\"timeoutSeconds\":90}]}"
        assertNull("the hold alone would be a weaker chain than the person's", ChainJson.decode(text))
        assertNotNull("and with a way that is known it reads", ChainJson.decode(text.replace("RETINA_SCAN", "TYPING")))
    }

    @Test
    fun `a way that is only planned is a way, it is stored and read back, and the ring runs its fallback`() {
        for (planned in listOf(HandshakeMode.QR, HandshakeMode.NFC, HandshakeMode.VOICE)) {
            val chain = ChainSpec(ChainOp.OR, listOf(StepSpec(planned, 3, 90)))
            assertEquals("$planned", chain, ChainJson.decode(ChainJson.encode(chain)))
        }
    }

    @Test
    fun `a way that is not built and the way that takes its place are the same way twice`() {
        val text = "{\"op\":\"OR\",\"steps\":[{\"mode\":\"NFC\",\"tries\":3,\"timeoutSeconds\":90},{\"mode\":\"TYPING\",\"tries\":3,\"timeoutSeconds\":90}]}"
        assertNull(ChainJson.decode(text))
        assertNotNull(ChainJson.decode(text.replace("TYPING", "HOLD")))
    }

    @Test
    fun `QR is built, so QR and typing are two ways and read back the same on every phone`() {
        val text = "{\"op\":\"OR\",\"steps\":[{\"mode\":\"QR\",\"tries\":3,\"timeoutSeconds\":90},{\"mode\":\"TYPING\",\"tries\":3,\"timeoutSeconds\":90}]}"
        val chain = ChainSpec(ChainOp.OR, listOf(StepSpec(HandshakeMode.QR, 3, 90), StepSpec(HandshakeMode.TYPING, 3, 90)))
        assertEquals(chain, ChainJson.decode(text))
        assertEquals(chain, ChainJson.decode(ChainJson.encode(chain)))
    }

    @Test
    fun `values of the wrong kind are no chain and never an exception`() {
        val texts = listOf(
            "{\"op\":\"OR\",\"steps\":[null]}",
            "{\"op\":\"OR\",\"steps\":[{\"mode\":3,\"tries\":3,\"timeoutSeconds\":90}]}",
            "{\"op\":\"OR\",\"steps\":[{\"mode\":\"HOLD\",\"tries\":\"three\",\"timeoutSeconds\":90}]}",
            "{\"op\":\"OR\",\"steps\":[{\"mode\":\"HOLD\",\"tries\":3.5,\"timeoutSeconds\":90}]}",
            "{\"op\":null,\"steps\":[{\"mode\":\"HOLD\",\"tries\":3,\"timeoutSeconds\":90}]}",
            "{\"op\":\"OR\",\"steps\":{\"mode\":\"HOLD\"}}",
            "{\"op\":\"OR\",\"steps\":[{\"mode\":\"HOLD\",\"tries\":3}]}",
            "[{\"mode\":\"HOLD\"}]",
            "{\"op\":\"OR\",\"steps\":[{\"mode\":\"HOLD\",\"tries\":3,\"timeoutSeconds\":90,\"holdSeconds\":\"long\"}]}",
        )
        for (text in texts) assertNull("[$text]", ChainJson.decode(text))
    }

    @Test
    fun `the limits are kept at their plain numbers when a chain is read, 2 to 9 tries, 10 to 300 seconds, a press of 0 or 1 to 10`() {
        fun chain(tries: Int, time: Int, hold: Int) =
            "{\"op\":\"OR\",\"steps\":[{\"mode\":\"HOLD\",\"tries\":$tries,\"timeoutSeconds\":$time,\"holdSeconds\":$hold}]}"
        for ((tries, time, hold) in listOf(Triple(2, 10, 0), Triple(9, 300, 1), Triple(5, 90, 10))) {
            assertNotNull("$tries tries, $time s, a press of $hold s", ChainJson.decode(chain(tries, time, hold)))
        }
        for ((tries, time, hold) in listOf(Triple(1, 90, 3), Triple(10, 90, 3), Triple(3, 9, 3), Triple(3, 301, 3), Triple(3, 90, 11), Triple(3, 90, -1))) {
            assertNull("$tries tries, $time s, a press of $hold s", ChainJson.decode(chain(tries, time, hold)))
        }
    }

    @Test
    fun `a field a later version adds is ignored`() {
        val text = "{\"op\":\"AND\",\"extra\":true,\"steps\":[{\"mode\":\"HOLD\",\"tries\":4,\"timeoutSeconds\":90,\"holdSeconds\":3,\"colour\":\"red\"}]}"
        assertEquals(ChainSpec(ChainOp.AND, listOf(StepSpec(HandshakeMode.HOLD, 4, 90, 3))), ChainJson.decode(text))
    }
}
