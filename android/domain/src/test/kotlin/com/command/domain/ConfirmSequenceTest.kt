package com.command.domain

import com.command.domain.handshake.ConfirmSequence
import com.command.domain.handshake.ConfirmSequence.Press
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmSequenceTest {

    @Test
    fun `the numbers one to four stand in some order that is never the order`() {
        val seen = HashSet<List<Int>>()
        for (seed in 0 until 600) {
            for (attempt in 0 until 6) {
                val layout = ConfirmSequence.layout("alarm-$seed", attempt)
                assertEquals("alarm-$seed/$attempt", listOf(1, 2, 3, 4), layout.sorted())
                assertNotEquals("a row in order would make it a tap along the row", listOf(1, 2, 3, 4), layout)
                seen += layout
            }
        }
        assertEquals("every other arrangement is used", 23, seen.size)
    }

    @Test
    fun `turning the phone does not shuffle it, and a wrong tap does`() {
        for (seed in 0 until 200) {
            val id = "alarm-$seed"
            assertEquals(ConfirmSequence.layout(id, 0), ConfirmSequence.layout(id, 0))
            assertNotEquals("the answer cannot be learnt by position ($id)", ConfirmSequence.layout(id, 0), ConfirmSequence.layout(id, 1))
        }
    }

    @Test
    fun `a wrong tap moves at least three of the four numbers to another cell`() {
        for (seed in 0 until 300) {
            val id = "alarm-$seed"
            for (attempt in 0 until 8) {
                val a = ConfirmSequence.layout(id, attempt)
                val b = ConfirmSequence.layout(id, attempt + 1)
                val moved = a.indices.count { a[it] != b[it] }
                assertTrue("$id try $attempt: $a then $b", moved >= 3)
            }
        }
    }

    @Test
    fun `any seed gives a layout, whatever its hash`() {
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        assertEquals(listOf(1, 2, 3, 4), ConfirmSequence.layout("polygenelubricants", Int.MIN_VALUE).sorted())
        assertEquals(listOf(1, 2, 3, 4), ConfirmSequence.layout("", -7).sorted())
        assertEquals(ConfirmSequence.TARGETS, ConfirmSequence.layout("x").size)
    }

    @Test
    fun `the numbers are tapped in order, and anything else is wrong`() {
        assertEquals(Press.RIGHT, ConfirmSequence.press(tapped = 0, pressed = 1))
        assertEquals(Press.RIGHT, ConfirmSequence.press(tapped = 1, pressed = 2))
        assertEquals(Press.RIGHT, ConfirmSequence.press(tapped = 2, pressed = 3))
        assertEquals(Press.DONE, ConfirmSequence.press(tapped = 3, pressed = 4))

        assertEquals("skipping ahead", Press.WRONG, ConfirmSequence.press(tapped = 0, pressed = 2))
        assertEquals("the same one twice", Press.WRONG, ConfirmSequence.press(tapped = 1, pressed = 1))
        assertEquals("one that was done", Press.WRONG, ConfirmSequence.press(tapped = 2, pressed = 1))
        assertEquals("the last one too early", Press.WRONG, ConfirmSequence.press(tapped = 2, pressed = 4))
        assertEquals("nothing is left after the last", Press.WRONG, ConfirmSequence.press(tapped = 4, pressed = 5))
        assertEquals("the third before the first", Press.WRONG, ConfirmSequence.press(tapped = 0, pressed = 3))
        assertEquals(4, ConfirmSequence.TARGETS)
    }
}
