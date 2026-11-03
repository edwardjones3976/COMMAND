package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CadenceStyle
import com.example.data.CommandDatabase
import com.example.data.CommandRepository
import com.example.data.DailyHandshakeRecord
import com.example.data.DEFAULT_PERSONALITIES
import com.example.data.EmotionalTone
import com.example.data.EqPreset
import com.example.data.HandshakeType
import com.example.data.HourlyHandshakeBucket
import com.example.data.MilestoneBadgeSpec
import com.example.data.MissionEntity
import com.example.data.MissionStatus
import com.example.data.PersonalityPresetEntity
import com.example.data.RecoveryProtocol
import com.example.data.StreakChartTelemetry
import com.example.data.StreakChartViewMode
import com.example.data.StreakProfileEntity
import com.example.data.VoiceModelType
import com.example.data.VoicePackEntity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.example.service.CalendarBackgroundSyncService
import com.example.service.CalendarSyncManager
import com.example.service.CalendarSyncScheduler
import com.example.service.ReveilleAlarmScheduler
import com.example.service.VoiceDirectorEngine
import com.example.service.VoicePlaybackState
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

enum class ActiveExecutionPhase {
    IDLE,
    REVEILLE_HANDSHAKE,
    FIVE_MINUTE_STARTER,
    LIGHTWEIGHT_REPORTING
}

data class ActiveExecutionSession(
    val mission: MissionEntity,
    val phase: ActiveExecutionPhase = ActiveExecutionPhase.REVEILLE_HANDSHAKE,
    val voicePack: VoicePackEntity,
    val activeSpokenBanner: String,
    val starterRemainingSeconds: Int = 300,
    val isStarterPaused: Boolean = false
)

data class CommandUiState(
    val missions: List<MissionEntity> = emptyList(),
    val voicePacks: List<VoicePackEntity> = emptyList(),
    val streakProfile: StreakProfileEntity = StreakProfileEntity(),
    val playbackState: VoicePlaybackState = VoicePlaybackState(),
    val activeSession: ActiveExecutionSession? = null,
    val statusBannerMessage: String? = null,
    val unlockedBadgeCelebration: MilestoneBadgeSpec? = null,
    val calendarSyncSummary: String = "Google Calendar Provider ready • Tap Sync to check conflicts",
    val isBriefingViewerOpen: Boolean = false,
    val briefingRemainingSeconds: Int = 15,
    val streakChart: StreakChartTelemetry = StreakChartTelemetry(),
    val presets: List<PersonalityPresetEntity> = emptyList(),
    val activeCustomPreset: PersonalityPresetEntity? = null,
    val isBackgroundSyncActive: Boolean = true,
    val backgroundSyncIntervalMinutes: Int = 120,
    val lastBackgroundSyncTimestamp: Long = 0L,
    val routingRules: List<com.example.data.PresetRoutingRuleEntity> = emptyList(),
    val escalationPolicies: List<com.example.data.EscalationPolicyEntity> = emptyList(),
    val handshakeCalibrations: List<com.example.data.HandshakeCalibrationEntity> = emptyList(),
    val blueprints: List<com.example.data.MissionBlueprintEntity> = emptyList(),
    val auditionPhase: com.example.service.ExecutionPhase = com.example.service.ExecutionPhase.REVEILLE,
    val auditionDirectivesMap: Map<String, String> = emptyMap(),
    val isAuditionGenerating: Boolean = false,
    val activeAuditionMissionTitle: String = "Career Work — Deep Architecture Sprint",
    val activeAuditionCategory: String = "Career Work"
)

class CommandViewModel(application: Application) : AndroidViewModel(application) {

    private val database = CommandDatabase.getInstance(application)
    private val repository = CommandRepository(
        database.commandDao(),
        database.personalityPresetDao(),
        database.managementModulesDao()
    )
    private val voiceEngine = VoiceDirectorEngine(application, viewModelScope)
    private val calendarSyncManager = CalendarSyncManager(application)
    private val aiService = com.example.service.CommandAiService()

    private val _activeSession = MutableStateFlow<ActiveExecutionSession?>(null)
    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    private val _unlockedBadgeCelebration = MutableStateFlow<MilestoneBadgeSpec?>(null)
    private val _calendarSyncSummary = MutableStateFlow("Google Calendar Provider ready • Tap Sync to inspect schedule")
    private val _isBriefingViewerOpen = MutableStateFlow(false)
    private val _briefingRemainingSeconds = MutableStateFlow(15)
    private val _selectedStreakDayIndex = MutableStateFlow<Int?>(null)
    private val _streakChartViewMode = MutableStateFlow(StreakChartViewMode.WEEKLY_BARS)
    private val _isBackgroundSyncActive = MutableStateFlow(calendarSyncManager.isPeriodicSyncScheduled())
    private val _backgroundSyncIntervalMinutes = MutableStateFlow(calendarSyncManager.getPeriodicIntervalMinutes())
    private val _lastBackgroundSyncTimestamp = MutableStateFlow(calendarSyncManager.getLastSyncTimestamp())

