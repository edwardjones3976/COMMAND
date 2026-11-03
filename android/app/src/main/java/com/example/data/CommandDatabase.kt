package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandDao {
    @Query("SELECT * FROM missions ORDER BY scheduledEpochMs ASC")
    fun observeAllMissions(): Flow<List<MissionEntity>>

    @Query("SELECT * FROM missions ORDER BY scheduledEpochMs ASC")
    suspend fun getAllMissionsOnce(): List<MissionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMission(mission: MissionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMissions(missions: List<MissionEntity>)

    @Update
    suspend fun updateMission(mission: MissionEntity)

    @Query("DELETE FROM missions WHERE id = :missionId")
    suspend fun deleteMission(missionId: Long)

    @Query("SELECT * FROM voice_packs ORDER BY isFavorite DESC, isCustom ASC, name ASC")
    fun observeVoicePacks(): Flow<List<VoicePackEntity>>

    @Query("SELECT * FROM voice_packs")
    suspend fun getVoicePacksOnce(): List<VoicePackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoicePack(voicePack: VoicePackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoicePacks(voicePacks: List<VoicePackEntity>)

    @Query("SELECT * FROM streak_profile WHERE id = 1")
    fun observeStreakProfile(): Flow<StreakProfileEntity?>

    @Query("SELECT * FROM streak_profile WHERE id = 1")
    suspend fun getStreakProfileOnce(): StreakProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStreakProfile(profile: StreakProfileEntity)
}

@Database(
    entities = [
        MissionEntity::class,
        VoicePackEntity::class,
        StreakProfileEntity::class,
        PersonalityPresetEntity::class,
        PresetRoutingRuleEntity::class,
        EscalationPolicyEntity::class,
        HandshakeCalibrationEntity::class,
        MissionBlueprintEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class CommandDatabase : RoomDatabase() {
    abstract fun commandDao(): CommandDao
    abstract fun personalityPresetDao(): PersonalityPresetDao
    abstract fun managementModulesDao(): ManagementModulesDao

    companion object {
        @Volatile
        private var INSTANCE: CommandDatabase? = null

        fun getInstance(context: Context): CommandDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CommandDatabase::class.java,
                    "project_command.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
