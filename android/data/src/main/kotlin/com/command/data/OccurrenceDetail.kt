package com.command.data

import com.command.data.db.ChallengeRow
import com.command.data.db.CheckInRow
import com.command.data.db.EventRow
import com.command.data.db.OccurrenceRow

/**
 * One occurrence with everything the phone has recorded about it: the audit events (ids, enums, counts and times, never free text),
 * the follow-up questions with their answers and the attempts at the handshake. [title] is the mission's, which is the person's own text.
 */
data class OccurrenceDetail(
    val occurrence: OccurrenceRow,
    val title: String,
    val events: List<EventRow>,
    val checkIns: List<CheckInRow>,
    val challenges: List<ChallengeRow>,
)
