package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonalityPresetDao {
    @Query("SELECT * FROM personality_presets ORDER BY isPreloaded DESC, createdAtEpochMs DESC")
    fun observeAllPresets(): Flow<List<PersonalityPresetEntity>>

    @Query("SELECT * FROM personality_presets ORDER BY isPreloaded DESC, createdAtEpochMs DESC")
    suspend fun getAllPresetsOnce(): List<PersonalityPresetEntity>

    @Query("SELECT * FROM personality_presets WHERE id = :presetId")
    suspend fun getPresetById(presetId: String): PersonalityPresetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: PersonalityPresetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPresets(presets: List<PersonalityPresetEntity>)

    @Update
    suspend fun updatePreset(preset: PersonalityPresetEntity)

    @Query("DELETE FROM personality_presets WHERE id = :presetId")
    suspend fun deletePreset(presetId: String)

    @Query("UPDATE personality_presets SET isActiveGlobal = 0")
    suspend fun clearActiveGlobal()

    @Query("UPDATE personality_presets SET isActiveGlobal = 1 WHERE id = :presetId")
    suspend fun setActiveGlobal(presetId: String)

    @Query("SELECT * FROM personality_presets WHERE isActiveGlobal = 1 LIMIT 1")
    suspend fun getActiveGlobalPresetOnce(): PersonalityPresetEntity?
}
