package com.command.data

import androidx.room.withTransaction
import com.command.data.db.CommandDb
import com.command.data.db.MissionRow
import com.command.data.db.ScheduleRow
import com.command.domain.alarm.AlarmScheduler
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
import kotlinx.coroutines.flow.Flow
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
    val category: String = "General",
    val starterMinutes: Int = 5,
    val profileId: String? = null,
)

/** The parts of a mission that shape a ring, for a test alarm: what it says and how firm it is. */
data class TestRingSpec(
    val title: String = "",
    val firstAction: String = "",
    val flexibility: Flexibility = Flexibility.FLEXIBLE,
    val starterMinutes: Int = 5,
    val profileId: String? = null,
)

enum class DraftProblem { TITLE_EMPTY, FIRST_ACTION_EMPTY, IN_THE_PAST, NO_DAYS_SELECTED }

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
 * reconciler that also runs after a reboot supersedes [refreshWindow] in a later milestone.
 */
@Singleton
class MissionRepository @Inject constructor(
    private val db: CommandDb,
    private val occurrences: OccurrenceRepository,
    private val scheduler: AlarmScheduler,
    private val clock: Clock,
) {
    private val generator = OccurrenceGenerator()
    private val writes = Mutex()

    // --- reads --------------------------------------------------------------------------------------------------

    /** Occurrences from a day ago on, with their mission's title, for the schedule screen. */
    fun observeSchedule(): Flow<List<ScheduleRow>> =
        db.occurrences().observeSchedule(clock.nowMs() - RECENT_MS, LIST_LIMIT)

    suspend fun get(id: String): MissionRow? = db.missions().get(id)?.takeIf { it.deletedAt == null }

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

    // --- writes -------------------------------------------------------------------------------------------------

    suspend fun save(draft: MissionDraft): SaveResult = writes.withLock {
        val problems = validate(draft)
        if (problems.isNotEmpty()) return@withLock SaveResult.Invalid(problems)

        val zone = clock.zone()
        val now = clock.nowMs()
        val existing = draft.id?.let { db.missions().get(it) }?.takeIf { it.deletedAt == null }
        val row = rowOf(draft, existing, zone)
        db.withTransaction { if (existing == null) db.missions().insert(row) else db.missions().update(row) }

        val wanted = instantsOf(row, zone, now)
        if (existing != null) discardFuture(row.id, keep = wanted.toSet(), now, EventSource.USER, includeDelayed = false)
        createAndArm(row, zone, wanted, EventSource.USER)
        SaveResult.Saved(row.id, wanted.size)
    }

    /** Hides the mission and discards its coming occurrences. What already rang stays in history. */
    suspend fun delete(missionId: String) = writes.withLock {
        val now = clock.nowMs()
        db.missions().softDelete(missionId, now)
        discardFuture(missionId, keep = emptySet(), now, EventSource.USER, includeDelayed = true)
    }

    /**
     * A real alarm a few seconds from now, through the whole path (alarm, service, lock screen, handshake), so a person
     * can hear and see what a ring will be like with this mission's title, first action and firmness. It belongs to one
     * hidden mission that never shows on the schedule. Returns the occurrence it armed.
     */
    suspend fun testRing(spec: TestRingSpec = TestRingSpec()): String = writes.withLock {
        val now = clock.nowMs()
        val at = now + TEST_DELAY_MS
        val row = MissionRow(
            id = TEST_MISSION_ID,
            title = spec.title.trim().ifEmpty { DEFAULT_TEST_TITLE },
            firstAction = spec.firstAction.trim().ifEmpty { DEFAULT_TEST_ACTION },
            category = TEST_CATEGORY, priority = 2, flexibility = spec.flexibility,
            rigidWeekdays = null, source = MissionSource.MANUAL, startKind = StartKind.ONE_OFF, startAtUtc = at, dtstartLocal = null,
            rrule = null, zoneMode = ZoneMode.DEVICE, zoneId = null, durationMin = 5,
            starterMinutes = spec.starterMinutes.coerceIn(1, MAX_STARTER_MINUTES),
            profileId = spec.profileId, lockPolicy = null, launcherPackage = null, firstActionVariantsJson = "[]",
            enabled = false, deletedAt = now, syncRev = 0, syncDirty = false,
        )
        db.withTransaction { if (db.missions().get(TEST_MISSION_ID) == null) db.missions().insert(row) else db.missions().update(row) }
        val occurrence = occurrences.createOccurrence(TEST_MISSION_ID, at, clock.zone().id).occurrence
        occurrences.logEvent(EventType.TEST_RING, occurrence, EventSource.USER, EventPayload.EMPTY)
        occurrences.apply(occurrence.id, OccurrenceCommand.Arm(armModeNow()), EventSource.USER)
        occurrence.id
    }

    /**
     * Makes sure every enabled mission has its next week of occurrences, that every armed one has its platform alarm (a
     * reboot or a force-stop silently drops them), and that an alarm that came due while the phone was off is recorded as
     * missed instead of ringing hours late. Cheap and idempotent: safe whenever the app opens.
     */
    suspend fun refreshWindow() = writes.withLock {
        val now = clock.nowMs()
        val zone = clock.zone()
        for (mission in db.missions().enabled()) {
            val z = zoneOf(mission, zone)
            createAndArm(mission, z, instantsOf(mission, z, now), EventSource.SYSTEM)
        }
        for (occ in db.occurrences().inStates(listOf(OccurrenceState.SCHEDULED, OccurrenceState.ARMED))) {
            if (occ.scheduledAtUtc < now - GRACE_MS) {
                occurrences.apply(occ.id, OccurrenceCommand.Miss(MissReason.DOWNTIME), EventSource.SYSTEM)
            } else if (occ.state == OccurrenceState.ARMED) {
                occurrences.rearm(occ.id)
            }
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private fun validate(d: MissionDraft): Set<DraftProblem> {
        val out = LinkedHashSet<DraftProblem>()
        if (d.title.isBlank()) out += DraftProblem.TITLE_EMPTY
        if (d.firstAction.isBlank()) out += DraftProblem.FIRST_ACTION_EMPTY
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
            category = d.category, priority = existing?.priority ?: 2, flexibility = d.flexibility, rigidWeekdays = existing?.rigidWeekdays,
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

    /** The instants a mission starts at from [now]: one week of a repeating one, or a one-off however far away. */
    private fun instantsOf(m: MissionRow, zone: ZoneId, now: Long): List<Long> = when (m.startKind) {
        StartKind.ONE_OFF -> listOfNotNull(m.startAtUtc?.takeIf { it > now })
        StartKind.RRULE -> {
            // A rule that cannot be read yields nothing rather than a guess.
            val start = startOf(m)
            val rule = ruleOf(m)
            if (start == null || rule == null) emptyList() else generator.generate(StartRule.Recurring(start, rule), zone, now, now + HORIZON_MS)
        }
    }

    /** Creates the occurrences that do not exist yet and arms every one that is still waiting to be armed. */
    private suspend fun createAndArm(m: MissionRow, zone: ZoneId, instants: List<Long>, source: EventSource) {
        for (at in instants) {
            val occurrence = occurrences.createOccurrence(m.id, at, zone.id).occurrence
            if (occurrence.state == OccurrenceState.SCHEDULED) {
                occurrences.apply(occurrence.id, OccurrenceCommand.Arm(armModeNow()), source)
            }
        }
    }

    /** Discards the mission's coming occurrences that have not fired, except those at the [keep] instants. */
    private suspend fun discardFuture(missionId: String, keep: Set<Long>, now: Long, source: EventSource, includeDelayed: Boolean) {
        val doomed = db.occurrences().forMission(missionId).filter {
            (it.state == OccurrenceState.SCHEDULED || it.state == OccurrenceState.ARMED) &&
                it.scheduledAtUtc >= now && it.scheduledAtUtc !in keep &&
                // A delayed child is something the person asked for by delaying; an edit leaves it, a deletion removes it.
                (includeDelayed || it.parentOccurrenceId == null)
        }.map { it.id }
        if (doomed.isNotEmpty()) occurrences.discardUnfired(doomed, source)
    }

    private fun armModeNow(): ArmMode = if (scheduler.canScheduleExact()) ArmMode.EXACT else ArmMode.INEXACT

    companion object {
        const val TEST_MISSION_ID = "builtin_test_mission"
        const val TEST_CATEGORY = "TEST"
        const val DEFAULT_STARTER_MINUTES = 5
        const val MAX_STARTER_MINUTES = 60
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val HORIZON_MS = 7 * DAY_MS
        private const val RECENT_MS = DAY_MS
        private const val GRACE_MS = 10 * 60 * 1000L
        private const val MIN_LEAD_MS = 5_000L
        private const val TEST_DELAY_MS = 5_000L
        private const val LIST_LIMIT = 200
        private const val DEFAULT_TEST_TITLE = "Test alarm"
        private const val DEFAULT_TEST_ACTION = "This is a test. Say you are starting."
    }
}
