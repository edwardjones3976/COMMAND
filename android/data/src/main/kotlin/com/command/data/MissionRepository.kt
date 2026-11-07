package com.command.data

import androidx.room.withTransaction
import com.command.data.db.CommandDb
import com.command.data.db.ConflictRow
import com.command.data.db.MissionRow
import com.command.data.db.ScheduleRow
import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
import com.command.domain.alarm.AlarmScheduler
import com.command.domain.alarm.PauseEffects
import com.command.domain.calendar.ConflictDetector
import com.command.domain.calendar.TimeInterval
import com.command.domain.dials.Flexibility
import com.command.domain.model.EventSource
import com.command.domain.model.MissionSource
import com.command.domain.model.StartKind
import com.command.domain.model.ZoneMode
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.EventPayload
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.schedule.Frequency
import com.command.domain.schedule.OccurrenceGenerator
import com.command.domain.schedule.RRule
import com.command.domain.schedule.RRuleParse
import com.command.domain.schedule.RRuleParser
import com.command.domain.schedule.StartRule
import com.command.domain.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** When a mission starts, as the person editing it thinks of it: a date and a time on the phone's clock. */
sealed interface ScheduleDraft {
    data class Once(val at: LocalDateTime) : ScheduleDraft

    /** Starts on [firstDate] at [time] and repeats by [rule] (the supported subset: daily, or weekly on chosen days). */
    data class Repeating(val firstDate: LocalDate, val time: LocalTime, val rule: RRule) : ScheduleDraft
}

data class MissionDraft(
    val id: String? = null,
    val title: String,
    val firstAction: String,
    val flexibility: Flexibility,
    val schedule: ScheduleDraft,
    val category: String = DEFAULT_CATEGORY,
    val starterMinutes: Int = 5,
    val profileId: String? = null,
) {
    companion object {
        const val DEFAULT_CATEGORY = "General"
    }
}

/** The parts of a mission that shape a ring, for a test alarm: what it says and how firm it is. */
data class TestRingSpec(
    val title: String = "",
    val firstAction: String = "",
    val flexibility: Flexibility = Flexibility.FLEXIBLE,
    val starterMinutes: Int = 5,
    val profileId: String? = null,
    /** The category typed so far, so that a test rings with the profile the saved mission would. */
    val category: String = MissionDraft.DEFAULT_CATEGORY,
)

/** What the schedule shows: what is still to come, soonest first, and what happened recently, newest first. */
data class ScheduleSnapshot(val upcoming: List<ScheduleRow>, val recent: List<ScheduleRow>)

/**
 * [READ_ONLY]: the mission is a copy of a calendar event, which only the calendar can change. [CATEGORY_RESERVED]: the category is the word that
 * marks the hidden mission behind a test alarm, whose alarms stay in the history after it is gone and which a pause lets through.
 */
enum class DraftProblem { TITLE_EMPTY, FIRST_ACTION_EMPTY, IN_THE_PAST, NO_DAYS_SELECTED, READ_ONLY, CATEGORY_RESERVED }

sealed interface SaveResult {
    /** The mission is stored; [scheduled] alarms exist for its coming week (a far-off one-off counts as one). */
    data class Saved(val missionId: String, val scheduled: Int) : SaveResult
    data class Invalid(val problems: Set<DraftProblem>) : SaveResult
}

/**
 * Creating, editing and deleting missions, and keeping the next week of their occurrences created and armed.
 *
 * Occurrences that have not fired are a plan derived from the mission: an edit keeps the ones whose time did not change,
 * discards the rest and creates what is missing, so changing a title never disturbs an alarm that is already armed.
 * Everything that has rung stays as history. Every state change still goes through [OccurrenceRepository]. A background
 * [reconcile] is what makes the stored plan and the platform's alarms match the missions again after anything has
 * disturbed them.
 */
