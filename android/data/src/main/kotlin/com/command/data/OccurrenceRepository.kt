package com.command.data

import androidx.room.withTransaction
import com.command.data.db.ChallengeRow
import com.command.data.db.CheckInRow
import com.command.data.db.CommandDb
import com.command.data.db.EventRow
import com.command.data.db.OccurrenceRow
import com.command.data.db.ProfileRow
import com.command.data.db.RestWindowRow
import com.command.data.db.SettingRow
import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
import com.command.domain.alarm.AlarmScheduler
import com.command.domain.alarm.CheckInPoster
import com.command.domain.alarm.RingController
import com.command.domain.alarm.WindowRoller
import com.command.domain.alarm.WorkEnqueuer
import com.command.domain.dials.CheckInSchedule
import com.command.domain.dials.DialEffects
import com.command.domain.dials.Dials
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.MissionLock
import com.command.domain.dials.Presets
import com.command.domain.dials.PresetOrigin
import com.command.domain.model.CheckInAnswer
import com.command.domain.model.EventSource
import com.command.domain.model.RingMode
import com.command.domain.model.SettingKeys
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.ChildKind
import com.command.domain.occurrence.Effect
import com.command.domain.occurrence.EventPayload
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceSnapshot
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.OccurrenceStateMachine
import com.command.domain.occurrence.SkipReason
import com.command.domain.occurrence.TransitionResult
import com.command.domain.schedule.RestWindow
import com.command.domain.schedule.RestWindows
import com.command.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A challenge attempt to record with a transition (written by the handshake driver). */
data class ChallengeRecord(
    val chainPosition: Int,
    val mode: com.command.domain.dials.HandshakeMode,
    val attemptNo: Int,
    val outcome: com.command.domain.model.ChallengeOutcome,
    val startedAt: Long,
    val endedAt: Long,
)

/** Extra facts that travel with a command and are stored in the same transaction. */
data class ApplyExtras(
    val challenges: List<ChallengeRecord> = emptyList(),
    val ringMode: RingMode? = null,
) {
    companion object { val NONE = ApplyExtras() }
}

sealed interface ApplyResult {
    data class Applied(val occurrence: OccurrenceRow) : ApplyResult

    /** Not legal from the current state (including stale alarms). Logged as REJECTED_TRANSITION; nothing changed. */
    data class Rejected(val from: OccurrenceState, val command: String) : ApplyResult

    data class NeedsFriction(val required: LockPolicy) : ApplyResult
    data class RecoveryExhausted(val offersUsed: Int) : ApplyResult

    /** The row moved while the decision was being made. Re-read and decide again. */
    data object Stale : ApplyResult

    data object NotFound : ApplyResult
}

data class Created(val occurrence: OccurrenceRow, val isNew: Boolean)

data class MissionBrief(val title: String, val firstAction: String, val starterMinutes: Int)

/**
 * The only writer of `occurrences.state`.
 *
 * One transaction reads the row, asks the pure state machine what follows, compare-and-sets the new state, appends the
 * audit events and any challenge or check-in rows, and creates a delayed child occurrence when asked. Effects that
 * touch the platform (alarms, background work, notifications) run only after the transaction has committed, so a
 * rolled-back change can never leave an alarm armed. If the process dies between the commit and the effects, the
 * boot/open-time reconciler re-derives every alarm from the database.
 */
