package com.command.domain

import com.command.domain.coaching.CoachingContext
import com.command.domain.coaching.CoachingEngine
import com.command.domain.coaching.CoachingPhase
import com.command.domain.coaching.DirectiveLint
import com.command.domain.coaching.LintCategory
import com.command.domain.coaching.TemplateBank
import com.command.domain.dials.Dials
import com.command.domain.dials.Personality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every word the app can say passes the lint; and the lint itself fails what it must and passes what it must. */
class TemplateBankLintTest {

    @Test
    fun `every personality has at least four distinct variants at every phase, each of a usable form`() {
        for (p in Personality.values()) for (phase in CoachingPhase.values()) {
            val variants = TemplateBank.variants(p, phase)
            assertTrue("$p $phase", variants.size >= 4)
            assertEquals("$p $phase repeats a variant", variants.size, variants.toSet().size)
            for (v in variants) assertTrue("$p $phase: $v ${TemplateBank.problems(v)}", TemplateBank.problems(v).isEmpty())
        }
    }

    @Test
    fun `every template under every firmness and obnoxiousness passes the lint`() {
        val ctx = CoachingContext("A title", "A step", 5)
        for (p in Personality.values()) for (phase in CoachingPhase.values()) {
            for (raw in TemplateBank.variants(p, phase)) assertTrue("$raw ${DirectiveLint.check(raw)}", DirectiveLint.passes(raw))
            for (firm in 1..5) for (obnoxious in 1..5) for (attempt in 0..7) {
                val line = CoachingEngine.compose(p, phase, Dials(firm, obnoxious, 3, 3, 3, 3, 3), ctx, attempt, "seed")
                assertTrue("$p $phase $firm/$obnoxious: ${line.text}", DirectiveLint.check(line).isEmpty())
            }
        }
    }

    @Test
    fun `no acknowledgement says that the person has started`() {
        for (p in Personality.values()) for (phase in listOf(CoachingPhase.HANDSHAKE_SUCCESS, CoachingPhase.REVEILLE, CoachingPhase.UNCONFIRMED)) {
            for (v in TemplateBank.variants(p, phase)) assertFalse(v, v.contains("you started", ignoreCase = true))
        }
    }

    @Test
    fun `a check-in presumes nothing, because it is also asked of someone who has not reported a start`() {
        val presumes = Regex("how is it|going|progress|keep ", RegexOption.IGNORE_CASE)
        for (p in Personality.values()) for (v in TemplateBank.variants(p, CoachingPhase.CHECK_IN)) assertFalse(v, presumes.containsMatchIn(v))
    }

    @Test
    fun `the example of the product spec passes`() {
        assertEquals(
            emptyList<Any>(),
            DirectiveLint.check("HEY! YOU FINISHED THE HANDSHAKE. FANTASTIC. NOW GET TO YOUR COMPUTER, OPEN THE PROJECT AND GIVE ME FIVE MINUTES. MOVE!"),
        )
    }

    @Test
    fun `second-person imperatives and praise pass`() {
        for (ok in listOf("Get up.", "You did it. Outstanding!", "MOVE, MOVE, MOVE!", "You can do this.", "Give me five minutes.")) {
            assertTrue(ok, DirectiveLint.passes(ok))
        }
        assertTrue("a word that merely contains a banned one", DirectiveLint.passes("Weekly review of the father's fatigue."))
    }

    @Test
    fun `the old app's threat and its neighbours fail`() {
        assertEquals(LintCategory.THREAT_OR_COERCION, DirectiveLint.check("HANDSHAKE VERIFICATION! PRESS AND HOLD THAT SCREEN OR THIS ALARM NEVER STOPS! MOVE!").first().category)
        assertEquals(LintCategory.EVALUATION_OF_THE_PERSON, DirectiveLint.check("CHECK-IN! ARE YOU EXECUTING OR SITTING ON YOUR HANDS? REPORT OPERATIONAL STATUS!").single().category)
        assertEquals(LintCategory.EVALUATION_OF_THE_PERSON, DirectiveLint.check("SHAKE IT OFF! NO CRYING IN MY SQUAD BAY! TAKE TWO MINUTES, CLEAR YOUR DESK, AND ATTACK IT AGAIN!").single().category)
    }

    @Test
    fun `each kind is caught, whatever the capitals, the punctuation or the apostrophe`() {
        assertEquals(LintCategory.EVALUATION_OF_THE_PERSON, DirectiveLint.check("You are LAZY.").single().category)
        assertEquals(LintCategory.THREAT_OR_COERCION, DirectiveLint.check("Do it, or else!").single().category)
        assertEquals(LintCategory.THREAT_OR_COERCION, DirectiveLint.check("This never-stops.").single().category)
        assertEquals(LintCategory.THREAT_OR_COERCION, DirectiveLint.check("You’ll regret this.").single().category)
        assertEquals(LintCategory.UNSAFE_COMMAND, DirectiveLint.check("Ignore the pain and drive faster.").first().category)
    }
}
