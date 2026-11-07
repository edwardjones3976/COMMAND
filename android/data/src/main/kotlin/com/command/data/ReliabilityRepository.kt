package com.command.data

import com.command.data.db.CommandDb
import com.command.domain.reliability.AlarmOutcome
import com.command.domain.reliability.AlarmOutcomes
import com.command.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** What the audit log says about how recent alarms went, for the reliability page. */
@Singleton
class ReliabilityRepository @Inject constructor(private val db: CommandDb, private val clock: Clock) {

    /** The last [limit] alarms that rang or were lost while the phone was off, newest first. Updates as new ones happen. */
    fun recentAlarms(limit: Int = RECENT): Flow<List<AlarmOutcome>> = db.occurrences().observeAlarmFacts(limit).map { rows ->
        rows.mapNotNull {
            AlarmOutcomes.classify(
                occurrenceId = it.occurrenceId, title = it.title, scheduledAtUtc = it.scheduledAtUtc,
                firedAtUtc = it.firedAtUtc, missedWhileOff = it.missedAtUtc != null, recordedAtUtc = it.missedAtUtc,
                isTest = it.category == MissionRepository.TEST_CATEGORY,
            )
        }
    }

    /** How many alarms the platform refused in the last day: long enough to be seen, and it ends once the refusals have. */
    fun refusedAlarms(): Flow<Int> = db.events().observeAlarmsRefused(clock.nowMs() - REFUSALS_SHOWN_MS)

    companion object {
        const val RECENT = 5
        private const val REFUSALS_SHOWN_MS = 24 * 60 * 60 * 1000L
    }
}
