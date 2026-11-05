package com.command.reveille

import com.command.domain.dials.Personality

/** The phrases of a test: always the same, and a note of whose phrases were asked for. */
internal class FixedPhrases(private val phrases: List<String>) : PhraseSource {
    val asked = ArrayList<Personality>()

    override fun phrases(personality: Personality): List<String> {
        asked += personality
        return phrases
    }
}
