package com.command.domain.coaching

import com.command.domain.dials.Personality
import com.command.domain.dials.Personality.ACCOUNTABILITY_PARTNER
import com.command.domain.dials.Personality.CUSTOM
import com.command.domain.dials.Personality.DRILL_SERGEANT
import com.command.domain.dials.Personality.EXECUTIVE_DIRECTOR
import com.command.domain.dials.Personality.MINIMALIST
import com.command.domain.dials.Personality.SUPPORTIVE_COACH
import com.command.domain.dials.Personality as P
import com.command.domain.coaching.CoachingPhase.*

/** The moments at which the app has something to say. */
enum class CoachingPhase {
    REVEILLE, HANDSHAKE_PROMPT, HANDSHAKE_SUCCESS, DIRECTION, STARTER_START, STARTER_MIDPOINT, CHECK_IN,
    RECOVERY_RESET, RECOVERY_SMALLER, RECOVERY_RESCHEDULE, SKIP_ACK, UNCONFIRMED, REPEATED_ACK_NO_START,
}

/** What is wrong with a template's form, whatever its words say. */
enum class TemplateProblem {
    BLANK,

    /** A `{name}` that is not `{title}`, `{first_action}` or `{minutes}`. */
    UNKNOWN_PLACEHOLDER,

    /** The person's words must close a sentence: `{title}` or `{first_action}`, then at most a mark. They are kept in a segment of their own, and a sentence has one placeholder. */
    USER_TEXT_INSIDE_SENTENCE,

    /** At least one sentence has to stand without the mission's words, because a line can be asked for without them, and a mission may have none. */
    NOTHING_WITHOUT_MISSION_WORDS,
}

/**
 * The words of every personality at every phase, offline and in the app's own text. A template is sentences; `{title}` and `{first_action}`
 * stand for the person's words and close their sentence, `{minutes}` is said as "5 minutes". A sentence whose value is missing is left out.
 *
 * Nothing here says a person has started: an acknowledgement is only an acknowledgement, and a start is theirs to report. Nothing here gets
 * louder because a person did not start, either (spec section 5): [REPEATED_ACK_NO_START] offers something smaller.
 */
object TemplateBank {
    const val TITLE = "title"
    const val FIRST_ACTION = "first_action"
    const val MINUTES = "minutes"
    internal val PLACEHOLDER = Regex("""\{([^{}]*)\}""")

    fun variants(personality: Personality, phase: CoachingPhase): List<String> = BANK.getValue(personality).getValue(phase)

    /** Everything wrong with the form of [template]; empty when it can be used. */
    fun problems(template: String): Set<TemplateProblem> {
        if (template.isBlank()) return setOf(TemplateProblem.BLANK)
        val found = HashSet<TemplateProblem>()
        val sentences = sentences(template)
        for (sentence in sentences) {
            if (PLACEHOLDER.findAll(sentence).count() > 1) found += TemplateProblem.USER_TEXT_INSIDE_SENTENCE
            for (match in PLACEHOLDER.findAll(sentence)) {
                val name = match.groupValues[1]
                if (name != TITLE && name != FIRST_ACTION && name != MINUTES) found += TemplateProblem.UNKNOWN_PLACEHOLDER
                if ((name == TITLE || name == FIRST_ACTION) && sentence.substring(match.range.last + 1).trim().trim('.', '!', '?').isNotEmpty()) {
                    found += TemplateProblem.USER_TEXT_INSIDE_SENTENCE
                }
            }
        }
        if (sentences.all { PLACEHOLDER.containsMatchIn(it) }) found += TemplateProblem.NOTHING_WITHOUT_MISSION_WORDS
        return found
    }

    internal fun sentences(template: String): List<String> =
        template.trim().split(Regex("""(?<=[.!?])\s+""")).filter { it.isNotBlank() }

