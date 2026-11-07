package com.command.data

import androidx.room.withTransaction
import com.command.data.db.ChallengeRow
import com.command.data.db.CheckInRow
import com.command.data.db.CommandDb
import com.command.data.db.EventRow
import com.command.data.db.MissionRow
import com.command.data.db.OccurrenceRow
import com.command.data.db.ProfileRow
import com.command.data.db.RestWindowRow
import com.command.data.db.SettingRow
import com.command.data.db.VoicePackRow
import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
import com.command.domain.alarm.AlarmScheduler
import com.command.domain.alarm.CheckInPoster
import com.command.domain.alarm.CheckInPrompt
import com.command.domain.alarm.RingController
import com.command.domain.alarm.WindowRoller
import com.command.domain.alarm.WorkEnqueuer
import com.command.domain.coaching.CoachingContext
import com.command.domain.coaching.CoachingEngine
import com.command.domain.coaching.CoachingPhase
import com.command.domain.coaching.TemplateOverrides
import com.command.domain.voice.Line
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.CheckInSchedule
import com.command.domain.dials.DialEffects
import com.command.domain.dials.Dials
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.MissionLock
import com.command.domain.dials.Personality
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
import com.command.domain.occurrence.StarterWindow
import com.command.domain.handshake.Attempt
import com.command.domain.voice.SpeechLevel
import com.command.domain.voice.SpeechVoice
import com.command.domain.occurrence.TransitionResult
import com.command.domain.schedule.RestWindow
import com.command.domain.schedule.RestWindows
import com.command.domain.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    /**
     * The time the delivered alarm was set for, passed with a Fire. An armed occurrence that has moved since is not rung: Fire is rejected in
     * its own transaction, so that no move can commit between this decision and the ring, and the used-up alarm is set for the new time.
     * Null for an alarm that does not say.
     */
    val setForUtc: Long? = null,
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

/** What a ring asks of the person: the chain of challenges that the occurrence's profile calls for, and the personality whose words they are in. */
data class HandshakePlan(val chain: ChainSpec, val personality: Personality)

