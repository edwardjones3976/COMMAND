package com.command.reveille

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.domain.dials.Personality
import com.command.domain.handshake.TypedPhrase
import com.command.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The words the typing challenge asks for, as they are in the resources. The screens are tested with phrases of their own; this is where the
 * real ones are held to what the product says about them: a few plain words each, easy to type on any keyboard, six personalities with a
 * bank of their own, and nothing that says the person has started (typing one is an acknowledgement, and is recorded as exactly that).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhraseBanksTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val source = ResourcePhraseSource(context)

    private val bankOf = mapOf(
        Personality.DRILL_SERGEANT to R.array.handshake_phrases_drill_sergeant,
        Personality.EXECUTIVE_DIRECTOR to R.array.handshake_phrases_executive_director,
        Personality.SUPPORTIVE_COACH to R.array.handshake_phrases_supportive_coach,
        Personality.ACCOUNTABILITY_PARTNER to R.array.handshake_phrases_accountability_partner,
        Personality.MINIMALIST to R.array.handshake_phrases_minimalist,
        Personality.CUSTOM to R.array.handshake_phrases_custom,
    )

    @Test
    fun `every personality is mapped to the bank of its own name, and nothing is left out`() {
        assertEquals(Personality.values().toSet(), bankOf.keys)
        for ((personality, bank) in bankOf) {
            assertEquals("$personality", context.resources.getStringArray(bank).toList(), source.phrases(personality))
        }
    }

    @Test
    fun `each bank has four phrases that differ from one another, each one a few plain words that can be typed`() {
        for (personality in Personality.values()) {
            val phrases = source.phrases(personality)
            assertEquals("$personality: four to choose from", 4, phrases.size)
            assertEquals("$personality: all different, however they are typed", 4, phrases.map { TypedPhrase.normalize(it) }.toSet().size)
            for (phrase in phrases) {
                assertTrue("$personality: '$phrase' is only letters and spaces", phrase.all { it.isLetter() || it == ' ' })
                assertEquals("$personality: '$phrase' has no spare space", phrase.trim().replace(Regex(" +"), " "), phrase)
                assertTrue("$personality: '$phrase' is a few words", phrase.split(' ').size in 2..10)
                assertTrue("$personality: '$phrase' is right when it is typed as it stands", TypedPhrase.matches(phrase, phrase))
            }
        }
    }

    @Test
    fun `the banks are not the same bank under six names`() {
        val banks = Personality.values().map { source.phrases(it).map { phrase -> TypedPhrase.normalize(phrase) }.toSet() }
        assertEquals(banks.size, banks.toSet().size)
        for (a in Personality.values().indices) {
            for (b in a + 1 until Personality.values().size) {
                assertNotEquals("${Personality.values()[a]} and ${Personality.values()[b]}", banks[a], banks[b])
            }
        }
    }

    @Test
    fun `no phrase says that the person has started, because typing one only acknowledges the alarm`() {
        for (personality in Personality.values()) {
            for (phrase in source.phrases(personality)) {
                val words = phrase.lowercase().split(' ')
                assertFalse("$personality: '$phrase' claims a start", words.any { it.startsWith("start") || it.startsWith("begin") || it == "began" || it == "begun" })
            }
        }
    }

    @Test
    fun `the plain phrase that stands in for an empty bank is one that can be typed too`() {
        val fallback = context.getString(R.string.handshake_phrase_fallback)
        assertTrue(fallback.isNotBlank())
        assertTrue(fallback.all { it.isLetter() || it == ' ' })
        assertTrue(TypedPhrase.matches(fallback, fallback))
    }
}
