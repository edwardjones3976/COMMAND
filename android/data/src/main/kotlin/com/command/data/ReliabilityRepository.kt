package com.command.data

import com.command.data.db.CommandDb
import com.command.domain.reliability.AlarmOutcome
import com.command.domain.reliability.AlarmOutcomes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** What the audit log says about how recent alarms went, for the reliability page. */
@Singleton
class ReliabilityRepository @Inject constructor(private val db: CommandDb) {

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

    companion object {
        const val RECENT = 5
    }
}
