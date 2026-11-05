package com.command.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.command.domain.dials.Flexibility
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.Personality
import com.command.domain.dials.PresetOrigin
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.CheckInAnswer
import com.command.domain.model.ConflictKind
import com.command.domain.model.ConsentKey
import com.command.domain.model.ConsentSource
import com.command.domain.model.EventSource
import com.command.domain.model.MissionSource
import com.command.domain.model.RestKind
import com.command.domain.model.RingMode
import com.command.domain.model.StartKind
import com.command.domain.model.SyncOp
import com.command.domain.model.VoiceEngine
import com.command.domain.model.ZoneMode
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason

/*
 * Database v1. Ids are UUID text; enums are stored by name; times are epoch milliseconds (UTC); lists are JSON text.
 * Column names are snake_case and are part of the exported schema, so renaming one needs a migration.
 */

@Entity(tableName = "missions", indices = [Index("enabled"), Index("deleted_at")])
data class MissionRow(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "first_action") val firstAction: String,
    val category: String,
    /** 1 = low, 2 = normal, 3 = high. */
    val priority: Int,
    val flexibility: Flexibility,
    /** Bit 0 = Monday ... bit 6 = Sunday; null means every day. Only meaningful for rigid missions. */
    @ColumnInfo(name = "rigid_weekdays") val rigidWeekdays: Int?,
    val source: MissionSource,
    @ColumnInfo(name = "start_kind") val startKind: StartKind,
    /** ONE_OFF: the instant. */
    @ColumnInfo(name = "start_at_utc") val startAtUtc: Long?,
    /** RRULE: first local date-time, ISO-8601 without zone. */
    @ColumnInfo(name = "dtstart_local") val dtstartLocal: String?,
    val rrule: String?,
    @ColumnInfo(name = "zone_mode") val zoneMode: ZoneMode,
    @ColumnInfo(name = "zone_id") val zoneId: String?,
    @ColumnInfo(name = "duration_min") val durationMin: Int,
    @ColumnInfo(name = "starter_minutes") val starterMinutes: Int,
    @ColumnInfo(name = "profile_id") val profileId: String?,
    /** Null means derived from flexibility and the rigidity dial. */
    @ColumnInfo(name = "lock_policy") val lockPolicy: LockPolicy?,
    @ColumnInfo(name = "launcher_package") val launcherPackage: String?,
    @ColumnInfo(name = "first_action_variants_json") val firstActionVariantsJson: String,
    val enabled: Boolean,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "sync_rev") val syncRev: Long,
    @ColumnInfo(name = "sync_dirty") val syncDirty: Boolean,
)

@Entity(
    tableName = "occurrences",
    foreignKeys = [ForeignKey(MissionRow::class, ["id"], ["mission_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index("request_code", unique = true),
        Index("mission_id", "scheduled_at_utc", unique = true),
        Index("state", "scheduled_at_utc"),
        Index("parent_occurrence_id"),
    ],
)
data class OccurrenceRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "mission_id") val missionId: String,
    @ColumnInfo(name = "scheduled_at_utc") val scheduledAtUtc: Long,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    val state: OccurrenceState,
    /** Compare-and-set guard: every state change bumps it. */
    @ColumnInfo(name = "state_version") val stateVersion: Long,
    /** Unique per occurrence, issued from the settings counter; used by every platform alarm for it. */
    @ColumnInfo(name = "request_code") val requestCode: Int,
    @ColumnInfo(name = "arm_mode") val armMode: ArmMode,
    @ColumnInfo(name = "ring_mode") val ringMode: RingMode?,
    @ColumnInfo(name = "session_json") val sessionJson: String,
    @ColumnInfo(name = "parent_occurrence_id") val parentOccurrenceId: String?,
    @ColumnInfo(name = "skip_reason") val skipReason: SkipReason?,
    @ColumnInfo(name = "miss_reason") val missReason: MissReason?,
)

@Entity(
    tableName = "calendar_links",
    foreignKeys = [ForeignKey(OccurrenceRow::class, ["id"], ["occurrence_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("calendar_id", "event_id", "instance_begin_ms", unique = true)],
)
data class CalendarLinkRow(
    @PrimaryKey @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "calendar_id") val calendarId: Long,
    @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "instance_begin_ms") val instanceBeginMs: Long,
    @ColumnInfo(name = "original_id") val originalId: Long?,
    @ColumnInfo(name = "original_instance_ms") val originalInstanceMs: Long?,
    @ColumnInfo(name = "event_tz") val eventTz: String?,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    val misses: Int,
)

@Entity(tableName = "calendar_sources")
data class CalendarSourceRow(
    @PrimaryKey @ColumnInfo(name = "calendar_id") val calendarId: Long,
    val account: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    /** Nothing is imported until the user selects a calendar. */
    val selected: Boolean,
    @ColumnInfo(name = "default_profile_id") val defaultProfileId: String?,
    @ColumnInfo(name = "default_flexibility") val defaultFlexibility: Flexibility,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long?,
)

/** Append-only audit log. Insert and query only; payloads hold ids, enums, counts and timestamps, never free text. */
@Entity(tableName = "events", indices = [Index("occurrence_id"), Index("type", "at_utc")])
data class EventRow(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String?,
    @ColumnInfo(name = "mission_id") val missionId: String?,
    val type: EventType,
    @ColumnInfo(name = "at_utc") val atUtc: Long,
    @ColumnInfo(name = "elapsed_realtime") val elapsedRealtime: Long,
    @ColumnInfo(name = "boot_id") val bootId: String?,
    val source: EventSource,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
)

@Entity(
    tableName = "check_ins",
    primaryKeys = ["occurrence_id", "check_in_index"],
    foreignKeys = [ForeignKey(OccurrenceRow::class, ["id"], ["occurrence_id"], onDelete = ForeignKey.CASCADE)],
)
data class CheckInRow(
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "check_in_index") val checkInIndex: Int,
    @ColumnInfo(name = "asked_at") val askedAt: Long,
    @ColumnInfo(name = "answered_at") val answeredAt: Long?,
    val answer: CheckInAnswer?,
    @ColumnInfo(name = "delay_min") val delayMin: Int?,
    /** How long after its scheduled time the question actually went out. */
    @ColumnInfo(name = "late_ms") val lateMs: Long,
)

