package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ManagementModulesDao {

    // --- Preset Routing Rules ---
    @Query("SELECT * FROM preset_routing_rules ORDER BY priorityOrder ASC, id ASC")
    fun observeAllRoutingRules(): Flow<List<PresetRoutingRuleEntity>>

    @Query("SELECT * FROM preset_routing_rules ORDER BY priorityOrder ASC, id ASC")
    suspend fun getAllRoutingRulesOnce(): List<PresetRoutingRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutingRule(rule: PresetRoutingRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutingRules(rules: List<PresetRoutingRuleEntity>)

    @Update
    suspend fun updateRoutingRule(rule: PresetRoutingRuleEntity)

    @Query("DELETE FROM preset_routing_rules WHERE id = :ruleId")
    suspend fun deleteRoutingRule(ruleId: Long)

    // --- Escalation Policies ---
    @Query("SELECT * FROM escalation_policies ORDER BY isSystemDefault DESC, policyName ASC")
    fun observeAllEscalationPolicies(): Flow<List<EscalationPolicyEntity>>

    @Query("SELECT * FROM escalation_policies ORDER BY isSystemDefault DESC, policyName ASC")
    suspend fun getAllEscalationPoliciesOnce(): List<EscalationPolicyEntity>

    @Query("SELECT * FROM escalation_policies WHERE id = :policyId")
    suspend fun getEscalationPolicyById(policyId: String): EscalationPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEscalationPolicy(policy: EscalationPolicyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEscalationPolicies(policies: List<EscalationPolicyEntity>)

    @Update
    suspend fun updateEscalationPolicy(policy: EscalationPolicyEntity)

    @Query("DELETE FROM escalation_policies WHERE id = :policyId")
    suspend fun deleteEscalationPolicy(policyId: String)

    // --- Handshake Calibrations ---
    @Query("SELECT * FROM handshake_calibrations ORDER BY isSystemDefault DESC, profileName ASC")
    fun observeAllHandshakeCalibrations(): Flow<List<HandshakeCalibrationEntity>>

    @Query("SELECT * FROM handshake_calibrations ORDER BY isSystemDefault DESC, profileName ASC")
    suspend fun getAllHandshakeCalibrationsOnce(): List<HandshakeCalibrationEntity>

    @Query("SELECT * FROM handshake_calibrations WHERE id = :calibrationId")
    suspend fun getHandshakeCalibrationById(calibrationId: String): HandshakeCalibrationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHandshakeCalibration(calibration: HandshakeCalibrationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHandshakeCalibrations(calibrations: List<HandshakeCalibrationEntity>)

    @Update
    suspend fun updateHandshakeCalibration(calibration: HandshakeCalibrationEntity)

    @Query("DELETE FROM handshake_calibrations WHERE id = :calibrationId")
    suspend fun deleteHandshakeCalibration(calibrationId: String)

    // --- Mission Blueprints ---
    @Query("SELECT * FROM mission_blueprints ORDER BY category ASC, title ASC")
    fun observeAllMissionBlueprints(): Flow<List<MissionBlueprintEntity>>

    @Query("SELECT * FROM mission_blueprints ORDER BY category ASC, title ASC")
    suspend fun getAllMissionBlueprintsOnce(): List<MissionBlueprintEntity>

    @Query("SELECT * FROM mission_blueprints WHERE id = :blueprintId")
    suspend fun getMissionBlueprintById(blueprintId: String): MissionBlueprintEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMissionBlueprint(blueprint: MissionBlueprintEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMissionBlueprints(blueprints: List<MissionBlueprintEntity>)

    @Update
    suspend fun updateMissionBlueprint(blueprint: MissionBlueprintEntity)

    @Query("DELETE FROM mission_blueprints WHERE id = :blueprintId")
    suspend fun deleteMissionBlueprint(blueprintId: String)
}
