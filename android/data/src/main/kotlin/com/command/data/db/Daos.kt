package com.command.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.command.domain.dials.Personality
import com.command.domain.dials.Flexibility
import com.command.domain.model.CheckInAnswer
import com.command.domain.model.ConsentKey
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import com.command.domain.model.RingMode
import kotlinx.coroutines.flow.Flow

@Dao
interface MissionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(mission: MissionRow)

    @Update
    suspend fun update(mission: MissionRow)

    @Query("SELECT * FROM missions WHERE id = :id")
    suspend fun get(id: String): MissionRow?

    @Query("SELECT * FROM missions WHERE deleted_at IS NULL ORDER BY title")
    fun observeActive(): Flow<List<MissionRow>>

    @Query("SELECT * FROM missions WHERE deleted_at IS NULL AND enabled = 1")
    suspend fun enabled(): List<MissionRow>

    @Query("SELECT * FROM missions ORDER BY title")
    suspend fun all(): List<MissionRow>

    @Query("SELECT COUNT(*) FROM missions")
    suspend fun count(): Int

    /** Hides a mission from every list and stops it generating occurrences. Its history stays. */
    @Query("UPDATE missions SET deleted_at = :now, enabled = 0, sync_rev = sync_rev + 1, sync_dirty = 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)
}

private const val SCHEDULE_SELECT = """SELECT o.id AS occurrenceId, o.mission_id AS missionId, m.title AS title, m.flexibility AS flexibility,
           o.scheduled_at_utc AS scheduledAtUtc, o.zone_id AS zoneId, o.state AS state, o.arm_mode AS armMode,
           o.skip_reason AS skipReason, o.miss_reason AS missReason
           FROM occurrences o JOIN missions m ON m.id = o.mission_id"""

/** What the audit log knows about one alarm that rang or was lost: when it rang, or when it was found to have been missed. */
data class AlarmFactRow(
    val occurrenceId: String,
    val title: String,
    val category: String,
    val scheduledAtUtc: Long,
    val firedAtUtc: Long?,
    val missedAtUtc: Long?,
)

/** One line of the schedule: an occurrence with the facts about its mission that a list needs. */
data class ScheduleRow(
    val occurrenceId: String,
    val missionId: String,
    val title: String,
    val flexibility: Flexibility,
    val scheduledAtUtc: Long,
    val zoneId: String,
    val state: OccurrenceState,
    val armMode: ArmMode,
    val skipReason: SkipReason?,
    val missReason: MissReason?,
)

