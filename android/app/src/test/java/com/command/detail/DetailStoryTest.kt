package com.command.detail

import com.command.data.OccurrenceDetail
import com.command.data.db.ChallengeRow
import com.command.data.db.CheckInRow
import com.command.data.db.EventRow
import com.command.data.db.OccurrenceRow
import com.command.domain.dials.HandshakeMode
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.CheckInAnswer
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an occurrence's record says, in the words of a person and as written. Pure, so no screen is needed. */
class DetailStoryTest {

    private var seq = 0L

    private fun event(type: EventType, at: Long, vararg payload: Pair<String, String>) = EventRow(
        seq = ++seq, occurrenceId = "o", missionId = "m", type = type, atUtc = at, elapsedRealtime = 0, bootId = null,
        source = EventSource.SYSTEM, payloadJson = payload.joinToString(",", "{", "}") { "\"${it.first}\":\"${it.second}\"" },
    )

    private fun changed(at: Long, from: String, to: String, command: String) =
        event(EventType.STATE_CHANGED, at, "from" to from, "to" to to, "command" to command)

    private fun detail(
        events: List<EventRow>,
        checkIns: List<CheckInRow> = emptyList(),
        challenges: List<ChallengeRow> = emptyList(),
        missReason: MissReason? = null,
    ) = OccurrenceDetail(
        OccurrenceRow("o", "m", 1_000, "UTC", OccurrenceState.STARTED_SELF_REPORTED, 3, 1, ArmMode.EXACT, null, "{}", null, null, missReason),
        "Write the report", events, checkIns, challenges,
    )

    @Test
    fun `a morning that went well is told in order, in plain words`() {
        val events = listOf(
            changed(1, "SCHEDULED", "ARMED", "ARM"),
            event(EventType.ALARM_ARMED, 2, "mode" to "EXACT"),
            changed(10, "ARMED", "FIRING", "FIRE"),
            event(EventType.ALARM_FIRED, 11),
            changed(20, "FIRING", "ACKNOWLEDGED", "ACKNOWLEDGE"),
            changed(21, "ACKNOWLEDGED", "GUIDED_START", "START_GUIDANCE"),
            event(EventType.CHECK_IN_ASKED, 30, "index" to "0"),
            changed(40, "GUIDED_START", "STARTED_SELF_REPORTED", "REPORT_STARTED"),
            changed(50, "STARTED_SELF_REPORTED", "COMPLETED_SELF_REPORTED", "REPORT_DONE"),
        )
        assertEquals(
            listOf(
                StoryKind.SET, StoryKind.RANG, StoryKind.ANSWERED, StoryKind.DIRECTED, StoryKind.ASKED, StoryKind.STARTED, StoryKind.DONE,
            ),
            DetailStory.story(detail(events)).map { it.kind },
        )
    }

    @Test
    fun `a question the phone held back says for how many seconds`() {
        val line = DetailStory.story(detail(listOf(event(EventType.CHECKIN_LATE, 5, "index" to "1", "late_s" to "95")))).single()
        assertEquals(StoryKind.QUESTION_LATE, line.kind)
        assertEquals("95", line.arg)
    }

    @Test
    fun `the ways an occurrence can go wrong or sideways each have their line`() {
        val events = listOf(
            changed(1, "FIRING", "MISSED", "MISS"),
            changed(2, "FIRING", "SAFETY_SILENCED", "SAFETY_SILENCE"),
            changed(3, "GUIDED_START", "SKIPPED", "SKIP"),
            changed(4, "GUIDED_START", "DELAYED", "DELAY"),
            changed(5, "GUIDED_START", "UNCONFIRMED", "NO_RESPONSE"),
            changed(6, "GUIDED_START", "RECOVERY_OFFERED", "REQUEST_RECOVERY"),
            changed(7, "RECOVERY_OFFERED", "GUIDED_START", "ACCEPT_RECOVERY"),
            changed(8, "MISSED", "GUIDED_START", "RESUME"),
            changed(9, "GUIDED_START", "BLOCKED", "REPORT_BLOCKED"),
            event(EventType.MISSED_WHILE_OFF, 10, "late_s" to "900"),
            event(EventType.NOTIFICATION_DISMISSED, 11),
            event(EventType.TTS_UNAVAILABLE, 12, "reason" to "NO_ENGINE"),
            event(EventType.TEST_RING, 13),
            event(EventType.RECOVERY_ACCEPTED, 14, "choice" to "RESCHEDULE", "minutes" to "30"),
        )
        assertEquals(
            listOf(
                StoryKind.MISSED, StoryKind.SILENCED, StoryKind.SKIPPED, StoryKind.DELAYED, StoryKind.NOT_CONFIRMED, StoryKind.RESTART_OFFERED,
                StoryKind.RESTARTED, StoryKind.RESTARTED, StoryKind.BLOCKED, StoryKind.PHONE_WAS_OFF, StoryKind.NOTIFICATION_SWIPED,
                StoryKind.VOICE_UNAVAILABLE, StoryKind.TEST, StoryKind.MOVED_LATER,
            ),
            DetailStory.story(detail(events)).map { it.kind },
        )
    }

