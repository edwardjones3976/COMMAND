package com.command.data

import androidx.room.withTransaction
import com.command.data.db.CalendarLinkRow
import com.command.data.db.CalendarSourceRow
import com.command.data.db.CommandDb
import com.command.data.db.MissionRow
import com.command.data.db.SettingRow
import com.command.domain.calendar.CalendarInstance
import com.command.domain.calendar.CalendarLink
import com.command.domain.calendar.CalendarRead
import com.command.domain.calendar.CalendarPlan
import com.command.domain.calendar.CalendarReader
import com.command.domain.calendar.CalendarReconciler
import com.command.domain.calendar.ReadFailure
import com.command.domain.dials.Flexibility
import com.command.domain.model.ConsentKey
import com.command.domain.model.EventSource
import com.command.domain.model.MissionSource
import com.command.domain.model.SettingKeys
import com.command.domain.model.StartKind
import com.command.domain.model.ZoneMode
import com.command.domain.occurrence.EventPayload
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import com.command.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class CalendarSyncOutcome {
    /** Import is not switched on (no consent, or no calendar selected and nothing left from one): there is no reason to run again. */
    OFF,

    /** The permission is gone. Everything armed stays armed; a grant and the next run put it right. */
    PERMISSION_LOST,
    DONE,

    /** The read failed. Nothing was changed; try again. */
    RETRY,
}

/**
 * Brings the imported occurrences in line with the phone's calendars. The whole plan commits in one transaction, and its alarms are only
 * touched afterwards. Arming, moving an armed alarm, and holding back during a pause, are left to [MissionRepository.reconcile], which does that for every
 * occurrence there is. Nothing is ever invented: an empty reader gives no rows.
 */
