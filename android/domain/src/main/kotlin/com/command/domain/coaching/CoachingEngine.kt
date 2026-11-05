package com.command.domain.coaching

import com.command.domain.dials.DialEffects
import com.command.domain.dials.Dials
import com.command.domain.dials.ImperativeTier
import com.command.domain.dials.Personality
import com.command.domain.voice.Line
import com.command.domain.voice.Segment

/**
 * All a template line may read about a mission: its title, its first step and the starter's length. This is the whole of the template path's
 * view of the person; history, check-ins, calendar text and identity have no way in.
 */
data class CoachingContext(val title: String = "", val firstAction: String = "", val minutes: Int = 0)

/**
 * The templates a person wrote in place of the bank's. They are the person's words: a line built from one is flagged as carrying user text.
 * An entry that fails the lint or has a bad form (one that came in by another way than the checked save) is ignored and the bank's words are used.
 */
class TemplateOverrides(private val texts: Map<Key, String> = emptyMap()) {
    data class Key(val personality: Personality, val phase: CoachingPhase, val variant: Int)

    internal fun usable(personality: Personality, phase: CoachingPhase, variant: Int): String? =
        texts[Key(personality, phase, variant)]?.takeIf { TemplateBank.problems(it).isEmpty() && DirectiveLint.passes(it) }
}

/** Puts the words of a line together. Pure: the same arguments always give the same line. */
object CoachingEngine {
    const val MINIMALIST_MAX_WORDS = 12

    private enum class Kind { NOISE, CLOSER, FIRST_ACTION, BODY }
    private class Part(val text: String, val user: Boolean, val kind: Kind, val closesSentence: Boolean = true)

    /** Lines a person acts on; the firmness dial sets how they close. A question, a piece of news or a recovery has nothing to be firm about. */
    private val DIRECTIVE = setOf(
        CoachingPhase.REVEILLE, CoachingPhase.HANDSHAKE_PROMPT, CoachingPhase.DIRECTION, CoachingPhase.STARTER_START,
    )

    /** Noise belongs to getting someone moving. A person who is recovering, skipping or has gone quiet hears none, at any setting. */
    private val NOISY = setOf(
        CoachingPhase.REVEILLE, CoachingPhase.HANDSHAKE_PROMPT, CoachingPhase.HANDSHAKE_SUCCESS, CoachingPhase.DIRECTION,
        CoachingPhase.STARTER_START, CoachingPhase.STARTER_MIDPOINT, CoachingPhase.CHECK_IN,
    )

    fun compose(
        personality: Personality,
        phase: CoachingPhase,
        dials: Dials,
        ctx: CoachingContext,
        attempt: Int,
        seed: String,
        overrides: TemplateOverrides = TemplateOverrides(),
    ): Line {
        val variants = TemplateBank.variants(personality, phase)
        val pick = pick(seed, attempt)
        val variant = Math.floorMod(pick, variants.size)
        val override = overrides.usable(personality, phase, variant)
        val body = parts(override ?: variants[variant], ctx, personalWords = override != null)

        val parts = ArrayList<Part>()
        val noise = DialEffects.interjections(dials.obnoxiousness)
        val shouts = if (phase in NOISY) interjections(personality) else emptyList()
        if (shouts.isEmpty() || noise.density == 0) {
            parts += body
        } else {
            // One at the front, then one after each sentence the template has (never inside the person's words), as many as the density allows.
            val said = (0 until noise.density).map { shouts[Math.floorMod(pick + it, shouts.size)] }
            parts += Part(said[0], false, Kind.NOISE)
            var next = 1
            for (part in body) {
                parts += part
                if (next < said.size && part.closesSentence) parts += Part(said[next++], false, Kind.NOISE)
            }
            // Repetition is of the app's last call, at most DialEffects' cap of two extra sayings, and never of the person's words.
            repeat(noise.maxRepeats) { parts += Part(said[next - 1], false, Kind.NOISE) }
        }
        if (phase in DIRECTIVE) parts += Part(closer(personality, DialEffects.imperativeTier(dials.firmness)), false, Kind.CLOSER)
        return line(if (personality == Personality.MINIMALIST) capped(parts) else parts)
    }

    /** Different occurrences start at different variants, and each further attempt moves on by one, so a repeat is never the same words. */
    private fun pick(seed: String, attempt: Int): Int = seed.hashCode() * 31 + attempt

