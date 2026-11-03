package com.command.data

import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.command.data.db.CommandDb
import com.command.data.db.EventRow
import com.command.data.db.MissionRow
import com.command.data.db.ProfileRow
import com.command.data.db.SettingRow
import com.command.domain.dials.Flexibility
import com.command.domain.dials.Personality
import com.command.domain.dials.PresetOrigin
import com.command.domain.model.EventSource
import com.command.domain.model.MissionSource
import com.command.domain.model.SettingKeys
import com.command.domain.model.StartKind
import com.command.domain.model.ZoneMode
import com.command.domain.occurrence.EventPayload
import com.command.domain.occurrence.EventType
import com.command.domain.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import java.util.UUID
import javax.inject.Inject

/** What an import did, for the audit event and the tests. */
data class LegacyImportReport(
    val imported: Int,
    val skippedSeed: Int,
    val droppedCalendar: Int,
    val disabledPast: Int,
    val presets: Int,
    val customProfileCreated: Boolean,
    /** The old database could not be read. Nothing was imported and nothing was changed; the old files are untouched. */
    val failed: Boolean = false,
)

/**
 * One-time import of the previous app's database (`project_command.db`, Room schema v6). It brings across only what the
 * user made: manual missions, and their dials and personality if they changed them. The previous version's invented
 * data is not carried over: its three seed missions, the two calendar events it fabricated, calendar-flagged missions,
 * streaks, credits, badges, routing rules, escalation policies, calibrations, blueprints and voice packs.
 *
 * The legacy files are opened and left exactly where they are, so an install of the old build can still find its data.
 * Only the data eraser deletes them. Past-dated missions are imported switched off with no occurrence, so the first
 * reconcile cannot manufacture a pile of missed alarms.
 */
class LegacyImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: CommandDb,
    private val clock: Clock,
) {
    suspend fun importIfNeeded(): LegacyImportReport? {
        val done = db.settings().get(SettingKeys.LEGACY_IMPORT_DONE)
        if (done == "true" || done == "failed") return null
        val file: File = context.getDatabasePath(LEGACY_DB_NAME)
        val report = if (file.isFile) {
            try {
                readAndImport(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordFailure(e)
            }
        } else {
            EMPTY
        }
        db.settings().put(SettingRow(SettingKeys.LEGACY_IMPORT_DONE, if (report.failed) "failed" else "true"))
        return report
    }

    /** Leaves a trace (the kind of error only) and gives up for good: the app starts normally without the old data. */
    private suspend fun recordFailure(e: Exception): LegacyImportReport {
        val kind = e.javaClass.simpleName.filter { it.isLetterOrDigit() }.take(48).ifEmpty { "Error" }
        db.events().insert(
            EventRow(
                occurrenceId = null, missionId = null, type = EventType.LEGACY_IMPORTED, atUtc = clock.nowMs(),
                elapsedRealtime = clock.elapsedRealtimeMs(), bootId = null, source = EventSource.SYSTEM,
                payloadJson = Codec.encodePayload(EventPayload.of("failed" to "true", "error" to kind).values),
            ),
        )
        return LegacyImportReport(0, 0, 0, 0, 0, customProfileCreated = false, failed = true)
    }

    private suspend fun readAndImport(file: File): LegacyImportReport {
        // Android's default corruption handler deletes a database it cannot open. These are the user's old files and
        // must survive whatever state they are in, so corruption is handled by doing nothing here.
        val legacy = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE, DatabaseErrorHandler { })
        try {
            // Fold the write-ahead log into the main file first so rows the old app wrote last are visible.
            legacy.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            val now = clock.nowMs()
            val missions = readMissions(legacy)
            val profile = readCustomProfile(legacy)
            val presets = readUserPresets(legacy)

            var imported = 0
            var skippedSeed = 0
            var droppedCalendar = 0
            var disabledPast = 0
            val rows = ArrayList<MissionRow>()
            val occurrenceSlots = ArrayList<Pair<String, Long>>()
            for (m in missions) {
                when {
                    m.fromCalendar -> droppedCalendar++
                    (m.title to m.firstAction) in SEED_MISSIONS -> skippedSeed++
                    else -> {
                        val past = m.scheduledAtMs <= now
                        val finished = m.status != "SCHEDULED" && m.status != "DELAYED"
                        val enabled = !past && !finished
                        if (!enabled) disabledPast++
                        val id = UUID.randomUUID().toString()
                        rows += MissionRow(
                            id = id, title = m.title, firstAction = m.firstAction, category = m.category.ifBlank { "General" },
                            priority = 2, flexibility = if (m.locked) Flexibility.RIGID else Flexibility.FLEXIBLE, rigidWeekdays = null,
                            source = MissionSource.MANUAL, startKind = StartKind.ONE_OFF, startAtUtc = m.scheduledAtMs,
                            dtstartLocal = null, rrule = null, zoneMode = ZoneMode.DEVICE, zoneId = null,
                            durationMin = m.durationMinutes.coerceIn(1, 24 * 60), starterMinutes = 5, profileId = null,
                            lockPolicy = null, launcherPackage = null, firstActionVariantsJson = "[]", enabled = enabled,
                            deletedAt = null, syncRev = 0, syncDirty = false,
                        )
                        if (enabled) occurrenceSlots += id to m.scheduledAtMs
                        imported++
                    }
                }
            }

            db.withTransaction {
                for (row in rows) db.missions().insert(row)
                val zone = clock.zone().id
                for ((missionId, at) in occurrenceSlots) {
                    // Created inside the same transaction through the DAO; alarms are armed later by the reconciler.
                    val code = (db.settings().get(SettingKeys.NEXT_REQUEST_CODE)?.trim()?.toIntOrNull() ?: 1)
                    db.settings().put(SettingRow(SettingKeys.NEXT_REQUEST_CODE, (code + 1).toString()))
                    db.occurrences().insert(
                        com.command.data.db.OccurrenceRow(
                            UUID.randomUUID().toString(), missionId, at, zone,
                            com.command.domain.occurrence.OccurrenceState.SCHEDULED, 0, code,
                            com.command.domain.occurrence.ArmMode.NONE, null, Codec.encodeSession(SessionData()), null, null, null,
                        ),
                    )
                }
                var customCreated = false
                if (profile != null) {
                    db.profiles().upsert(profile)
                    db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"${profile.id}\""))
                    customCreated = true
                }
                for (p in presets) db.profiles().upsert(p)
                db.events().insert(
                    EventRow(
                        occurrenceId = null, missionId = null, type = EventType.LEGACY_IMPORTED, atUtc = now,
                        elapsedRealtime = clock.elapsedRealtimeMs(), bootId = null, source = EventSource.SYSTEM,
                        payloadJson = Codec.encodePayload(
                            EventPayload.of(
                                "imported" to imported, "skippedSeed" to skippedSeed, "droppedCalendar" to droppedCalendar,
                                "disabledPast" to disabledPast, "presets" to presets.size, "customProfile" to customCreated,
                            ).values,
                        ),
                    ),
                )
            }
            return LegacyImportReport(imported, skippedSeed, droppedCalendar, disabledPast, presets.size, profile != null)
        } finally {
            legacy.close()
        }
    }

    private class LegacyMission(
        val title: String, val firstAction: String, val category: String, val scheduledAtMs: Long, val durationMinutes: Int,
        val locked: Boolean, val status: String, val fromCalendar: Boolean,
    )

    private fun readMissions(legacy: SQLiteDatabase): List<LegacyMission> {
        if (!legacy.hasTable("missions")) return emptyList()
        legacy.rawQuery("SELECT title, firstActionPrompt, category, scheduledEpochMs, durationMinutes, isMissionLocked, status, isFromGoogleCalendar FROM missions", null).use { c ->
            val out = ArrayList<LegacyMission>()
            while (c.moveToNext()) {
                out += LegacyMission(
                    title = c.str(0), firstAction = c.str(1), category = c.str(2), scheduledAtMs = c.getLong(3),
                    durationMinutes = c.getInt(4), locked = c.getInt(5) != 0, status = c.str(6), fromCalendar = c.getInt(7) != 0,
                )
            }
            return out
        }
    }

    /** A CUSTOM profile, but only if the user's stored dials or personality differ from the previous app's defaults. */
    private fun readCustomProfile(legacy: SQLiteDatabase): ProfileRow? {
        if (!legacy.hasTable("streak_profile")) return null
        legacy.rawQuery(
            "SELECT selectedPersonalityId, instructionFirmness, obnoxiousness, alertIntensity, persistence, handshakeDifficulty, scheduleRigidity, recoveryStructure FROM streak_profile WHERE id = 1",
            null,
        ).use { c ->
            if (!c.moveToFirst()) return null
            val personalityId = c.str(0)
            val dials = (1..7).map { c.getInt(it) }
            if (personalityId == LEGACY_DEFAULT_PERSONALITY && dials == LEGACY_DEFAULT_DIALS) return null
            return profile("Imported settings", personalityOf(personalityId), dials)
        }
    }

    private fun readUserPresets(legacy: SQLiteDatabase): List<ProfileRow> {
        if (!legacy.hasTable("personality_presets")) return emptyList()
        legacy.rawQuery(
            "SELECT name, basePersonalityId, instructionFirmness, obnoxiousness, alertIntensity, persistence, handshakeDifficulty, scheduleRigidity, recoveryStructure FROM personality_presets WHERE isPreloaded = 0",
            null,
        ).use { c ->
            val out = ArrayList<ProfileRow>()
            while (c.moveToNext()) out += profile(c.str(0).ifBlank { "Imported preset" }, personalityOf(c.str(1)), (2..8).map { c.getInt(it) })
            return out
        }
    }

    private fun profile(name: String, personality: Personality, d: List<Int>): ProfileRow {
        fun v(i: Int) = d[i].coerceIn(1, 5)
        return ProfileRow(
            id = UUID.randomUUID().toString(), name = name, origin = PresetOrigin.CUSTOM, personality = personality, voicePackId = null,
            firmness = v(0), obnoxiousness = v(1), alertIntensity = v(2), followUpPersistence = v(3), handshakeDifficulty = v(4),
            scheduleRigidity = v(5), recoveryStructure = v(6), handshakeChainJson = null, isBuiltin = false,
        )
    }

    private fun personalityOf(legacyId: String): Personality = when (legacyId) {
        "drill_sergeant" -> Personality.DRILL_SERGEANT
        "executive_director" -> Personality.EXECUTIVE_DIRECTOR
        "supportive_coach", "motivational_guru" -> Personality.SUPPORTIVE_COACH
        "accountability_partner" -> Personality.ACCOUNTABILITY_PARTNER
        "minimalist" -> Personality.MINIMALIST
        else -> Personality.CUSTOM
    }

    private fun SQLiteDatabase.hasTable(name: String): Boolean =
        rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    private fun Cursor.str(i: Int): String = if (isNull(i)) "" else getString(i)

    companion object {
        const val LEGACY_DB_NAME = "project_command.db"
        private val EMPTY = LegacyImportReport(0, 0, 0, 0, 0, false)

        /** The previous app's defaults (CommandModels.kt, StreakProfileEntity): firmness 5, obnoxiousness 5, alert 5, persistence 4, difficulty 3, rigidity 4, recovery 4. */
        private const val LEGACY_DEFAULT_PERSONALITY = "drill_sergeant"
        private val LEGACY_DEFAULT_DIALS = listOf(5, 5, 5, 4, 3, 4, 4)

        /** (title, first action) of the rows the previous version invented on first run. */
        private val SEED_MISSIONS: Set<Pair<String, String>> = setOf(
            "Career Work — Deep Architecture Sprint" to "EYEBALLS! GET TO THE COMPUTER! OPEN THE PROJECT AND GIVE ME FIVE MINUTES RIGHT NOW!",
            "Product Strategy Review (Google Calendar Sync)" to "Open strategy doc and write the top 3 bullet points before joining.",
            "Physical Conditioning & Mobility Block" to "Put on training shoes and start the 5-minute dynamic warm-up.",
            "Daily Engineering Standup & Triage" to "Open GitHub notifications and write your 3 standup items now.",
            "Weekly Core Architecture Review" to "Open system architecture diagram and review pending interface proposals.",
        )
    }
}
