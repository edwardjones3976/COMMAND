package com.command.domain.coaching

import com.command.domain.voice.Line
import java.util.Locale

enum class LintCategory { EVALUATION_OF_THE_PERSON, THREAT_OR_COERCION, UNSAFE_COMMAND }

/** One thing a text may not say: why, and the words that matched (they are the app's own text or the person's draft, and are never logged). */
data class LintViolation(val category: LintCategory, val matched: String)

/**
 * What the app's words may never do (spec section 3): characterise the person, threaten or coerce them, or tell them to do something unsafe.
 * Second-person imperatives and praise are allowed: "GET TO YOUR COMPUTER. MOVE!" is exactly what was asked for. It is a list of phrasings, so
 * it catches what it lists and their spellings (capitals, punctuation, curly apostrophes), not every possible way of being unkind.
 */
object DirectiveLint {

    private val RULES: List<Pair<LintCategory, Regex>> = listOf(
        LintCategory.EVALUATION_OF_THE_PERSON to words(
            "lazy", "laziness", "pathetic", "worthless", "useless", "stupid", "idiot", "idiots", "moron", "dumb", "loser", "losers", "failure", "disgrace",
            "disgraceful", "disgusting", "embarrassing", "embarrassment", "shameful", "ashamed", "coward", "cowardly", "slacker", "slackers", "maggot", "maggots",
            "weak", "weakling", "crybaby", "crybabies", "hopeless", "incompetent", "fat", "ugly", "sad excuse", "your excuses", "no excuses", "you failed",
            "you always", "you never", "sitting on your hands", "sit on your hands", "no crying", "stop crying", "quit crying", "whining", "whiner",
        ),
        LintCategory.THREAT_OR_COERCION to words(
            "never stops", "never stop", "never ends", "never end", "won't stop", "wont stop", "will not stop", "doesn't stop", "does not stop", "or else",
            "or this alarm", "or the alarm", "you will regret", "you'll regret", "you will pay", "you'll pay", "you'd better", "you had better", "you better",
            "no choice", "no way out", "no negotiating", "not negotiable", "non negotiable", "consequences", "punish", "punishment", "punished", "i will make you",
            "i'll make you", "i will find you", "i'll find you", "keep ringing until", "until you obey", "obey",
        ),
        LintCategory.UNSAFE_COMMAND to words(
            "drive faster", "while driving", "run the light", "run the red", "speed up the car", "skip your meds", "skip your medication", "skip your medicine",
            "stop taking", "don't eat", "do not eat", "skip meals", "skip a meal", "don't sleep", "do not sleep", "no sleep", "without sleep", "ignore the pain",
            "ignore your pain", "push through the pain", "through the pain", "ignore your doctor", "hurt yourself", "harm yourself", "kill yourself", "starve",
            "take more pills", "don't call for help", "do not call for help",
        ),
    )

    /** Every rule [text] breaks; empty when it may be said. */
    fun check(text: String): List<LintViolation> {
        val plain = normalise(text)
        return RULES.flatMap { (category, rule) -> rule.findAll(plain).map { LintViolation(category, it.value) }.toList() }
    }

    fun passes(text: String): Boolean = check(text).isEmpty()

    /** The app's own words in [line]. What the person typed is theirs: a mission called "Stop being lazy" is not the app saying so. */
    fun check(line: Line): List<LintViolation> = line.segments.filterNot { it.carriesUserText }.flatMap { check(it.text) }

    /** Lower case, straight apostrophes, and everything that is not a letter, a digit or an apostrophe as one space. */
    private fun normalise(text: String): String =
        text.lowercase(Locale.ROOT).replace('’', '\'').replace('‘', '\'').replace(Regex("""[^\p{L}\p{N}']+"""), " ").trim()

    private fun words(vararg phrases: String): Regex =
        Regex(phrases.joinToString("|", prefix = """(?<![\p{L}\p{N}'])(?:""", postfix = """)(?![\p{L}\p{N}'])""") { Regex.escape(it) })
}
