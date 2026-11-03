package com.command.data

import androidx.room.withTransaction
import com.command.data.db.CommandDb
import com.command.data.db.ConsentRow
import com.command.data.db.EventRow
import com.command.data.db.SettingRow
import com.command.domain.model.ConsentSource
import com.command.domain.model.EventSource
import com.command.domain.model.SettingKeys
import com.command.domain.occurrence.EventPayload
import com.command.domain.occurrence.EventType
import com.command.domain.reliability.Precondition
import com.command.domain.reliability.PreconditionChange
import com.command.domain.reliability.PreconditionSnapshot
import com.command.domain.reliability.PreconditionValue
import com.command.domain.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers what the platform last said about the things an alarm depends on, and writes down what changed: the audit log
 * learns of every changed value, a permission that flipped says so, the consent history gets a platform-sourced row for
 * each permission whose state is new or changed (so "notifications were turned off in settings" is on the record), and an
 * alarm stream that became silent is flagged. Nothing is written when nothing changed, so opening the app is quiet.
 */
@Singleton
class PreconditionRecorder @Inject constructor(
    private val db: CommandDb,
    private val clock: Clock,
) {
    /** The last snapshot recorded, or null if none has been (or it cannot be read). */
    suspend fun last(): PreconditionSnapshot? =
        db.settings().get(SettingKeys.PERMISSION_SNAPSHOT)
            ?.let { Codec.decodePayload(it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { PreconditionSnapshot.fromTokens(it) }

    /** Returns the changes since the last snapshot (all of them the first time, none when nothing changed). */
    suspend fun record(snapshot: PreconditionSnapshot): List<PreconditionChange> {
        val changes = snapshot.changesFrom(last())
        if (changes.isEmpty()) return emptyList()
        val now = clock.nowMs()
        db.withTransaction {
            for (change in changes) {
                event(EventType.PRECONDITION, change, now)
                // The first reading is a baseline, not a change.
                if (change.precondition.isPermission && change.from != null) event(EventType.PERMISSION_CHANGED, change, now)
                change.precondition.consent?.let { key ->
                    db.consents().insert(
                        ConsentRow(
                            key = key, granted = change.to == PreconditionValue.GRANTED, atUtc = now,
                            source = ConsentSource.SYSTEM, boundaryVersion = 1, shownTextHash = null,
                        ),
                    )
                }
                if (change.precondition == Precondition.ALARM_VOLUME && change.to == PreconditionValue.MUTED) {
                    event(EventType.DND_MAY_SUPPRESS, change, now)
                }
            }
            db.settings().put(SettingRow(SettingKeys.PERMISSION_SNAPSHOT, Codec.encodePayload(snapshot.toTokens())))
        }
        return changes
    }

    private suspend fun event(type: EventType, change: PreconditionChange, now: Long) {
        db.events().insert(
            EventRow(
                occurrenceId = null, missionId = null, type = type, atUtc = now, elapsedRealtime = clock.elapsedRealtimeMs(),
                bootId = null, source = EventSource.SYSTEM,
                payloadJson = Codec.encodePayload(EventPayload.of("key" to change.precondition, "value" to change.to, "from" to change.from).values),
            ),
        )
    }
}
