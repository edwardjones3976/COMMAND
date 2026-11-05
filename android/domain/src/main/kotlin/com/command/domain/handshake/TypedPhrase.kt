package com.command.domain.handshake

/**
 * The typing challenge: the person repeats a short phrase that is on the screen. The check ignores capital letters and the amount of space
 * between words (a phone keyboard capitalises on its own, and a thumb adds a space), and nothing else: a wrong letter is a wrong try.
 */
object TypedPhrase {
    /** Any kind of space a keyboard can type: the ordinary one, a tab or a line break, a non-breaking, thin, narrow or ideographic space. */
    private fun isSpace(c: Char): Boolean = c.isWhitespace() || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    /** The phrase as it is compared: no space at either end, one space between words, no capitals. */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var gap = false
        for (c in text) {
            if (isSpace(c)) {
                gap = out.isNotEmpty()
            } else {
                if (gap) out.append(' ')
                gap = false
                out.append(c)
            }
        }
        return out.toString().lowercase()
    }

    /** An empty answer is never right, whatever the phrase. */
    fun matches(typed: String, phrase: String): Boolean {
        val answer = normalize(typed)
        return answer.isNotEmpty() && answer == normalize(phrase)
    }

    /**
     * One phrase of [phrases] for [seed], the same one every time for the same seed: the phrase for an alarm does not change when the screen
     * is turned or recreated, nor between a person's tries, and another day's alarm is likely to ask for another.
     */
    fun pick(phrases: List<String>, seed: String): String {
        require(phrases.isNotEmpty()) { "no phrases to pick from" }
        return phrases[Math.floorMod(seed.hashCode(), phrases.size)]
    }
}
