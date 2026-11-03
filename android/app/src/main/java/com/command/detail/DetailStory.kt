package com.command.detail

import com.command.data.OccurrenceDetail
import com.command.data.db.CheckInRow
import com.command.data.db.EventRow
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceState

/** What the person is told happened, one line each. The words are string resources; [arg] fills the one that has a number in it. */
enum class StoryKind {
    SET, SET_AGAIN, SET_ROUGHLY, RANG, ANSWERED, DIRECTED, ASKED, QUESTION_LATE, STARTED, STILL_WORKING, DONE, SKIPPED, DELAYED, MISSED, SILENCED,
    NOT_CONFIRMED, RESTART_OFFERED, RESTARTED, MOVED_LATER, BLOCKED, PHONE_WAS_OFF, PAUSED, NOTIFICATION_SWIPED, VOICE_UNAVAILABLE, TEST,
}

data class StoryItem(val atUtcMs: Long, val kind: StoryKind, val arg: String? = null)

/** Everything recorded, for the person who wants to see it: the name of the event, and its details (ids, enums, counts, times). */
data class TechnicalItem(val atUtcMs: Long, val title: String, val details: String)

/** The recorded events of one occurrence in the words of a person, and as they were written. Pure, so it is tested without a screen. */
object DetailStory {

    /** The lines of the story, in the order they happened. Events that only matter to someone debugging the app are not in it. */
    fun story(detail: OccurrenceDetail): List<StoryItem> = detail.events.mapNotNull { storyOf(it, detail.occurrence.missReason) }

    /** Every event, every follow-up question and every handshake attempt, as recorded. */
    fun technical(detail: OccurrenceDetail): List<TechnicalItem> {
        val events = detail.events.map { TechnicalItem(it.atUtc, it.type.name, payloadText(it)) }
        val questions = detail.checkIns.map { TechnicalItem(it.askedAt, "QUESTION_${it.checkInIndex}", questionText(it)) }
        val attempts = detail.challenges.map {
            TechnicalItem(it.startedAt, "HANDSHAKE_${it.mode.name}", "attempt=${it.attemptNo} outcome=${it.outcome.name} step=${it.chainPosition}")
        }
        return (events + questions + attempts).sortedBy { it.atUtcMs }
    }

    private fun storyOf(e: EventRow, missReason: MissReason?): StoryItem? {
        val values = payload(e)
        val at = e.atUtc
        return when (e.type) {
            EventType.STATE_CHANGED -> when (runCatching { OccurrenceState.valueOf(values["to"].orEmpty()) }.getOrNull()) {
                OccurrenceState.FIRING -> StoryItem(at, StoryKind.RANG)
                OccurrenceState.ACKNOWLEDGED -> StoryItem(at, StoryKind.ANSWERED)
                OccurrenceState.GUIDED_START -> StoryItem(at, if (values["command"] == "RESUME" || values["command"] == "ACCEPT_RECOVERY") StoryKind.RESTARTED else StoryKind.DIRECTED)
                OccurrenceState.STARTED_SELF_REPORTED -> StoryItem(at, StoryKind.STARTED)
                OccurrenceState.WORKING_SELF_REPORTED -> StoryItem(at, StoryKind.STILL_WORKING)
                OccurrenceState.COMPLETED_SELF_REPORTED -> StoryItem(at, StoryKind.DONE)
                OccurrenceState.SKIPPED -> StoryItem(at, StoryKind.SKIPPED)
                OccurrenceState.DELAYED -> StoryItem(at, StoryKind.DELAYED)
                OccurrenceState.MISSED -> when (missReason) {
                    // An alarm that never rang did not run out: the phone being off is told by its own event.
                    MissReason.DOWNTIME -> null
                    MissReason.PAUSED -> StoryItem(at, StoryKind.PAUSED)
                    MissReason.TIMEOUT, null -> StoryItem(at, StoryKind.MISSED)
                }
                OccurrenceState.SAFETY_SILENCED -> StoryItem(at, StoryKind.SILENCED)
                OccurrenceState.UNCONFIRMED -> StoryItem(at, StoryKind.NOT_CONFIRMED)
                OccurrenceState.RECOVERY_OFFERED -> StoryItem(at, StoryKind.RESTART_OFFERED)
                OccurrenceState.BLOCKED -> StoryItem(at, StoryKind.BLOCKED)
                else -> null // scheduled and armed are told by the alarm being set
            }
            EventType.ALARM_ARMED -> StoryItem(
                at,
                when {
                    values["adjusted"] != "true" -> StoryKind.SET
                    // The platform could not arm what was asked for: the alarm is only roughly on time.
                    values["mode"] == "INEXACT" -> StoryKind.SET_ROUGHLY
                    else -> StoryKind.SET_AGAIN
                },
            )
            EventType.ALARM_LOST -> StoryItem(at, StoryKind.SET_AGAIN)
            EventType.CHECK_IN_ASKED -> StoryItem(at, StoryKind.ASKED)
            EventType.CHECKIN_LATE -> StoryItem(at, StoryKind.QUESTION_LATE, values["late_s"])
            EventType.RECOVERY_ACCEPTED -> if (values["choice"] == "RESCHEDULE") StoryItem(at, StoryKind.MOVED_LATER, values["minutes"]) else null
            EventType.MISSED_WHILE_OFF -> StoryItem(at, StoryKind.PHONE_WAS_OFF)
            EventType.NOTIFICATION_DISMISSED -> StoryItem(at, StoryKind.NOTIFICATION_SWIPED)
            EventType.TTS_UNAVAILABLE -> StoryItem(at, StoryKind.VOICE_UNAVAILABLE)
            EventType.TEST_RING -> StoryItem(at, StoryKind.TEST)
            else -> null
        }
    }

    private fun payload(e: EventRow): Map<String, String> =
        Regex("\"([A-Za-z0-9_.:+-]{1,64})\"\\s*:\\s*\"([A-Za-z0-9_.:+-]{0,64})\"").findAll(e.payloadJson).associate { it.groupValues[1] to it.groupValues[2] }

    private fun payloadText(e: EventRow): String = payload(e).entries.joinToString(" ") { "${it.key}=${it.value}" }

    private fun questionText(q: CheckInRow): String = buildString {
        append("late_ms=").append(q.lateMs)
        q.answer?.let { append(" answer=").append(it.name) }
        q.answeredAt?.let { append(" answered_at=").append(it) }
        q.delayMin?.let { append(" delay_min=").append(it) }
    }
}