private val FOLLOWING_UP = listOf(OccurrenceState.GUIDED_START, OccurrenceState.STARTED_SELF_REPORTED, OccurrenceState.WORKING_SELF_REPORTED)

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
    private val profiles = ProfileResolver(db)

    /**
     * A command's platform effects run after its transaction has committed, outside the database, so two commands for one occurrence must
     * not interleave there: a later cancel could otherwise be undone by an earlier command's effect that was still on its way (a question
     * posted for an occurrence that is already done, a follow-up alarm armed after it was cancelled). One lock per occurrence, from a fixed
     * set picked by its id so that there is nothing to clean up, is held from the start of [apply]'s transaction to the end of its effects.
     * No effect calls back into [apply], so nothing waits on itself. All of it runs on [Dispatchers.Default], so that whoever holds a lock
     * never needs the main looper to go on: a caller that blocks the main thread (a test, `runBlocking`) would otherwise wait for a lock held
     * by a command that was started on that thread and cannot resume without it. Commands that share a transaction through [inTransaction]
     * span several occurrences and are not covered.
     */
    private val locks = Array(OCCURRENCE_LOCKS) { Mutex() }

    private fun lockOf(occurrenceId: String): Mutex = locks[(occurrenceId.hashCode() and Int.MAX_VALUE) % OCCURRENCE_LOCKS]

    internal class Pending(val occurrence: OccurrenceRow, val effect: Effect, val nowMs: Long)

    /** Commands applied through this scope share one transaction; their platform effects run after it commits. */
    class Tx internal constructor(private val repo: OccurrenceRepository, private val pending: MutableList<Pending>) {
        suspend fun apply(id: String, command: OccurrenceCommand, source: EventSource, extras: ApplyExtras = ApplyExtras.NONE): ApplyResult =
            repo.applyInTx(id, command, source, extras, pending)

        /** [createOccurrence] inside this transaction. */
        suspend fun create(missionId: String, scheduledAtUtc: Long, zoneId: String): Created =
            repo.createInTx(missionId, scheduledAtUtc, zoneId, null, avoidCollision = false)

        /**
         * The row only, inside this transaction. The caller puts an armed alarm back afterwards with [OccurrenceRepository.rearm], which looks at
         * the row as it then is: an effect queued here could re-arm something answered in between.
         */
        suspend fun reschedule(occurrenceId: String, newAtUtc: Long, zoneId: String): Boolean =
            repo.rescheduleInTx(occurrenceId, newAtUtc, zoneId, EventSource.WORKER) != null
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

    /** Everything recorded about one occurrence, or null if there is none with that id (or its mission is gone from the database). */
    suspend fun detail(id: String): OccurrenceDetail? = db.withTransaction {
        // One snapshot: the story reads the row (why it was missed) and the events together, and a command commits both at once.
        val occurrence = db.occurrences().get(id) ?: return@withTransaction null
        val mission = db.missions().get(occurrence.missionId) ?: return@withTransaction null
        OccurrenceDetail(occurrence, mission.title, db.events().forOccurrence(id), db.checkIns().forOccurrence(id), db.challenges().forOccurrence(id))
    }

    fun observe(id: String): Flow<OccurrenceRow?> = db.occurrences().observe(id)

    /**
     * [detail], followed: it is read again whenever anything it is made of is written, the row, its mission, its events, its questions or its
     * attempts. Some of those are written without the occurrence changing (a note that the voice was unavailable, a hold released early),
     * and a page that is open must show them.
     */
    fun observeDetail(id: String): Flow<OccurrenceDetail?> =
        db.invalidationTracker.createFlow("occurrences", "missions", "events", "check_ins", "challenges").map { detail(id) }

    /** What the database says is ringing. A ring that was killed stays here, so only the plan and the live service can say whether it is sounding. */
    suspend fun firing(): List<OccurrenceRow> = db.occurrences().inStates(listOf(OccurrenceState.FIRING))

    /** The title of an occurrence's mission, for notifications and screens that need to name it. */
    suspend fun missionTitle(missionId: String): String? = db.missions().get(missionId)?.title

    /** What the screens show about a mission while one of its occurrences is being handled. */
    suspend fun missionBrief(missionId: String): MissionBrief? =
        db.missions().get(missionId)?.let { MissionBrief(it.title, it.firstAction, it.starterMinutes) }

    /**
     * What the coaching engine says for [occurrence] at [phase], in the personality the ring was fired with and the dials now in force. It reads
     * the mission's title, first step and starter length and nothing else. [withMissionWords] false leaves the mission's words out, for a
     * place that already shows them.
     */
    suspend fun lineFor(occurrence: OccurrenceRow, phase: CoachingPhase, attempt: Int = 0, withMissionWords: Boolean = true, minutes: Int? = null): Line {
        val brief = missionBrief(occurrence.missionId)
        val ctx = CoachingContext(
            title = if (withMissionWords) brief?.title.orEmpty() else "",
            firstAction = if (withMissionWords) brief?.firstAction.orEmpty() else "",
            minutes = minutes ?: brief?.starterMinutes ?: 0,
        )
        return CoachingEngine.compose(handshakeOf(occurrence).personality, phase, dialsOf(occurrence), ctx, attempt, occurrence.id, overrides())
    }

    /** The starter begun for [occurrence] in this round of guidance, as `session_json` has it; null when none is. */
    fun starterOf(occurrence: OccurrenceRow): StarterWindow? = starterOf(Codec.decodeSession(occurrence.sessionJson))

    private fun starterOf(session: SessionData): StarterWindow? {
        val start = session.starterStartUtc ?: return null
        val deadline = session.starterDeadlineUtc ?: return null
        return StarterWindow(start, deadline)
    }

    /** True while the words "you passed the handshake" are true and news: it was acknowledged, and no recovery has come since. */
    suspend fun handshakeJustPassed(occurrence: OccurrenceRow): Boolean =
        Codec.decodeSession(occurrence.sessionJson).recoveryOffers == 0 && db.events().wasAcknowledged(occurrence.id)

    /** Follows whether this ring's line was not heard (see [com.command.data.db.EventDao.observeLineNotSpoken]): from then on the screen shows it. */
    fun observeLineNotSpoken(occurrenceId: String): Flow<Boolean> = db.events().observeLineNotSpoken(occurrenceId)

    /**
     * "Acknowledged without a start, N times": how many of the mission's occurrences before [beforeUtc] were acknowledged with no start reported,
     * counting back to the last one with a start. Other missions do not count, and neither does the occurrence at [beforeUtc] itself, which has
     * not had its chance yet.
     */
    suspend fun acknowledgedWithoutStart(missionId: String, beforeUtc: Long): Int = db.occurrences().acknowledgedWithoutStart(missionId, beforeUtc)

    /** A row that cannot be read (from a newer version, say) costs the person's own wording, never the line. */
    private suspend fun overrides(): TemplateOverrides = try {
        db.templateOverrides().all().toOverrides()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TemplateOverrides()
    }

    /** The question is asked whatever happens to its wording: without a line it is the plain one, and the effects after it still run. */
    private suspend fun checkInLine(occ: OccurrenceRow, index: Int): Line? = try {
        lineFor(occ, CoachingPhase.CHECK_IN, index, withMissionWords = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The seven dials in force for an occurrence: its mission's profile, else the active one. */
    suspend fun dialsOf(occurrence: OccurrenceRow): Dials = dialsFor(db.missions().get(occurrence.missionId))

    /**
     * The handshake an occurrence asks for: the chain the person saved for its profile, if there is one that can be used ([ChainJson]),
     * otherwise the default chain of a handshake difficulty, and a personality, whose phrases the typing challenge uses. All of them are
     * the ones the ring was fired with, so that a profile edited while it sounds changes nothing for the person in front of it. A ring
     * from before the difficulty was pinned (before M8b) follows the profile; one from before the chain was pinned (M8b pinned the
     * difficulty and the personality) asks for the standard chain of its difficulty.
     */
    suspend fun handshakeOf(occurrence: OccurrenceRow): HandshakePlan {
        val pinned = Codec.decodeSession(occurrence.sessionJson)
        val profile = profiles.resolve(db.missions().get(occurrence.missionId))
        val difficulty = pinned.handshakeDifficulty ?: profile?.handshakeDifficulty ?: Presets.dialsFor(PresetOrigin.STEADY).handshakeDifficulty
        val saved = if (pinned.handshakeDifficulty != null) pinned.handshakeChainJson else profile?.handshakeChainJson
        val personality = pinned.handshakePersonality?.let { name -> Personality.values().firstOrNull { it.name == name } }
            ?: profile?.personality
            ?: Personality.CUSTOM
        return HandshakePlan(ChainJson.decode(saved) ?: DialEffects.defaultChain(difficulty), personality)
    }

    /**
     * How loud an occurrence's spoken line is: what the ring was fired with, so that a profile edited while it sounds changes nothing. A ring
     * from before these were pinned follows the profile. A level that cannot be read costs the setting, never the line: it is the louder choice.
     */
    suspend fun speechLevelOf(occurrence: OccurrenceRow): SpeechLevel = try {
        val pinned = Codec.decodeSession(occurrence.sessionJson)
        val mission = db.missions().get(occurrence.missionId)
        val intensity = pinned.alertIntensity ?: dialsFor(mission).alertIntensity
        val trim = pinned.voiceTrimDb ?: voiceTrimOf(profiles.resolve(mission))
        SpeechLevel(intensity, trim)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SpeechLevel.LOUDEST
    }

    /**
     * Who says an occurrence's line: the voice the ring was fired with, so that a pack chosen or edited while it sounds changes nothing. A ring
     * from before this was pinned follows the profile. A voice that cannot be read costs the setting, never the line.
     */
    suspend fun speechVoiceOf(occurrence: OccurrenceRow): SpeechVoice = try {
        val pinned = Codec.decodeSession(occurrence.sessionJson)
        val pitch = pinned.voicePitch
        if (pitch != null) {
            SpeechVoice(pinned.voiceLanguageTag, pitch, pinned.voiceRate ?: 1f, pinned.voiceFemale)
        } else {
            voiceOf(profiles.resolve(db.missions().get(occurrence.missionId)))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SpeechVoice.DEFAULT
    }

    /** The voice of the profile's pack; the phone's own when it has no pack, or one that is gone. */
    private suspend fun voiceOf(profile: ProfileRow?): SpeechVoice =
        packOf(profile)?.speechVoice() ?: SpeechVoice.DEFAULT

    /** The loudness trim of the profile's voice pack; none when it has no pack, or one that is gone. */
    private suspend fun voiceTrimOf(profile: ProfileRow?): Float = packOf(profile)?.loudnessTrimDb ?: 0f

    /** The pack of a profile, if it can be read at all: a ring is fired inside this read, and a pack is not worth stopping a ring for. */
    private suspend fun packOf(profile: ProfileRow?): VoicePackRow? = try {
        profile?.voicePackId?.let { db.voicePacks().get(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Every try recorded for [occurrenceId], oldest first: what a ring that is shown again is restored from. */
    suspend fun attemptsOf(occurrenceId: String): List<Attempt> =
        db.challenges().forOccurrence(occurrenceId).map { Attempt(it.chainPosition, it.mode, it.attemptNo, it.outcome) }

    /** Records a handshake attempt that did not change the occurrence's state (a hold released too early). */
    suspend fun recordChallenge(occurrenceId: String, record: ChallengeRecord) {
        db.challenges().insert(ChallengeRow(0, occurrenceId, record.chainPosition, record.mode, record.attemptNo, record.outcome, record.startedAt, record.endedAt))
    }

    /**
     * The rows of one event, written together: a restart never finds a try without the change of mode that it caused. They are written only
     * while the ring is still going: an alarm that was silenced, skipped, put off or missed while the event waited for its turn takes no
     * more rows, which would otherwise sit in its history after its end. True when they were written (nothing to write counts as written).
     */
    suspend fun recordChallenges(occurrenceId: String, records: List<ChallengeRecord>): Boolean {
        if (records.isEmpty()) return true
        return db.withTransaction {
            if (db.occurrences().get(occurrenceId)?.state != OccurrenceState.FIRING) return@withTransaction false
            for (r in records) db.challenges().insert(ChallengeRow(0, occurrenceId, r.chainPosition, r.mode, r.attemptNo, r.outcome, r.startedAt, r.endedAt))
            true
        }
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
    ): ApplyResult = withContext(Dispatchers.Default) {
        lockOf(occurrenceId).withLock {
            val pending = ArrayList<Pending>()
            val result = db.withTransaction { applyInTx(occurrenceId, command, source, extras, pending) }
            // What a rejected command queues is only the alarm of a Fire that came for a time since moved.
            if (result is ApplyResult.Applied || pending.isNotEmpty()) runEffects(pending, source)
            result
        }
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
        if (extras.setForUtc != null && occ.state == OccurrenceState.ARMED && occ.scheduledAtUtc != extras.setForUtc) {
            log(EventType.REJECTED_TRANSITION, occ, source, EventPayload.of("from" to occ.state, "command" to command.label, "why" to "MOVED", "set_for" to extras.setForUtc))
            pending += Pending(occ, Effect.ArmAlarm(occ.armMode), now)
            return ApplyResult.Rejected(occ.state, command.label)
        }
        val dials = dialsFor(mission)
        val lock = MissionLock.effectivePolicy(mission.flexibility, mission.lockPolicy, dials.scheduleRigidity)
        val session = Codec.decodeSession(occ.sessionJson)
        // A test alarm is the one thing a pause lets through: the person asked for it, there and then.
        val pausedUntil = if (mission.category == MissionRepository.TEST_CATEGORY) null else PauseStore.active(db, now)?.untilUtcMs
        val snapshot = OccurrenceSnapshot(
            state = occ.state,
            dials = dials,
            lockPolicy = lock,
            recoveryOffers = session.recoveryOffers,
            checkInsAsked = session.checkInsAsked,
            restEndsAtUtc = restEndsAt(now, pausedUntil),
            zoneId = occ.zoneId,
            pausedUntilUtc = pausedUntil,
            starterMinutes = mission.starterMinutes,
            starter = starterOf(session),
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
                    starterStartUtc = r.snapshot.starter?.startUtc,
                    starterDeadlineUtc = r.snapshot.starter?.deadlineUtc,
                )
                // The alarm of a follow-up that has come due has done its work; what the effects ask for last is what should be set now.
                if (command is OccurrenceCommand.CheckInDue) newSession = newSession.copy(pendingCheckInIndex = null, pendingCheckInAtUtc = null)
                for (effect in r.effects) {
                    when (effect) {
                        Effect.CancelCheckIns -> newSession = newSession.copy(pendingCheckInIndex = null, pendingCheckInAtUtc = null)
                        is Effect.ScheduleCheckIn -> newSession = newSession.copy(pendingCheckInIndex = effect.index, pendingCheckInAtUtc = effect.atUtcMs)
                        else -> Unit
                    }
                }
                r.effects.filterIsInstance<Effect.ArmCycleEnd>().firstOrNull()?.let {
                    val ring = DialEffects.alertPlan(dials.alertIntensity)
                    val profile = profiles.resolve(mission)
                    val voice = voiceOf(profile)
                    newSession = newSession.copy(
                        planEndUtc = now + it.totalMs,
                        planEndElapsed = clock.elapsedRealtimeMs() + it.totalMs,
                        bootId = clock.bootId(),
                        ringStartUtc = now,
                        ringCycles = ring.cycles,
                        ringCycleMs = ring.cycleMs,
                        ringGapMs = ring.gapMs,
                        handshakeDifficulty = dials.handshakeDifficulty,
                        handshakePersonality = (profile?.personality ?: Personality.CUSTOM).name,
                        handshakeChainJson = profile?.handshakeChainJson,
                        alertIntensity = dials.alertIntensity,
                        voiceTrimDb = voiceTrimOf(profile),
                        voiceLanguageTag = voice.languageTag,
                        voicePitch = voice.pitch,
                        voiceRate = voice.rate,
                        voiceFemale = voice.female,
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
                            // A pause holds a delayed alarm back like any other that falls inside it; the end of the pause settles it.
                            if (pausedUntil == null || child.scheduledAtUtc >= pausedUntil) {
                                val childMode = if (scheduler.canScheduleExact()) ArmMode.EXACT else ArmMode.INEXACT
                                applyInTx(child.id, OccurrenceCommand.Arm(childMode), EventSource.SYSTEM, ApplyExtras.NONE, pending)
                            }
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
                val late = (command as? OccurrenceCommand.CheckInDue)?.lateMs ?: 0
                checkIns.upsert(CheckInRow(occ.id, effect.index, askedAt = now, answeredAt = null, answer = null, delayMin = null, lateMs = late))
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
        val row = db.withTransaction { rescheduleInTx(occurrenceId, newAtUtc, zoneId, source) } ?: return false
        if (row.state == OccurrenceState.ARMED) {
            val mode = arm(row, AlarmRequest(row.id, row.requestCode, AlarmKind.FIRE, row.scheduledAtUtc), source)
            if (mode != row.armMode) db.occurrences().setArmMode(row.id, mode)
        }
        return true
    }

    private suspend fun rescheduleInTx(occurrenceId: String, newAtUtc: Long, zoneId: String, source: EventSource): OccurrenceRow? {
        val occ = db.occurrences().get(occurrenceId) ?: return null
        if (occ.state != OccurrenceState.SCHEDULED && occ.state != OccurrenceState.ARMED) return null
        if (db.occurrences().find(occ.missionId, newAtUtc) != null) return null
        db.occurrences().reschedule(occ.id, newAtUtc, zoneId)
        log(EventType.OCCURRENCE_MOVED, occ, source, EventPayload.of("from" to occ.scheduledAtUtc, "to" to newAtUtc))
        return db.occurrences().get(occ.id)
    }

    /**
     * Removes occurrences that have not started to ring (SCHEDULED or ARMED) and cancels their alarms, one audit event
     * each. They are the plan, not history: an edit or a deletion regenerates them from the mission, and removing the
     * row (rather than keeping a tombstone) lets an edit be undone without a stale row holding the slot. Anything that
     * has rung is left alone. Returns how many were removed.
     */
    suspend fun discardUnfired(occurrenceIds: Collection<String>, source: EventSource): Int {
        val removed = removeUnfiredRows(occurrenceIds, source)
        cancelAlarms(removed)
        return removed.size
    }

    /**
     * The database half of [discardUnfired]. It can run inside a caller's transaction, so that a mission change and the
     * removal of its plan commit together or not at all; the caller then cancels the platform alarms with [cancelAlarms]
     * once that transaction has committed. Returns the rows it removed.
     */
    suspend fun removeUnfiredRows(occurrenceIds: Collection<String>, source: EventSource): List<OccurrenceRow> {
        val removed = ArrayList<OccurrenceRow>()
        db.withTransaction {
            for (id in occurrenceIds) {
                val occ = db.occurrences().get(id) ?: continue
                if (occ.state != OccurrenceState.SCHEDULED && occ.state != OccurrenceState.ARMED) continue
                db.occurrences().delete(id)
                log(EventType.OCCURRENCE_DISCARDED, occ, source, EventPayload.of("state" to occ.state))
                removed += occ
            }
        }
        return removed
    }

    /** Takes the platform alarms of removed (never rung) occurrences down. Idempotent. */
    fun cancelAlarms(rows: List<OccurrenceRow>) {
        for (occ in rows) scheduler.cancel(AlarmRequest(occ.id, occ.requestCode, AlarmKind.FIRE, occ.scheduledAtUtc))
    }

    /** Puts the platform alarm of an armed occurrence back (idempotent) and records the precision actually obtained. */
    suspend fun rearm(occurrenceId: String): ArmMode? {
        val occ = db.occurrences().get(occurrenceId) ?: return null
        if (occ.state != OccurrenceState.ARMED) return null
        val mode = arm(occ, AlarmRequest(occ.id, occ.requestCode, AlarmKind.FIRE, occ.scheduledAtUtc), EventSource.SYSTEM)
        if (mode != occ.armMode) db.occurrences().setArmMode(occ.id, mode)
        return mode
    }

    /**
     * Sets (or replaces) the alarm that ends the ring of [occurrenceId] at [atUtc], the one [com.command.domain.occurrence.Effect.ArmCycleEnd] set when
     * it began. It takes its turn with every command and looks again, because the ring may have been answered since the caller read it, and a ring
     * that is over must not get an alarm back.
     */
    suspend fun armCycleEnd(occurrenceId: String, atUtc: Long) {
        withContext(Dispatchers.Default) {
            lockOf(occurrenceId).withLock {
                val row = db.occurrences().get(occurrenceId)
                if (row?.state == OccurrenceState.FIRING) {
                    arm(row, AlarmRequest(row.id, row.requestCode, AlarmKind.CYCLE_END, atUtc), EventSource.SYSTEM)
                }
            }
        }
    }

    /** What may have a follow-up question or a final timeout on the way. */
    suspend fun followingUp(): List<OccurrenceRow> = db.occurrences().inStates(FOLLOWING_UP)

    /**
     * Sets the follow-up alarm that [occurrenceId] should have, for the time it was due, and answers how the platform took it; null when it should
     * have none. Nothing else puts one back, so without this one that was refused or went with a reboot would leave the occurrence with no question
     * and no timeout. Setting it again only replaces it, and one already due rings at once.
     */
    suspend fun rearmCheckIn(occurrenceId: String): ArmMode? = withContext(Dispatchers.Default) {
        lockOf(occurrenceId).withLock {
            val row = db.occurrences().get(occurrenceId)?.takeIf { it.state in FOLLOWING_UP } ?: return@withLock null
            val session = Codec.decodeSession(row.sessionJson)
            val index = session.pendingCheckInIndex ?: return@withLock null
            val at = session.pendingCheckInAtUtc ?: return@withLock null
            arm(row, AlarmRequest(row.id, row.requestCode, AlarmKind.CHECK_IN, at, index), EventSource.SYSTEM)
        }
    }

    /** Every alarm goes through here, so that one the platform refuses is on the record, whichever kind it was. */
    private suspend fun arm(occurrence: OccurrenceRow, request: AlarmRequest, source: EventSource): ArmMode {
        val mode = scheduler.arm(request)
        if (mode == ArmMode.REFUSED) log(EventType.ALARM_CAP, occurrence, source, EventPayload.of("kind" to request.kind))
        return mode
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private suspend fun dialsFor(mission: MissionRow?): Dials {
        val profile: ProfileRow? = profiles.resolve(mission)
        return profile?.storedDials() ?: Presets.dialsFor(PresetOrigin.STEADY)
    }

    /**
     * When the protected time containing [nowMs] ends, or null if the person is not in any: the end of the rest window they are in or of a
     * global pause, whichever is later. A follow-up question is held back until then.
     */
    private suspend fun restEndsAt(nowMs: Long, pausedUntil: Long?): Long? {
        val windowEnd = restWindowEndsAt(nowMs)
        return if (pausedUntil == null) windowEnd else maxOf(pausedUntil, windowEnd ?: pausedUntil)
    }

    /** When the rest window (sleep, leisure, driving) containing [nowMs] ends, or null if the person is not in one. */
    private suspend fun restWindowEndsAt(nowMs: Long): Long? {
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
                    val actual = arm(occ, request(AlarmKind.FIRE), source)
                    if (actual != e.mode) {
                        db.occurrences().setArmMode(occ.id, actual)
                        log(EventType.ALARM_ARMED, occ, source, EventPayload.of("mode" to actual, "adjusted" to "true"))
                    }
                }
                Effect.DisarmAlarm -> scheduler.cancel(request(AlarmKind.FIRE))
                Effect.StopRinging -> ringController.stop(occ.id, occ.requestCode)
                is Effect.ArmCycleEnd -> arm(occ, request(AlarmKind.CYCLE_END, at = p.nowMs + e.totalMs), source)
                Effect.CancelCycleEnd -> scheduler.cancel(request(AlarmKind.CYCLE_END))
                is Effect.ScheduleCheckIn -> arm(occ, request(AlarmKind.CHECK_IN, at = e.atUtcMs, index = e.index), source)
                is Effect.PostCheckIn -> {
                    val title = missionBrief(occ.missionId)?.title.orEmpty()
                    poster.post(CheckInPrompt(occ.id, occ.requestCode, e.index, title, started = occ.state != OccurrenceState.GUIDED_START, line = checkInLine(occ, e.index)))
                }
                // Index == count is the final-timeout alarm, so the highest index in use is MAX_COUNT itself.
                Effect.CancelCheckIns -> {
                    for (i in 0..CheckInSchedule.MAX_COUNT) scheduler.cancel(request(AlarmKind.CHECK_IN, index = i))
                    poster.cancel(occ.id)
                }
                is Effect.ArmChild -> Unit // handled inside the transaction
                Effect.RollWindow -> roller.roll()
                is Effect.EnqueueWork -> work.enqueue(e.kind, occ.id)
            }
        }
    }
}

/** After this many acknowledgements without a start the line offers something smaller, and nothing gets louder or harder (spec section 5). */
const val REPEATED_ACK_NO_START_AFTER = 2

/** How many locks there are to share out between occurrences by their id. */
private const val OCCURRENCE_LOCKS = 64