    @Test
    fun `an alarm that came due while the phone was off is told as that and not also as running out`() {
        val events = listOf(
            changed(1, "ARMED", "MISSED", "MISS"),
            event(EventType.MISSED_WHILE_OFF, 1, "late_s" to "900"),
        )
        assertEquals(listOf(StoryKind.PHONE_WAS_OFF), DetailStory.story(detail(events, missReason = MissReason.DOWNTIME)).map { it.kind })
    }

    @Test
    fun `an alarm that came due during a pause says so, and one that rang out unanswered says that`() {
        val events = listOf(changed(1, "SCHEDULED", "MISSED", "MISS"))
        assertEquals(listOf(StoryKind.PAUSED), DetailStory.story(detail(events, missReason = MissReason.PAUSED)).map { it.kind })
        assertEquals(listOf(StoryKind.MISSED), DetailStory.story(detail(events, missReason = MissReason.TIMEOUT)).map { it.kind })
    }

    @Test
    fun `an alarm that could only be set roughly says so, and one that was lost and put back is set again`() {
        val events = listOf(
            event(EventType.ALARM_ARMED, 1, "mode" to "EXACT"),
            event(EventType.ALARM_ARMED, 2, "mode" to "INEXACT", "adjusted" to "true"),
            event(EventType.ALARM_LOST, 3, "trigger" to "OPEN"),
        )
        assertEquals(listOf(StoryKind.SET, StoryKind.SET_ROUGHLY, StoryKind.SET_AGAIN), DetailStory.story(detail(events)).map { it.kind })
    }

    @Test
    fun `what only matters to someone debugging is not in the story`() {
        val events = listOf(
            event(EventType.REJECTED_TRANSITION, 1, "from" to "MISSED", "command" to "MISS"),
            event(EventType.PRECONDITION, 2, "key" to "ALARM_VOLUME"),
            event(EventType.AUDIO_ROUTE, 3, "spoke" to "true"),
            event(EventType.ALARM_FIRED_STALE, 4),
        )
        assertEquals(emptyList<StoryKind>(), DetailStory.story(detail(events)).map { it.kind })
    }

    @Test
    fun `an event with a state it does not know, or with no details, is left out of the story and not a crash`() {
        val events = listOf(
            event(EventType.STATE_CHANGED, 1, "to" to "NOT_A_STATE"),
            event(EventType.STATE_CHANGED, 2),
            changed(3, "FIRING", "ARMED", "DISARM"),
        )
        assertTrue(DetailStory.story(detail(events)).isEmpty())
    }

    @Test
    fun `the technical list has every event, every question and every attempt, in time order, as written`() {
        val events = listOf(
            changed(10, "ARMED", "FIRING", "FIRE"),
            event(EventType.AUDIO_ROUTE, 12, "devices" to "speaker", "spoke" to "false"),
        )
        val questions = listOf(CheckInRow("o", 0, askedAt = 15, answeredAt = 25, answer = CheckInAnswer.STARTED, delayMin = null, lateMs = 95_000))
        val attempts = listOf(ChallengeRow(1, "o", 0, HandshakeMode.HOLD, 1, ChallengeOutcome.SUCCESS, 11, 14))
        val technical = DetailStory.technical(detail(events, questions, attempts))

        assertEquals(listOf("STATE_CHANGED", "HANDSHAKE_HOLD", "AUDIO_ROUTE", "QUESTION_0"), technical.map { it.title })
        assertEquals("from=ARMED to=FIRING command=FIRE", technical[0].details)
        assertEquals("attempt=1 outcome=SUCCESS step=0", technical[1].details)
        assertEquals("devices=speaker spoke=false", technical[2].details)
        assertEquals("late_ms=95000 answer=STARTED answered_at=25", technical[3].details)
    }
}