@Dao
interface OccurrenceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: OccurrenceRow)

    @Query("SELECT * FROM occurrences WHERE id = :id")
    suspend fun get(id: String): OccurrenceRow?

    @Query("SELECT * FROM occurrences WHERE id = :id")
    fun observe(id: String): Flow<OccurrenceRow?>

    @Query("SELECT * FROM occurrences WHERE mission_id = :missionId AND scheduled_at_utc = :scheduledAtUtc")
    suspend fun find(missionId: String, scheduledAtUtc: Long): OccurrenceRow?

    @Query("SELECT * FROM occurrences WHERE request_code = :code")
    suspend fun byRequestCode(code: Int): OccurrenceRow?

    @Query("SELECT * FROM occurrences WHERE mission_id = :missionId ORDER BY scheduled_at_utc")
    suspend fun forMission(missionId: String): List<OccurrenceRow>

    /** Every occurrence there is, for the one job that has to reach all of them: taking back every alarm before everything is erased. */
    @Query("SELECT * FROM occurrences ORDER BY scheduled_at_utc")
    suspend fun all(): List<OccurrenceRow>

    @Query("SELECT * FROM occurrences WHERE state IN (:states) ORDER BY scheduled_at_utc")
    suspend fun inStates(states: List<OccurrenceState>): List<OccurrenceRow>

    @Query("SELECT * FROM occurrences WHERE state IN (:states) AND scheduled_at_utc >= :fromUtc ORDER BY scheduled_at_utc LIMIT :limit")
    fun observeFrom(states: List<OccurrenceState>, fromUtc: Long, limit: Int): Flow<List<OccurrenceRow>>

    @Query("SELECT * FROM occurrences WHERE scheduled_at_utc >= :fromUtc AND scheduled_at_utc < :toUtc ORDER BY scheduled_at_utc")
    suspend fun between(fromUtc: Long, toUtc: Long): List<OccurrenceRow>

    @Query("SELECT * FROM occurrences WHERE scheduled_at_utc >= :sinceUtc ORDER BY scheduled_at_utc")
    suspend fun since(sinceUtc: Long): List<OccurrenceRow>

    /**
     * Compare-and-set: succeeds (returns 1) only if the row is still at [expectedVersion]. A stale writer gets 0 and
     * must re-read. The state, its version and the fields that move with it change together.
     */
    @Query(
        """UPDATE occurrences SET state = :state, state_version = state_version + 1, session_json = :sessionJson,
           skip_reason = :skipReason, miss_reason = :missReason, arm_mode = :armMode, ring_mode = COALESCE(:ringMode, ring_mode)
           WHERE id = :id AND state_version = :expectedVersion""",
    )
    suspend fun compareAndSet(
        id: String,
        expectedVersion: Long,
        state: OccurrenceState,
        sessionJson: String,
        skipReason: SkipReason?,
        missReason: MissReason?,
        armMode: ArmMode,
        ringMode: RingMode?,
    ): Int

    @Query("UPDATE occurrences SET arm_mode = :armMode WHERE id = :id")
    suspend fun setArmMode(id: String, armMode: ArmMode)

    /** Only for occurrences that never fired; see OccurrenceRepository.discardUnfired. */
    @Query("DELETE FROM occurrences WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE occurrences SET scheduled_at_utc = :scheduledAtUtc, zone_id = :zoneId WHERE id = :id")
    suspend fun reschedule(id: String, scheduledAtUtc: Long, zoneId: String)

    @Query("SELECT COUNT(*) FROM occurrences")
    suspend fun count(): Int

    /** What is still to come (or ringing), soonest first, of missions that still exist. */
    @Query(
        SCHEDULE_SELECT + """ WHERE m.deleted_at IS NULL AND o.state IN ('SCHEDULED', 'ARMED', 'FIRING')
           ORDER BY o.scheduled_at_utc LIMIT :limit""",
    )
    fun observeUpcoming(limit: Int): Flow<List<ScheduleRow>>

    /**
     * The latest alarms (by the time they were due) that rang or were lost while the phone was off, newest first, with the times
     * the audit log recorded. Missions the person deleted are left out, like on every other list; the hidden mission behind a test
     * alarm is marked deleted so that it never shows on the schedule, and its alarms are kept because how a test went is the point.
     */
    @Query(
        """SELECT * FROM (
             SELECT o.id AS occurrenceId, m.title AS title, m.category AS category, o.scheduled_at_utc AS scheduledAtUtc,
                    (SELECT MIN(e.at_utc) FROM events e WHERE e.occurrence_id = o.id AND e.type = 'ALARM_FIRED') AS firedAtUtc,
                    (SELECT MIN(e.at_utc) FROM events e WHERE e.occurrence_id = o.id AND e.type = 'MISSED_WHILE_OFF') AS missedAtUtc
             FROM occurrences o JOIN missions m ON m.id = o.mission_id
             WHERE m.deleted_at IS NULL OR m.category = 'TEST')
           WHERE firedAtUtc IS NOT NULL OR missedAtUtc IS NOT NULL
           ORDER BY scheduledAtUtc DESC LIMIT :limit""",
    )
    fun observeAlarmFacts(limit: Int): Flow<List<AlarmFactRow>>

    /** What has happened since [fromUtc] (so the recent past shows what was just missed), newest first. */
    @Query(
        SCHEDULE_SELECT + """ WHERE m.deleted_at IS NULL AND o.state NOT IN ('SCHEDULED', 'ARMED', 'FIRING') AND o.scheduled_at_utc >= :fromUtc
           ORDER BY o.scheduled_at_utc DESC LIMIT :limit""",
    )
    fun observeRecent(fromUtc: Long, limit: Int): Flow<List<ScheduleRow>>

    /** Everything due in [fromUtc, toUtc) of missions that still exist, newest first: what History is made of. */
    @Query(
        SCHEDULE_SELECT + """ WHERE m.deleted_at IS NULL AND o.scheduled_at_utc >= :fromUtc AND o.scheduled_at_utc < :toUtc
           ORDER BY o.scheduled_at_utc DESC LIMIT :limit""",
    )
    fun observeBetween(fromUtc: Long, toUtc: Long, limit: Int): Flow<List<ScheduleRow>>
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(row: EventRow): Long

    @Query("SELECT * FROM events WHERE occurrence_id = :occurrenceId ORDER BY seq")
    suspend fun forOccurrence(occurrenceId: String): List<EventRow>

    @Query("SELECT * FROM events ORDER BY seq DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EventRow>

    @Query("SELECT * FROM events WHERE type = :type ORDER BY seq")
    suspend fun ofType(type: EventType): List<EventRow>

    @Query("SELECT * FROM events ORDER BY seq")
    suspend fun all(): List<EventRow>
}

@Dao
interface CheckInDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CheckInRow)

    @Query("SELECT * FROM check_ins WHERE occurrence_id = :occurrenceId ORDER BY check_in_index")
    suspend fun forOccurrence(occurrenceId: String): List<CheckInRow>

    /** Closes the most recent question that is still waiting for an answer. */
    @Query(
        """UPDATE check_ins SET answered_at = :answeredAt, answer = :answer, delay_min = :delayMin
           WHERE occurrence_id = :occurrenceId AND answered_at IS NULL AND check_in_index =
             (SELECT MAX(check_in_index) FROM check_ins WHERE occurrence_id = :occurrenceId AND answered_at IS NULL)""",
    )
    suspend fun answerLatest(occurrenceId: String, answeredAt: Long, answer: CheckInAnswer, delayMin: Int?): Int
}