@Singleton
class OccurrenceRepository @Inject constructor(
    private val db: CommandDb,
    private val clock: Clock,
    private val scheduler: AlarmScheduler,
    private val work: WorkEnqueuer,
    private val poster: CheckInPoster,
    private val roller: WindowRoller,
    private val ringController: RingController,
) {
    internal class Pending(val occurrence: OccurrenceRow, val effect: Effect, val nowMs: Long)

    /** Commands applied through this scope share one transaction; their platform effects run after it commits. */
    class Tx internal constructor(private val repo: OccurrenceRepository, private val pending: MutableList<Pending>) {
        suspend fun apply(id: String, command: OccurrenceCommand, source: EventSource, extras: ApplyExtras = ApplyExtras.NONE): ApplyResult =
            repo.applyInTx(id, command, source, extras, pending)
    }

    /**
     * Runs [block] in one transaction so several occurrences (and whatever else the caller writes through the same
     * database) change together or not at all. Alarms, work and notifications are only touched after the commit.
     */
    suspend fun <T> inTransaction(source: EventSource, block: suspend (Tx) -> T): T {
        val pending = ArrayList<Pending>()
        val result = db.withTransaction { block(Tx(this, pending)) }
        runEffects(pending, source)
        return result
    }

    // --- reads -------------------------------------------------------------------------------------------------

    suspend fun get(id: String): OccurrenceRow? = db.occurrences().get(id)

    fun observe(id: String): Flow<OccurrenceRow?> = db.occurrences().observe(id)

    /** The title of an occurrence's mission, for notifications and screens that need to name it. */
    suspend fun missionTitle(missionId: String): String? = db.missions().get(missionId)?.title

    /** What the screens show about a mission while one of its occurrences is being handled. */
    suspend fun missionBrief(missionId: String): MissionBrief? =
        db.missions().get(missionId)?.let { MissionBrief(it.title, it.firstAction, it.starterMinutes) }

    /** The seven dials in force for an occurrence: its mission's profile, else the active one. */
    suspend fun dialsOf(occurrence: OccurrenceRow): Dials = dialsFor(db.missions().get(occurrence.missionId)?.profileId)

    /** Records a handshake attempt that did not change the occurrence's state (a hold released too early). */
    suspend fun recordChallenge(occurrenceId: String, record: ChallengeRecord) {
        db.challenges().insert(ChallengeRow(0, occurrenceId, record.chainPosition, record.mode, record.attemptNo, record.outcome, record.startedAt, record.endedAt))
    }

    /** Appends an audit event that no state transition produced (a degraded ring, a dismissed notification). */
    suspend fun logEvent(type: EventType, occurrence: OccurrenceRow?, source: EventSource, payload: EventPayload) =
        log(type, occurrence, source, payload)

    // --- creation ----------------------------------------------------------------------------------------------

    /** Idempotent: the same mission at the same instant yields the same occurrence. */
    suspend fun createOccurrence(missionId: String, scheduledAtUtc: Long, zoneId: String, parentId: String? = null): Created =
        db.withTransaction { createInTx(missionId, scheduledAtUtc, zoneId, parentId, avoidCollision = false) }

    private suspend fun createInTx(
        missionId: String,
        scheduledAtUtc: Long,
        zoneId: String,
        parentId: String?,
        avoidCollision: Boolean,
    ): Created {
        var at = scheduledAtUtc
        val dao = db.occurrences()
        while (true) {
            val existing = dao.find(missionId, at)
            if (existing == null) break
            if (!avoidCollision) return Created(existing, isNew = false)
            at += 1_000 // a delayed child lands a second later rather than on top of its sibling
        }
        val row = OccurrenceRow(
            id = UUID.randomUUID().toString(),
            missionId = missionId,
            scheduledAtUtc = at,
            zoneId = zoneId,
            state = OccurrenceState.SCHEDULED,
            stateVersion = 0,
            requestCode = nextRequestCode(),
            armMode = ArmMode.NONE,
            ringMode = null,
            sessionJson = Codec.encodeSession(SessionData()),
            parentOccurrenceId = parentId,
            skipReason = null,
            missReason = null,
        )
        dao.insert(row)
        return Created(row, isNew = true)
    }

    private suspend fun nextRequestCode(): Int {
        val settings = db.settings()
        val current = settings.get(SettingKeys.NEXT_REQUEST_CODE)?.trim()?.toIntOrNull() ?: 1
        check(current < Int.MAX_VALUE) { "request codes exhausted" }
        settings.put(SettingRow(SettingKeys.NEXT_REQUEST_CODE, (current + 1).toString()))
        return current
    }

    // --- the single writer -------------------------------------------------------------------------------------

    suspend fun apply(
        occurrenceId: String,
        command: OccurrenceCommand,
        source: EventSource,
        extras: ApplyExtras = ApplyExtras.NONE,
    ): ApplyResult {
        val pending = ArrayList<Pending>()
        val result = db.withTransaction { applyInTx(occurrenceId, command, source, extras, pending) }
        if (result is ApplyResult.Applied) runEffects(pending, source)
        return result
    }

    private suspend fun applyInTx(
        occurrenceId: String,
        command: OccurrenceCommand,
        source: EventSource,
        extras: ApplyExtras,
        pending: MutableList<Pending>,
    ): ApplyResult {
        val occ = db.occurrences().get(occurrenceId) ?: return ApplyResult.NotFound
        val mission = db.missions().get(occ.missionId) ?: return ApplyResult.NotFound
        val now = clock.nowMs()
        val dials = dialsFor(mission.profileId)
        val lock = MissionLock.effectivePolicy(mission.flexibility, mission.lockPolicy, dials.scheduleRigidity)
        val session = Codec.decodeSession(occ.sessionJson)
        val snapshot = OccurrenceSnapshot(
            state = occ.state,
            dials = dials,
            lockPolicy = lock,
            recoveryOffers = session.recoveryOffers,
            checkInsAsked = session.checkInsAsked,
            restEndsAtUtc = restEndsAt(now),
        )

        when (val r = OccurrenceStateMachine.transition(snapshot, command, now)) {
            is TransitionResult.Illegal -> {
                log(EventType.REJECTED_TRANSITION, occ, source, EventPayload.of("from" to r.from, "command" to r.command))
                return ApplyResult.Rejected(r.from, r.command)
            }
            is TransitionResult.NeedsFriction -> return ApplyResult.NeedsFriction(r.required)
            is TransitionResult.RecoveryExhausted -> return ApplyResult.RecoveryExhausted(r.offersUsed)
            is TransitionResult.Applied -> {
                var newSession = session.copy(
                    recoveryOffers = r.snapshot.recoveryOffers,
                    checkInsAsked = r.snapshot.checkInsAsked,
                )
                r.effects.filterIsInstance<Effect.ArmCycleEnd>().firstOrNull()?.let {
                    val ring = DialEffects.alertPlan(dials.alertIntensity)
                    newSession = newSession.copy(
                        planEndUtc = now + it.totalMs,
                        planEndElapsed = clock.elapsedRealtimeMs() + it.totalMs,
                        ringStartUtc = now,
                        ringCycles = ring.cycles,
                        ringCycleMs = ring.cycleMs,
                        ringGapMs = ring.gapMs,
                    )
                }
                val armMode = r.effects.filterIsInstance<Effect.ArmAlarm>().firstOrNull()?.mode
                    ?: if (r.effects.any { it is Effect.DisarmAlarm }) ArmMode.NONE else occ.armMode
                val skip = (command as? OccurrenceCommand.Skip)?.reason ?: occ.skipReason
                val miss = (command as? OccurrenceCommand.Miss)?.reason ?: occ.missReason

                val changed = db.occurrences().compareAndSet(
                    id = occ.id,
                    expectedVersion = occ.stateVersion,
                    state = r.state,
                    sessionJson = Codec.encodeSession(newSession),
                    skipReason = skip,
                    missReason = miss,
                    armMode = armMode,
                    ringMode = extras.ringMode,
                )
                if (changed == 0) {
                    log(EventType.REJECTED_TRANSITION, occ, source, EventPayload.of("from" to occ.state, "command" to command.label, "why" to "STALE"))
                    return ApplyResult.Stale
                }

                for (event in r.events) log(event.type, occ, source, event.payload)
                for (c in extras.challenges) {
                    db.challenges().insert(ChallengeRow(0, occ.id, c.chainPosition, c.mode, c.attemptNo, c.outcome, c.startedAt, c.endedAt))
                }
                recordCheckIns(occ, command, r, now)

                val updated = db.occurrences().get(occ.id)!!
                for (effect in r.effects) {
                    when (effect) {
                        is Effect.ArmChild -> {
                            val child = createInTx(occ.missionId, effect.atUtcMs, occ.zoneId, occ.id, avoidCollision = true).occurrence
                            val childMode = if (scheduler.canScheduleExact()) ArmMode.EXACT else ArmMode.INEXACT
                            applyInTx(child.id, OccurrenceCommand.Arm(childMode), EventSource.SYSTEM, ApplyExtras.NONE, pending)
                        }
                        else -> pending += Pending(updated, effect, now)
                    }
                }
                return ApplyResult.Applied(updated)
            }
        }
    }

    private suspend fun recordCheckIns(occ: OccurrenceRow, command: OccurrenceCommand, r: TransitionResult.Applied, now: Long) {
        val checkIns = db.checkIns()
        for (effect in r.effects) {
            if (effect is Effect.PostCheckIn) {
                checkIns.upsert(CheckInRow(occ.id, effect.index, askedAt = now, answeredAt = null, answer = null, delayMin = null, lateMs = 0))
            }
        }
        val answer: CheckInAnswer? = when (command) {
            OccurrenceCommand.ReportStarted -> CheckInAnswer.STARTED
            OccurrenceCommand.ReportWorking -> CheckInAnswer.WORKING
            OccurrenceCommand.ReportDone -> CheckInAnswer.DONE
            OccurrenceCommand.RequestRecovery -> CheckInAnswer.NEED_RESET
            is OccurrenceCommand.Delay -> CheckInAnswer.DELAY
            is OccurrenceCommand.Skip -> CheckInAnswer.SKIP
            else -> if (r.events.any { it.type == EventType.CHECKIN_UNANSWERED } || r.state == OccurrenceState.UNCONFIRMED) CheckInAnswer.NO_RESPONSE else null
        }
        if (answer != null) {
            val delay = (command as? OccurrenceCommand.Delay)?.minutes
            checkIns.answerLatest(occ.id, now, answer, delay)
        }
    }

    // --- calendar moves ----------------------------------------------------------------------------------------

    /**
     * A moved calendar instance keeps its occurrence: only a scheduled or armed one can be moved, and an armed alarm
     * is replaced in place. Returns false if it cannot be moved (already fired, or the slot is taken).
     */
    suspend fun reschedule(occurrenceId: String, newAtUtc: Long, zoneId: String, source: EventSource = EventSource.WORKER): Boolean {
        var moved: OccurrenceRow? = null
        db.withTransaction {
            val occ = db.occurrences().get(occurrenceId) ?: return@withTransaction
            if (occ.state != OccurrenceState.SCHEDULED && occ.state != OccurrenceState.ARMED) return@withTransaction
            if (db.occurrences().find(occ.missionId, newAtUtc) != null) return@withTransaction
            db.occurrences().reschedule(occ.id, newAtUtc, zoneId)
            log(EventType.OCCURRENCE_MOVED, occ, source, EventPayload.of("from" to occ.scheduledAtUtc, "to" to newAtUtc))
            moved = db.occurrences().get(occ.id)
        }
        val row = moved ?: return false
        if (row.state == OccurrenceState.ARMED) {
            val mode = scheduler.arm(AlarmRequest(row.id, row.requestCode, AlarmKind.FIRE, row.scheduledAtUtc))
            if (mode != row.armMode) db.occurrences().setArmMode(row.id, mode)
        }
        return true
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private suspend fun dialsFor(profileId: String?): Dials {
        val profile: ProfileRow? = profileId?.let { db.profiles().get(it) }
            ?: db.settings().get(SettingKeys.ACTIVE_PROFILE_ID)?.trim('"')?.let { db.profiles().get(it) }
        return profile?.let {
            Dials(it.firmness, it.obnoxiousness, it.alertIntensity, it.followUpPersistence, it.handshakeDifficulty, it.scheduleRigidity, it.recoveryStructure)
        } ?: Presets.dialsFor(PresetOrigin.STEADY)
    }

    /** When the protected window containing [nowMs] ends, or null if the user is not in one. */
    private suspend fun restEndsAt(nowMs: Long): Long? {
        val rows: List<RestWindowRow> = db.restWindows().all()
        if (rows.isEmpty()) return null
        val windows = rows.mapNotNull { row ->
            runCatching {
                RestWindow(DayOfWeek.of(row.weekday), LocalTime.parse(row.startLocal), LocalTime.parse(row.endLocal), row.kind)
            }.getOrNull()
        }
        return RestWindows.endOf(windows, nowMs, clock.zone())
    }

    private suspend fun log(type: EventType, occ: OccurrenceRow?, source: EventSource, payload: EventPayload = EventPayload.EMPTY) {
        db.events().insert(
            EventRow(
                occurrenceId = occ?.id,
                missionId = occ?.missionId,
                type = type,
                atUtc = clock.nowMs(),
                elapsedRealtime = clock.elapsedRealtimeMs(),
                bootId = null,
                source = source,
                payloadJson = Codec.encodePayload(payload.values),
            ),
        )
    }

    /** Runs the platform side of a committed transition. Idempotent: every call replaces or cancels by identity. */
    private suspend fun runEffects(pending: List<Pending>, source: EventSource) {
        for (p in pending) {
            val occ = p.occurrence
            fun request(kind: AlarmKind, at: Long = occ.scheduledAtUtc, index: Int = 0) = AlarmRequest(occ.id, occ.requestCode, kind, at, index)
            when (val e = p.effect) {
                is Effect.ArmAlarm -> {
                    val actual = scheduler.arm(request(AlarmKind.FIRE))
                    if (actual != e.mode) {
                        db.occurrences().setArmMode(occ.id, actual)
                        log(EventType.ALARM_ARMED, occ, source, EventPayload.of("mode" to actual, "adjusted" to "true"))
                    }
                }
                Effect.DisarmAlarm -> scheduler.cancel(request(AlarmKind.FIRE))
                Effect.StopRinging -> ringController.stop(occ.id, occ.requestCode)
                is Effect.ArmCycleEnd -> scheduler.arm(request(AlarmKind.CYCLE_END, at = p.nowMs + e.totalMs))
                Effect.CancelCycleEnd -> scheduler.cancel(request(AlarmKind.CYCLE_END))
                is Effect.ScheduleCheckIn -> scheduler.arm(request(AlarmKind.CHECK_IN, at = e.atUtcMs, index = e.index))
                is Effect.PostCheckIn -> poster.post(occ.id, occ.requestCode, e.index)
                // Index == count is the final-timeout alarm, so the highest index in use is MAX_COUNT itself.
                Effect.CancelCheckIns -> for (i in 0..CheckInSchedule.MAX_COUNT) scheduler.cancel(request(AlarmKind.CHECK_IN, index = i))
                is Effect.ArmChild -> Unit // handled inside the transaction
                Effect.RollWindow -> roller.roll()
                is Effect.EnqueueWork -> work.enqueue(e.kind, occ.id)
            }
        }
    }
}