@Entity(
    tableName = "challenges",
    foreignKeys = [ForeignKey(OccurrenceRow::class, ["id"], ["occurrence_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("occurrence_id")],
)
data class ChallengeRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "chain_position") val chainPosition: Int,
    val mode: HandshakeMode,
    @ColumnInfo(name = "attempt_no") val attemptNo: Int,
    val outcome: ChallengeOutcome,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long,
)

/** A registered QR or NFC target. Only a hash is kept, never the payload or the tag id. */
@Entity(tableName = "handshake_tokens")
data class HandshakeTokenRow(
    @PrimaryKey val id: String,
    val mode: HandshakeMode,
    val label: String,
    val hash: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Append-only consent history. The current state of a key is its latest row. */
@Entity(tableName = "consents", indices = [Index("key", "seq")])
data class ConsentRow(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val key: ConsentKey,
    val granted: Boolean,
    @ColumnInfo(name = "at_utc") val atUtc: Long,
    val source: ConsentSource,
    @ColumnInfo(name = "boundary_version") val boundaryVersion: Int,
    @ColumnInfo(name = "shown_text_hash") val shownTextHash: String?,
)

@Entity(tableName = "settings")
data class SettingRow(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "value_json") val valueJson: String,
)

@Entity(tableName = "execution_profiles")
data class ProfileRow(
    @PrimaryKey val id: String,
    val name: String,
    val origin: PresetOrigin,
    val personality: Personality,
    @ColumnInfo(name = "voice_pack_id") val voicePackId: String?,
    val firmness: Int,
    val obnoxiousness: Int,
    @ColumnInfo(name = "alert_intensity") val alertIntensity: Int,
    @ColumnInfo(name = "follow_up_persistence") val followUpPersistence: Int,
    @ColumnInfo(name = "handshake_difficulty") val handshakeDifficulty: Int,
    @ColumnInfo(name = "schedule_rigidity") val scheduleRigidity: Int,
    @ColumnInfo(name = "recovery_structure") val recoveryStructure: Int,
    @ColumnInfo(name = "handshake_chain_json") val handshakeChainJson: String?,
    @ColumnInfo(name = "is_builtin") val isBuiltin: Boolean,
)

@Entity(tableName = "activity_profiles")
data class ActivityProfileRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "match_category") val matchCategory: String,
    @ColumnInfo(name = "match_title_contains") val matchTitleContains: String?,
    @ColumnInfo(name = "profile_id") val profileId: String,
)

@Entity(tableName = "template_overrides", primaryKeys = ["personality", "phase", "variant"])
data class TemplateOverrideRow(
    val personality: Personality,
    val phase: String,
    val variant: Int,
    val text: String,
)

@Entity(tableName = "voice_packs")
data class VoicePackRow(
    @PrimaryKey val id: String,
    val name: String,
    val engine: VoiceEngine,
    @ColumnInfo(name = "gemini_voice") val geminiVoice: String?,
    @ColumnInfo(name = "brief_key") val briefKey: String?,
    val locale: String,
    val pitch: Float,
    val rate: Float,
    /** Extra loudness above the alert-intensity target, 0 to 8 dB. */
    @ColumnInfo(name = "loudness_trim_db") val loudnessTrimDb: Float,
    @ColumnInfo(name = "default_personality") val defaultPersonality: Personality,
    @ColumnInfo(name = "is_custom") val isCustom: Boolean,
    /** Credit line only; never a licence claim. */
    val attribution: String?,
)

/** A weekly protected period; times are local "HH:mm". */
@Entity(tableName = "rest_windows")
data class RestWindowRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO day of week, 1 = Monday ... 7 = Sunday. */
    val weekday: Int,
    @ColumnInfo(name = "start_local") val startLocal: String,
    @ColumnInfo(name = "end_local") val endLocal: String,
    val kind: RestKind,
)

@Entity(tableName = "conflicts", primaryKeys = ["occ_a", "occ_b"])
data class ConflictRow(
    @ColumnInfo(name = "occ_a") val occA: String,
    @ColumnInfo(name = "occ_b") val occB: String,
    val kind: ConflictKind,
)

@Entity(tableName = "voice_renders")
data class VoiceRenderRow(
    @PrimaryKey val key: String,
    val engine: VoiceEngine,
    val path: String,
    val bytes: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long,
)

@Entity(tableName = "directive_cache")
data class DirectiveCacheRow(
    @PrimaryKey val key: String,
    val text: String,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
)

@Entity(tableName = "sync_outbox")
data class SyncOutboxRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "table_name") val tableName: String,
    @ColumnInfo(name = "row_id") val rowId: String,
    val op: SyncOp,
    @ColumnInfo(name = "queued_at") val queuedAt: Long,
)