@Dao
interface ChallengeDao {
    @Insert
    suspend fun insert(row: ChallengeRow): Long

    @Query("SELECT * FROM challenges WHERE occurrence_id = :occurrenceId ORDER BY id")
    suspend fun forOccurrence(occurrenceId: String): List<ChallengeRow>
}

@Dao
interface ConsentDao {
    @Insert
    suspend fun insert(row: ConsentRow): Long

    @Query("SELECT * FROM consents WHERE key = :key ORDER BY seq DESC LIMIT 1")
    suspend fun latest(key: ConsentKey): ConsentRow?

    @Query("SELECT * FROM consents WHERE key = :key ORDER BY seq DESC LIMIT 1")
    fun observeLatest(key: ConsentKey): Flow<ConsentRow?>

    @Query("SELECT * FROM consents WHERE key = :key ORDER BY seq")
    suspend fun history(key: ConsentKey): List<ConsentRow>

    @Query("SELECT * FROM consents ORDER BY seq")
    suspend fun all(): List<ConsentRow>

    @Query("SELECT * FROM consents ORDER BY seq")
    fun observeAll(): Flow<List<ConsentRow>>
}

@Dao
interface SettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: SettingRow)

    @Query("SELECT value_json FROM settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value_json FROM settings WHERE key = :key")
    fun observe(key: String): Flow<String?>

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun remove(key: String)
}

@Dao
interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ProfileRow)

    @Query("SELECT * FROM execution_profiles WHERE id = :id")
    suspend fun get(id: String): ProfileRow?

    @Query("SELECT * FROM execution_profiles ORDER BY is_builtin DESC, name")
    suspend fun all(): List<ProfileRow>

    /** In the order a person thinks of them: the built-in ones first, by how hard a handshake they ask for and then by name, then the rest in the same order. */
    @Query("SELECT * FROM execution_profiles ORDER BY is_builtin DESC, handshake_difficulty, name")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<ProfileRow>>

    /**
     * Sets the chain of one profile and nothing else, in one statement: reading the row and writing it back would undo a change that
     * another writer made to the rest of it in between. Returns the number of profiles changed (0 when there is no such profile).
     */
    @Query("UPDATE execution_profiles SET handshake_chain_json = :json WHERE id = :id")
    suspend fun setChainJson(id: String, json: String?): Int
}

@Dao
interface RestWindowDao {
    @Insert
    suspend fun insert(row: RestWindowRow): Long

    @Query("SELECT * FROM rest_windows")
    suspend fun all(): List<RestWindowRow>
}

@Dao
interface VoicePackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: VoicePackRow)

    @Query("SELECT * FROM voice_packs ORDER BY name")
    suspend fun all(): List<VoicePackRow>
}

@Dao
interface MaintenanceDao {
    @Query("DELETE FROM directive_cache")
    suspend fun clearDirectiveCache(): Int

    @Query("SELECT path FROM voice_renders WHERE engine = 'GEMINI_STUDIO'")
    suspend fun geminiRenderPaths(): List<String>

    @Query("DELETE FROM voice_renders WHERE engine = 'GEMINI_STUDIO'")
    suspend fun clearGeminiRenders(): Int

    @Query("DELETE FROM sync_outbox")
    suspend fun clearSyncOutbox(): Int

    @Query("SELECT occurrence_id FROM calendar_links")
    suspend fun linkedOccurrenceIds(): List<String>

    @Query("DELETE FROM calendar_links")
    suspend fun clearCalendarLinks(): Int

    @Query("UPDATE calendar_sources SET selected = 0")
    suspend fun deselectCalendars(): Int
}

@Dao
interface CalendarDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(row: CalendarSourceRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLink(row: CalendarLinkRow)

    @Query("SELECT COUNT(*) FROM calendar_links")
    suspend fun linkCount(): Int

    @Query("SELECT COUNT(*) > 0 FROM calendar_sources WHERE selected = 1")
    suspend fun anySelected(): Boolean
}

@Dao
interface DirectiveCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: DirectiveCacheRow)

    @Query("SELECT COUNT(*) FROM directive_cache")
    suspend fun count(): Int
}

@Dao
interface TemplateOverrideDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TemplateOverrideRow)

    @Query("SELECT * FROM template_overrides")
    suspend fun all(): List<TemplateOverrideRow>

    @Query("DELETE FROM template_overrides WHERE personality = :personality AND phase = :phase AND variant = :variant")
    suspend fun delete(personality: Personality, phase: String, variant: Int)
}

@Dao
interface VoiceRenderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: VoiceRenderRow)

    @Query("SELECT key FROM voice_renders ORDER BY key")
    suspend fun keys(): List<String>
}

@Dao
interface SyncOutboxDao {
    @Insert
    suspend fun insert(row: SyncOutboxRow): Long

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun count(): Int
}
