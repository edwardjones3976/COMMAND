package com.command.domain

import com.command.domain.handshake.TypedPhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TypedPhraseTest {

    private val phrase = "I am up and starting now"

    @Test
    fun `capital letters and the amount of space between words do not matter`() {
        assertTrue(TypedPhrase.matches("I am up and starting now", phrase))
        assertTrue(TypedPhrase.matches("i am up and starting now", phrase))
        assertTrue(TypedPhrase.matches("I AM UP AND STARTING NOW", phrase))
        assertTrue(TypedPhrase.matches("  i am   up and\tstarting\nnow  ", phrase))
        assertTrue("a keyboard may type a non-breaking space", TypedPhrase.matches("i am up and starting now", phrase))
        assertEquals("i am up and starting now", TypedPhrase.normalize("  I Am  Up And Starting   Now "))
    }

    @Test
    fun `every kind of space a keyboard can type is a space`() {
        for (space in listOf("\u2009", "\u202F", "\u3000", "\u2003", "\u00A0", "\t", "\r\n")) {
            assertTrue("U+" + space.map { Integer.toHexString(it.code) }, TypedPhrase.matches("i${space}am${space}up and starting now", phrase))
        }
        assertTrue(TypedPhrase.matches("\u3000i am up and starting now\u2009", phrase))
    }

    @Test
    fun `a part of the phrase is not the phrase`() {
        assertFalse(TypedPhrase.matches("i am up", phrase))
        assertFalse(TypedPhrase.matches("i am up and starting no", phrase))
        assertFalse(TypedPhrase.matches("am up and starting now", phrase))
        assertFalse(TypedPhrase.matches("i", phrase))
    }

    @Test
    fun `anything else is a wrong try`() {
        assertFalse("a wrong letter", TypedPhrase.matches("i am up and starting nov", phrase))
        assertFalse("a missing word", TypedPhrase.matches("i am up starting now", phrase))
        assertFalse("an extra word", TypedPhrase.matches("i am up and starting now please", phrase))
        assertFalse("punctuation is part of what is typed", TypedPhrase.matches("i am up and starting now.", phrase))
        assertFalse("a different order", TypedPhrase.matches("starting now i am up and", phrase))
        assertFalse("letters run together", TypedPhrase.matches("iamupandstartingnow", phrase))
    }

    @Test
    fun `nothing is never right`() {
        assertFalse(TypedPhrase.matches("", phrase))
        assertFalse(TypedPhrase.matches("   \n ", phrase))
        assertFalse("not even for a phrase with nothing in it", TypedPhrase.matches("", ""))
        assertFalse(TypedPhrase.matches("   ", "  "))
    }

    @Test
    fun `capitals of other alphabets are read the same way`() {
        assertTrue(TypedPhrase.matches("ÉCOUTE LE RÉVEIL", "écoute le réveil"))
        assertTrue(TypedPhrase.matches("Привет, ВСТАЮ", "привет, встаю"))
        assertFalse("an accent is a letter", TypedPhrase.matches("ecoute le reveil", "écoute le réveil"))
    }

    @Test
    fun `the same seed asks for the same phrase, and the seeds between them ask for every phrase`() {
        val bank = listOf("one", "two", "three", "four", "five")
        val picked = (0 until 400).map { TypedPhrase.pick(bank, "occurrence-$it") }
        assertEquals(bank.toSet(), picked.toSet())
        for (i in 0 until 50) assertEquals(TypedPhrase.pick(bank, "occurrence-$i"), TypedPhrase.pick(bank, "occurrence-$i"))
        assertEquals("a bank of one has one answer", "only", TypedPhrase.pick(listOf("only"), "anything"))
    }

    @Test
    fun `a seed whose hash is the lowest number there is picks a phrase like any other`() {
        // "polygenelubricants".hashCode() == Int.MIN_VALUE, the one value whose absolute value is still negative.
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        val bank = listOf("a", "b", "c")
        assertTrue(TypedPhrase.pick(bank, "polygenelubricants") in bank)
    }

    @Test
    fun `there has to be something to pick from`() {
        try {
            TypedPhrase.pick(emptyList(), "x")
            fail("an empty bank must be refused")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
