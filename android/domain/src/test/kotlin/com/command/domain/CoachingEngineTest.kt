package com.command.domain

import com.command.domain.coaching.CoachingContext
import com.command.domain.coaching.CoachingEngine
import com.command.domain.coaching.CoachingPhase
import com.command.domain.coaching.TemplateBank
import com.command.domain.coaching.TemplateOverrides
import com.command.domain.coaching.TemplateProblem
import com.command.domain.dials.Dial
import com.command.domain.dials.Dials
import com.command.domain.dials.Personality
import com.command.domain.voice.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachingEngineTest {
    private val quiet = Dials(3, 1, 3, 3, 3, 3, 3)
    private val ctx = CoachingContext("Write report", "Open file", 5)
    private fun compose(
        p: Personality = Personality.CUSTOM,
        phase: CoachingPhase = CoachingPhase.REVEILLE,
        dials: Dials = quiet,
        c: CoachingContext = ctx,
        attempt: Int = 0,
        seed: String = "occ-1",
        overrides: TemplateOverrides = TemplateOverrides(),
    ): Line = CoachingEngine.compose(p, phase, dials, c, attempt, seed, overrides)

    @Test
    fun `the same arguments give the same line, and the next attempt another`() {
        assertEquals(compose(), compose())
        val seen = (0 until 4).map { compose(attempt = it).text }
        assertEquals("four attempts walk the four variants", 4, seen.toSet().size)
        assertEquals("the walk comes round", compose(attempt = 0), compose(attempt = 4))
    }

    @Test
    fun `any seed and attempt lands on a variant, also where the hash is negative`() {
        for (seed in listOf("", "polygenelubricants", "zzzzzzzzzzzzz", "occ-2")) for (attempt in listOf(0, 1, Int.MAX_VALUE)) {
            assertFalse(compose(seed = seed, attempt = attempt).isEmpty)
        }
    }

    @Test
    fun `the person's words are in flagged segments only, in sentences of their own`() {
        for (p in Personality.values()) for (attempt in 0..3) {
            val line = compose(p = p, attempt = attempt, c = CoachingContext("Zebra", "Quokka", 5), dials = Dials(5, 5, 3, 3, 3, 3, 3))
            assertEquals(listOf("Zebra.", "Quokka."), line.segments.filter { it.carriesUserText }.map { it.text })
            val ours = line.segments.filterNot { it.carriesUserText }.joinToString(" ") { it.text }
            assertFalse(ours, ours.contains("Zebra") || ours.contains("Quokka"))
        }
    }

    @Test
    fun `what the person typed is never read as a placeholder, and a closing mark is not doubled`() {
        val line = compose(c = CoachingContext("{minutes}.", "Ready?", 5))
        assertEquals(listOf("{minutes}.", "Ready?"), line.segments.filter { it.carriesUserText }.map { it.text })
    }

    @Test
    fun `a sentence with nothing to say is left out`() {
        val bare = compose(c = CoachingContext())
        assertTrue(bare.segments.none { it.carriesUserText })
        assertFalse(bare.text, bare.text.contains("{") || bare.text.contains("First"))
        val direction = compose(phase = CoachingPhase.DIRECTION, c = CoachingContext("", "Open file", 0))
        assertFalse(direction.text, direction.text.contains("minute") || direction.text.contains("{"))
    }

    @Test
    fun `one minute is one minute`() {
        val text = compose(phase = CoachingPhase.STARTER_START, c = CoachingContext(minutes = 1)).text
        assertTrue(text, text.contains("1 minute") && !text.contains("1 minutes"))
    }

    @Test
    fun `minimalist never says more than twelve words, dropping noise first and the person's words last`() {
        val long = CoachingContext("one two three four five six seven eight nine ten eleven twelve thirteen fourteen", "aa bb cc dd ee", 25)
        for (phase in CoachingPhase.values()) for (firm in 1..5) for (obnoxious in 1..5) for (c in listOf(ctx, long)) for (attempt in 0..3) {
            val text = compose(Personality.MINIMALIST, phase, Dials(firm, obnoxious, 1, 1, 1, 1, 1), c, attempt).text
            assertTrue("$phase: $text", text.split(" ").size <= CoachingEngine.MINIMALIST_MAX_WORDS)
        }
        val cut = compose(Personality.MINIMALIST, dials = Dials(5, 5, 1, 1, 1, 1, 1), c = long)
        assertTrue("cut words stay flagged as the person's", cut.segments.any { it.carriesUserText && it.text.startsWith("one two") })
        val fits = compose(Personality.MINIMALIST, dials = quiet, c = CoachingContext("Report", "Open", 5))
        assertTrue("a line within the cap keeps its first step", fits.text.contains("Open."))
    }

    @Test
    fun `a line that is over the cap gives up its noise before its first step`() {
        val c = CoachingContext("a b c", "d e f g", 5)
        val text = compose(Personality.MINIMALIST, dials = Dials(1, 5, 1, 1, 1, 1, 1), c = c).text
        assertTrue(text, text.contains("d e f g."))
        assertEquals("all of the noise is gone", compose(Personality.MINIMALIST, dials = Dials(1, 1, 1, 1, 1, 1, 1), c = c).text, text)
    }

    @Test
    fun `an override replaces its variant and is flagged as the person's words`() {
        val all = (0 until 4).associate { TemplateOverrides.Key(Personality.CUSTOM, CoachingPhase.REVEILLE, it) to "My own words. {title}." }
        val line = compose(overrides = TemplateOverrides(all))
        assertEquals("My own words.", line.segments.first().text)
        assertTrue(line.segments.first().carriesUserText)
    }

    @Test
    fun `a stored override that fails the lint or its form is ignored`() {
        val base = compose()
        for (bad in listOf("Up, or else. Go.", "Do {title} today. Go.", "{title}.", "   ", "Go {nope}.")) {
            val all = (0 until 4).associate { TemplateOverrides.Key(Personality.CUSTOM, CoachingPhase.REVEILLE, it) to bad }
            assertEquals(bad, base, compose(overrides = TemplateOverrides(all)))
        }
    }

    @Test
    fun `the form of a template is checked`() {
        assertEquals(setOf(TemplateProblem.BLANK), TemplateBank.problems(" "))
        assertEquals(setOf(TemplateProblem.UNKNOWN_PLACEHOLDER), TemplateBank.problems("Go. {who}."))
        assertEquals(setOf(TemplateProblem.USER_TEXT_INSIDE_SENTENCE), TemplateBank.problems("Go. Do {title} now."))
        assertEquals(setOf(TemplateProblem.NOTHING_WITHOUT_MISSION_WORDS), TemplateBank.problems("{title}."))
        assertEquals(setOf(TemplateProblem.USER_TEXT_INSIDE_SENTENCE), TemplateBank.problems("Go. Do {minutes} on {title}."))
        assertEquals(emptySet<TemplateProblem>(), TemplateBank.problems("Go. First: {first_action}! {minutes} now."))
    }

    // --- the dials that change words ---

    @Test
    fun `firmness closes a directive line by its tier, and nothing else`() {
        val closes = (1..5).map { compose(dials = quiet.with(Dial.FIRMNESS, it)).text }
        assertEquals(5, closes.toSet().size)
        assertTrue(closes[0], closes[0].endsWith("When you are ready."))
        assertTrue(closes[4], closes[4].endsWith("Do it now."))
        assertEquals("a skip is not ordered about", compose(phase = CoachingPhase.SKIP_ACK, dials = quiet.with(Dial.FIRMNESS, 1)), compose(phase = CoachingPhase.SKIP_ACK, dials = quiet.with(Dial.FIRMNESS, 5)))
    }

    @Test
    fun `obnoxiousness adds interjections by density and repeats at most twice`() {
        fun count(level: Int): Int {
            val a = compose(dials = quiet.with(Dial.OBNOXIOUSNESS, level)).text.split(Regex("""(?<=[.!?:])\s+""")).size
            return a
        }
        val sizes = (1..5).map { count(it) }
        assertEquals("each step says more", sizes.sorted(), sizes)
        assertTrue(sizes[4] > sizes[0])
        assertEquals(sizes[0], compose(dials = quiet).text.split(Regex("""(?<=[.!?:])\s+""")).size)
        val text = compose(Personality.DRILL_SERGEANT, CoachingPhase.CHECK_IN, quiet.with(Dial.OBNOXIOUSNESS, 5)).text
        val most = Regex("""[^.!?]+[.!?]""").findAll(text).map { it.value.trim() }.groupingBy { it }.eachCount().values.max()
        assertTrue(text, most <= 3)
        assertEquals("the top setting repeats its last call twice", 3, most)
    }

    @Test
    fun `a recovery is not ordered about`() {
        for (phase in listOf(CoachingPhase.RECOVERY_RESET, CoachingPhase.RECOVERY_SMALLER)) {
            assertEquals("$phase", compose(phase = phase, dials = quiet.with(Dial.FIRMNESS, 1)), compose(phase = phase, dials = quiet.with(Dial.FIRMNESS, 5)))
        }
    }

    @Test
    fun `a person who is recovering, skipping or has gone quiet hears no noise`() {
        for (phase in listOf(CoachingPhase.RECOVERY_RESET, CoachingPhase.RECOVERY_SMALLER, CoachingPhase.RECOVERY_RESCHEDULE, CoachingPhase.SKIP_ACK, CoachingPhase.UNCONFIRMED, CoachingPhase.REPEATED_ACK_NO_START)) {
            assertEquals("$phase", compose(phase = phase, dials = quiet.with(Dial.OBNOXIOUSNESS, 1)), compose(phase = phase, dials = quiet.with(Dial.OBNOXIOUSNESS, 5)))
        }
    }

    @Test
    fun `the other five dials do not change a word`() {
        for (dial in listOf(Dial.ALERT_INTENSITY, Dial.FOLLOW_UP_PERSISTENCE, Dial.HANDSHAKE_DIFFICULTY, Dial.SCHEDULE_RIGIDITY, Dial.RECOVERY_STRUCTURE)) {
            assertEquals("$dial", compose(dials = quiet.with(dial, 1)), compose(dials = quiet.with(dial, 5)))
        }
        assertNotEquals(compose(seed = "a"), compose(p = Personality.DRILL_SERGEANT, seed = "a"))
    }
}
