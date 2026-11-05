package com.command.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.command.domain.dials.Dials
import com.command.domain.dials.Personality
import com.command.domain.dials.PresetOrigin
import com.command.domain.dials.Presets
import com.command.domain.model.SettingKeys

@Database(
    entities = [
        MissionRow::class, OccurrenceRow::class, CalendarLinkRow::class, CalendarSourceRow::class, EventRow::class,
        CheckInRow::class, ChallengeRow::class, HandshakeTokenRow::class, ConsentRow::class, SettingRow::class,
        ProfileRow::class, ActivityProfileRow::class, TemplateOverrideRow::class, VoicePackRow::class,
        RestWindowRow::class, ConflictRow::class, VoiceRenderRow::class, DirectiveCacheRow::class, SyncOutboxRow::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class CommandDb : RoomDatabase() {
    abstract fun missions(): MissionDao
    abstract fun occurrences(): OccurrenceDao
    abstract fun events(): EventDao
    abstract fun checkIns(): CheckInDao
    abstract fun challenges(): ChallengeDao
    abstract fun consents(): ConsentDao
    abstract fun settings(): SettingDao
    abstract fun profiles(): ProfileDao
    abstract fun restWindows(): RestWindowDao
    abstract fun voicePacks(): VoicePackDao
    abstract fun maintenance(): MaintenanceDao
    abstract fun calendarDao(): CalendarDao
    abstract fun directiveCacheDao(): DirectiveCacheDao
    abstract fun voiceRenderDao(): VoiceRenderDao
    abstract fun syncOutboxDao(): SyncOutboxDao

    companion object {
        const val NAME = "command.db"

        /**
         * There is deliberately no destructive-migration fallback: a version the code cannot migrate must fail loudly,
         * never wipe the user's missions and history.
         */
        fun open(context: Context): CommandDb =
            Room.databaseBuilder(context.applicationContext, CommandDb::class.java, NAME).addCallback(Seed).build()

        fun inMemory(context: Context): CommandDb =
            Room.inMemoryDatabaseBuilder(context.applicationContext, CommandDb::class.java).addCallback(Seed).build()
    }

    /** First-run rows: the four built-in profiles and the settings every other part of the app expects to find. */
    internal object Seed : RoomDatabase.Callback() {
        const val DEFAULT_PROFILE_ID = "builtin_steady"

        private val builtins = listOf(
            Triple("builtin_nudge", "Nudge", PresetOrigin.NUDGE) to Personality.SUPPORTIVE_COACH,
            Triple(DEFAULT_PROFILE_ID, "Steady", PresetOrigin.STEADY) to Personality.EXECUTIVE_DIRECTOR,
            Triple("builtin_command", "Command", PresetOrigin.COMMAND) to Personality.ACCOUNTABILITY_PARTNER,
            Triple("builtin_reveille_max", "Reveille MAX", PresetOrigin.REVEILLE_MAX) to Personality.DRILL_SERGEANT,
        )

        override fun onCreate(db: SupportSQLiteDatabase) {
            for ((spec, personality) in builtins) {
                val (id, name, origin) = spec
                val d: Dials = Presets.dialsFor(origin)
                db.execSQL(
                    "INSERT INTO execution_profiles (id, name, origin, personality, voice_pack_id, firmness, obnoxiousness, " +
                        "alert_intensity, follow_up_persistence, handshake_difficulty, schedule_rigidity, recovery_structure, " +
                        "handshake_chain_json, is_builtin) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, NULL, 1)",
                    arrayOf<Any>(
                        id, name, origin.name, personality.name, d.firmness, d.obnoxiousness, d.alertIntensity,
                        d.followUpPersistence, d.handshakeDifficulty, d.scheduleRigidity, d.recoveryStructure,
                    ),
                )
            }
            val defaults = mapOf(
                SettingKeys.ACTIVE_PROFILE_ID to "\"$DEFAULT_PROFILE_ID\"",
                SettingKeys.NEXT_REQUEST_CODE to "1",
                SettingKeys.ALARM_ROUTE_POLICY to "\"SPEAKER_AND_HEADSET\"",
                SettingKeys.AI_DAILY_BUDGET to "60",
            )
            for ((key, json) in defaults) {
                db.execSQL("INSERT INTO settings (`key`, value_json) VALUES (?, ?)", arrayOf<Any>(key, json))
            }
        }
    }
}