    private fun parts(template: String, ctx: CoachingContext, personalWords: Boolean): List<Part> {
        val out = ArrayList<Part>()
        for (sentence in TemplateBank.sentences(template)) {
            val placeholder = TemplateBank.PLACEHOLDER.find(sentence)
            val name = placeholder?.groupValues?.get(1)
            when (name) {
                null -> out += Part(sentence, personalWords, Kind.BODY)
                TemplateBank.MINUTES -> if (ctx.minutes > 0) {
                    val said = if (ctx.minutes == 1) "1 minute" else "${ctx.minutes} minutes"
                    out += Part(sentence.replace(placeholder.value, said), personalWords, Kind.BODY)
                }
                else -> {
                    val value = spoken(if (name == TemplateBank.TITLE) ctx.title else ctx.firstAction)
                    if (value.isNotEmpty()) {
                        val kind = if (name == TemplateBank.FIRST_ACTION) Kind.FIRST_ACTION else Kind.BODY
                        val before = sentence.substring(0, placeholder.range.first).trim()
                        val mark = sentence.substring(placeholder.range.last + 1).trim()
                        if (before.isNotEmpty()) out += Part(before, personalWords, kind, closesSentence = false)
                        out += Part(if (value.last() in "?!") value else value + mark, true, kind)
                    }
                }
            }
        }
        return out
    }

    /** The person's words, trimmed and without a closing full stop, because the sentence they are put in has its own. */
    private fun spoken(text: String): String = text.trim().trimEnd('.').trim()

    /** Within the cap: the noise goes first, then the closer, then the first step; if the rest is still too long it is cut at the cap. */
    private fun capped(parts: List<Part>): List<Part> {
        var kept = parts
        for (kind in listOf(Kind.NOISE, Kind.CLOSER, Kind.FIRST_ACTION)) {
            if (kept.sumOf { words(it.text).size } <= MINIMALIST_MAX_WORDS) return kept
            kept = kept.filter { it.kind != kind }
        }
        val out = ArrayList<Part>()
        var left = MINIMALIST_MAX_WORDS
        for (part in kept) {
            if (left <= 0) break
            val w = words(part.text)
            out += if (w.size <= left) part else Part(w.take(left).joinToString(" "), part.user, part.kind)
            left -= w.size
        }
        return out
    }

    private fun words(text: String): List<String> = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }

    /** The app's neighbouring words become one segment; the person's stay in their own. */
    private fun line(parts: List<Part>): Line {
        val segments = ArrayList<Segment>()
        for (part in parts) {
            val last = segments.lastOrNull()
            if (last != null && !last.carriesUserText && !part.user) {
                segments[segments.size - 1] = Segment(last.text + " " + part.text)
            } else {
                segments += Segment(part.text, part.user)
            }
        }
        return Line(segments)
    }

    private fun interjections(personality: Personality): List<String> = when (personality) {
        Personality.DRILL_SERGEANT -> listOf("Hey!", "Move!", "Let's go!", "Come on!")
        Personality.EXECUTIVE_DIRECTOR -> listOf("Attention, please.", "Now, then.", "Moving on.", "Right.")
        Personality.SUPPORTIVE_COACH -> listOf("Hey there!", "Come on!", "You can do this!", "Here we go!")
        Personality.ACCOUNTABILITY_PARTNER -> listOf("Hey!", "It's me again!", "Come on!", "Still here!")
        Personality.MINIMALIST -> listOf("Hey.", "Go.", "Now.", "Up.")
        Personality.CUSTOM -> listOf("Hey!", "Okay!", "Come on!", "Let's go!")
    }

    private fun closer(personality: Personality, tier: ImperativeTier): String =
        if (personality == Personality.DRILL_SERGEANT) {
            when (tier) {
                ImperativeTier.SUGGEST -> "When you are ready."
                ImperativeTier.INVITE -> "Let's get going."
                ImperativeTier.DIRECT -> "Get going."
                ImperativeTier.FIRM -> "Get going now."
                ImperativeTier.COMMANDING -> "Do it now!"
            }
        } else {
            when (tier) {
                ImperativeTier.SUGGEST -> "When you are ready."
                ImperativeTier.INVITE -> "Let's begin."
                ImperativeTier.DIRECT -> "Begin."
                ImperativeTier.FIRM -> "Begin now."
                ImperativeTier.COMMANDING -> "Do it now."
            }
        }
}