@Singleton
class CalendarSync @Inject constructor(
    private val db: CommandDb,
    private val occurrences: OccurrenceRepository,
    private val missions: MissionRepository,
    private val consents: ConsentStore,
    private val reader: CalendarReader,
    private val clock: Clock,
) {
    private val gate = Mutex()

    fun observeSources(): Flow<List<CalendarSourceRow>> = db.calendarDao().observeSources()

    fun hasPermission(): Boolean = reader.hasPermission()

    /** Lists the phone's calendars for the person to choose from. A new one starts unselected. */
    suspend fun refreshSources() = gate.withLock {
        if (!consents.isGranted(ConsentKey.CALENDAR_READ)) return@withLock
        val found = reader.calendars() ?: return@withLock
        db.withTransaction {
            // Withdrawn while the list was read: the cascade has run, and nothing is stored behind it.
            if (!consents.isGranted(ConsentKey.CALENDAR_READ)) return@withTransaction
            val dao = db.calendarDao()
            val known = dao.sources().associateBy { it.calendarId }
            for (c in found) {
                val old = known[c.id]
                dao.insertSource(
                    old?.copy(account = c.account, displayName = c.displayName)
                        ?: CalendarSourceRow(c.id, c.account, c.displayName, false, null, Flexibility.FLEXIBLE, null),
                )
            }
        }
    }

    /** The person agreed on the Calendars screen: their calendars can be listed as soon as the consent is on record. */
    suspend fun allow() {
        consents.grant(ConsentKey.CALENDAR_READ)
        refreshSources()
    }

    /** Waits for a sync in progress, which must reconcile against the same selection that it read. */
    suspend fun setSelected(calendarId: Long, selected: Boolean) = gate.withLock {
        db.calendarDao().setSelected(calendarId, selected)
    }

    suspend fun setDefaults(calendarId: Long, flexibility: Flexibility, profileId: String?) = gate.withLock {
        db.calendarDao().setDefaults(calendarId, flexibility, profileId)
    }

    suspend fun run(): CalendarSyncOutcome = gate.withLock {
        val dao = db.calendarDao()
        if (!consents.isGranted(ConsentKey.CALENDAR_READ)) return@withLock CalendarSyncOutcome.OFF
        val links = dao.links()
        if (!dao.anySelected() && links.isEmpty()) return@withLock CalendarSyncOutcome.OFF
        if (!reader.hasPermission()) return@withLock permissionLost()

        val now = clock.nowMs()
        val selected = dao.sources().filter { it.selected }.map { it.calendarId }.toSet()
        val parents = links.flatMap { listOfNotNull(it.originalId, it.eventId) }.toSet()
        val read = reader.read(now - CalendarReconciler.BEFORE_NOW_MS, now + CalendarReconciler.AFTER_NOW_MS, selected, parents)
        when (read) {
            CalendarRead.PermissionLost -> permissionLost()
            is CalendarRead.Failed -> readFailed(read.why)
            // Every calendar gone at once while some are known is a provider that is not ready, not a person who removed them all.
            is CalendarRead.Ok ->
                if (read.calendars.isEmpty() && dao.sources().isNotEmpty()) {
                    readFailed(ReadFailure.NO_CALENDARS)
                } else {
                    // The moved alarms first, so that none is left at its old time should the reconcile fail.
                    for (id in apply(read, now)) occurrences.rearm(id)
                    missions.reconcile(ReconcileTrigger.CALENDAR_SYNC)
                    CalendarSyncOutcome.DONE
                }
        }
    }

    private suspend fun readFailed(why: ReadFailure): CalendarSyncOutcome {
        occurrences.logEvent(EventType.CALENDAR_READ_FAILED, null, EventSource.WORKER, EventPayload.of("why" to why))
        return CalendarSyncOutcome.RETRY
    }

    /** Written down once each time it is lost, not every hour for as long as it stays that way. */
    private suspend fun permissionLost(): CalendarSyncOutcome {
        if (db.settings().get(SettingKeys.CALENDAR_PERMISSION_LOST) != "true") {
            db.settings().put(SettingRow(SettingKeys.CALENDAR_PERMISSION_LOST, "true"))
            occurrences.logEvent(EventType.CALENDAR_PERMISSION_LOST, null, EventSource.WORKER, EventPayload.EMPTY)
        }
        return CalendarSyncOutcome.PERMISSION_LOST
    }

    /** Commits the plan and returns the occurrences it moved. */
    private suspend fun apply(read: CalendarRead.Ok, now: Long): List<String> {
        val moved = ArrayList<String>()
        val dao = db.calendarDao()
        occurrences.inTransaction(EventSource.WORKER) { tx ->
            // Consent withdrawn while the read ran: the cascade has already cleared everything, and nothing may come back behind it.
            if (!consents.isGranted(ConsentKey.CALENDAR_READ)) return@inTransaction
            db.settings().put(SettingRow(SettingKeys.CALENDAR_PERMISSION_LOST, "false"))

            val known = dao.sources().associateBy { it.calendarId }
            val present = read.calendars.map { it.id }.toSet()
            for (gone in known.keys - present) dao.deleteSource(gone)
            for (c in read.calendars) {
                dao.insertSource(
                    known[c.id]?.copy(account = c.account, displayName = c.displayName, lastSyncAt = now)
                        ?: CalendarSourceRow(c.id, c.account, c.displayName, false, null, Flexibility.FLEXIBLE, now),
                )
            }
            val sources = dao.sources().associateBy { it.calendarId }
            val selected = sources.values.filter { it.selected }.map { it.calendarId }.toSet()

            val rows = dao.links().associateBy { it.occurrenceId }
            val links = rows.values.map { row ->
                val state = db.occurrences().get(row.occurrenceId)?.state
                CalendarLink(
                    row.occurrenceId, row.calendarId, row.eventId, row.instanceBeginMs, row.originalId, row.originalInstanceMs, row.eventTz, row.misses,
                    unfired = state == OccurrenceState.SCHEDULED || state == OccurrenceState.ARMED,
                )
            }
            val plan = CalendarReconciler.reconcile(links, read.instances, selected, now, read.goneEventIds)

            // The missions of what is linked or about to be: their title, length and the calendar's defaults follow the calendar.
            val wanted = plan.creates.map { it.calendarId to (it.originalId ?: it.eventId) }.toSet() +
                links.map { it.calendarId to (it.originalId ?: it.eventId) }
            read.instances
                .filter { !it.allDay && !it.cancelled && !it.deleted && it.calendarId in selected }
                .groupBy { it.calendarId to (it.originalId ?: it.eventId) }
                .filterKeys { it in wanted }
                .forEach { (key, group) -> upsertMission(key.first, key.second, group, sources.getValue(key.first)) }

            // First, because each can free a slot that a move below needs. The link goes with it: the calendar took this away, and if the
            // calendar brings it back (a calendar selected again, an instance the provider lost for a while) it must ring again.
            for (skip in plan.skips) {
                if (tx.apply(skip.occurrenceId, OccurrenceCommand.Skip(skip.reason), EventSource.WORKER) is ApplyResult.Applied) dao.deleteLink(skip.occurrenceId)
            }

            // A series moved as a whole lands each instance on the next one's slot, so whichever has a free slot goes first.
            var pending = plan.moves
            while (pending.isNotEmpty()) {
                val blocked = pending.filter { move ->
                    val own = db.occurrences().get(move.occurrenceId)
                    if (own != null) freeSlot(own.missionId, move.instance.beginMs)
                    val done = tx.reschedule(move.occurrenceId, move.instance.beginMs, move.instance.eventTz ?: clock.zone().id)
                    if (done) {
                        dao.updateLink(rowOf(move.instance, move.occurrenceId, now))
                        moved += move.occurrenceId
                    }
                    !done
                }
                if (blocked.size == pending.size) {
                    // Blocked by something that is not going to move: rung or answered at that very time, or a sibling that stays and will ring
                    // then anyway. That instant has its alarm, and one left at the old time would be wrong. Decided before any is given up, because
                    // giving one up changes what the next one finds. Whatever was waiting behind those gets its turn.
                    val waiting = blocked.map { it.occurrenceId }.toSet()
                    val givenUp = blocked.filter { move ->
                        val own = db.occurrences().get(move.occurrenceId)
                        val occupant = own?.let { db.occurrences().find(it.missionId, move.instance.beginMs) }
                        occupant != null && occupant.id !in waiting
                    }
                    if (givenUp.isNotEmpty()) {
                        for (move in givenUp) {
                            if (tx.apply(move.occurrenceId, OccurrenceCommand.Skip(SkipReason.CALENDAR_REMOVED), EventSource.WORKER) is ApplyResult.Applied) dao.deleteLink(move.occurrenceId)
                        }
                        pending = blocked - givenUp.toSet()
                    } else {
                        // Instances that block only each other (two swapped ones): the alarms already stand on the times the calendar wants, so
                        // the links change hands and no alarm moves. Left waiting, the first to ring would spend the other's instance, and the next
                        // sync would skip the other as moved into the past.
                        val swapped = cyclesIn(blocked)
                        if (swapped.isEmpty()) break
                        for (move in swapped) {
                            val own = db.occurrences().get(move.occurrenceId) ?: continue
                            val occupant = db.occurrences().find(own.missionId, move.instance.beginMs) ?: continue
                            dao.updateLink(rowOf(move.instance, occupant.id, now))
                        }
                        pending = blocked - swapped.toSet()
                    }
                } else {
                    pending = blocked
                }
            }

            // Last: a new instance may want a slot that a skip or a move above has just left.
            for (i in plan.creates) {
                val mission = db.missions().get(missionIdOf(i.calendarId, i.originalId ?: i.eventId))
                // The person deleted this event's mission: it stays deleted.
                if (mission == null || mission.deletedAt != null) continue
                freeSlot(mission.id, i.beginMs)
                val created = tx.create(mission.id, i.beginMs, i.eventTz ?: clock.zone().id)
                // The slot is taken by another instance of the same event, which is about to ring anyway: never a second alarm.
                if (created.isNew) dao.addLink(rowOf(i, created.occurrence.id, now))
            }

            for (update in plan.linkUpdates) {
                val old = rows.getValue(update.link.occurrenceId)
                dao.updateLink(
                    old.copy(
                        eventId = update.link.eventId, originalId = update.link.originalId, originalInstanceMs = update.link.originalInstanceMs, eventTz = update.link.eventTz, misses = update.link.misses,
                        lastSeenAt = if (update.seen) now else old.lastSeenAt,
                    ),
                )
            }
        }
        return moved
    }

    /** The moves that lead round to themselves, each wanting the slot of the next. One that merely wants a slot in such a ring is not among them. */
    private suspend fun cyclesIn(moves: List<CalendarPlan.Move>): List<CalendarPlan.Move> {
        val occupantOf = HashMap<String, String>()
        for (move in moves) {
            val own = db.occurrences().get(move.occurrenceId) ?: continue
            val occupant = db.occurrences().find(own.missionId, move.instance.beginMs) ?: continue
            occupantOf[move.occurrenceId] = occupant.id
        }
        return moves.filter { move ->
            var at = occupantOf[move.occurrenceId]
            var steps = 0
            while (at != null && at != move.occurrenceId && steps < moves.size) {
                at = occupantOf[at]
                steps++
            }
            at == move.occurrenceId
        }
    }

    /** A row the calendar took away only holds a time slot, and the calendar now wants that slot: its history is replaced by what follows. */
    private suspend fun freeSlot(missionId: String, at: Long) {
        val row = db.occurrences().find(missionId, at) ?: return
        if (row.state == OccurrenceState.SKIPPED && (row.skipReason == SkipReason.CALENDAR_CANCELLED || row.skipReason == SkipReason.CALENDAR_REMOVED)) {
            db.occurrences().delete(row.id)
        }
    }

    private suspend fun upsertMission(calendarId: Long, parent: Long, group: List<CalendarInstance>, source: CalendarSourceRow) {
        val first = group.filter { it.originalId == null }.minByOrNull { it.beginMs } ?: group.minBy { it.beginMs }
        val id = missionIdOf(calendarId, parent)
        val old = db.missions().get(id)
        if (old?.deletedAt != null) return
        val title = first.title.trim().ifEmpty { UNTITLED }
        val row = MissionRow(
            id = id, title = title, firstAction = "Open: $title", category = CATEGORY, priority = 2,
            flexibility = source.defaultFlexibility, rigidWeekdays = null, source = MissionSource.DEVICE_CALENDAR,
            startKind = StartKind.ONE_OFF, startAtUtc = first.beginMs, dtstartLocal = null, rrule = null,
            zoneMode = ZoneMode.DEVICE, zoneId = null, durationMin = ((first.endMs - first.beginMs) / 60_000L).toInt().coerceAtLeast(1),
            starterMinutes = old?.starterMinutes ?: MissionRepository.DEFAULT_STARTER_MINUTES, profileId = source.defaultProfileId,
            lockPolicy = null, launcherPackage = null, firstActionVariantsJson = "[]", enabled = true, deletedAt = null, syncRev = 0, syncDirty = false,
        )
        when {
            old == null -> db.missions().insert(row)
            old != row -> db.missions().update(row)
        }
    }

    private fun rowOf(i: CalendarInstance, occurrenceId: String, now: Long) =
        CalendarLinkRow(occurrenceId, i.calendarId, i.eventId, i.beginMs, i.originalId, i.originalInstanceMs, i.eventTz, now, 0)

    companion object {
        const val CATEGORY = "Calendar"
        private const val UNTITLED = "Untitled event"

        /** One mission per event: the same event always finds the same mission, and all its instances share it. */
        fun missionIdOf(calendarId: Long, parentEventId: Long): String =
            UUID.nameUUIDFromBytes("calendar:$calendarId:$parentEventId".toByteArray()).toString()
    }
}
