package com.example.data

import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

class CommandRepository(
    private val dao: CommandDao,
    private val presetDao: PersonalityPresetDao? = null,
    private val modulesDao: ManagementModulesDao? = null
) {

    val missionsFlow: Flow<List<MissionEntity>> = dao.observeAllMissions()
    val voicePacksFlow: Flow<List<VoicePackEntity>> = dao.observeVoicePacks()
    val streakProfileFlow: Flow<StreakProfileEntity?> = dao.observeStreakProfile()
    val presetsFlow: Flow<List<PersonalityPresetEntity>> = presetDao?.observeAllPresets() ?: kotlinx.coroutines.flow.flowOf(defaultPresets())
    val routingRulesFlow: Flow<List<PresetRoutingRuleEntity>> = modulesDao?.observeAllRoutingRules() ?: kotlinx.coroutines.flow.flowOf(defaultRoutingRules())
    val escalationPoliciesFlow: Flow<List<EscalationPolicyEntity>> = modulesDao?.observeAllEscalationPolicies() ?: kotlinx.coroutines.flow.flowOf(defaultEscalationPolicies())
    val handshakeCalibrationsFlow: Flow<List<HandshakeCalibrationEntity>> = modulesDao?.observeAllHandshakeCalibrations() ?: kotlinx.coroutines.flow.flowOf(defaultHandshakeCalibrations())
    val blueprintsFlow: Flow<List<MissionBlueprintEntity>> = modulesDao?.observeAllMissionBlueprints() ?: kotlinx.coroutines.flow.flowOf(defaultMissionBlueprints())

    suspend fun ensureSeedData() {
        val existingPacks = dao.getVoicePacksOnce()
        if (existingPacks.size < 6 || existingPacks.none { it.stemResourceId.contains("raw/voice") }) {
            dao.insertVoicePacks(defaultVoicePacks())
        }

        if (presetDao != null) {
            val existingPresets = presetDao.getAllPresetsOnce()
            if (existingPresets.isEmpty()) {
                presetDao.insertPresets(defaultPresets())
            }
        }

        if (modulesDao != null) {
            val existingRules = modulesDao.getAllRoutingRulesOnce()
            if (existingRules.isEmpty()) {
                modulesDao.insertRoutingRules(defaultRoutingRules())
            }
            val existingPolicies = modulesDao.getAllEscalationPoliciesOnce()
            if (existingPolicies.isEmpty()) {
                modulesDao.insertEscalationPolicies(defaultEscalationPolicies())
            }
            val existingCalibrations = modulesDao.getAllHandshakeCalibrationsOnce()
            if (existingCalibrations.isEmpty()) {
                modulesDao.insertHandshakeCalibrations(defaultHandshakeCalibrations())
            }
            val existingBlueprints = modulesDao.getAllMissionBlueprintsOnce()
            if (existingBlueprints.isEmpty()) {
                modulesDao.insertMissionBlueprints(defaultMissionBlueprints())
            }
        }

        val existingProfile = dao.getStreakProfileOnce()
        if (existingProfile == null) {
            val yesterdayDay = currentEpochDay() - 1L
            dao.upsertStreakProfile(
                StreakProfileEntity(
                    id = 1,
                    currentStreakDays = 2,
                    longestStreakDays = 6,
                    lastCompletedEpochDay = yesterdayDay,
                    recoverableStreakDays = 0,
                    isStreakMissed = false,
                    commandCredits = 125,
                    unlockedBadgesCsv = "",
                    activePresetName = "Reveille MAX",
                    selectedPersonalityId = "drill_sergeant",
                    selectedVoicePackId = "pack_major_stone",
                    headphoneOnlyMode = false,
                    quietHoursDucking = false,
                    customTestPhrase = "EYEBALLS! SNAP TO RIGHT NOW! GET OFF THAT STARTING LINE! OPEN YOUR PROJECT AND GIVE ME FIVE MINUTES! MOVE, AYE SIR!",
                    compareVoiceAId = "pack_major_stone",
                    compareVoiceBId = "pack_sgt_brody",
                    hasCompletedOnboarding = false
                )
            )
        }

        val existingMissions = dao.getAllMissionsOnce()
        if (existingMissions.isEmpty()) {
            val now = System.currentTimeMillis()
            val seeds = listOf(
                MissionEntity(
                    title = "Career Work — Deep Architecture Sprint",
                    category = "Career Work",
                    firstActionPrompt = "EYEBALLS! GET TO THE COMPUTER! OPEN THE PROJECT AND GIVE ME FIVE MINUTES RIGHT NOW!",
                    scheduledEpochMs = now + TimeUnit.MINUTES.toMillis(15),
                    durationMinutes = 60,
                    personalityId = "drill_sergeant",
                    voicePackId = "pack_major_stone",
                    handshakeType = HandshakeType.HOLD.name,
                    isMissionLocked = true,
                    status = MissionStatus.SCHEDULED.name,
                    isFromGoogleCalendar = true,
                    isRecurring = true,
                    recurrenceRule = "Repeats Daily (FREQ=DAILY)",
                    calendarEventId = 1001L,
                    hasConflict = false
                ),
                MissionEntity(
                    title = "Product Strategy Review (Google Calendar Sync)",
                    category = "Recurring Calendar",
                    firstActionPrompt = "Open strategy doc and write the top 3 bullet points before joining.",
                    scheduledEpochMs = now + TimeUnit.MINUTES.toMillis(45),
                    durationMinutes = 45,
                    personalityId = "executive_director",
                    voicePackId = "pack_eleanor_vance",
                    handshakeType = HandshakeType.SEQUENCE.name,
                    isMissionLocked = false,
                    status = MissionStatus.SCHEDULED.name,
                    isFromGoogleCalendar = true,
                    isRecurring = true,
                    recurrenceRule = "Every Weekday",
                    calendarEventId = 1002L,
                    hasConflict = true,
                    conflictDescription = "Overlaps with 'Career Work — Deep Architecture Sprint' on Google Calendar"
                ),
                MissionEntity(
                    title = "Physical Conditioning & Mobility Block",
                    category = "Health & Reset",
                    firstActionPrompt = "Put on training shoes and start the 5-minute dynamic warm-up.",
                    scheduledEpochMs = now + TimeUnit.HOURS.toMillis(2),
                    durationMinutes = 30,
                    personalityId = "supportive_coach",
                    voicePackId = "pack_coach_ray",
                    handshakeType = HandshakeType.TYPING.name,
                    isMissionLocked = false,
                    status = MissionStatus.SCHEDULED.name,
                    isFromGoogleCalendar = false
                )
            )
            dao.insertMissions(seeds)
        }
    }

    suspend fun insertMission(mission: MissionEntity): Long = dao.insertMission(mission)

    suspend fun updateMission(mission: MissionEntity) = dao.updateMission(mission)

    suspend fun deleteMission(missionId: Long) = dao.deleteMission(missionId)

    suspend fun insertVoicePack(pack: VoicePackEntity) = dao.insertVoicePack(pack)

    suspend fun updateVoicePack(pack: VoicePackEntity) = dao.insertVoicePack(pack)

    suspend fun getStreakProfile(): StreakProfileEntity {
        return dao.getStreakProfileOnce() ?: StreakProfileEntity()
    }

    suspend fun updateStreakProfile(profile: StreakProfileEntity) {
        dao.upsertStreakProfile(profile)
    }

    suspend fun recordMissionCompletion(): StreakUpdateOutcome {
        val profile = getStreakProfile()
        val today = currentEpochDay()

        val newStreak = when {
            profile.isStreakMissed -> 1
            profile.lastCompletedEpochDay == today -> profile.currentStreakDays.coerceAtLeast(1)
            profile.lastCompletedEpochDay == today - 1L -> profile.currentStreakDays + 1
            else -> profile.currentStreakDays + 1
        }

        val unlockedSet = profile.unlockedBadgesCsv
            .split(",")
            .filter { it.isNotBlank() }
            .toMutableSet()

        var bonusCredits = 0
        var newlyUnlockedBadge: MilestoneBadgeSpec? = null

        for (badge in MILESTONE_BADGES) {
            if (newStreak >= badge.requiredDays && !unlockedSet.contains(badge.id)) {
                unlockedSet.add(badge.id)
                bonusCredits += badge.bonusCredits
                newlyUnlockedBadge = badge
            }
        }

        val earnedCredits = 25 + bonusCredits
        val updated = profile.copy(
            currentStreakDays = newStreak,
            longestStreakDays = maxOf(profile.longestStreakDays, newStreak),
            lastCompletedEpochDay = today,
            isStreakMissed = false,
            recoverableStreakDays = 0,
            commandCredits = profile.commandCredits + earnedCredits,
            unlockedBadgesCsv = unlockedSet.joinToString(",")
        )
        dao.upsertStreakProfile(updated)
        return StreakUpdateOutcome(
            newStreakDays = newStreak,
            creditsAdded = earnedCredits,
            newlyUnlockedBadge = newlyUnlockedBadge
        )
    }

    suspend fun simulateMissedDay() {
        val profile = getStreakProfile()
        val preserved = if (profile.currentStreakDays > 0) profile.currentStreakDays else 4
        val updated = profile.copy(
            recoverableStreakDays = preserved,
            currentStreakDays = 0,
            isStreakMissed = true,
            lastCompletedEpochDay = currentEpochDay() - 2L
        )
        dao.upsertStreakProfile(updated)
    }

    suspend fun buyBackStreakWithCredits(cost: Int = 50): Boolean {
        val profile = getStreakProfile()
        if (!profile.isStreakMissed || profile.commandCredits < cost) return false
        val restoredStreak = (profile.recoverableStreakDays.coerceAtLeast(1)) + 1
        val unlockedSet = profile.unlockedBadgesCsv
            .split(",")
            .filter { it.isNotBlank() }
            .toMutableSet()
        for (badge in MILESTONE_BADGES) {
            if (restoredStreak >= badge.requiredDays) {
                unlockedSet.add(badge.id)
            }
        }
        val updated = profile.copy(
            currentStreakDays = restoredStreak,
            longestStreakDays = maxOf(profile.longestStreakDays, restoredStreak),
            lastCompletedEpochDay = currentEpochDay(),
            recoverableStreakDays = 0,
            isStreakMissed = false,
            commandCredits = profile.commandCredits - cost,
            unlockedBadgesCsv = unlockedSet.joinToString(",")
        )
        dao.upsertStreakProfile(updated)
        return true
    }

    suspend fun buyBackStreakWithBriefing(): Boolean {
        val profile = getStreakProfile()
        if (!profile.isStreakMissed) return false
        val restoredStreak = (profile.recoverableStreakDays.coerceAtLeast(1)) + 1
        val unlockedSet = profile.unlockedBadgesCsv
            .split(",")
            .filter { it.isNotBlank() }
            .toMutableSet()
        for (badge in MILESTONE_BADGES) {
            if (restoredStreak >= badge.requiredDays) {
                unlockedSet.add(badge.id)
            }
        }
        val updated = profile.copy(
            currentStreakDays = restoredStreak,
            longestStreakDays = maxOf(profile.longestStreakDays, restoredStreak),
            lastCompletedEpochDay = currentEpochDay(),
            recoverableStreakDays = 0,
            isStreakMissed = false,
            unlockedBadgesCsv = unlockedSet.joinToString(",")
        )
        dao.upsertStreakProfile(updated)
        return true
    }

    suspend fun getAllPresets(): List<PersonalityPresetEntity> {
        return presetDao?.getAllPresetsOnce() ?: defaultPresets()
    }

    suspend fun getPresetById(id: String): PersonalityPresetEntity? {
        return presetDao?.getPresetById(id) ?: defaultPresets().find { it.id == id }
    }

    suspend fun savePreset(preset: PersonalityPresetEntity) {
        presetDao?.insertPreset(preset)
    }

    suspend fun deletePreset(id: String) {
        presetDao?.deletePreset(id)
    }

    suspend fun clonePreset(sourcePresetId: String): PersonalityPresetEntity? {
        val source = getPresetById(sourcePresetId) ?: return null
        val cloneId = "preset_${System.currentTimeMillis()}"
        val cloned = source.copy(
            id = cloneId,
            name = "${source.name} (Copy)",
            isActiveGlobal = false,
            isPreloaded = false,
            createdAtEpochMs = System.currentTimeMillis(),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        savePreset(cloned)
        return cloned
    }

    suspend fun setActiveGlobalPreset(preset: PersonalityPresetEntity) {
        presetDao?.clearActiveGlobal()
        presetDao?.setActiveGlobal(preset.id)
        val profile = getStreakProfile()
        val updated = profile.copy(
            activePresetName = preset.name,
            activeCustomPresetId = preset.id,
            selectedPersonalityId = preset.basePersonalityId,
            selectedVoicePackId = preset.voicePackId,
            instructionFirmness = preset.instructionFirmness,
            obnoxiousness = preset.obnoxiousness,
            alertIntensity = preset.alertIntensity,
            persistence = preset.persistence,
            handshakeDifficulty = preset.handshakeDifficulty,
            scheduleRigidity = preset.scheduleRigidity,
            recoveryStructure = preset.recoveryStructure,
            defaultHandshakeType = preset.preferredHandshakeType
        )
        dao.upsertStreakProfile(updated)
    }

    suspend fun saveRoutingRule(rule: PresetRoutingRuleEntity): Long {
        return modulesDao?.insertRoutingRule(rule) ?: 0L
    }

    suspend fun deleteRoutingRule(ruleId: Long) {
        modulesDao?.deleteRoutingRule(ruleId)
    }

    suspend fun saveEscalationPolicy(policy: EscalationPolicyEntity) {
        modulesDao?.insertEscalationPolicy(policy)
    }

    suspend fun deleteEscalationPolicy(policyId: String) {
        modulesDao?.deleteEscalationPolicy(policyId)
    }

    suspend fun saveHandshakeCalibration(calibration: HandshakeCalibrationEntity) {
        modulesDao?.insertHandshakeCalibration(calibration)
    }

    suspend fun deleteHandshakeCalibration(calibrationId: String) {
        modulesDao?.deleteHandshakeCalibration(calibrationId)
    }

    suspend fun saveMissionBlueprint(blueprint: MissionBlueprintEntity) {
        modulesDao?.insertMissionBlueprint(blueprint)
    }

    suspend fun deleteMissionBlueprint(blueprintId: String) {
        modulesDao?.deleteMissionBlueprint(blueprintId)
    }

    suspend fun deployBlueprintToQueue(
        blueprint: MissionBlueprintEntity,
        scheduledEpochMs: Long = System.currentTimeMillis() + 900_000L
    ): MissionEntity {
        val mission = MissionEntity(
            title = blueprint.title,
            category = blueprint.category,
            firstActionPrompt = blueprint.firstActionInstruction,
            scheduledEpochMs = scheduledEpochMs,
            durationMinutes = blueprint.defaultDurationMinutes,
            personalityId = "drill_sergeant",
            voicePackId = "pack_major_stone",
            handshakeType = HandshakeType.HOLD.name,
            isMissionLocked = true,
            status = MissionStatus.SCHEDULED.name,
            presetId = blueprint.assignedPresetId
        )
        val id = insertMission(mission)
        return mission.copy(id = id)
    }

    fun resolvePresetForMission(
        mission: MissionEntity,
        allPresets: List<PersonalityPresetEntity>,
        allRules: List<PresetRoutingRuleEntity>
    ): PersonalityPresetEntity {
        // 1st Priority: Mission-specific override
        if (!mission.presetId.isNullOrBlank()) {
            val missionPreset = allPresets.find { it.id == mission.presetId }
            if (missionPreset != null) return missionPreset
        }

        // 2nd Priority: Active routing rules matching category and time of day
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = mission.scheduledEpochMs
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)

        val matchingRule = allRules
            .filter { it.isEnabled }
            .sortedBy { it.priorityOrder }
            .firstOrNull { rule ->
                val categoryMatch = rule.targetCategory.equals("ALL", ignoreCase = true) ||
                        rule.targetCategory.equals(mission.category, ignoreCase = true)
                val hourMatch = if (rule.startHour <= rule.endHour) {
                    hour in rule.startHour..rule.endHour
                } else {
                    hour >= rule.startHour || hour <= rule.endHour
                }
                categoryMatch && hourMatch
            }

        if (matchingRule != null) {
            val rulePreset = allPresets.find { it.id == matchingRule.targetPresetId }
            if (rulePreset != null) return rulePreset
        }

        // 3rd Priority: Active global preset, or first available preset
        return allPresets.find { it.isActiveGlobal }
            ?: allPresets.firstOrNull()
            ?: defaultPresets().first()
    }

    fun currentEpochDay(): Long = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis())

    companion object {
        fun defaultPresets(): List<PersonalityPresetEntity> = listOf(
            PersonalityPresetEntity(
                id = "preset_morning_grind",
                name = "Morning Grind",
                description = "Maximum morning reveille intensity with physical 4-step sequence challenge to break grogginess.",
                basePersonalityId = "drill_sergeant",
                voicePackId = "pack_major_stone",
                instructionFirmness = 5,
                obnoxiousness = 5,
                alertIntensity = 5,
                persistence = 5,
                handshakeDifficulty = 4,
                scheduleRigidity = 5,
                recoveryStructure = 4,
                preferredHandshakeType = HandshakeType.SEQUENCE.name,
                cadenceStyle = CadenceStyle.CLIPPED_TACTICAL.name,
                eqPreset = EqPreset.TACTICAL_HUD.name,
                customPitch = 0.86f,
                customSpeechRate = 1.28f,
                vocalGritPercent = 85,
                aggressionPercent = 95,
                isActiveGlobal = true,
                isPreloaded = true,
                assignedCategoriesCsv = "Morning Routine,Health & Reset",
                sampleDirective = "EYEBALLS! SNAP TO RIGHT NOW! NO EXCUSES ON DECK! COMPLETE THIS SEQUENCE AND GET ON YOUR FEET! MOVE!"
            ),
            PersonalityPresetEntity(
                id = "preset_deep_work_focus",
                name = "Deep Work Focus",
                description = "Surgical executive precision with typing challenge for high-leverage professional deep work blocks.",
                basePersonalityId = "executive_director",
                voicePackId = "pack_eleanor_vance",
                instructionFirmness = 4,
                obnoxiousness = 1,
                alertIntensity = 4,
                persistence = 4,
                handshakeDifficulty = 3,
                scheduleRigidity = 4,
                recoveryStructure = 4,
                preferredHandshakeType = HandshakeType.TYPING.name,
                cadenceStyle = CadenceStyle.PACE_LOCKED.name,
                eqPreset = EqPreset.STUDIO_CRISP.name,
                customPitch = 1.01f,
                customSpeechRate = 1.04f,
                vocalGritPercent = 0,
                aggressionPercent = 35,
                isActiveGlobal = false,
                isPreloaded = true,
                assignedCategoriesCsv = "Career Work,Product Strategy",
                sampleDirective = "Schedule block active. Clear secondary tabs, enter the commitment phrase, and initiate your five-minute starter immediately."
            ),
            PersonalityPresetEntity(
                id = "preset_gentle_momentum",
                name = "Life Coach Momentum",
                description = "High-energy personal and life coaching by Coach Jordan Vance with tactile hold handshake to build unstoppable momentum.",
                basePersonalityId = "supportive_coach",
                voicePackId = "pack_coach_ray",
                instructionFirmness = 4,
                obnoxiousness = 2,
                alertIntensity = 4,
                persistence = 4,
                handshakeDifficulty = 2,
                scheduleRigidity = 4,
                recoveryStructure = 4,
                preferredHandshakeType = HandshakeType.HOLD.name,
                cadenceStyle = CadenceStyle.PACE_LOCKED.name,
                eqPreset = EqPreset.DEEP_COMMAND.name,
                customPitch = 0.94f,
                customSpeechRate = 1.15f,
                vocalGritPercent = 25,
                aggressionPercent = 75,
                isActiveGlobal = false,
                isPreloaded = true,
                assignedCategoriesCsv = "Personal,Health & Reset",
                sampleDirective = "I see you on that starting line! Lock in that handshake right now! Give me five focused minutes—let's build real momentum!"
            ),
            PersonalityPresetEntity(
                id = "preset_relentless_partner",
                name = "Relentless Partner",
                description = "Conversational accountability partner who refuses to let you rationalize skipping.",
                basePersonalityId = "accountability_partner",
                voicePackId = "pack_sgt_brody",
                instructionFirmness = 4,
                obnoxiousness = 3,
                alertIntensity = 4,
                persistence = 5,
                handshakeDifficulty = 3,
                scheduleRigidity = 4,
                recoveryStructure = 3,
                preferredHandshakeType = HandshakeType.HOLD.name,
                cadenceStyle = CadenceStyle.PACE_LOCKED.name,
                eqPreset = EqPreset.DEEP_COMMAND.name,
                customPitch = 0.94f,
                customSpeechRate = 1.14f,
                vocalGritPercent = 35,
                aggressionPercent = 65,
                isActiveGlobal = false,
                isPreloaded = true,
                assignedCategoriesCsv = "Career Work,Recurring Calendar",
                sampleDirective = "Lock it in right now. You put this on your calendar for a reason. Do the handshake and let's get off zero together."
            ),
            PersonalityPresetEntity(
                id = "preset_minimalist_telemetry",
                name = "Minimalist Telemetry",
                description = "Ultra-terse, clinical telemetry directives with zero fluff or commentary.",
                basePersonalityId = "minimalist",
                voicePackId = "pack_aris_thorne",
                instructionFirmness = 4,
                obnoxiousness = 1,
                alertIntensity = 3,
                persistence = 2,
                handshakeDifficulty = 2,
                scheduleRigidity = 5,
                recoveryStructure = 5,
                preferredHandshakeType = HandshakeType.HOLD.name,
                cadenceStyle = CadenceStyle.DELIBERATE_AI.name,
                eqPreset = EqPreset.STUDIO_CRISP.name,
                customPitch = 0.95f,
                customSpeechRate = 1.00f,
                vocalGritPercent = 0,
                aggressionPercent = 10,
                isActiveGlobal = false,
                isPreloaded = true,
                assignedCategoriesCsv = "ALL",
                sampleDirective = "Time reached. Acknowledge handshake. Begin execution."
            ),
            PersonalityPresetEntity(
                id = "preset_custom_sprint",
                name = "Sprint Director",
                description = "Custom tuned director with high tempo, urgent barks, and 2-step verification.",
                basePersonalityId = "custom",
                voicePackId = "pack_major_stone",
                instructionFirmness = 5,
                obnoxiousness = 4,
                alertIntensity = 5,
                persistence = 4,
                handshakeDifficulty = 4,
                scheduleRigidity = 4,
                recoveryStructure = 4,
                preferredHandshakeType = HandshakeType.TWO_STEP.name,
                cadenceStyle = CadenceStyle.CLIPPED_TACTICAL.name,
                eqPreset = EqPreset.TACTICAL_HUD.name,
                customPitch = 0.90f,
                customSpeechRate = 1.20f,
                vocalGritPercent = 70,
                aggressionPercent = 85,
                customPersonaPrompt = "High-energy startup sprint commander. Demands swift execution without overthinking.",
                vocabularyRules = "Use terms like 'Ship it', 'High velocity', 'Zero hesitation'. Avoid passive verbs.",
                catchphrasesCsv = "Momentum is everything!, Ship or shut down!, Fast over perfect!",
                isActiveGlobal = false,
                isPreloaded = true,
                assignedCategoriesCsv = "Career Work",
                sampleDirective = "Sprint block is live! Do not overthink the architecture—get the first function working right now!"
            )
        )

        fun defaultRoutingRules(): List<PresetRoutingRuleEntity> = listOf(
            PresetRoutingRuleEntity(
                id = 1,
                ruleName = "Early Morning Boot Camp",
                targetCategory = "Morning Routine",
                startHour = 5,
                endHour = 9,
                targetPresetId = "preset_morning_grind",
                priorityOrder = 1,
                isEnabled = true,
                notes = "High-intensity wake-up directive to break morning inertia"
            ),
            PresetRoutingRuleEntity(
                id = 2,
                ruleName = "Core Business Deep Work",
                targetCategory = "Career Work",
                startHour = 9,
                endHour = 17,
                targetPresetId = "preset_deep_work_focus",
                priorityOrder = 2,
                isEnabled = true,
                notes = "Surgical executive focus for professional deep work blocks"
            ),
            PresetRoutingRuleEntity(
                id = 3,
                ruleName = "Evening Recovery & Reset",
                targetCategory = "Health & Reset",
                startHour = 17,
                endHour = 22,
                targetPresetId = "preset_gentle_momentum",
                priorityOrder = 3,
                isEnabled = true,
                notes = "Encouraging low-anxiety coaching to wrap up the day cleanly"
            )
        )

        fun defaultEscalationPolicies(): List<EscalationPolicyEntity> = listOf(
            EscalationPolicyEntity(
                id = "policy_urgent_pulse",
                policyName = "Urgent Pulse Surge",
                graceWindowMinutes = 3,
                repeatIntervalSeconds = 30,
                volumeRampCurve = VolumeRampCurve.PULSE_SURGE.name,
                maxStrikesBeforeRecovery = 3,
                autoTriggerFiveMinStarter = true,
                isSystemDefault = true
            ),
            EscalationPolicyEntity(
                id = "policy_executive_standard",
                policyName = "Executive Standard",
                graceWindowMinutes = 5,
                repeatIntervalSeconds = 45,
                volumeRampCurve = VolumeRampCurve.STEADY_CLIMB.name,
                maxStrikesBeforeRecovery = 3,
                autoTriggerFiveMinStarter = true,
                isSystemDefault = false
            ),
            EscalationPolicyEntity(
                id = "policy_gentle_ramp",
                policyName = "Gentle Extended Grace",
                graceWindowMinutes = 10,
                repeatIntervalSeconds = 60,
                volumeRampCurve = VolumeRampCurve.STEADY_CLIMB.name,
                maxStrikesBeforeRecovery = 5,
                autoTriggerFiveMinStarter = false,
                isSystemDefault = false
            )
        )

        fun defaultHandshakeCalibrations(): List<HandshakeCalibrationEntity> = listOf(
            HandshakeCalibrationEntity(
                id = "calib_tactical_chained",
                profileName = "Tactical Chained (Hold + Sequence)",
                primaryHandshakeType = HandshakeType.HOLD.name,
                secondaryHandshakeType = HandshakeType.SEQUENCE.name,
                holdSecondsRequired = 3,
                mathComplexityLevel = 2,
                requiredVoicePhrase = "SNAP TO",
                strictVerification = true,
                isSystemDefault = true
            ),
            HandshakeCalibrationEntity(
                id = "calib_typing_focus",
                profileName = "Executive Phrase Commitment",
                primaryHandshakeType = HandshakeType.TYPING.name,
                secondaryHandshakeType = "NONE",
                holdSecondsRequired = 3,
                mathComplexityLevel = 1,
                requiredVoicePhrase = "LOCKED IN",
                strictVerification = true,
                isSystemDefault = false
            ),
            HandshakeCalibrationEntity(
                id = "calib_single_hold",
                profileName = "Single 3-Second Physical Lock",
                primaryHandshakeType = HandshakeType.HOLD.name,
                secondaryHandshakeType = "NONE",
                holdSecondsRequired = 3,
                mathComplexityLevel = 1,
                requiredVoicePhrase = "READY",
                strictVerification = false,
                isSystemDefault = false
            )
        )

        fun defaultMissionBlueprints(): List<MissionBlueprintEntity> = listOf(
            MissionBlueprintEntity(
                id = "bp_deep_work",
                title = "Career Work — Deep Architecture Sprint",
                category = "Career Work",
                defaultDurationMinutes = 60,
                firstActionInstruction = "Open code workspace and write core interface signature.",
                spokenPromptTemplate = "Focus block active. Clear secondary tabs and engage step one.",
                assignedPresetId = "preset_deep_work_focus",
                escalationPolicyId = "policy_executive_standard",
                handshakeProfileId = "calib_typing_focus"
            ),
            MissionBlueprintEntity(
                id = "bp_morning_conditioning",
                title = "Morning Physical Conditioning",
                category = "Morning Routine",
                defaultDurationMinutes = 30,
                firstActionInstruction = "Put on training shoes and start 5-minute dynamic warm-up.",
                spokenPromptTemplate = "EYEBALLS! ON YOUR FEET! FIVE MINUTES OF WARMUP RIGHT NOW!",
                assignedPresetId = "preset_morning_grind",
                escalationPolicyId = "policy_urgent_pulse",
                handshakeProfileId = "calib_tactical_chained"
            ),
            MissionBlueprintEntity(
                id = "bp_email_triage",
                title = "Zero-Inbox Communications Triage",
                category = "Career Work",
                defaultDurationMinutes = 25,
                firstActionInstruction = "Archive or flag top 5 urgent threads in inbox.",
                spokenPromptTemplate = "Triage block initiated. Process communications without distraction.",
                assignedPresetId = "preset_deep_work_focus",
                escalationPolicyId = "policy_executive_standard"
            )
        )
        fun defaultVoicePacks(): List<VoicePackEntity> = listOf(
            VoicePackEntity(
                id = "pack_marcus_vance",
                name = "Marcus Vance — Professional Studio Vocalist",
                actorName = "Marcus Vance (Studio Voice Actor)",
                accentLabel = "Neutral US Studio • Clear Articulation",
                emotionalTone = EmotionalTone.CALM.name,
                languageTag = "en-US",
                pitch = 1.0f,
                speechRate = 1.02f,
                isCustom = false,
                usesDrillSergeantArt = false,
                isFavorite = true,
                eqPreset = EqPreset.STUDIO_CRISP.name,
                volumeBoostPercent = 125,
                cadenceStyle = CadenceStyle.DELIBERATE_AI.name,
                qualityTierLabel = "CERTIFIED HUMAN • CC0",
                voiceModelType = VoiceModelType.EXEC_NEURAL_HD.name,
                vocalGritPercent = 0,
                aggressionPercent = 30,
                isHumanStem = true,
                licenseType = "Creative Commons Zero (CC0) / Public Domain",
                legalAttribution = "Recorded with verified human voice artist consent. 100% royalty-free for commercial & private application use. Unlimited right to alter, pitch-shift, and apply DSP filters without copyright restrictions.",
                formantShiftPercent = 0,
                stemResourceId = "stem_marcus_vance",
                sampleQuote = "Welcome. Your commitment window is active. Let's verify readiness through the handshake and complete your first five minutes together.",
                reveilleScript = "Attention. Your scheduled commitment window is open. Verify your readiness via the handshake.",
                handshakeSuccessScript = "Handshake confirmed. Direct your focus to the primary objective and complete your opening five-minute block.",
                starterScript = "Five-minute initiation underway. Stay locked on step one."
            ),
            VoicePackEntity(
                id = "pack_elena_rostova",
                name = "Elena Rostova — Precision Cadence Specialist",
                actorName = "Elena Rostova (Studio Voice Artist)",
                accentLabel = "Neutral European Studio • Crisp Cadence",
                emotionalTone = EmotionalTone.STERN.name,
                languageTag = "en-GB",
                pitch = 1.02f,
                speechRate = 1.05f,
                isCustom = false,
                usesDrillSergeantArt = false,
                isFavorite = true,
                eqPreset = EqPreset.STUDIO_CRISP.name,
                volumeBoostPercent = 125,
                cadenceStyle = CadenceStyle.DELIBERATE_AI.name,
                qualityTierLabel = "CERTIFIED HUMAN • CC0",
                voiceModelType = VoiceModelType.EXEC_NEURAL_HD.name,
                vocalGritPercent = 0,
                aggressionPercent = 45,
                isHumanStem = true,
                licenseType = "Creative Commons Zero (CC0)",
                legalAttribution = "Studio vocal performance released under CC0 1.0 Universal Public Domain Dedication. Unrestricted commercial use, modification, formant alteration, and distribution.",
                formantShiftPercent = 10,
                stemResourceId = "stem_elena_rostova",
                sampleQuote = "Time is precise. Acknowledge your commitment immediately. Execute the first action and maintain structured discipline.",
                reveilleScript = "Protocol active. Your scheduled commitment starts now. Verify with the handshake.",
                handshakeSuccessScript = "Readiness verified. Eliminate secondary tasks and execute step one immediately.",
                starterScript = "Five-minute starter initiated. Keep steady focus."
            ),
            VoicePackEntity(
                id = "pack_sgt_miller",
                name = "Staff Sgt. Miller — Public Domain Field Announcer",
                actorName = "SSgt. David Miller (DoD Field Voice)",
                accentLabel = "U.S. Public Domain • Tactical Command Projection",
                emotionalTone = EmotionalTone.URGENT.name,
                languageTag = "en-US",
                pitch = 0.90f,
                speechRate = 1.15f,
                isCustom = false,
                usesDrillSergeantArt = true,
                isFavorite = true,
                eqPreset = EqPreset.TACTICAL_HUD.name,
                volumeBoostPercent = 140,
                cadenceStyle = CadenceStyle.CLIPPED_TACTICAL.name,
                qualityTierLabel = "U.S. PUBLIC DOMAIN • 44.1kHz",
                voiceModelType = VoiceModelType.USMC_PARADE_CALLER.name,
                vocalGritPercent = 55,
                aggressionPercent = 85,
                isHumanStem = true,
                licenseType = "U.S. Public Domain (17 U.S.C. § 105)",
                legalAttribution = "Work of the United States Federal Government under Title 17, Chapter 1, Section 105 of the United States Code. Globally in the public domain and fully cleared for alteration, sampling, and commercial redistribution.",
                formantShiftPercent = -20,
                stemResourceId = "stem_sgt_miller",
                sampleQuote = "STAND BY FOR MISSION INITIATION! YOU LOCKED IN THIS BLOCK—NOW SOUND OFF AND ATTACK THE FIRST FIVE MINUTES! MOVE OUT!",
                reveilleScript = "ALL STATIONS ALERT! REVEILLE HAS SOUNDED! HIT THAT HANDSHAKE AND REPORT READY!",
                handshakeSuccessScript = "HANDSHAKE CONFIRMED! INITIATE FIVE-MINUTE ACTION RUN IMMEDIATELY! DO NOT HESITATE!",
                starterScript = "STARTER CLOCK TICKING! DRIVE FORWARD ON PRIMARY OBJECTIVE!"
            ),
            VoicePackEntity(
                id = "pack_major_stone",
                name = "Gunnery Sgt. Stone — MCRD Pick-Up Day",
                actorName = "GySgt. Marcus Stone (USMC MCRD)",
                accentLabel = "MCRD San Diego • Frog-Voice Overdrive Bark",
                emotionalTone = EmotionalTone.URGENT.name,
                languageTag = "en-US",
                pitch = 0.86f,
                speechRate = 1.28f,
                isCustom = false,
                usesDrillSergeantArt = true,
                isFavorite = true,
                eqPreset = EqPreset.TACTICAL_HUD.name,
                volumeBoostPercent = 145,
                cadenceStyle = CadenceStyle.CLIPPED_TACTICAL.name,
                qualityTierLabel = "USMC MCRD MASTER • 44.1kHz",
                voiceModelType = VoiceModelType.USMC_MCRD_BARK.name,
                vocalGritPercent = 85,
                aggressionPercent = 95,
                isHumanStem = false,
                licenseType = "Open Tactical Engine (Studio Stem)",
                legalAttribution = "Studio vocal master recording with authentic MCRD diaphragm projection, staccato barks, and whistle cue.",
                formantShiftPercent = -35,
                stemResourceId = "raw/voice_drill_sergeant",
                sampleQuote = "The squad bay with an unmistakable, guttural roar! EYEBALLS! SNAP TO! AND MOVE! Every word is fired like a volley of kinetic brass—immediate, reflex-level commitment!",
                reveilleScript = "EYEBALLS! REVEILLE ON DECK! DROP EVERY DISTRACTION RIGHT NOW! LOCK IN YOUR HANDSHAKE BEFORE I COUNT TO THREE! MOVE!",
                handshakeSuccessScript = "HANDSHAKE VERIFIED! OUTSTANDING! NOW GET OFF MY STARTING LINE! OPEN YOUR WORKSPACE AND ATTACK THE FIRST FIVE MINUTES! ZERO HESITATION!",
                starterScript = "FIVE-MINUTE STARTER ON THE CLOCK! DO NOT STARE AT THE SCREEN—EXECUTE STEP ONE RIGHT NOW, AYE SIR!"
            ),
            VoicePackEntity(
                id = "pack_sgt_brody",
                name = "Master Sgt. Brody — Parade Deck Caller",
                actorName = "MSgt. Cole Brody (Drill Cadence)",
                accentLabel = "US Southern Tactical • Booming Chest Resonance",
                emotionalTone = EmotionalTone.STERN.name,
                languageTag = "en-US",
                pitch = 0.82f,
                speechRate = 1.15f,
                isCustom = false,
                usesDrillSergeantArt = true,
                isFavorite = false,
                eqPreset = EqPreset.DEEP_COMMAND.name,
                volumeBoostPercent = 140,
                cadenceStyle = CadenceStyle.PACE_LOCKED.name,
                qualityTierLabel = "CHEST RESONATOR • 44.1kHz",
                voiceModelType = VoiceModelType.USMC_PARADE_CALLER.name,
                vocalGritPercent = 60,
                aggressionPercent = 82,
                isHumanStem = false,
                licenseType = "Open Tactical Engine (MIT)",
                legalAttribution = "Chest sub-harmonic resonator model with parade-deck cadence modulation.",
                formantShiftPercent = -25,
                stemResourceId = "dsp_parade_caller",
                sampleQuote = "LEFT, RIGHT, LOCK IT IN! SOUND OFF! YOU MADE THIS SCHEDULED COMMITMENT—NOW STEP UP TO THE LINE AND GIVE ME FIVE SOLID MINUTES! MOVE OUT!",
                reveilleScript = "ATTENTION ON DECK! YOUR SCHEDULED COMMITMENT IS LIVE! SOUND OFF WITH THE HANDSHAKE RIGHT NOW!",
                handshakeSuccessScript = "LOCKED AND LOADED! FORWARD MARCH INTO YOUR FIRST FIVE MINUTES! KEEP YOUR EYES ON THE OBJECTIVE!",
                starterScript = "CADENCE COUNTING DOWN! FIVE MINUTES OF PURE EXECUTION—STAY IN FORMATION!"
            ),
            VoicePackEntity(
                id = "pack_eleanor_vance",
                name = "Director Eleanor Vance — Corporate Executive",
                actorName = "Director Eleanor Vance (Fortune 500 Executive)",
                accentLabel = "Black American Corporate Executive • Velvet-Textured Authority",
                emotionalTone = EmotionalTone.CALM.name,
                languageTag = "en-US",
                pitch = 1.01f,
                speechRate = 1.04f,
                isCustom = false,
                usesDrillSergeantArt = false,
                isFavorite = true,
                eqPreset = EqPreset.STUDIO_CRISP.name,
                volumeBoostPercent = 125,
                cadenceStyle = CadenceStyle.DELIBERATE_AI.name,
                qualityTierLabel = "EXECUTIVE MASTER • 44.1kHz",
                voiceModelType = VoiceModelType.EXEC_NEURAL_HD.name,
                vocalGritPercent = 0,
                aggressionPercent = 35,
                isHumanStem = true,
                licenseType = "Studio Master Stem Recording",
                legalAttribution = "High-definition executive studio recording with surgical intellect and velvet-textured boardroom authority.",
                formantShiftPercent = 10,
                stemResourceId = "raw/voice_female_executive",
                sampleQuote = "Commands the boardroom with an effortless, velvet-textured authority: Let's look at the bottom line. Here is how we deliver on this, and we move forward with intention.",
                reveilleScript = "Attention please. Your protected calendar commitment is now active. Verify readiness via the handshake.",
                handshakeSuccessScript = "Handshake confirmed. Clear secondary tabs and dedicate the next five minutes exclusively to your primary objective.",
                starterScript = "Five-minute initiation timer engaged. Momentum begins with the very first action."
            ),
            VoicePackEntity(
                id = "pack_coach_ray",
                name = "Coach Jordan Vance — Personal & Life Coach",
                actorName = "Coach Jordan Vance (Personal & Life Coach)",
                accentLabel = "Black American Male Fitness Coach • Smooth Commanding Baritone",
                emotionalTone = EmotionalTone.ENERGETIC.name,
                languageTag = "en-US",
                pitch = 0.94f,
                speechRate = 1.10f,
                isCustom = false,
                usesDrillSergeantArt = false,
                isFavorite = true,
                eqPreset = EqPreset.DEEP_COMMAND.name,
                volumeBoostPercent = 135,
                cadenceStyle = CadenceStyle.PACE_LOCKED.name,
                qualityTierLabel = "PERSONAL COACH MASTER • 44.1kHz",
                voiceModelType = VoiceModelType.KINETIC_COACH.name,
                vocalGritPercent = 15,
                aggressionPercent = 65,
                isHumanStem = true,
                licenseType = "Studio Master Stem Recording",
                legalAttribution = "Studio vocal master recording of experienced personal coach with rich baritone resonance, steady conviction, and contagious confidence.",
                formantShiftPercent = -10,
                stemResourceId = "raw/voice_male_fitness",
                sampleQuote = "Commands the gym floor with an effortless, rich baritone and an audible smile: Take a breath, reset your shoulders, and lock in. We’re not rushing this rep—we’re owning it. You’ve already done the hard part by showing up; now let's just execute one solid set at a time.",
                reveilleScript = "Take a breath, reset your shoulders, and lock in. Your coaching block is live right now! Acknowledge that handshake and let's own this day together.",
                handshakeSuccessScript = "Handshake locked in! You've already done the hard part by showing up; now let's execute one solid set at a time.",
                starterScript = "Timer is running smooth. Stay steady, stay focused, and own this opening block."
            ),
            VoicePackEntity(
                id = "pack_coach_maya",
                name = "Coach Maya Lin — Radiant Motivational Coach",
                actorName = "Coach Maya Lin (Motivational Coach & Life Guru)",
                accentLabel = "Radiant Motivation • Magnetic Optimism & High-Stakes Clarity",
                emotionalTone = EmotionalTone.ENERGETIC.name,
                languageTag = "en-US",
                pitch = 0.98f,
                speechRate = 1.15f,
                isCustom = false,
                usesDrillSergeantArt = false,
                isFavorite = true,
                eqPreset = EqPreset.STUDIO_CRISP.name,
                volumeBoostPercent = 135,
                cadenceStyle = CadenceStyle.PACE_LOCKED.name,
                qualityTierLabel = "RADIANT MOTIVATION • 44.1kHz",
                voiceModelType = VoiceModelType.KINETIC_COACH.name,
                vocalGritPercent = 20,
                aggressionPercent = 75,
                isHumanStem = true,
                licenseType = "Studio Master Stem Recording",
                legalAttribution = "Studio vocal recording of top-tier motivational life coach with vibrant cadence, magnetic optimism, and empowering inflection.",
                formantShiftPercent = -5,
                stemResourceId = "raw/voice_motivational_coach",
                sampleQuote = "Own your energy! Claim this morning! And step into your power right now! Make ambitious goals feel completely inevitable—an unstoppable surge of confidence before your feet even touch the floor!",
                reveilleScript = "Eyes up! Your personal coaching mission is live! Hit that handshake with conviction and let's get after it!",
                handshakeSuccessScript = "Outstanding, handshake locked! We're building discipline rep by rep! Dive straight into your first five minutes right now!",
                starterScript = "Timer is running! Five minutes of total commitment. Stay locked on step one!"
            ),
            VoicePackEntity(
                id = "pack_iron_sentinel",
                name = "Commander Kael — Tactical Overwatch",
                actorName = "Victor Kael",
                accentLabel = "Transatlantic • Overwatch Radio Comms",
                emotionalTone = EmotionalTone.STERN.name,
                languageTag = "en-US",
                pitch = 0.92f,
                speechRate = 1.12f,
                isCustom = false,
                usesDrillSergeantArt = false,
                isFavorite = false,
                eqPreset = EqPreset.TACTICAL_HUD.name,
                volumeBoostPercent = 130,
                cadenceStyle = CadenceStyle.CLIPPED_TACTICAL.name,
                qualityTierLabel = "COMMS BANDPASS • 44.1kHz",
                voiceModelType = VoiceModelType.TACTICAL_COMMS.name,
                vocalGritPercent = 35,
                aggressionPercent = 72,
                isHumanStem = false,
                licenseType = "Tactical Comms License (CC0)",
                legalAttribution = "Military bandpass filtered comms simulation model.",
                formantShiftPercent = -15,
                stemResourceId = "comms_kael",
                sampleQuote = "Overwatch actual. Commitment window open. Authenticate via handshake and engage primary tasking for five minutes. Out.",
                reveilleScript = "Overwatch alert. Schedule lock engaged. Complete verification handshake immediately.",
                handshakeSuccessScript = "Verification copy. Proceed to primary workstation and execute five-minute starter protocol.",
                starterScript = "Starter clock active. Maintain operational focus."
            ),
            VoicePackEntity(
                id = "pack_aris_thorne",
                name = "Dr. Aris Thorne — Calm Strategist",
                actorName = "Dr. Aris Thorne",
                accentLabel = "US Warm Baritone • Deep Focus & Recovery",
                emotionalTone = EmotionalTone.CALM.name,
                languageTag = "en-US",
                pitch = 0.95f,
                speechRate = 0.96f,
                isCustom = false,
                usesDrillSergeantArt = false,
                isFavorite = false,
                eqPreset = EqPreset.BROADCAST.name,
                volumeBoostPercent = 115,
                cadenceStyle = CadenceStyle.DELIBERATE_AI.name,
                qualityTierLabel = "VELVET LOW-PASS • 44.1kHz",
                voiceModelType = VoiceModelType.CALM_STRATEGIST.name,
                vocalGritPercent = 0,
                aggressionPercent = 15,
                isHumanStem = false,
                licenseType = "Warm Baritone Neural Model",
                legalAttribution = "Low-pass velvet baritone model designed for stress-free initiation.",
                formantShiftPercent = -20,
                stemResourceId = "calm_thorne",
                sampleQuote = "Take a steady breath. Complete the handshake when ready, and begin with just five calm, focused minutes on your first step.",
                reveilleScript = "Your scheduled block is beginning. Acknowledge the handshake to transition smoothly into focus.",
                handshakeSuccessScript = "Thank you. Set aside everything else and give five unhurried minutes to your first action.",
                starterScript = "Five-minute starter underway. One simple step at a time."
            )
        )
    }
}