@Singleton
class MissionRepository @Inject constructor(
    private val db: CommandDb,
    private val occurrences: OccurrenceRepository,
    private val scheduler: AlarmScheduler,
    private val clock: Clock,
    private val pauseEffects: PauseEffects,
) {
    private val generator = OccurrenceGenerator()
    private val profiles = ProfileResolver(db)
    private val writes = Mutex()

    // --- reads --------------------------------------------------------------------------------------------------

    /** Each list has its own bound, so a long upcoming list can never push the rest off the screen. */
    fun observeSchedule(): Flow<ScheduleSnapshot> = combine(
        db.occurrences().observeUpcoming(UPCOMING_LIMIT),
        db.occurrences().observeRecent(clock.nowMs() - RECENT_MS, RECENT_LIMIT),
    ) { upcoming, recent -> ScheduleSnapshot(upcoming, recent) }

    /**
     * What may have come due in the last [days] local days, today included, newest first, of missions that still exist: what History is made
     * of. What is still to come is left out until it is due. The query is bounded by instants and generous at the start: two zones are at
     * most 26 hours apart, so an occurrence of a mission pinned to a zone ahead of the phone's can be on a day inside the window while its
     * instant is that much before the phone's midnight. Which of these rows belong to which days is decided afterwards, by
     * `HistoryCalculator.isWithin`, for the list and the counts alike.
     */
    fun observeHistory(days: Int): Flow<List<ScheduleRow>> {
        require(days >= 1)
        val zone = clock.zone()
        val today = java.time.Instant.ofEpochMilli(clock.nowMs()).atZone(zone).toLocalDate()
        val from = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli() - ZONE_SLACK_MS
        return db.occurrences().observeBetween(from, clock.nowMs() + 1, HISTORY_LIMIT)
    }

    suspend fun get(id: String): MissionRow? = db.missions().get(id)?.takeIf { it.deletedAt == null }

    /** Whether the person has a mission of their own that is switched on; the hidden mission behind a test alarm is not one. */
    suspend fun hasMissions(): Boolean = db.missions().enabled().any { it.category != TEST_CATEGORY && it.source == MissionSource.MANUAL }

    /** The editor's view of a stored mission, or null if its rule can no longer be read. */
    fun draftOf(mission: MissionRow): MissionDraft? {
        val schedule: ScheduleDraft = when (mission.startKind) {
            StartKind.ONE_OFF -> {
                val at = mission.startAtUtc ?: return null
                ScheduleDraft.Once(java.time.Instant.ofEpochMilli(at).atZone(zoneOf(mission)).toLocalDateTime())
            }
            StartKind.RRULE -> {
                val start = startOf(mission) ?: return null
                val rule = ruleOf(mission) ?: return null
                ScheduleDraft.Repeating(start.toLocalDate(), start.toLocalTime(), rule)
            }
        }
        return MissionDraft(
            mission.id, mission.title, mission.firstAction, mission.flexibility, schedule, mission.category,
            mission.starterMinutes, mission.profileId,
        )
    }

    /**
     * The ids of the occurrences that overlap another one still to come. A delay or an answer changes what is coming without any mission
     * being saved, so the overlaps are worked out again whenever what is coming changes, for as long as somebody is watching.
     */
    fun observeConflicted(): Flow<Set<String>> = channelFlow {
        // Never on the collector's dispatcher, which is the main thread on the schedule screen: this holds [writes] across database calls, and
        // a lock held by the main thread's queue is never let go if anything on that thread is waiting for the same lock.
        launch(Dispatchers.Default) {
            db.occurrences().observeFrom(listOf(OccurrenceState.SCHEDULED, OccurrenceState.ARMED), 0L, Int.MAX_VALUE)
                .map { rows -> rows.map { it.id to it.scheduledAtUtc } }
                .distinctUntilChanged()
                .collect { writes.withLock { refreshConflicts(clock.nowMs()) } }
        }
        db.calendarDao().observeConflicts().collect { rows -> send(rows.flatMap { listOf(it.occA, it.occB) }.toSet()) }
    }

    /** How many of this mission's coming occurrences overlap another one. */
    suspend fun conflictsOf(missionId: String): Int {
        val conflicted = db.calendarDao().conflicts().flatMap { listOf(it.occA, it.occB) }.toSet()
        return db.occurrences().forMission(missionId).count { it.id in conflicted }
    }

    /** When this mission's alarms still to come will ring, on the phone's clock. A calendar series is one mission with one start and many occurrences, so only these say all of its days. */
    suspend fun comingAlarmsOf(missionId: String): List<LocalDateTime> {
        val now = clock.nowMs()
        val zone = clock.zone()
        return db.occurrences().forMission(missionId)
            .filter { it.scheduledAtUtc >= now && (it.state == OccurrenceState.SCHEDULED || it.state == OccurrenceState.ARMED) }
            .map { java.time.Instant.ofEpochMilli(it.scheduledAtUtc).atZone(zone).toLocalDateTime() }
    }

    // --- writes -------------------------------------------------------------------------------------------------

    suspend fun save(draft: MissionDraft): SaveResult = writes.withLock {
        val problems = validate(draft)
        if (problems.isNotEmpty()) return@withLock SaveResult.Invalid(problems)

        val zone = clock.zone()
        val now = clock.nowMs()
        val existing = draft.id?.let { db.missions().get(it) }?.takeIf { it.deletedAt == null }
        if (existing != null && existing.source != MissionSource.MANUAL) return@withLock SaveResult.Invalid(setOf(DraftProblem.READ_ONLY))
        val row = rowOf(draft, existing, zone)
        val wanted = instantsOf(row, zone, now)
        // What to leave alone: occurrences at times the new rule still gives, including one that is only just overdue (an
        // alarm the system has not delivered yet must not be lost by renaming its mission).
        // What a pause is holding back is history in waiting: the ten-minute look-back must not let a rename delete it.
        val held = PauseStore.active(db, now)?.let { p ->
            db.occurrences().forMission(row.id)
                .filter { it.state == OccurrenceState.SCHEDULED && it.scheduledAtUtc >= p.sinceUtcMs && it.scheduledAtUtc < now }
                .map { it.scheduledAtUtc }
        }.orEmpty()
        val keep = instantsOf(row, zone, now, lookBackMs = GRACE_MS).toSet() + held
        val removed = db.withTransaction {
            if (existing == null) db.missions().insert(row) else db.missions().update(row)
            if (existing == null) {
                emptyList()
            } else {
                occurrences.removeUnfiredRows(unfiredIds(row.id, keep, now, futureOnly = false, includeDelayed = false), EventSource.USER)
            }
        }
        occurrences.cancelAlarms(removed)
        createAndArm(row, zone, wanted, EventSource.USER, PauseStore.active(db, now))
        refreshConflicts(now)
        SaveResult.Saved(row.id, wanted.size)
    }

    /** Hides the mission and discards its coming occurrences. What already rang stays in history. */
    suspend fun delete(missionId: String) = writes.withLock {
        val now = clock.nowMs()
        // Hiding the mission and removing everything that has not rung commit together, so no alarm can outlive its mission.
        val removed = db.withTransaction {
            db.missions().softDelete(missionId, now)
            occurrences.removeUnfiredRows(unfiredIds(missionId, emptySet(), now, futureOnly = false, includeDelayed = true), EventSource.USER)
        }
        occurrences.cancelAlarms(removed)
        refreshConflicts(now)
    }

    /**
     * A real alarm a few seconds from now, through the whole path (alarm, service, lock screen, handshake), so a person
     * can hear and see what a ring will be like with this mission's title, first action and firmness. It belongs to one
     * hidden mission that never shows on the schedule. Returns the occurrence it armed.
     */
    suspend fun testRing(spec: TestRingSpec = TestRingSpec()): String = writes.withLock {
        val now = clock.nowMs()
        val at = now + TEST_DELAY_MS
        // The hidden mission carries what the test says, so only one test ring may be outstanding: a second replaces the first.
        val outstanding = unfiredIds(TEST_MISSION_ID, emptySet(), now, futureOnly = false, includeDelayed = true)
        if (outstanding.isNotEmpty()) occurrences.discardUnfired(outstanding, EventSource.USER)
        val profileId = profiles.resolveTyped(spec.title, categoryOf(spec.category), spec.profileId)
        val row = MissionRow(
            id = TEST_MISSION_ID,
            title = spec.title.trim().ifEmpty { DEFAULT_TEST_TITLE },
            firstAction = spec.firstAction.trim().ifEmpty { DEFAULT_TEST_ACTION },
            category = TEST_CATEGORY, priority = 2, flexibility = spec.flexibility,
            rigidWeekdays = null, source = MissionSource.MANUAL, startKind = StartKind.ONE_OFF, startAtUtc = at, dtstartLocal = null,
            rrule = null, zoneMode = ZoneMode.DEVICE, zoneId = null, durationMin = 5,
            starterMinutes = spec.starterMinutes.coerceIn(1, MAX_STARTER_MINUTES),
            profileId = profileId, lockPolicy = null, launcherPackage = null, firstActionVariantsJson = "[]",
            enabled = false, deletedAt = now, syncRev = 0, syncDirty = false,
        )
        db.withTransaction { if (db.missions().get(TEST_MISSION_ID) == null) db.missions().insert(row) else db.missions().update(row) }
        val occurrence = occurrences.createOccurrence(TEST_MISSION_ID, at, clock.zone().id).occurrence
        occurrences.logEvent(EventType.TEST_RING, occurrence, EventSource.USER, EventPayload.EMPTY)
        occurrences.apply(occurrence.id, OccurrenceCommand.Arm(armModeNow()), EventSource.USER)
        occurrence.id
    }

    /**
     * Makes the stored plan and the platform's alarms match the missions again. Run when the app opens, after a reboot, an
     * update, a clock or zone change or a change of the exact-alarm permission, and every few hours as a backstop:
     *  - each enabled manual mission gets exactly its next week of occurrences (an occurrence at a time the rule no longer
     *    gives, because the zone or the clock changed, is discarded; a missing one is created and armed);
     *  - every armed occurrence is asked after, because a reboot or a force-stop silently drops platform alarms: one that
     *    is gone is logged as lost and put back;
     *  - an alarm that came due more than ten minutes ago while the phone was off is recorded as missed (and says so)
     *    instead of ringing hours late; one that is only just due, armed or not, rings now.
     * Idempotent and cheap. Runs that the person did not start leave a line in the audit log even when they found nothing.
     */
    suspend fun reconcile(trigger: ReconcileTrigger): ReconcileReport = writes.withLock { reconcileLocked(trigger) }

    private suspend fun reconcileLocked(trigger: ReconcileTrigger): ReconcileReport {
        val now = clock.nowMs()
        val zone = clock.zone()
        // A pause that has run out is closed first, so that what it held back is settled before anything is armed again.
        var pause = PauseStore.read(db)
        var swallowed = 0
        if (pause != null && pause.isOver(now)) {
            swallowed = endPause(pause, now, PauseEnd.EXPIRED)
            pause = null
        }
        // A reboot clears the notice in the shade while the pause goes on: it is posted again with the run after the reboot.
        if (pause != null && (trigger == ReconcileTrigger.BOOT || trigger == ReconcileTrigger.PACKAGE_REPLACED)) pauseEffects.started(pause.untilUtcMs)
        var created = 0
        var discarded = 0
        var repaired = 0
        var paused = 0
        for (mission in db.missions().enabled().filter { it.source == MissionSource.MANUAL }) {
            val z = zoneOf(mission, zone)
            val wanted = instantsOf(mission, z, now)
            discarded += discardFuture(mission.id, keep = wanted.toSet(), now, EventSource.SYSTEM)
            val counts = createAndArm(mission, z, wanted, EventSource.SYSTEM, pause)
            created += counts.created
            repaired += counts.repaired
        }
        var rearmed = 0
        var lost = 0
        var missed = 0
        var precisionChanged = 0
        var refused = 0
        for (occ in db.occurrences().inStates(listOf(OccurrenceState.SCHEDULED, OccurrenceState.ARMED))) {
            if (pause != null && occ.scheduledAtUtc < pause.untilUtcMs && occ.missionId != TEST_MISSION_ID) {
                // The pause holds it back: nothing arms it and nothing misses it; the end of the pause settles it.
                if (occ.state == OccurrenceState.ARMED && occurrences.apply(occ.id, OccurrenceCommand.Disarm, EventSource.SYSTEM) is ApplyResult.Applied) paused++
                continue
            }
            if (occ.scheduledAtUtc < now - GRACE_MS) {
                val result = occurrences.apply(occ.id, OccurrenceCommand.Miss(MissReason.DOWNTIME, lateMs = now - occ.scheduledAtUtc), EventSource.SYSTEM)
                if (result is ApplyResult.Applied) missed++
            } else if (occ.state == OccurrenceState.ARMED) {
                // One the platform refused was never there to lose.
                if (occ.armMode != ArmMode.REFUSED && !scheduler.isArmed(AlarmRequest(occ.id, occ.requestCode, AlarmKind.FIRE, occ.scheduledAtUtc))) {
                    lost++
                    occurrences.logEvent(EventType.ALARM_LOST, occ, EventSource.SYSTEM, EventPayload.of("trigger" to trigger))
                }
                // Always put it back, found or not: arming the same alarm again replaces it, so this can only help.
                val mode = occurrences.rearm(occ.id)
                if (mode == ArmMode.REFUSED) {
                    refused++
                } else if (mode != null) {
                    rearmed++
                    if (mode != occ.armMode) precisionChanged++
                }
            } else {
                // Created but never armed (the app stopped in between) and only just due: arm it now, so it rings at once.
                val mode = armStored(occ.id, EventSource.SYSTEM)
                if (mode == ArmMode.REFUSED) refused++ else if (mode != null) repaired++
            }
        }
        for (occ in occurrences.followingUp()) {
            if (occurrences.rearmCheckIn(occ.id) == ArmMode.REFUSED) refused++
        }
        refreshConflicts(now)
        val report = ReconcileReport(created, discarded, rearmed, lost, missed, precisionChanged, repaired, paused, swallowed, refused)
        if (trigger.alwaysLogged || report.changedAnything) {
            occurrences.logEvent(
                EventType.BOOT_RECONCILE, null, EventSource.SYSTEM,
                EventPayload.of(
                    "trigger" to trigger, "created" to created, "discarded" to discarded,
                    "rearmed" to rearmed, "lost" to lost, "missed" to missed, "precision_changed" to precisionChanged, "repaired" to repaired,
                    "paused" to paused, "swallowed" to swallowed, "refused" to refused,
                ),
            )
        }
        return report
    }

    /** Works the overlaps of what is still to come out again. The table is only written when they changed, so a quiet run wakes nobody. */
    private suspend fun refreshConflicts(now: Long) {
        val coming = db.occurrences().inStates(listOf(OccurrenceState.SCHEDULED, OccurrenceState.ARMED))
            .filter { it.scheduledAtUtc >= now && it.missionId != TEST_MISSION_ID }
        val minutes = HashMap<String, Int>()
        for (occ in coming) if (occ.missionId !in minutes) minutes[occ.missionId] = db.missions().get(occ.missionId)?.durationMin ?: 0
        val found = ConflictDetector.detect(coming.map { TimeInterval(it.id, it.scheduledAtUtc, it.scheduledAtUtc + minutes.getValue(it.missionId) * 60_000L) })
            .map { ConflictRow(it.a, it.b, it.kind) }
        val dao = db.calendarDao()
        if (found.toSet() == dao.conflicts().toSet()) return
        db.withTransaction {
            dao.clearConflicts()
            dao.insertConflicts(found)
        }
    }

    // --- global pause -------------------------------------------------------------------------------------------

    /** The pause that is stored: in force, or run out and not yet closed by a reconcile. Null when there is none. */
    fun observePause(): Flow<Pause?> = PauseStore.observe(db)

    /**
     * Pauses COMMAND for [forMs]: nothing that is due before the end rings. The alarms already armed for that time are taken down (their
     * occurrences go back to scheduled), occurrences created meanwhile are left unarmed, and a reconcile is queued for the end. A ring that
     * is going and a question the person is being asked are not touched. Pausing while paused moves the end and keeps the start, so what
     * the pause already held back stays blamed on it.
     */
    suspend fun pause(forMs: Long): Pause = writes.withLock {
        require(forMs in MIN_PAUSE_MS..MAX_PAUSE_MS) { "a pause lasts between a minute and $MAX_PAUSE_HOURS hours" }
        val now = clock.nowMs()
        val stored = PauseStore.read(db)
        // A pause that ran out and was never closed is closed first, so that what it held back is settled before a new one begins.
        if (stored != null && stored.isOver(now)) endPause(stored, now, PauseEnd.EXPIRED)
        val pause = Pause(sinceUtcMs = stored?.takeUnless { it.isOver(now) }?.sinceUtcMs ?: now, untilUtcMs = now + forMs)
        PauseStore.write(db, pause)
        pauseEffects.started(pause.untilUtcMs)
        // The reconcile under the new pause takes down what it holds back, and arms again what a shorter pause than the one before released.
        val report = reconcileLocked(ReconcileTrigger.PAUSE_STARTED)
        occurrences.logEvent(
            EventType.PAUSED, null, EventSource.USER,
            EventPayload.of("since" to pause.sinceUtcMs, "until" to pause.untilUtcMs, "disarmed" to report.paused),
        )
        pause
    }

    /**
     * Ends the pause now. What came due while it lasted is recorded as missed because of it, and everything still to come is armed again.
     * Nothing happens when there is no pause.
     */
    suspend fun resume(): ReconcileReport = writes.withLock {
        val pause = PauseStore.read(db) ?: return@withLock ReconcileReport()
        endPause(pause, clock.nowMs(), PauseEnd.RESUMED)
        reconcileLocked(ReconcileTrigger.RESUMED)
    }

    private enum class PauseEnd { EXPIRED, RESUMED }

    /**
     * Closes [pause]: what came due inside it and was held back (it is still scheduled, never armed) is recorded as missed because of it,
     * the pause is forgotten and the platform is told. What is still to come is armed by the reconcile that follows. Returns how many it
     * recorded.
     */
    private suspend fun endPause(pause: Pause, now: Long, how: PauseEnd): Int {
        // Up to the end of the pause, or up to now when the person ended it early.
        val boundary = minOf(pause.untilUtcMs, now)
        var swallowed = 0
        for (occ in db.occurrences().inStates(listOf(OccurrenceState.SCHEDULED, OccurrenceState.ARMED))) {
            if (occ.missionId == TEST_MISSION_ID || occ.scheduledAtUtc < pause.sinceUtcMs || occ.scheduledAtUtc >= boundary) continue
            // One the pause never got to take down (the app stopped in between) is taken down first, to be settled as what it was.
            if (occ.state == OccurrenceState.ARMED && occurrences.apply(occ.id, OccurrenceCommand.Disarm, EventSource.SYSTEM) !is ApplyResult.Applied) continue
            if (occurrences.apply(occ.id, OccurrenceCommand.Miss(MissReason.PAUSED), EventSource.SYSTEM) is ApplyResult.Applied) swallowed++
        }
        PauseStore.clear(db)
        occurrences.logEvent(EventType.UNPAUSED, null, EventSource.SYSTEM, EventPayload.of("how" to how, "swallowed" to swallowed))
        if (how == PauseEnd.RESUMED) pauseEffects.resumed() else pauseEffects.expired()
        return swallowed
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    /** The category as it is stored: what was typed, trimmed, or the default when that leaves nothing. */
    private fun categoryOf(typed: String) = typed.trim().ifEmpty { MissionDraft.DEFAULT_CATEGORY }

    private fun validate(d: MissionDraft): Set<DraftProblem> {
        val out = LinkedHashSet<DraftProblem>()
        if (d.title.isBlank()) out += DraftProblem.TITLE_EMPTY
        if (d.firstAction.isBlank()) out += DraftProblem.FIRST_ACTION_EMPTY
        if (d.category.trim() == TEST_CATEGORY) out += DraftProblem.CATEGORY_RESERVED
        when (val s = d.schedule) {
            is ScheduleDraft.Once ->
                if (s.at.atZone(clock.zone()).toInstant().toEpochMilli() < clock.nowMs() + MIN_LEAD_MS) out += DraftProblem.IN_THE_PAST
            is ScheduleDraft.Repeating ->
                if (s.rule.frequency == Frequency.WEEKLY && s.rule.byDay.isEmpty()) out += DraftProblem.NO_DAYS_SELECTED
        }
        return out
    }

    private fun rowOf(d: MissionDraft, existing: MissionRow?, zone: ZoneId): MissionRow {
        var startAt: Long? = null
        var dtstart: String? = null
        var rrule: String? = null
        val kind: StartKind
        when (val s = d.schedule) {
            is ScheduleDraft.Once -> {
                kind = StartKind.ONE_OFF
                startAt = s.at.atZone(zone).toInstant().toEpochMilli()
            }
            is ScheduleDraft.Repeating -> {
                kind = StartKind.RRULE
                dtstart = LocalDateTime.of(s.firstDate, s.time).toString()
                rrule = RRuleParser.format(s.rule)
            }
        }
        return MissionRow(
            id = existing?.id ?: UUID.randomUUID().toString(), title = d.title.trim(), firstAction = d.firstAction.trim(),
            category = categoryOf(d.category), priority = existing?.priority ?: 2, flexibility = d.flexibility, rigidWeekdays = existing?.rigidWeekdays,
            source = MissionSource.MANUAL, startKind = kind, startAtUtc = startAt, dtstartLocal = dtstart, rrule = rrule,
            zoneMode = ZoneMode.DEVICE, zoneId = null, durationMin = existing?.durationMin ?: 30,
            starterMinutes = d.starterMinutes.coerceIn(1, MAX_STARTER_MINUTES), profileId = d.profileId,
            lockPolicy = existing?.lockPolicy, launcherPackage = existing?.launcherPackage,
            firstActionVariantsJson = existing?.firstActionVariantsJson ?: "[]", enabled = true, deletedAt = null,
            syncRev = (existing?.syncRev ?: 0) + 1, syncDirty = true,
        )
    }

    private fun zoneOf(m: MissionRow, fallback: ZoneId = clock.zone()): ZoneId = when (m.zoneMode) {
        ZoneMode.DEVICE -> fallback
        ZoneMode.FIXED -> try { ZoneId.of(m.zoneId) } catch (e: DateTimeException) { fallback }
    }

    private fun startOf(m: MissionRow): LocalDateTime? = m.dtstartLocal?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    private fun ruleOf(m: MissionRow): RRule? = m.rrule?.let { (RRuleParser.parse(it) as? RRuleParse.Ok)?.rule }

    /**
     * The instants a mission starts at from [now] (or from [lookBackMs] before it): a one-off however far away, a week of a
     * repeating one, and, when that week is empty, the next occurrence however far away, so that a mission that starts next
     * month still has a row on the schedule to open, edit or delete.
     */
    private fun instantsOf(m: MissionRow, zone: ZoneId, now: Long, lookBackMs: Long = 0): List<Long> = when (m.startKind) {
        StartKind.ONE_OFF -> listOfNotNull(m.startAtUtc?.takeIf { it > now - lookBackMs })
        StartKind.RRULE -> {
            // A rule that cannot be read yields nothing rather than a guess.
            val start = startOf(m)
            val rule = ruleOf(m)
            if (start == null || rule == null) {
                emptyList()
            } else {
                val recurring = StartRule.Recurring(start, rule)
                generator.generate(recurring, zone, now - lookBackMs, now + HORIZON_MS)
                    .ifEmpty { generator.generate(recurring, zone, now + HORIZON_MS, now + FAR_MS, limit = 1) }
            }
        }
    }

    /** What [createAndArm] did: occurrences created, and ones that already existed but had never been armed and now are. */
    private class ArmCounts(val created: Int, val repaired: Int)

    /** Arms a row that is waiting for it, and answers how the platform took it; null when the row was no longer waiting. */
    private suspend fun armStored(occurrenceId: String, source: EventSource): ArmMode? =
        if (occurrences.apply(occurrenceId, OccurrenceCommand.Arm(armModeNow()), source) is ApplyResult.Applied) occurrences.get(occurrenceId)?.armMode else null

    /**
     * Creates the occurrences that do not exist yet and arms every one that is still waiting to be armed. One the platform refuses is no repair; the
     * reconcile's own pass over armed rows tries it again and counts it there, once.
     */
    private suspend fun createAndArm(m: MissionRow, zone: ZoneId, instants: List<Long>, source: EventSource, pause: Pause? = null): ArmCounts {
        var created = 0
        var repaired = 0
        for (at in instants) {
            val result = occurrences.createOccurrence(m.id, at, zone.id)
            if (result.isNew) created++
            if (result.occurrence.state == OccurrenceState.SCHEDULED) {
                // A pause holds back what is due inside it; the end of the pause settles it.
                if (pause != null && at < pause.untilUtcMs) continue
                val mode = armStored(result.occurrence.id, source)
                if (!result.isNew && mode != null && mode != ArmMode.REFUSED) repaired++
            }
        }
        return ArmCounts(created, repaired)
    }

    /**
     * The ids of the mission's occurrences that have not started to ring, except those at the [keep] instants. A delayed
     * child is something the person asked for by delaying: an edit leaves it, a deletion removes it ([includeDelayed]).
     * An alarm the system delivers late can still be armed after its time, so a change that makes it obsolete passes
     * `futureOnly = false`; the periodic refresh passes true and leaves an overdue one to the grace rule.
     */
    private suspend fun unfiredIds(missionId: String, keep: Set<Long>, now: Long, futureOnly: Boolean, includeDelayed: Boolean): List<String> =
        db.occurrences().forMission(missionId).filter {
            (it.state == OccurrenceState.SCHEDULED || it.state == OccurrenceState.ARMED) &&
                (!futureOnly || it.scheduledAtUtc >= now) && it.scheduledAtUtc !in keep &&
                (includeDelayed || it.parentOccurrenceId == null)
        }.map { it.id }

    /** Discards the mission's coming unfired occurrences except those at the [keep] instants. */
    private suspend fun discardFuture(missionId: String, keep: Set<Long>, now: Long, source: EventSource): Int {
        val doomed = unfiredIds(missionId, keep, now, futureOnly = true, includeDelayed = false)
        return if (doomed.isNotEmpty()) occurrences.discardUnfired(doomed, source) else 0
    }

    private fun armModeNow(): ArmMode = if (scheduler.canScheduleExact()) ArmMode.EXACT else ArmMode.INEXACT

    companion object {
        const val TEST_MISSION_ID = "builtin_test_mission"
        const val TEST_CATEGORY = "TEST"
        const val DEFAULT_STARTER_MINUTES = 5

        /** A pause lasts at least a minute and at most a day: a longer one is a mission that should be deleted. */
        const val MIN_PAUSE_MS = 60_000L
        const val MAX_PAUSE_HOURS = 24
        const val MAX_PAUSE_MS = MAX_PAUSE_HOURS * 3_600_000L

        const val MAX_STARTER_MINUTES = 60
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val HORIZON_MS = 7 * DAY_MS
        private const val FAR_MS = 400 * DAY_MS
        private const val RECENT_MS = DAY_MS
        private const val GRACE_MS = 10 * 60 * 1000L
        private const val MIN_LEAD_MS = 5_000L
        private const val TEST_DELAY_MS = 5_000L
        private const val UPCOMING_LIMIT = 200
        /** The counts of History are made from these rows, so the bound is only against a runaway table: it takes 330 alarms a day to reach it. */
        private const val HISTORY_LIMIT = 10_000

        /** The most two zones can differ (UTC-12 and UTC+14). */
        private const val ZONE_SLACK_MS = 26 * 60 * 60 * 1000L
        private const val RECENT_LIMIT = 50
        private const val DEFAULT_TEST_TITLE = "Test alarm"
        private const val DEFAULT_TEST_ACTION = "This is a test. Say you are starting."
    }
}
