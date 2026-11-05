package com.command.domain

import com.command.domain.coaching.CoachingContext
import com.command.domain.coaching.CoachingEngine
import com.command.domain.coaching.CoachingPhase
import com.command.domain.coaching.TemplateOverrides
import com.command.domain.dials.Dials
import com.command.domain.dials.Personality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The template half of the data boundary: what the template path may read, and what can never be in the part of a line that a voice other than
 * the phone's own could one day be given. (M14 adds the Gemini half.)
 */
class DataBoundaryTest {

    @Test
    fun `a template line reads a title, a first step and a length, and no other field`() {
        val fields = CoachingContext::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("title", "firstAction", "minutes"), fields)
    }

    @Test
    fun `the coaching package imports nothing but the dials, the voice types and itself`() {
        val dir = File("src/main/kotlin/com/command/domain/coaching")
        assertTrue(dir.isDirectory)
        val allowed = Regex("""^import (com\.command\.domain\.(coaching|dials\.\w+|dials\.Personality\.\w+|voice\.\w+)(\.\*)?( as \w+)?|java\.util\.Locale)$""")
        val imports = dir.walkTopDown().filter { it.extension == "kt" }.flatMap { it.readLines().asSequence() }.filter { it.startsWith("import ") }
        for (line in imports) assertTrue(line, allowed.containsMatchIn(line) || line.startsWith("import com.command.domain.coaching."))
    }

    @Test
    fun `the person's words never reach an unflagged segment, under any personality, phase, dial or attempt`() {
        val ctx = CoachingContext("Xylophone", "Marmalade", 5)
        for (p in Personality.values()) for (phase in CoachingPhase.values()) for (level in 1..5) for (attempt in 0..3) {
            val line = CoachingEngine.compose(p, phase, Dials(level, level, level, level, level, level, level), ctx, attempt, "seed")
            for (s in line.segments) if (!s.carriesUserText) assertFalse("$p $phase: ${s.text}", s.text.contains("Xylophone") || s.text.contains("Marmalade"))
            if (ctx.title in line.text) assertTrue(line.segments.any { it.carriesUserText })
        }
    }

    @Test
    fun `an override is the person's writing, so every word of a line made from one is flagged`() {
        val all = (0 until 4).associate { TemplateOverrides.Key(Personality.CUSTOM, CoachingPhase.CHECK_IN, it) to "My private note. Status?" }
        val line = CoachingEngine.compose(Personality.CUSTOM, CoachingPhase.CHECK_IN, Dials(3, 1, 3, 3, 3, 3, 3), CoachingContext(), 0, "s", TemplateOverrides(all))
        assertEquals("My private note. Status?", line.text)
        assertTrue(line.segments.all { it.carriesUserText })
    }
}