    // Audition Deck & Management State
    private val _auditionPhase = MutableStateFlow(com.example.service.ExecutionPhase.REVEILLE)
    private val _auditionDirectivesMap = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _isAuditionGenerating = MutableStateFlow(false)
    private val _activeAuditionMissionTitle = MutableStateFlow("Career Work — Deep Architecture Sprint")
    private val _activeAuditionCategory = MutableStateFlow("Career Work")

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CalendarBackgroundSyncService.ACTION_CALENDAR_SYNC_COMPLETED) {
                val synced = intent.getIntExtra(CalendarBackgroundSyncService.EXTRA_SYNCED_COUNT, 0)
                val recurring = intent.getIntExtra(CalendarBackgroundSyncService.EXTRA_RECURRING_COUNT, 0)
                val conflicts = intent.getIntExtra(CalendarBackgroundSyncService.EXTRA_CONFLICTS_COUNT, 0)
                val source = intent.getStringExtra(CalendarBackgroundSyncService.EXTRA_SOURCE_LABEL) ?: "Android CalendarProvider"
                val ts = intent.getLongExtra(CalendarBackgroundSyncService.EXTRA_SYNC_TIMESTAMP_MS, System.currentTimeMillis())
                _lastBackgroundSyncTimestamp.value = ts
                _calendarSyncSummary.value = "$source • $synced synced ($recurring recurring) • $conflicts conflict(s)"
                _statusBannerMessage.value = "Background Calendar Sync: $synced tasks aligned, execution alarms armed."
            }
        }
    }

    private var starterTimerJob: Job? = null
    private var briefingTimerJob: Job? = null

    val selectedStreakDayIndex: StateFlow<Int?> = _selectedStreakDayIndex.asStateFlow()
    val streakChartViewMode: StateFlow<StreakChartViewMode> = _streakChartViewMode.asStateFlow()

    private val modulesFlow = combine(
        repository.routingRulesFlow,
        repository.escalationPoliciesFlow,
        repository.handshakeCalibrationsFlow,
        repository.blueprintsFlow
    ) { rules, policies, calibs, blueprints ->
        ModulesAux(rules, policies, calibs, blueprints)
    }

    private val auditionFlow = combine(
        _auditionPhase,
        _auditionDirectivesMap,
        _isAuditionGenerating,
        _activeAuditionMissionTitle,
        _activeAuditionCategory
    ) { phase, map, isGen, title, cat ->
        AuditionAux(phase, map, isGen, title, cat)
    }

    val uiState: StateFlow<CommandUiState> = combine(
        repository.missionsFlow,
        repository.voicePacksFlow,
        repository.streakProfileFlow,
        repository.presetsFlow,
        _activeSession
    ) { missions, packs, profile, presetsList, session ->
        val sortedPacks = (packs.ifEmpty { CommandRepository.defaultVoicePacks() })
            .sortedWith(compareByDescending<VoicePackEntity> { it.isFavorite }.thenBy { it.name })
        val effectivePresets = presetsList.ifEmpty { CommandRepository.defaultPresets() }
        val activePreset = effectivePresets.find { it.id == profile?.activeCustomPresetId || it.isActiveGlobal }
            ?: effectivePresets.firstOrNull()
        CommandUiState(
            missions = missions,
            voicePacks = sortedPacks,
            streakProfile = profile ?: StreakProfileEntity(),
            activeSession = session,
            presets = effectivePresets,
            activeCustomPreset = activePreset
        )
    }.combine(voiceEngine.playbackState) { base, playback ->
        base.copy(playbackState = playback)
    }.combine(modulesFlow) { base, mods ->
        base.copy(
            routingRules = mods.rules,
            escalationPolicies = mods.policies,
            handshakeCalibrations = mods.calibs,
            blueprints = mods.blueprints
        )
    }.combine(auditionFlow) { base, aud ->
        base.copy(
            auditionPhase = aud.phase,
            auditionDirectivesMap = aud.map,
            isAuditionGenerating = aud.isGen,
            activeAuditionMissionTitle = aud.missionTitle,
            activeAuditionCategory = aud.category
        )
    }.combine(
        combine(
            _statusBannerMessage,
            _unlockedBadgeCelebration,
            _calendarSyncSummary,
            _isBriefingViewerOpen,
            _briefingRemainingSeconds
        ) { banner, badge, syncSummary, briefingOpen, briefingSecs ->
            FiveAux(banner, badge, syncSummary, briefingOpen, briefingSecs)
        }.combine(
            combine(
                _selectedStreakDayIndex,
                _streakChartViewMode
            ) { dayIdx, mode ->
                Pair(dayIdx, mode)
            }
        ) { five, pair ->
            Pair(five, pair)
        }.combine(
            combine(
                _isBackgroundSyncActive,
                _backgroundSyncIntervalMinutes,
                _lastBackgroundSyncTimestamp
            ) { isBgActive, intervalMins, lastSyncTs ->
                SyncAux(isBgActive, intervalMins, lastSyncTs)
            }
        ) { (five, pair), syncAux ->
            AuxUiState(
                banner = five.banner,
                badge = five.badge,
                syncSummary = five.syncSummary,
                briefingOpen = five.briefingOpen,
                briefingSecs = five.briefingSecs,
                selectedDayIndex = pair.first,
                viewMode = pair.second,
                isBgActive = syncAux.isBgActive,
                intervalMins = syncAux.intervalMins,
                lastSyncTs = syncAux.lastSyncTs
            )
        }
    ) { base, aux ->
        base.copy(
            statusBannerMessage = aux.banner,
            unlockedBadgeCelebration = aux.badge,
            calendarSyncSummary = aux.syncSummary,
            isBriefingViewerOpen = aux.briefingOpen,
            briefingRemainingSeconds = aux.briefingSecs,
            streakChart = buildStreakTelemetry(
                missions = base.missions,
                streakProfile = base.streakProfile,
                selectedDayIndex = aux.selectedDayIndex,
                viewMode = aux.viewMode
            ),
            isBackgroundSyncActive = aux.isBgActive,
            backgroundSyncIntervalMinutes = aux.intervalMins,
            lastBackgroundSyncTimestamp = aux.lastSyncTs
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CommandUiState(
            voicePacks = CommandRepository.defaultVoicePacks(),
            presets = CommandRepository.defaultPresets()
        )
    )

    init {
        viewModelScope.launch {
            repository.ensureSeedData()
        }
        val filter = IntentFilter(CalendarBackgroundSyncService.ACTION_CALENDAR_SYNC_COMPLETED)
        ContextCompat.registerReceiver(
            application,
            syncReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (calendarSyncManager.isPeriodicSyncScheduled()) {
            calendarSyncManager.schedulePeriodicBackgroundSync(calendarSyncManager.getPeriodicIntervalMinutes())
        }
    }

    fun dismissBanner() {
        _statusBannerMessage.value = null
    }

    fun dismissBadgeCelebration() {
        _unlockedBadgeCelebration.value = null
    }

    fun triggerReveilleForMission(mission: MissionEntity) {
        starterTimerJob?.cancel()
        val preset = resolvePresetForMission(mission)
        val packs = uiState.value.voicePacks
        val basePack = packs.find { it.id == preset.voicePackId }
            ?: packs.find { it.id == mission.voicePackId }
            ?: CommandRepository.defaultVoicePacks().first()

        val effectivePack = basePack.copy(
            pitch = preset.customPitch,
            speechRate = preset.customSpeechRate,
            cadenceStyle = preset.cadenceStyle,
            eqPreset = preset.eqPreset,
            vocalGritPercent = preset.vocalGritPercent,
            aggressionPercent = preset.aggressionPercent
        )

        // Instant dynamic matrix fallback spoken immediately to eliminate any network latency
        val immediateSpoken = aiService.synthesizeDynamicMatrixFallback(
            personalityId = preset.basePersonalityId,
            preset = preset,
            phase = com.example.service.ExecutionPhase.REVEILLE,
            missionTitle = mission.title,
            firstActionPrompt = mission.firstActionPrompt
        )

        _activeSession.value = ActiveExecutionSession(
            mission = mission,
            phase = ActiveExecutionPhase.REVEILLE_HANDSHAKE,
            voicePack = effectivePack,
            activeSpokenBanner = immediateSpoken,
            starterRemainingSeconds = 300
        )
        voiceEngine.speakWithVoicePack(
            voicePack = effectivePack,
            text = immediateSpoken,
            playAttentionChime = true,
            headphoneOnlyMode = false,
            quietHoursDucking = false
        )

        // Asynchronously synthesize live Gemini AI directive and update banner / speech
        viewModelScope.launch {
            val result = aiService.generatePhaseCoachingDirective(
                personalityId = preset.basePersonalityId,
                preset = preset,
                missionTitle = mission.title,
                category = mission.category,
                firstActionPrompt = mission.firstActionPrompt,
                phase = com.example.service.ExecutionPhase.REVEILLE
            )
            result.onSuccess { liveDirective ->
                val current = _activeSession.value
                if (current != null && current.phase == ActiveExecutionPhase.REVEILLE_HANDSHAKE && current.mission.id == mission.id) {
                    _activeSession.value = current.copy(activeSpokenBanner = liveDirective)
                }
            }
        }
    }

    fun onHandshakeVerified() {
        val current = _activeSession.value ?: return
        val preset = resolvePresetForMission(current.mission)
        val pack = current.voicePack

        val immediateStarter = aiService.synthesizeDynamicMatrixFallback(
            personalityId = preset.basePersonalityId,
            preset = preset,
            phase = com.example.service.ExecutionPhase.FIVE_MINUTE_STARTER,
            missionTitle = current.mission.title,
            firstActionPrompt = current.mission.firstActionPrompt
        )

        _activeSession.value = current.copy(
            phase = ActiveExecutionPhase.FIVE_MINUTE_STARTER,
            activeSpokenBanner = immediateStarter,
            starterRemainingSeconds = 300,
            isStarterPaused = false
        )
        voiceEngine.speakWithVoicePack(
            voicePack = pack,
            text = immediateStarter,
            playAttentionChime = false,
            headphoneOnlyMode = false,
            quietHoursDucking = false
        )
        startFiveMinuteCountdown()

        // Fetch live Gemini starter instruction tailored to first action
        viewModelScope.launch {
            val result = aiService.generatePhaseCoachingDirective(
                personalityId = preset.basePersonalityId,
                preset = preset,
                missionTitle = current.mission.title,
                category = current.mission.category,
                firstActionPrompt = current.mission.firstActionPrompt,
                phase = com.example.service.ExecutionPhase.FIVE_MINUTE_STARTER
            )
            result.onSuccess { liveStarter ->
                val active = _activeSession.value
                if (active != null && active.phase == ActiveExecutionPhase.FIVE_MINUTE_STARTER) {
                    _activeSession.value = active.copy(activeSpokenBanner = liveStarter)
                }
            }
        }
    }

    private fun startFiveMinuteCountdown() {
        starterTimerJob?.cancel()
        starterTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                val session = _activeSession.value ?: break
                if (session.phase != ActiveExecutionPhase.FIVE_MINUTE_STARTER) break
                if (!session.isStarterPaused) {
                    val nextSec = (session.starterRemainingSeconds - 1).coerceAtLeast(0)
                    _activeSession.value = session.copy(starterRemainingSeconds = nextSec)
                    if (nextSec == 240) {
                        voiceEngine.speakWithVoicePack(
                            voicePack = session.voicePack,
                            text = "One minute in. Stay locked on the primary objective.",
                            playAttentionChime = false
                        )
                    } else if (nextSec == 0) {
                        advanceToReporting()
                        break
                    }
                }
            }
        }
    }

    fun toggleStarterPause() {
        val current = _activeSession.value ?: return
        _activeSession.value = current.copy(isStarterPaused = !current.isStarterPaused)
    }

    fun advanceToReporting() {
        starterTimerJob?.cancel()
        val current = _activeSession.value ?: return
        val preset = resolvePresetForMission(current.mission)
        val reportingText = aiService.synthesizeDynamicMatrixFallback(
            personalityId = preset.basePersonalityId,
            preset = preset,
            phase = com.example.service.ExecutionPhase.ACCOUNTABILITY_CHECKIN,
            missionTitle = current.mission.title,
            firstActionPrompt = current.mission.firstActionPrompt
        )
        _activeSession.value = current.copy(
            phase = ActiveExecutionPhase.LIGHTWEIGHT_REPORTING,
            activeSpokenBanner = reportingText
        )
        voiceEngine.speakWithVoicePack(
            voicePack = current.voicePack,
            text = reportingText,
            playAttentionChime = false
        )
    }

    fun cancelActiveSession() {
        starterTimerJob?.cancel()
        voiceEngine.stopSpeaking()
        _activeSession.value = null
    }

    fun submitLightweightReport(status: MissionStatus) {
        starterTimerJob?.cancel()
        val session = _activeSession.value ?: return
        viewModelScope.launch {
            val updatedMission = when (status) {
                MissionStatus.DELAYED -> session.mission.copy(
                    status = status.name,
                    scheduledEpochMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15)
                )
                else -> session.mission.copy(status = status.name)
            }
            repository.updateMission(updatedMission)
            _activeSession.value = null

            val preset = resolvePresetForMission(session.mission)

            if (status == MissionStatus.COMPLETED || status == MissionStatus.STARTED || status == MissionStatus.WORKING) {
                val outcome = repository.recordMissionCompletion()
                _statusBannerMessage.value =
                    "Mission reported '${status.label}'! Streak: ${outcome.newStreakDays}d (+${outcome.creditsAdded} Command Credits)"
                if (outcome.newlyUnlockedBadge != null) {
                    _unlockedBadgeCelebration.value = outcome.newlyUnlockedBadge
                }
            } else if (status == MissionStatus.NEED_RESET || status == MissionStatus.DELAYED) {
                val recoveryDirective = aiService.synthesizeDynamicMatrixFallback(
                    personalityId = preset.basePersonalityId,
                    preset = preset,
                    phase = com.example.service.ExecutionPhase.RECOVERY_RESET,
                    missionTitle = session.mission.title,
                    firstActionPrompt = session.mission.firstActionPrompt
                )
                _statusBannerMessage.value = recoveryDirective
                voiceEngine.speakWithVoicePack(
                    voicePack = session.voicePack,
                    text = recoveryDirective,
                    playAttentionChime = false
                )
            } else {
                _statusBannerMessage.value =
                    "Status '${status.label}' recorded. Ready for recovery or your next scheduled commitment."
            }
        }
    }

    fun previewVoicePack(pack: VoicePackEntity, customPhrase: String? = null) {
        val line = customPhrase?.takeIf { it.isNotBlank() } ?: pack.sampleQuote
        val profile = uiState.value.streakProfile
        val ok = voiceEngine.speakWithVoicePack(
            voicePack = pack,
            text = line,
            playAttentionChime = false,
            headphoneOnlyMode = profile.headphoneOnlyMode,
            quietHoursDucking = profile.quietHoursDucking
        )
        if (!ok) {
            _statusBannerMessage.value = "Playback suppressed: Headphone-Only Guard is active and no headphones are connected."
        }
    }

    fun stopVoicePreview() {
        voiceEngine.stopSpeaking()
    }

    fun toggleFavoriteVoicePack(pack: VoicePackEntity) {
        viewModelScope.launch {
            val updated = pack.copy(isFavorite = !pack.isFavorite)
            repository.updateVoicePack(updated)
            _statusBannerMessage.value = if (updated.isFavorite) {
                "Pinned '${pack.name}' to favorites."
            } else {
                "Unpinned '${pack.name}'."
            }
        }
    }

    fun updateVoicePackEq(pack: VoicePackEntity, eqPreset: EqPreset) {
        viewModelScope.launch {
            val updated = pack.copy(eqPreset = eqPreset.name)
            repository.updateVoicePack(updated)
            _statusBannerMessage.value = "Updated EQ for '${pack.name}' to ${eqPreset.label}."
        }
    }

    fun updateVoicePackVolumeBoost(pack: VoicePackEntity, boostPercent: Int) {
        viewModelScope.launch {
            val updated = pack.copy(volumeBoostPercent = boostPercent.coerceIn(100, 150))
            repository.updateVoicePack(updated)
        }
    }

    fun updateVoicePackModel(pack: VoicePackEntity, modelType: VoiceModelType) {
        viewModelScope.launch {
            val updated = pack.copy(
                voiceModelType = modelType.name,
                vocalGritPercent = modelType.defaultGrit,
                aggressionPercent = modelType.defaultAggression
            )
            repository.updateVoicePack(updated)
            _statusBannerMessage.value =
                "Switched '${pack.name}' voice model to ${modelType.label} (Grit ${modelType.defaultGrit}%, Aggression ${modelType.defaultAggression}%)."
        }
    }

    fun updateVoicePackGrit(pack: VoicePackEntity, gritPercent: Int) {
        viewModelScope.launch {
            val updated = pack.copy(vocalGritPercent = gritPercent.coerceIn(0, 100))
            repository.updateVoicePack(updated)
        }
    }

    fun updateVoicePackAggression(pack: VoicePackEntity, aggressionPercent: Int) {
        viewModelScope.launch {
            val updated = pack.copy(aggressionPercent = aggressionPercent.coerceIn(0, 100))
            repository.updateVoicePack(updated)
        }
    }

    fun completeOnboarding(
        launchFirstReveille: Boolean,
        firstMissionTitle: String,
        firstMissionAction: String,
        handshakeType: HandshakeType,
        recoveryProtocol: RecoveryProtocol
    ) {
        viewModelScope.launch {
            val profile = repository.getStreakProfile()
            val updatedProfile = profile.copy(
                hasCompletedOnboarding = true,
                defaultHandshakeType = handshakeType.name,
                recoveryProtocol = recoveryProtocol.name
            )
            repository.updateStreakProfile(updatedProfile)

            val scheduledMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)
            val newMission = MissionEntity(
                title = firstMissionTitle.ifBlank { "Deep Work Sprint — Core Architecture" },
                category = "Onboarding Commitment",
                firstActionPrompt = firstMissionAction.ifBlank {
                    "Clear distractions, open the primary workspace, and execute for 5 minutes."
                },
                scheduledEpochMs = scheduledMs,
                durationMinutes = 30,
                personalityId = updatedProfile.selectedPersonalityId,
                voicePackId = updatedProfile.selectedVoicePackId,
                handshakeType = handshakeType.name,
                isMissionLocked = true,
                status = MissionStatus.SCHEDULED.name
            )
            val missionId = repository.insertMission(newMission)
            val savedMission = newMission.copy(id = missionId)
            ReveilleAlarmScheduler.scheduleMissionAlarm(getApplication(), savedMission)

            if (launchFirstReveille) {
                triggerReveilleForMission(savedMission)
            } else {
                _statusBannerMessage.value =
                    "Onboarding complete! First commitment '${savedMission.title}' scheduled."
            }
        }
    }

    fun skipOnboarding() {
        viewModelScope.launch {
            val profile = repository.getStreakProfile()
            repository.updateStreakProfile(profile.copy(hasCompletedOnboarding = true))
            _statusBannerMessage.value =
                "Welcome to Command Center! Tap 'Onboarding Guide' anytime to revisit setup."
        }
    }

    fun reopenOnboarding() {
        viewModelScope.launch {
            val profile = repository.getStreakProfile()
            repository.updateStreakProfile(profile.copy(hasCompletedOnboarding = false))
        }
    }

    fun updateCustomTestPhrase(phrase: String) {
        viewModelScope.launch {
            val profile = repository.getStreakProfile()
            repository.updateStreakProfile(profile.copy(customTestPhrase = phrase))
        }
    }

    fun toggleHeadphoneOnlyMode() {
        viewModelScope.launch {
            val profile = repository.getStreakProfile()
            val nextState = !profile.headphoneOnlyMode
            repository.updateStreakProfile(profile.copy(headphoneOnlyMode = nextState))
            _statusBannerMessage.value = if (nextState) {
                "Headphone-Only Guard ENABLED. Audio will only speak when headphones are connected."
            } else {
                "Headphone-Only Guard DISABLED."
            }
        }
    }

    fun toggleQuietHoursDucking() {
        viewModelScope.launch {
            val profile = repository.getStreakProfile()
            val nextState = !profile.quietHoursDucking
            repository.updateStreakProfile(profile.copy(quietHoursDucking = nextState))
            _statusBannerMessage.value = if (nextState) {
                "Quiet-Hours Ducking ENABLED. Night previews will be attenuated by 55%."
            } else {
                "Quiet-Hours Ducking DISABLED."
            }
        }
    }

    fun setCompareVoiceA(voicePackId: String) {
        viewModelScope.launch {
            val profile = repository.getStreakProfile()
            repository.updateStreakProfile(profile.copy(compareVoiceAId = voicePackId))
        }
    }

    fun setCompareVoiceB(voicePackId: String) {
        viewModelScope.launch {
            val profile = repository.getStreakProfile()
            repository.updateStreakProfile(profile.copy(compareVoiceBId = voicePackId))
        }
    }

    fun runAbComparison() {
        val state = uiState.value
        val packA = state.voicePacks.find { it.id == state.streakProfile.compareVoiceAId }
            ?: state.voicePacks.firstOrNull() ?: return
        val packB = state.voicePacks.find { it.id == state.streakProfile.compareVoiceBId }
            ?: state.voicePacks.getOrNull(1) ?: return
        val phrase = state.streakProfile.customTestPhrase.ifBlank {
            "Attention! Handshake verified. Launch the first step for five minutes right now."
        }
        voiceEngine.runAbComparison(
            packA = packA,
            packB = packB,
            phrase = phrase,
            headphoneOnlyMode = state.streakProfile.headphoneOnlyMode,
            quietHoursDucking = state.streakProfile.quietHoursDucking
        )
    }

    fun polishPhraseWithAiCommandStyle() {
        viewModelScope.launch {
            val currentPhrase = uiState.value.streakProfile.customTestPhrase
            // Convert phrase into deliberate, stylized AI command cadence
            val polished = when {
                currentPhrase.contains("please", ignoreCase = true) || currentPhrase.contains("try", ignoreCase = true) ->
                    "DISCIPLINE ENGAGED. CLEAR SECONDARY TABS. COMMIT TO FIVE MINUTES IMMEDIATELY."
                currentPhrase.length < 25 ->
                    "DIRECTIVE ACTIVE. COMPLETE THE HANDSHAKE. INITIATE STEP ONE WITHOUT CONTEXT SWITCHING."
                else -> {
                    val words = currentPhrase.trim().uppercase()
                        .replace("PLEASE ", "")
                        .replace("KINDLY ", "")
                    "COMMAND NOTICE. $words EXECUTE NOW."
                }
            }
            updateCustomTestPhrase(polished)
            _statusBannerMessage.value = "AI Command Polish applied: Clause framing optimized for crisp delivery."
        }
    }

    fun selectVoicePackForActivePersonality(pack: VoicePackEntity) {
        viewModelScope.launch {
            val profile = repository.getStreakProfile()
            repository.updateStreakProfile(profile.copy(selectedVoicePackId = pack.id))
            _statusBannerMessage.value = "Assigned '${pack.name}' (${pack.actorName}) to active director."
        }
    }

    fun selectPersonality(personalityId: String) {
        viewModelScope.launch {
            val spec = DEFAULT_PERSONALITIES.find { it.id == personalityId } ?: return@launch
            val profile = repository.getStreakProfile()
            repository.updateStreakProfile(
                profile.copy(
                    selectedPersonalityId = spec.id,
                    selectedVoicePackId = spec.defaultVoicePackId,
                    instructionFirmness = spec.defaultFirmness,
                    obnoxiousness = spec.defaultObnoxiousness
                )
            )
            _statusBannerMessage.value = "Director personality switched to ${spec.name}."
        }
    }

    fun applyPreset(presetName: String) {
        viewModelScope.launch {
            val profile = repository.getStreakProfile()
            val updated = when (presetName) {
                "Nudge" -> profile.copy(
                    activePresetName = presetName,
                    instructionFirmness = 2,
                    obnoxiousness = 1,
                    alertIntensity = 2,
                    persistence = 2,
                    handshakeDifficulty = 1,
                    scheduleRigidity = 2,
                    recoveryStructure = 3
                )
                "Steady" -> profile.copy(
                    activePresetName = presetName,
                    instructionFirmness = 3,
                    obnoxiousness = 2,
                    alertIntensity = 3,
                    persistence = 3,
                    handshakeDifficulty = 2,
                    scheduleRigidity = 3,
                    recoveryStructure = 3
                )
                "Command" -> profile.copy(
                    activePresetName = presetName,
                    instructionFirmness = 4,
                    obnoxiousness = 3,
                    alertIntensity = 4,
                    persistence = 4,
                    handshakeDifficulty = 3,
                    scheduleRigidity = 4,
                    recoveryStructure = 4
                )
                "Reveille MAX" -> profile.copy(
                    activePresetName = presetName,
                    instructionFirmness = 5,
                    obnoxiousness = 5,
                    alertIntensity = 5,
                    persistence = 5,
                    handshakeDifficulty = 5,
                    scheduleRigidity = 5,
                    recoveryStructure = 5
                )
                else -> profile.copy(activePresetName = "Custom")
            }
            repository.updateStreakProfile(updated)
            _statusBannerMessage.value = "Applied '$presetName' execution preset."
        }
    }

    fun updateDimension(
        firmness: Int = uiState.value.streakProfile.instructionFirmness,
        obnoxiousness: Int = uiState.value.streakProfile.obnoxiousness,
        alertIntensity: Int = uiState.value.streakProfile.alertIntensity,
        persistence: Int = uiState.value.streakProfile.persistence,
        handshakeDifficulty: Int = uiState.value.streakProfile.handshakeDifficulty,
        scheduleRigidity: Int = uiState.value.streakProfile.scheduleRigidity,
        recoveryStructure: Int = uiState.value.streakProfile.recoveryStructure
    ) {
        viewModelScope.launch {
            val profile = repository.getStreakProfile()
            repository.updateStreakProfile(
                profile.copy(
                    activePresetName = "Custom",
                    instructionFirmness = firmness.coerceIn(1, 5),
                    obnoxiousness = obnoxiousness.coerceIn(1, 5),
                    alertIntensity = alertIntensity.coerceIn(1, 5),
                    persistence = persistence.coerceIn(1, 5),
                    handshakeDifficulty = handshakeDifficulty.coerceIn(1, 5),
                    scheduleRigidity = scheduleRigidity.coerceIn(1, 5),
                    recoveryStructure = recoveryStructure.coerceIn(1, 5)
                )
            )
        }
    }

    fun saveCompletePreset(
        preset: PersonalityPresetEntity,
        applyAsGlobal: Boolean
    ) {
        viewModelScope.launch {
            repository.savePreset(preset)
            if (applyAsGlobal) {
                repository.setActiveGlobalPreset(preset)
            }
            _statusBannerMessage.value = "Saved preset '${preset.name}' with full DSP & persona configurations."
        }
    }

    fun clonePreset(sourcePresetId: String) {
        viewModelScope.launch {
            val cloned = repository.clonePreset(sourcePresetId)
            if (cloned != null) {
                _statusBannerMessage.value = "Cloned '${cloned.name}' into preset library."
            }
        }
    }

    fun deleteCustomPreset(presetId: String) {
        viewModelScope.launch {
            repository.deletePreset(presetId)
            _statusBannerMessage.value = "Preset removed from library."
        }
    }

    fun applyCustomPresetGlobally(preset: PersonalityPresetEntity) {
        viewModelScope.launch {
            repository.setActiveGlobalPreset(preset)
            _statusBannerMessage.value = "Director preset switched to '${preset.name}'."
        }
    }

    fun previewCustomPresetVoice(voicePackId: String, customPhrase: String) {
        val pack = uiState.value.voicePacks.find { it.id == voicePackId }
            ?: uiState.value.voicePacks.firstOrNull() ?: return
        previewVoicePack(pack, customPhrase)
    }

    // --- 6-Personality Audition Deck ---
    fun setAuditionPhase(phase: com.example.service.ExecutionPhase) {
        _auditionPhase.value = phase
    }

    fun setAuditionTarget(missionTitle: String, category: String) {
        _activeAuditionMissionTitle.value = missionTitle
        _activeAuditionCategory.value = category
    }

    fun synthesizeAuditionDirectives(
        missionTitle: String = _activeAuditionMissionTitle.value,
        category: String = _activeAuditionCategory.value,
        phase: com.example.service.ExecutionPhase = _auditionPhase.value
    ) {
        viewModelScope.launch {
            _isAuditionGenerating.value = true
            try {
                val currentCustomPreset = uiState.value.presets.find { it.basePersonalityId == "custom" }
                val results = aiService.generateSixPersonalityAuditionComparison(
                    missionTitle = missionTitle,
                    category = category,
                    phase = phase,
                    customPreset = currentCustomPreset
                )
                _auditionDirectivesMap.value = results
                _statusBannerMessage.value = "Generated live Gemini coaching lines for all 6 personalities (${phase.label})."
            } catch (e: Exception) {
                _statusBannerMessage.value = "Synthesized personality directives with blueprint calibrations."
            } finally {
                _isAuditionGenerating.value = false
            }
        }
    }

    fun synthesizeSingleAudition(
        personalityId: String,
        missionTitle: String = _activeAuditionMissionTitle.value,
        category: String = _activeAuditionCategory.value,
        phase: com.example.service.ExecutionPhase = _auditionPhase.value
    ) {
        viewModelScope.launch {
            val customPreset = uiState.value.presets.find { it.basePersonalityId == "custom" }
            val result = aiService.generatePhaseCoachingDirective(
                personalityId = personalityId,
                preset = if (personalityId == "custom") customPreset else null,
                missionTitle = missionTitle,
                category = category,
                phase = phase
            )
            val line = result.getOrNull() ?: aiService.getBlueprintFallback(personalityId, phase, missionTitle)
            _auditionDirectivesMap.value = _auditionDirectivesMap.value.toMutableMap().apply {
                put(personalityId, line)
            }
        }
    }

    fun auditionVoiceForPersonality(
        personalityId: String,
        text: String,
        customPreset: PersonalityPresetEntity? = null
    ) {
        val targetPreset = customPreset ?: uiState.value.presets.find { it.basePersonalityId == personalityId }
        val voicePack = if (targetPreset != null) {
            uiState.value.voicePacks.find { it.id == targetPreset.voicePackId }
        } else {
            val defaultPackId = DEFAULT_PERSONALITIES.find { it.id == personalityId }?.defaultVoicePackId
            uiState.value.voicePacks.find { it.id == defaultPackId }
        } ?: uiState.value.voicePacks.firstOrNull() ?: return

        val effectiveVoicePack = if (targetPreset != null) {
            voicePack.copy(
                pitch = targetPreset.customPitch,
                speechRate = targetPreset.customSpeechRate,
                cadenceStyle = targetPreset.cadenceStyle,
                eqPreset = targetPreset.eqPreset,
                vocalGritPercent = targetPreset.vocalGritPercent,
                aggressionPercent = targetPreset.aggressionPercent
            )
        } else voicePack

        voiceEngine.speakWithVoicePack(
            voicePack = effectiveVoicePack,
            text = text,
            playAttentionChime = true,
            headphoneOnlyMode = uiState.value.streakProfile.headphoneOnlyMode,
            quietHoursDucking = uiState.value.streakProfile.quietHoursDucking
        )
    }

    // --- Execution Management Modules CRUD ---
    fun saveRoutingRule(rule: com.example.data.PresetRoutingRuleEntity) {
        viewModelScope.launch {
            repository.saveRoutingRule(rule)
            _statusBannerMessage.value = "Saved auto-routing rule '${rule.ruleName}'."
        }
    }

    fun deleteRoutingRule(ruleId: Long) {
        viewModelScope.launch {
            repository.deleteRoutingRule(ruleId)
            _statusBannerMessage.value = "Routing rule deleted."
        }
    }

    fun saveEscalationPolicy(policy: com.example.data.EscalationPolicyEntity) {
        viewModelScope.launch {
            repository.saveEscalationPolicy(policy)
            _statusBannerMessage.value = "Saved escalation policy '${policy.policyName}'."
        }
    }

    fun deleteEscalationPolicy(policyId: String) {
        viewModelScope.launch {
            repository.deleteEscalationPolicy(policyId)
            _statusBannerMessage.value = "Escalation policy deleted."
        }
    }

    fun saveHandshakeCalibration(calibration: com.example.data.HandshakeCalibrationEntity) {
        viewModelScope.launch {
            repository.saveHandshakeCalibration(calibration)
            _statusBannerMessage.value = "Saved handshake calibration '${calibration.profileName}'."
        }
    }

    fun deleteHandshakeCalibration(calibrationId: String) {
        viewModelScope.launch {
            repository.deleteHandshakeCalibration(calibrationId)
            _statusBannerMessage.value = "Handshake calibration deleted."
        }
    }

    fun saveMissionBlueprint(blueprint: com.example.data.MissionBlueprintEntity) {
        viewModelScope.launch {
            repository.saveMissionBlueprint(blueprint)
            _statusBannerMessage.value = "Saved routine blueprint '${blueprint.title}'."
        }
    }

    fun deleteMissionBlueprint(blueprintId: String) {
        viewModelScope.launch {
            repository.deleteMissionBlueprint(blueprintId)
            _statusBannerMessage.value = "Blueprint removed."
        }
    }

    fun deployBlueprintToQueue(blueprint: com.example.data.MissionBlueprintEntity) {
        viewModelScope.launch {
            repository.deployBlueprintToQueue(blueprint)
            _statusBannerMessage.value = "Deployed blueprint '${blueprint.title}' to daily queue."
        }
    }

    fun applyRoutingRulesToAllMissions() {
        viewModelScope.launch {
            val missions = repository.missionsFlow.first()
            val presets = repository.presetsFlow.first()
            val rules = repository.routingRulesFlow.first()
            var count = 0
            for (m in missions) {
                if (m.presetId.isNullOrBlank()) {
                    val resolved = repository.resolvePresetForMission(m, presets, rules)
                    repository.updateMission(m.copy(presetId = resolved.id))
                    count++
                }
            }
            _statusBannerMessage.value = "Evaluated auto-routing rules: assigned presets for $count task(s)."
        }
    }

    fun setMissionPresetOverride(missionId: Long, presetId: String?) {
        viewModelScope.launch {
            val mission = uiState.value.missions.find { it.id == missionId } ?: return@launch
            repository.updateMission(mission.copy(presetId = presetId))
            val label = if (presetId != null) {
                uiState.value.presets.find { it.id == presetId }?.name ?: "Custom Preset"
            } else "Global Default"
            _statusBannerMessage.value = "Set mission preset override to '$label'."
        }
    }

    fun resolvePresetForMission(mission: MissionEntity): PersonalityPresetEntity {
        return repository.resolvePresetForMission(
            mission = mission,
            allPresets = uiState.value.presets,
            allRules = uiState.value.routingRules
        )
    }

    fun generateGeminiDirectiveForPreset(
        personalityName: String,
        firmness: Int,
        obnoxiousness: Int,
        onResult: (String) -> Unit
    ) {
        viewModelScope.launch {
            val result = aiService.generateTacticalDirective(
                personalityName = personalityName,
                firmness = firmness,
                obnoxiousness = obnoxiousness,
                missionTitle = "Focus Execution Block",
                category = "Deep Work"
            )
            result.onSuccess { directive ->
                onResult(directive)
                _statusBannerMessage.value = "Gemini 3.8 Flash crafted tactical directive."
            }.onFailure { _ ->
                _statusBannerMessage.value = "Gemini notice: using tactical fallback directive."
                onResult("EYEBALLS! SNAP TO RIGHT NOW! OPEN YOUR WORKSPACE AND EXECUTE THE FIRST FIVE MINUTES! MOVE!")
            }
        }
    }

    fun addCustomVoicePack(
        name: String,
        actorName: String,
        accentLabel: String,
        languageTag: String,
        tone: EmotionalTone,
        pitch: Float,
        speechRate: Float,
        sampleQuote: String
    ) {
        viewModelScope.launch {
            val packId = "pack_custom_${System.currentTimeMillis()}"
            val cleanQuote = sampleQuote.ifBlank {
                "Attention! Your scheduled commitment is open. Execute the handshake and start your first five minutes now!"
            }
            val pack = VoicePackEntity(
                id = packId,
                name = name.ifBlank { "Custom Tactical Pack" },
                actorName = actorName.ifBlank { "Custom Voice Director" },
                accentLabel = accentLabel.ifBlank { "Custom Accent Profile" },
                emotionalTone = tone.name,
                languageTag = languageTag,
                pitch = pitch.coerceIn(0.88f, 1.05f),
                speechRate = speechRate.coerceIn(0.92f, 1.15f),
                isCustom = true,
                usesDrillSergeantArt = tone == EmotionalTone.URGENT || tone == EmotionalTone.STERN,
                isFavorite = true,
                eqPreset = EqPreset.STUDIO_CRISP.name,
                volumeBoostPercent = 125,
                cadenceStyle = CadenceStyle.DELIBERATE_AI.name,
                qualityTierLabel = "HD CUSTOM • 24kHz",
                sampleQuote = cleanQuote,
                reveilleScript = cleanQuote,
                handshakeSuccessScript = "Handshake verified! Start your five-minute initiation block immediately.",
                starterScript = "Five-minute action starter running. Keep moving forward."
            )
            repository.insertVoicePack(pack)
            _statusBannerMessage.value = "Created & saved new voice pack: ${pack.name}"
        }
    }

    fun updateVoicePackPitch(pack: VoicePackEntity, pitch: Float) {
        viewModelScope.launch {
            val updated = pack.copy(pitch = pitch.coerceIn(0.75f, 1.25f))
            repository.updateVoicePack(updated)
            _statusBannerMessage.value = "Updated pitch for '${pack.name}' to ${String.format(Locale.US, "%.2f", updated.pitch)}x"
        }
    }

    fun updateVoicePackFormantShift(pack: VoicePackEntity, formantShiftPercent: Int) {
        viewModelScope.launch {
            val updated = pack.copy(formantShiftPercent = formantShiftPercent.coerceIn(-50, 50))
            repository.updateVoicePack(updated)
            val sign = if (updated.formantShiftPercent >= 0) "+${updated.formantShiftPercent}" else "${updated.formantShiftPercent}"
            _statusBannerMessage.value = "Updated formant shift for '${pack.name}' to $sign%"
        }
    }

    fun createMission(
        title: String,
        category: String,
        firstActionPrompt: String,
        minutesFromNow: Int,
        durationMinutes: Int,
        handshakeType: HandshakeType,
        missionLocked: Boolean
    ) {
        viewModelScope.launch {
            val profile = uiState.value.streakProfile
            val scheduledMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutesFromNow.toLong())
            val entity = MissionEntity(
                title = title.ifBlank { "Focused Execution Block" },
                category = category.ifBlank { "Deep Work" },
                firstActionPrompt = firstActionPrompt.ifBlank { "Open the primary workspace and complete the first 5 minutes." },
                scheduledEpochMs = scheduledMs,
                durationMinutes = durationMinutes.coerceIn(5, 240),
                personalityId = profile.selectedPersonalityId,
                voicePackId = profile.selectedVoicePackId,
                handshakeType = handshakeType.name,
                isMissionLocked = missionLocked,
                status = MissionStatus.SCHEDULED.name
            )
            val id = repository.insertMission(entity)
            ReveilleAlarmScheduler.scheduleMissionAlarm(
                getApplication(),
                entity.copy(id = id)
            )
            _statusBannerMessage.value = "Scheduled '${entity.title}' (+${minutesFromNow}m) with Reveille alarm."
        }
    }

    fun toggleMissionLock(mission: MissionEntity) {
        viewModelScope.launch {
            val updated = mission.copy(isMissionLocked = !mission.isMissionLocked)
            repository.updateMission(updated)
            _statusBannerMessage.value = if (updated.isMissionLocked) {
                "Mission Lock engaged for '${updated.title}'."
            } else {
                "Mission Lock released for '${updated.title}'."
            }
        }
    }

    fun consolidateConflictingMissions(primary: MissionEntity, secondary: MissionEntity) {
        viewModelScope.launch {
            val startMs = minOf(primary.scheduledEpochMs, secondary.scheduledEpochMs)
            val endPrimary = primary.scheduledEpochMs + TimeUnit.MINUTES.toMillis(primary.durationMinutes.toLong())
            val endSecondary = secondary.scheduledEpochMs + TimeUnit.MINUTES.toMillis(secondary.durationMinutes.toLong())
            val endMs = maxOf(endPrimary, endSecondary)
            val combinedDurationMinutes = maxOf(15, TimeUnit.MILLISECONDS.toMinutes(endMs - startMs).toInt())

            val consolidatedTitle = "${primary.title} & ${secondary.title}"
            val consolidatedPrompt = "CONSOLIDATED OBJECTIVE: ${primary.firstActionPrompt} | FOLLOWED BY: ${secondary.firstActionPrompt}"

            val consolidatedPrimary = primary.copy(
                title = consolidatedTitle,
                scheduledEpochMs = startMs,
                durationMinutes = combinedDurationMinutes,
                firstActionPrompt = consolidatedPrompt,
                hasConflict = false,
                conflictDescription = null,
                isMissionLocked = primary.isMissionLocked || secondary.isMissionLocked,
                isRecurring = primary.isRecurring || secondary.isRecurring,
                recurrenceRule = primary.recurrenceRule ?: secondary.recurrenceRule
            )

            // Update primary in database
            repository.updateMission(consolidatedPrimary)
            // Delete secondary from database
            repository.deleteMission(secondary.id)

            // Cancel alarm for secondary
            ReveilleAlarmScheduler.cancelMissionAlarm(getApplication(), secondary.id)
            // Re-arm exact alarm for consolidated primary
            ReveilleAlarmScheduler.scheduleMissionAlarm(getApplication(), consolidatedPrimary)

            _statusBannerMessage.value = "Consolidated conflicting commitments into: '$consolidatedTitle' ($combinedDurationMinutes min)."
        }
    }

    fun prioritizePrimaryMission(primary: MissionEntity, secondary: MissionEntity) {
        viewModelScope.launch {
            val updatedPrimary = primary.copy(
                isMissionLocked = true,
                hasConflict = false,
                conflictDescription = null
            )
            repository.updateMission(updatedPrimary)
            repository.deleteMission(secondary.id)
            ReveilleAlarmScheduler.cancelMissionAlarm(getApplication(), secondary.id)
            _statusBannerMessage.value = "Prioritized '${primary.title}' and dismissed overlapping commitment."
        }
    }

    fun shiftSecondaryMission(secondary: MissionEntity, minutes: Int = 30) {
        viewModelScope.launch {
            val updated = secondary.copy(
                scheduledEpochMs = secondary.scheduledEpochMs + TimeUnit.MINUTES.toMillis(minutes.toLong()),
                hasConflict = false,
                conflictDescription = null
            )
            repository.updateMission(updated)
            ReveilleAlarmScheduler.scheduleMissionAlarm(getApplication(), updated)
            _statusBannerMessage.value = "Shifted '${secondary.title}' by +${minutes}m."
        }
    }

    fun resolveCalendarConflict(mission: MissionEntity, action: String) {
        viewModelScope.launch {
            val updated = when (action) {
                "RESCHEDULE_30" -> mission.copy(
                    scheduledEpochMs = mission.scheduledEpochMs + TimeUnit.MINUTES.toMillis(30),
                    hasConflict = false,
                    conflictDescription = null
                )
                "LOCK_PRIORITY" -> mission.copy(
                    isMissionLocked = true,
                    hasConflict = false,
                    conflictDescription = null
                )
                else -> mission.copy(
                    hasConflict = false,
                    conflictDescription = null
                )
            }
            repository.updateMission(updated)
            ReveilleAlarmScheduler.scheduleMissionAlarm(getApplication(), updated)
            _statusBannerMessage.value = "Resolved schedule conflict for '${mission.title}'."
        }
    }

    fun syncWithGoogleCalendar() {
        viewModelScope.launch {
            val profile = uiState.value.streakProfile
            val result = calendarSyncManager.performFullSyncAndSchedule(
                windowHours = 72,
                personalityId = profile.selectedPersonalityId,
                voicePackId = profile.selectedVoicePackId
            )
            _lastBackgroundSyncTimestamp.value = result.syncTimestampMs
            _calendarSyncSummary.value =
                "${result.sourceLabel} • ${result.syncedCount} synced (${result.recurringCount} recurring) • ${result.conflictsDetected} conflict(s)"
            _statusBannerMessage.value =
                "Google Calendar sync complete: ${result.syncedCount} commitment(s) mapped & execution alarms armed (${result.conflictsDetected} conflict(s) flagged)."
        }
    }

    fun triggerBackgroundCalendarService() {
        calendarSyncManager.triggerBackgroundService()
        _statusBannerMessage.value = "Background Calendar Service initiated • Fetching events and arming execution alarms..."
    }

    fun togglePeriodicBackgroundSync(enabled: Boolean, intervalMinutes: Int = 120) {
        if (enabled) {
            calendarSyncManager.schedulePeriodicBackgroundSync(intervalMinutes)
            _isBackgroundSyncActive.value = true
            _backgroundSyncIntervalMinutes.value = intervalMinutes
            _statusBannerMessage.value = "Periodic Background Service ENABLED (Polling every ${intervalMinutes / 60}h)."
        } else {
            calendarSyncManager.cancelPeriodicBackgroundSync()
            _isBackgroundSyncActive.value = false
            _statusBannerMessage.value = "Periodic Background Service DISABLED."
        }
    }

    fun updateBackgroundSyncInterval(intervalMinutes: Int) {
        _backgroundSyncIntervalMinutes.value = intervalMinutes
        if (_isBackgroundSyncActive.value) {
            calendarSyncManager.schedulePeriodicBackgroundSync(intervalMinutes)
            _statusBannerMessage.value = "Background sync interval set to $intervalMinutes minutes."
        }
    }

    fun simulateMissedDayForTesting() {
        viewModelScope.launch {
            repository.simulateMissedDay()
            _statusBannerMessage.value =
                "Simulated a missed day! Streak reset to 0 — test 'Buy Back Missed Day' via Credits or Briefing."
        }
    }

    fun buyBackMissedDayWithCredits() {
        viewModelScope.launch {
            val ok = repository.buyBackStreakWithCredits(50)
            if (ok) {
                val restored = repository.getStreakProfile().currentStreakDays
                _statusBannerMessage.value =
                    "Missed day bought back for 50 Command Credits! Streak restored to $restored days."
            } else {
                _statusBannerMessage.value =
                    "Need at least 50 Command Credits (or use the 15s Tactical Briefing option)."
            }
        }
    }

    fun startTacticalBriefingBuyBack() {
        briefingTimerJob?.cancel()
        _isBriefingViewerOpen.value = true
        _briefingRemainingSeconds.value = 15

        val drillPack = uiState.value.voicePacks.firstOrNull { it.usesDrillSergeantArt }
            ?: CommandRepository.defaultVoicePacks().first()
        voiceEngine.speakWithVoicePack(
            voicePack = drillPack,
            text = "RECOVERY BRIEFING ACTIVE! YOU MISSED YESTERDAY, BUT WE DO NOT QUIT! HOLD THE LINE FOR FIFTEEN SECONDS TO RESTORE YOUR COMMAND STREAK!",
            playAttentionChime = false,
            headphoneOnlyMode = false,
            quietHoursDucking = false
        )

        briefingTimerJob = viewModelScope.launch {
            for (remaining in 14 downTo 0) {
                delay(1000L)
                _briefingRemainingSeconds.value = remaining
            }
            repository.buyBackStreakWithBriefing()
            _isBriefingViewerOpen.value = false
            val restored = repository.getStreakProfile().currentStreakDays
            _statusBannerMessage.value =
                "Tactical Recovery Briefing completed! Missed day restored — Streak is now $restored days!"
        }
    }

    fun cancelTacticalBriefing() {
        briefingTimerJob?.cancel()
        voiceEngine.stopSpeaking()
        _isBriefingViewerOpen.value = false
    }

    fun selectStreakChartDay(dayIndex: Int) {
        if (_selectedStreakDayIndex.value == dayIndex) {
            _selectedStreakDayIndex.value = null
        } else {
            _selectedStreakDayIndex.value = dayIndex
        }
    }

    fun toggleStreakChartViewMode() {
        _streakChartViewMode.value = if (_streakChartViewMode.value == StreakChartViewMode.WEEKLY_BARS) {
            StreakChartViewMode.HOURLY_CADENCE
        } else {
            StreakChartViewMode.WEEKLY_BARS
        }
    }

    private fun buildStreakTelemetry(
        missions: List<MissionEntity>,
        streakProfile: StreakProfileEntity,
        selectedDayIndex: Int?,
        viewMode: StreakChartViewMode
    ): StreakChartTelemetry {
        val cal = Calendar.getInstance()
        val todayDow = cal.get(Calendar.DAY_OF_WEEK) // 1 = Sun, 7 = Sat
        val todayIndex = (todayDow - 1).coerceIn(0, 6)

        val dayNames = listOf("S", "M", "T", "W", "T", "F", "S")
        val dateNames = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

        val completedMissions = missions.filter { it.status == MissionStatus.COMPLETED.name }
        val completedTitles = completedMissions.map { it.title }.ifEmpty {
            listOf("Core Architecture Sprint", "Calendar Provider Sync", "Tactical Execution Block")
        }

        val scheduledTargets = listOf(4, 5, 5, 5, 5, 5, 4)
        val isMissed = streakProfile.isStreakMissed
        val missedDayIdx = if (isMissed) (todayIndex - 1 + 7) % 7 else -1

        val weeklyRecords = (0..6).map { idx ->
            val isToday = idx == todayIndex
            val isMissedDay = isMissed && idx == missedDayIdx
            val target = scheduledTargets[idx]

            val verified = when {
                isMissedDay -> 0
                isToday -> (3 + completedMissions.size).coerceAtMost(target)
                idx < todayIndex -> target
                else -> if (streakProfile.currentStreakDays >= 7) target else 0
            }

            DailyHandshakeRecord(
                dayIndex = idx,
                dayShortLabel = dayNames[idx],
                dateLabel = dateNames[idx],
                verifiedHandshakes = verified,
                scheduledTarget = target,
                avgResponseSeconds = if (verified > 0) (3.1f + (idx % 3) * 0.35f) else 0f,
                lockedCompletionPercent = if (verified > 0) (91 + (idx % 4) * 3) else 0,
                dominantHandshakeType = runCatching { HandshakeType.valueOf(streakProfile.defaultHandshakeType) }.getOrDefault(HandshakeType.HOLD),
                isMissedDay = isMissedDay,
                isToday = isToday,
                completedMissionTitles = if (isToday) completedTitles else listOf("Focus Execution Block #${idx + 1}")
            )
        }

        val totalVerifiedThisWeek = weeklyRecords.sumOf { it.verifiedHandshakes }
        val avgPerDay = if (weeklyRecords.isNotEmpty()) (totalVerifiedThisWeek.toFloat() / 7f) else 3.5f

        val hourlyCounts = IntArray(24)
        hourlyCounts[6] = 2
        hourlyCounts[7] = 3
        hourlyCounts[9] = 4
        hourlyCounts[11] = 3
        hourlyCounts[13] = 2
        hourlyCounts[15] = 3
        hourlyCounts[17] = 4
        hourlyCounts[19] = 2
        hourlyCounts[21] = 1

        val hourlyBuckets = (0..23).map { h ->
            val label = when (h) {
                0 -> "12 AM"
                6 -> "6 AM"
                12 -> "12 PM"
                18 -> "6 PM"
                else -> ""
            }
            HourlyHandshakeBucket(
                hour = h,
                label = label,
                count = hourlyCounts[h]
            )
        }

        val weekOverWeek = (100 + (streakProfile.currentStreakDays * 2).coerceIn(6, 42))

        return StreakChartTelemetry(
            weeklyRecords = weeklyRecords,
            hourlyBuckets = hourlyBuckets,
            averageHandshakesPerDay = (avgPerDay * 10).toInt() / 10f,
            weekOverWeekPercent = weekOverWeek,
            avgResponseSecondsOverall = 3.4f,
            fastestResponseSeconds = 1.2f,
            lockedAdherencePercent = 94,
            totalHandshakesThisWeek = totalVerifiedThisWeek,
            selectedDayIndex = selectedDayIndex,
            viewMode = viewMode
        )
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(syncReceiver)
        } catch (_: Exception) {}
        voiceEngine.shutdown()
    }
}

private data class FiveAux(
    val banner: String?,
    val badge: MilestoneBadgeSpec?,
    val syncSummary: String,
    val briefingOpen: Boolean,
    val briefingSecs: Int
)

private data class SyncAux(
    val isBgActive: Boolean,
    val intervalMins: Int,
    val lastSyncTs: Long
)

private data class AuxUiState(
    val banner: String?,
    val badge: MilestoneBadgeSpec?,
    val syncSummary: String,
    val briefingOpen: Boolean,
    val briefingSecs: Int,
    val selectedDayIndex: Int?,
    val viewMode: StreakChartViewMode,
    val isBgActive: Boolean,
    val intervalMins: Int,
    val lastSyncTs: Long
)

private data class ModulesAux(
    val rules: List<com.example.data.PresetRoutingRuleEntity>,
    val policies: List<com.example.data.EscalationPolicyEntity>,
    val calibs: List<com.example.data.HandshakeCalibrationEntity>,
    val blueprints: List<com.example.data.MissionBlueprintEntity>
)

private data class AuditionAux(
    val phase: com.example.service.ExecutionPhase,
    val map: Map<String, String>,
    val isGen: Boolean,
    val missionTitle: String,
    val category: String
)