    private val BANK: Map<P, Map<CoachingPhase, List<String>>> = mapOf(
        DRILL_SERGEANT to mapOf(
            REVEILLE to listOf(
                "Up and at it! Mission time: {title}. First move: {first_action}.",
                "On your feet! It is time for: {title}. First move: {first_action}.",
                "Reveille! You are on: {title}. Start here: {first_action}.",
                "Eyes open! Next up: {title}. First move: {first_action}.",
            ),
            HANDSHAKE_PROMPT to listOf(
                "Show me you are up!",
                "Handshake time. Let me see it!",
                "Check in with me. Do the handshake!",
                "Prove you are awake. Handshake, now!",
            ),
            HANDSHAKE_SUCCESS to listOf(
                "You finished the handshake. Fantastic!",
                "Handshake done. Outstanding!",
                "That is a clean handshake. Well done!",
                "Handshake locked in. Good work!",
            ),
            DIRECTION to listOf(
                "You finished the handshake. Fantastic. Now get to it: {first_action}. Give me {minutes}.",
                "Good. Here is your first move: {first_action}. Give me {minutes}.",
                "Eyes front. First move: {first_action}. Just {minutes}.",
                "Right, on to the job. First move: {first_action}. Give me {minutes}.",
            ),
            STARTER_START to listOf(
                "Clock is running. Give me {minutes}!",
                "Here we go. {minutes} on the clock!",
                "Go time. Give me {minutes}!",
                "Starter is on. {minutes}, right now!",
            ),
            STARTER_MIDPOINT to listOf(
                "Halfway there. Keep it up!",
                "Halfway. Stay on it!",
                "That is half. Hold the line!",
                "Half done. Keep moving!",
            ),
            CHECK_IN to listOf(
                "Check-in! Report in, please.",
                "Report in! Where do things stand?",
                "Status check! Tell me where you are.",
                "Time to report! What is your status?",
            ),
            RECOVERY_RESET to listOf(
                "Shake it off. Take a breath and go again.",
                "New round. Clear your desk and go again.",
                "Reset. Two slow breaths, then back to it.",
                "Fresh start. Line it up and go again.",
            ),
            RECOVERY_SMALLER to listOf(
                "Make it smaller. Two minutes, that is all.",
                "Shrink the job. Pick one tiny piece.",
                "Smaller target. Do the first tiny bit.",
                "Cut it down. Just the first small piece.",
            ),
            RECOVERY_RESCHEDULE to listOf(
                "Pick a new time and I will be there.",
                "New time. Set it and we go again then.",
                "Choose your new time. I will call it.",
                "Move it to a time that works. Your call.",
            ),
            SKIP_ACK to listOf(
                "Skipped. Understood.",
                "Copy that. This one is skipped.",
                "Heard you. Skipped for now.",
                "Roger. This one is off the board.",
            ),
            UNCONFIRMED to listOf(
                "No report came in. It stays unconfirmed.",
                "I did not hear back. Marked unconfirmed.",
                "No answer on this one. It is unconfirmed.",
                "Nothing reported. Logged as unconfirmed.",
            ),
            REPEATED_ACK_NO_START to listOf(
                "New plan. Five minutes on the smallest piece.",
                "Different angle. Pick one tiny first action.",
                "Let us shrink it. Five minutes, smallest piece.",
                "Try this instead. One tiny step, then report.",
            ),
        ),
        EXECUTIVE_DIRECTOR to mapOf(
            REVEILLE to listOf(
                "It is time. Your next commitment: {title}. First action: {first_action}.",
                "It is time. Your schedule now calls for: {title}. First action: {first_action}.",
                "It is time. Next on the agenda: {title}. Begin with: {first_action}.",
                "This is your scheduled start. Item: {title}. First action: {first_action}.",
            ),
            HANDSHAKE_PROMPT to listOf(
                "Please complete the acknowledgement.",
                "Acknowledge to proceed, please.",
                "Your acknowledgement is required to proceed.",
                "Please confirm that you are available.",
            ),
            HANDSHAKE_SUCCESS to listOf(
                "Acknowledged. Thank you.",
                "Acknowledgement received. Well done.",
                "Confirmed. Excellent.",
                "Noted. Thank you for confirming.",
            ),
            DIRECTION to listOf(
                "Next, please. Next action: {first_action}. Allocate {minutes}.",
                "Please proceed. First action: {first_action}. Allocate {minutes}.",
                "Here is the plan. First action: {first_action}. Budget {minutes}.",
                "One priority now. First action: {first_action}. Budget {minutes}.",
            ),
            STARTER_START to listOf(
                "The starter has begun. It runs {minutes}.",
                "Starter underway. Allocated time: {minutes}.",
                "Time is now allocated. It runs {minutes}.",
                "Your starter is live. Duration: {minutes}.",
            ),
            STARTER_MIDPOINT to listOf(
                "You are at the midpoint.",
                "Midpoint reached. Continue as planned.",
                "Half of the allocated time has passed.",
                "This is the midpoint. Stay on the plan.",
            ),
            CHECK_IN to listOf(
                "A status update, please.",
                "Please report your current status.",
                "Status review. Where does this stand?",
                "Brief update, please. What is the status?",
            ),
            RECOVERY_RESET to listOf(
                "Let us reset. Restate the first action and begin again.",
                "A reset is reasonable. Clear the desk and begin again.",
                "Reset the plan. Begin again from the first action.",
                "Take a moment to regroup, then begin again.",
            ),
            RECOVERY_SMALLER to listOf(
                "Reduce the scope. Choose one small deliverable.",
                "Narrow the scope to its first small piece.",
                "Scale this down. Two minutes on the first piece.",
                "Define a smaller first deliverable.",
            ),
            RECOVERY_RESCHEDULE to listOf(
                "Choose a new time for this item.",
                "Please select a revised start time.",
                "Move this to a time that works.",
                "Set a new time and it will be arranged.",
            ),
            SKIP_ACK to listOf(
                "Skipped. It has been noted.",
                "Understood. This item is skipped.",
                "Noted. This occurrence is skipped.",
                "Thank you. This item is set aside.",
            ),
            UNCONFIRMED to listOf(
                "No status was received. This is recorded as unconfirmed.",
                "No update arrived. The item remains unconfirmed.",
                "Status unknown. It is recorded as unconfirmed.",
                "No response was logged. It remains unconfirmed.",
            ),
            REPEATED_ACK_NO_START to listOf(
                "Let us adjust the approach. Consider a five minute starter.",
                "A different approach may help. Name one small first action.",
                "Consider reducing the first action to five minutes.",
                "Propose a smaller first action, and allow five minutes.",
            ),
        ),
        SUPPORTIVE_COACH to mapOf(
            REVEILLE to listOf(
                "Good to see you. It is time for: {title}. A first step: {first_action}.",
                "Here we go, one step at a time. Up next: {title}. First step: {first_action}.",
                "You planned this one. It is time for: {title}. First step: {first_action}.",
                "It is time, and you can do this. Up next: {title}. First step: {first_action}.",
            ),
            HANDSHAKE_PROMPT to listOf(
                "Let me know you are here.",
                "Whenever you can, let me know you are up.",
                "Check in with me, please.",
                "One small thing first. Let me know you are here.",
            ),
            HANDSHAKE_SUCCESS to listOf(
                "Nice work. You are here.",
                "Thank you. That is confirmed.",
                "Well done. Glad you are up.",
                "That is it. Nicely done.",
            ),
            DIRECTION to listOf(
                "You are here. Just this for now: {first_action}. {minutes} is plenty.",
                "A gentle start. First step: {first_action}. Try {minutes}.",
                "You are here. You only need the first step: {first_action}. Try {minutes}.",
                "Let us keep it simple. First step: {first_action}. Try {minutes}.",
            ),
            STARTER_START to listOf(
                "Starter on for {minutes}. You have got this.",
                "Here we go. Just {minutes}.",
                "Nice and easy. {minutes} starts now.",
                "Off you go. {minutes} is all this asks.",
            ),
            STARTER_MIDPOINT to listOf(
                "You are halfway. Nice going.",
                "Halfway there. Keep it gentle.",
                "That is half. You are doing well.",
                "Halfway. Good steady work.",
            ),
            CHECK_IN to listOf(
                "Where are things at?",
                "Just checking in. How are things?",
                "Thinking of you. Where are things at?",
                "A quick check-in. Where do things stand?",
            ),
            RECOVERY_RESET to listOf(
                "That is okay. Take a breath and try again.",
                "It happens. Let us begin again, gently.",
                "No problem. A fresh start is right here.",
                "Take a moment. Then pick it back up.",
            ),
            RECOVERY_SMALLER to listOf(
                "Let us make it smaller. What is the tiniest step?",
                "Smaller is fine. Try just two minutes.",
                "A tiny step counts. Pick the easiest piece.",
                "Let us shrink it. Just the first small bit.",
            ),
            RECOVERY_RESCHEDULE to listOf(
                "That is fine. Pick a time that suits you.",
                "A better time may help. Choose one that works.",
                "Let us find another time for this.",
                "No problem. Choose a new time.",
            ),
            SKIP_ACK to listOf(
                "That is okay. This one is skipped.",
                "Skipped. Thank you for letting me know.",
                "No problem. This one is set aside.",
                "Understood. Take care of yourself.",
            ),
            UNCONFIRMED to listOf(
                "I did not hear back, and that is okay. It stays unconfirmed.",
                "No answer came in. It is unconfirmed for now.",
                "This one stays unconfirmed. I am here next time.",
                "I have no update on this. It stays unconfirmed.",
            ),
            REPEATED_ACK_NO_START to listOf(
                "Starting can be the hard part. Want to try just five minutes?",
                "Let us try something different. What is one tiny first step?",
                "Maybe a smaller start. Try five minutes on one tiny thing.",
                "How about a tiny first step? Five minutes is enough.",
            ),
        ),
        ACCOUNTABILITY_PARTNER to mapOf(
            REVEILLE to listOf(
                "It is time. You told me you would do this: {title}. First step: {first_action}.",
                "It is time. This is the one you set up: {title}. First step: {first_action}.",
                "It is time. What we agreed: {title}. First step: {first_action}.",
                "It is time. I am here for this one: {title}. First step: {first_action}.",
            ),
            HANDSHAKE_PROMPT to listOf(
                "Let me know you saw this.",
                "Confirm with me that you are up.",
                "I need a quick sign from you.",
                "Check in, so I know you are up.",
            ),
            HANDSHAKE_SUCCESS to listOf(
                "Got it. Thanks for showing up.",
                "Good. I see you.",
                "Thanks. That is your check-in done.",
                "Great, I have got your acknowledgement.",
            ),
            DIRECTION to listOf(
                "Okay. Here is what you said comes first: {first_action}. Give it {minutes}.",
                "As we planned. First step: {first_action}. Give it {minutes}.",
                "This is your plan. It says this first: {first_action}. Give it {minutes}.",
                "Let us do what you planned. First step: {first_action}. Give it {minutes}.",
            ),
            STARTER_START to listOf(
                "Clock on for {minutes}. I will check back.",
                "Starter is on: {minutes}. I will ask how it went.",
                "Go ahead. {minutes}, and I will check back.",
                "Clock on. {minutes}, then we talk.",
            ),
            STARTER_MIDPOINT to listOf(
                "You are halfway. I am still here.",
                "Halfway point. Keep going.",
                "That is half. Nice going.",
                "Halfway. I will check back at the end.",
            ),
            CHECK_IN to listOf(
                "Checking in as agreed. What is the status?",
                "As promised, I am asking. Where are you with it?",
                "Time for our check-in. What is the status?",
                "This is your check-in. Tell me where it stands.",
            ),
            RECOVERY_RESET to listOf(
                "Okay. Let us reset and try again together.",
                "Plans slip. Let us go again.",
                "Let us call that a reset. Go again from the top.",
                "Fair enough. Try again, and I will check back.",
            ),
            RECOVERY_SMALLER to listOf(
                "Let us agree on something smaller. What is the first piece?",
                "A smaller promise works. Two minutes on one piece.",
                "Commit to just the first small piece.",
                "Let us shrink the deal. One small piece for now.",
            ),
            RECOVERY_RESCHEDULE to listOf(
                "Let us set a new time, and I will be there.",
                "Pick a new time that works.",
                "Name a new time, and I will ask then.",
                "Choose another time. I will check in then.",
            ),
            SKIP_ACK to listOf(
                "Okay, skipped. Thanks for telling me.",
                "Thanks for being straight with me. It is skipped.",
                "Heard. This one is skipped.",
                "Understood. I have marked it skipped.",
            ),
            UNCONFIRMED to listOf(
                "I did not hear back, so I have it as unconfirmed.",
                "No word from you on this one. It stays unconfirmed.",
                "I have no update. It stays unconfirmed.",
                "This one is unconfirmed. Tell me when you can.",
            ),
            REPEATED_ACK_NO_START to listOf(
                "Let us change the deal. Five minutes on one small piece?",
                "This plan may need changing. What is one tiny first action?",
                "Let us try a smaller agreement. Just five minutes.",
                "Different deal. One tiny step, then tell me.",
            ),
        ),
        MINIMALIST to mapOf(
            REVEILLE to listOf(
                "Time. {title}. {first_action}.",
                "Now. {title}. {first_action}.",
                "Start. {title}. {first_action}.",
                "Up. {title}. {first_action}.",
            ),
            HANDSHAKE_PROMPT to listOf("Confirm.", "Acknowledge.", "Confirm now.", "Respond."),
            HANDSHAKE_SUCCESS to listOf("Done.", "Good.", "Confirmed.", "Noted."),
            DIRECTION to listOf(
                "Next. {first_action}. {minutes}.",
                "Go. {first_action}. {minutes}.",
                "First. {first_action}. {minutes}.",
                "Begin. {first_action}. {minutes}.",
            ),
            STARTER_START to listOf("Go. {minutes}.", "Begin. {minutes}.", "Clock on. {minutes}.", "Now. {minutes}."),
            STARTER_MIDPOINT to listOf("Halfway.", "Half done.", "Midpoint.", "Half."),
            CHECK_IN to listOf("Status?", "Update?", "Where are you?", "Report."),
            RECOVERY_RESET to listOf("Reset. Again.", "Breathe. Again.", "Once more.", "Begin again."),
            RECOVERY_SMALLER to listOf("Smaller.", "One small piece.", "Two minutes.", "Tiny step."),
            RECOVERY_RESCHEDULE to listOf("New time.", "Pick a time.", "Choose a time.", "Set a time."),
            SKIP_ACK to listOf("Skipped.", "Noted.", "Understood.", "Set aside."),
            UNCONFIRMED to listOf("Unconfirmed.", "No report.", "No answer.", "No update."),
            REPEATED_ACK_NO_START to listOf("Five minutes?", "Smaller step?", "One tiny step.", "Try five minutes."),
        ),
        CUSTOM to mapOf(
            REVEILLE to listOf(
                "It is time. Next: {title}. First step: {first_action}.",
                "It is time. Start: {title}. First step: {first_action}.",
                "It is time. Up next: {title}. First step: {first_action}.",
                "It is time. Your scheduled time is here: {title}. First step: {first_action}.",
            ),
            HANDSHAKE_PROMPT to listOf(
                "Please acknowledge.",
                "Confirm that you are up.",
                "Acknowledge this alarm.",
                "Complete the acknowledgement.",
            ),
            HANDSHAKE_SUCCESS to listOf("Acknowledged.", "Thank you. That is confirmed.", "Confirmed. Well done.", "Got it. Thank you."),
            DIRECTION to listOf(
                "Next. First step: {first_action}. Try {minutes}.",
                "Moving on. Do this: {first_action}. Give it {minutes}.",
                "Here we go. Here is the first step: {first_action}. Try {minutes}.",
                "Ready. Start with this: {first_action}. Give it {minutes}.",
            ),
            STARTER_START to listOf("The starter is on. It runs {minutes}.", "Go ahead. It runs {minutes}.", "Timer on. It runs {minutes}.", "Begin now. It runs {minutes}."),
            STARTER_MIDPOINT to listOf("You are halfway.", "Halfway there.", "That is half.", "Midpoint reached."),
            CHECK_IN to listOf("Status check.", "What is your status?", "Time for a check-in.", "Where does this stand?"),
            RECOVERY_RESET to listOf("Reset, and begin again.", "Take a breath, then begin again.", "Start again from the first step.", "A fresh start is ready."),
            RECOVERY_SMALLER to listOf("Make it smaller.", "Pick one small piece.", "Try just two minutes.", "Choose a tiny first step."),
            RECOVERY_RESCHEDULE to listOf("Choose a new time.", "Pick a time that works.", "Set another time.", "Move this to a new time."),
            SKIP_ACK to listOf("Skipped.", "Understood. It is skipped.", "This one is skipped.", "Noted. It is skipped."),
            UNCONFIRMED to listOf("No report came in. It is unconfirmed.", "Nothing was reported. It is unconfirmed.", "It stays unconfirmed.", "No answer arrived. It is unconfirmed."),
            REPEATED_ACK_NO_START to listOf(
                "Try five minutes on one small piece.",
                "Pick a tiny first action.",
                "A smaller start may help. Try five minutes.",
                "Try something different. One tiny step.",
            ),
        ),
    )
}
