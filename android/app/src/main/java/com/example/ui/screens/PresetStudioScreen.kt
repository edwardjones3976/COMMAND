package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.CadenceStyle
import com.example.data.DEFAULT_PERSONALITIES
import com.example.data.EqPreset
import com.example.data.EscalationPolicyEntity
import com.example.data.HandshakeCalibrationEntity
import com.example.data.HandshakeType
import com.example.data.MissionBlueprintEntity
import com.example.data.PersonalityPresetEntity
import com.example.data.PersonalitySpec
import com.example.data.PresetRoutingRuleEntity
import com.example.data.VoicePackEntity
import com.example.data.VolumeRampCurve
import com.example.service.ExecutionPhase
import com.example.service.VoicePlaybackState
import com.example.ui.theme.CommandAmber
import com.example.ui.theme.CommandCrimson
import com.example.ui.theme.MissionEmerald
import com.example.ui.theme.SteelTextSecondary
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceElevated
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun PresetStudioScreen(
    presets: List<PersonalityPresetEntity>,
    voicePacks: List<VoicePackEntity>,
    playbackState: VoicePlaybackState,
    routingRules: List<PresetRoutingRuleEntity> = emptyList(),
    escalationPolicies: List<EscalationPolicyEntity> = emptyList(),
    handshakeCalibrations: List<HandshakeCalibrationEntity> = emptyList(),
    blueprints: List<MissionBlueprintEntity> = emptyList(),
    auditionPhase: ExecutionPhase = ExecutionPhase.REVEILLE,
    auditionDirectivesMap: Map<String, String> = emptyMap(),
    isAuditionGenerating: Boolean = false,
    activeAuditionMissionTitle: String = "Career Work — Deep Architecture Sprint",
    activeAuditionCategory: String = "Career Work",
    onSaveCompletePreset: (PersonalityPresetEntity, Boolean) -> Unit = { _, _ -> },
    onSavePreset: (
        name: String,
        description: String,
        basePersonalityId: String,
        voicePackId: String,
        firmness: Int,
        obnoxiousness: Int,
        alertIntensity: Int,
        persistence: Int,
        handshakeDifficulty: Int,
        preferredHandshake: String,
        sampleDirective: String,
        assignedCategories: String,
        applyAsGlobal: Boolean
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    onClonePreset: (String) -> Unit = {},
    onDeletePreset: (String) -> Unit = {},
    onApplyPresetGlobally: (PersonalityPresetEntity) -> Unit = {},
    onPreviewVoice: (voicePackId: String, customPhrase: String) -> Unit = { _, _ -> },
    onStopVoice: () -> Unit = {},
    onGenerateAiDirective: (
        personalityName: String,
        firmness: Int,
        obnoxiousness: Int,
        onResult: (String) -> Unit
    ) -> Unit = { _, _, _, _ -> },
    onSetAuditionPhase: (ExecutionPhase) -> Unit = {},
    onSetAuditionTarget: (String, String) -> Unit = { _, _ -> },
    onSynthesizeAuditionDirectives: (String, String, ExecutionPhase) -> Unit = { _, _, _ -> },
    onSynthesizeSingleAudition: (String, String, String, ExecutionPhase) -> Unit = { _, _, _, _ -> },
    onAuditionVoiceForPersonality: (String, String, PersonalityPresetEntity?) -> Unit = { _, _, _ -> },
    onSaveRoutingRule: (PresetRoutingRuleEntity) -> Unit = {},
    onDeleteRoutingRule: (Long) -> Unit = {},
    onSaveEscalationPolicy: (EscalationPolicyEntity) -> Unit = {},
    onDeleteEscalationPolicy: (String) -> Unit = {},
    onSaveHandshakeCalibration: (HandshakeCalibrationEntity) -> Unit = {},
    onDeleteHandshakeCalibration: (String) -> Unit = {},
    onSaveMissionBlueprint: (MissionBlueprintEntity) -> Unit = {},
    onDeleteMissionBlueprint: (String) -> Unit = {},
    onDeployBlueprintToQueue: (MissionBlueprintEntity) -> Unit = {},
    onApplyRoutingRulesToAllMissions: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedStudioTab by remember { mutableIntStateOf(0) } // 0: 6-Personality Tuning Studio, 1: Audition Deck, 2: Preset Library, 3: Modules Hub

    // Tuning Studio Working State
    var editingPresetId by remember { mutableStateOf<String?>(null) }
    var presetName by remember { mutableStateOf("Custom Focus") }
    var presetDescription by remember { mutableStateOf("Fine-tuned coaching director engineered for peak initiation momentum.") }
    var selectedArchetypeId by remember { mutableStateOf("drill_sergeant") }
    var selectedVoicePackId by remember { mutableStateOf(voicePacks.firstOrNull()?.id ?: "pack_major_stone") }

    // 5-Axis Intensity Sliders (1..5)
    var firmness by remember { mutableIntStateOf(5) }
    var obnoxiousness by remember { mutableIntStateOf(5) }
    var alertIntensity by remember { mutableIntStateOf(5) }
    var persistence by remember { mutableIntStateOf(4) }
    var handshakeDifficulty by remember { mutableIntStateOf(3) }

    // Handshake & Structure
    var preferredHandshake by remember { mutableStateOf(HandshakeType.HOLD.name) }
    var chainedHandshake by remember { mutableStateOf("NONE") }
    var assignedCategory by remember { mutableStateOf("Career Work") }
    var applyAsGlobal by remember { mutableStateOf(false) }

    // AI Custom Persona Wording Controls
    var customPersonaPrompt by remember { mutableStateOf("Aggressive accountability director for deep engineering sessions.") }
    var vocabularyRules by remember { mutableStateOf("Emphasize action words like 'Ship', 'Execute', 'Unblock'. Zero passive verbs.") }
    var catchphrasesCsv by remember { mutableStateOf("Eyeballs!, Snap to!, Fast over perfect!, Momentum wins!") }

    // Phase-specific script templates
    var reveilleTemplate by remember { mutableStateOf("EYEBALLS! SNAP TO RIGHT NOW! PUT THE PHONE DOWN AND OPEN YOUR PRIMARY WORKSPACE!") }
    var starterTemplate by remember { mutableStateOf("HANDSHAKE LOCKED! GIVE ME EXACTLY FIVE MINUTES OF NON-STOP FOCUS! MOVE!") }
    var accountabilityTemplate by remember { mutableStateOf("HALFWAY POINT! ARE YOU EXECUTING OR ARE YOU BROWSING? REPORT STATUS!") }
    var recoveryTemplate by remember { mutableStateOf("OBSTACLE NOTED! CLEAR YOUR DESK FOR TWO MINUTES AND DOWNSCALE THE FIRST STEP!") }

    // Vocal Delivery & DSP
    var cadenceStyle by remember { mutableStateOf(CadenceStyle.CLIPPED_TACTICAL.name) }
    var eqPreset by remember { mutableStateOf(EqPreset.TACTICAL_HUD.name) }
    var customPitch by remember { mutableFloatStateOf(0.86f) }
    var customSpeechRate by remember { mutableFloatStateOf(1.28f) }
    var vocalGrit by remember { mutableIntStateOf(85) }
    var aggression by remember { mutableIntStateOf(95) }

    // Active Global Preset for radar comparison
    val activeGlobalPreset = presets.find { it.isActiveGlobal } ?: presets.firstOrNull()

    // Modals state
    var isHandshakeSandboxOpen by remember { mutableStateOf(false) }
    var radarComparePreset by remember { mutableStateOf<PersonalityPresetEntity?>(null) }
    var isAiGeneratingDirective by remember { mutableStateOf(false) }

    // Function to load archetype defaults into the tuning state
    fun loadArchetypeDefaults(archetypeId: String) {
        selectedArchetypeId = archetypeId
        when (archetypeId) {
            "drill_sergeant" -> {
                firmness = 5; obnoxiousness = 5; alertIntensity = 5; persistence = 5; handshakeDifficulty = 4
                cadenceStyle = CadenceStyle.CLIPPED_TACTICAL.name; eqPreset = EqPreset.TACTICAL_HUD.name
                customPitch = 0.86f; customSpeechRate = 1.28f; vocalGrit = 85; aggression = 95
                preferredHandshake = HandshakeType.SEQUENCE.name
                selectedVoicePackId = "pack_major_stone"
                reveilleTemplate = "EYEBALLS! SNAP TO RIGHT NOW! NO EXCUSES ON DECK! COMPLETE THIS SEQUENCE AND GET ON YOUR FEET! MOVE!"
                starterTemplate = "YOU FINISHED THE HANDSHAKE! NOW GIVE ME FIVE MINUTES OF UNINTERRUPTED EFFORT! MOVE, MOVE, MOVE!"
            }
            "executive_director" -> {
                firmness = 4; obnoxiousness = 1; alertIntensity = 4; persistence = 4; handshakeDifficulty = 3
                cadenceStyle = CadenceStyle.PACE_LOCKED.name; eqPreset = EqPreset.STUDIO_CRISP.name
                customPitch = 1.01f; customSpeechRate = 1.04f; vocalGrit = 0; aggression = 35
                preferredHandshake = HandshakeType.TYPING.name
                selectedVoicePackId = "pack_eleanor_vance"
                reveilleTemplate = "Schedule block active. Clear secondary tabs, enter the commitment phrase, and initiate your five-minute starter immediately."
                starterTemplate = "Handshake recorded. Execute the first five minutes without context switching."
            }
            "supportive_coach" -> {
                firmness = 3; obnoxiousness = 1; alertIntensity = 3; persistence = 3; handshakeDifficulty = 2
                cadenceStyle = CadenceStyle.DELIBERATE_AI.name; eqPreset = EqPreset.BROADCAST.name
                customPitch = 0.98f; customSpeechRate = 1.08f; vocalGrit = 10; aggression = 25
                preferredHandshake = HandshakeType.HOLD.name
                selectedVoicePackId = "pack_coach_ray"
                reveilleTemplate = "Take a breath. You are ready to start. Complete your hold handshake and let us take five easy minutes together."
                starterTemplate = "Great job on the handshake. Just focus on the first 5 minutes—no pressure for perfection."
            }
            "accountability_partner" -> {
                firmness = 4; obnoxiousness = 3; alertIntensity = 4; persistence = 5; handshakeDifficulty = 3
                cadenceStyle = CadenceStyle.PACE_LOCKED.name; eqPreset = EqPreset.DEEP_COMMAND.name
                customPitch = 0.94f; customSpeechRate = 1.14f; vocalGrit = 35; aggression = 65
                preferredHandshake = HandshakeType.HOLD.name
                selectedVoicePackId = "pack_sgt_brody"
                reveilleTemplate = "Lock it in right now. You put this on your calendar for a reason. Do the handshake and let's get off zero together."
                starterTemplate = "Handshake locked. Let us knock out this five-minute starter block together."
            }
            "minimalist" -> {
                firmness = 4; obnoxiousness = 1; alertIntensity = 3; persistence = 2; handshakeDifficulty = 2
                cadenceStyle = CadenceStyle.DELIBERATE_AI.name; eqPreset = EqPreset.STUDIO_CRISP.name
                customPitch = 0.95f; customSpeechRate = 1.00f; vocalGrit = 0; aggression = 10
                preferredHandshake = HandshakeType.HOLD.name
                selectedVoicePackId = "pack_aris_thorne"
                reveilleTemplate = "Time reached. Acknowledge handshake. Begin execution."
                starterTemplate = "Handshake complete. 5-minute starter initiated."
            }
            "custom" -> {
                firmness = 5; obnoxiousness = 4; alertIntensity = 5; persistence = 4; handshakeDifficulty = 3
                cadenceStyle = CadenceStyle.CLIPPED_TACTICAL.name; eqPreset = EqPreset.TACTICAL_HUD.name
                customPitch = 0.90f; customSpeechRate = 1.20f; vocalGrit = 70; aggression = 85
                preferredHandshake = HandshakeType.TWO_STEP.name
                selectedVoicePackId = voicePacks.firstOrNull()?.id ?: "pack_major_stone"
            }
        }
    }

    // Function to load an existing preset entity into the studio editor
    fun loadPresetForEditing(preset: PersonalityPresetEntity) {
        editingPresetId = preset.id
        presetName = preset.name
        presetDescription = preset.description
        selectedArchetypeId = preset.basePersonalityId
        selectedVoicePackId = preset.voicePackId
        firmness = preset.instructionFirmness
        obnoxiousness = preset.obnoxiousness
        alertIntensity = preset.alertIntensity
        persistence = preset.persistence
        handshakeDifficulty = preset.handshakeDifficulty
        preferredHandshake = preset.preferredHandshakeType
        chainedHandshake = preset.chainedHandshakeType
        assignedCategory = preset.assignedCategoriesCsv.split(",").firstOrNull() ?: "Career Work"
        customPersonaPrompt = preset.customPersonaPrompt
        vocabularyRules = preset.vocabularyRules
        catchphrasesCsv = preset.catchphrasesCsv
        reveilleTemplate = preset.reveillePhaseTemplate.ifBlank { preset.sampleDirective }
        starterTemplate = preset.starterPhaseTemplate
        accountabilityTemplate = preset.accountabilityPhaseTemplate
        recoveryTemplate = preset.recoveryPhaseTemplate
        cadenceStyle = preset.cadenceStyle
        eqPreset = preset.eqPreset
        customPitch = preset.customPitch
        customSpeechRate = preset.customSpeechRate
        vocalGrit = preset.vocalGritPercent
        aggression = preset.aggressionPercent
        selectedStudioTab = 0
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C0F14))
            .padding(16.dp)
            .testTag("preset_studio_screen")
    ) {
        // Studio Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "COMMAND AI COACHING STUDIO",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = CommandAmber
                )
                Text(
                    text = "6 Blueprint Personalities • Live Gemini Directives • DSP Delivery • Execution Modules",
                    style = MaterialTheme.typography.bodySmall,
                    color = SteelTextSecondary
                )
            }
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Personality Studio Icon",
                tint = CommandAmber,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4 Studio Tabs
        TabRow(
            selectedTabIndex = selectedStudioTab,
            containerColor = TacticalSurface,
            contentColor = CommandAmber,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
        ) {
            Tab(
                selected = selectedStudioTab == 0,
                onClick = { selectedStudioTab = 0 },
                text = { Text("Tuning Studio", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                modifier = Modifier.testTag("tab_tuning_studio")
            )
            Tab(
                selected = selectedStudioTab == 1,
                onClick = { selectedStudioTab = 1 },
                text = { Text("6-Personality Audition", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                modifier = Modifier.testTag("tab_audition_deck")
            )
            Tab(
                selected = selectedStudioTab == 2,
                onClick = { selectedStudioTab = 2 },
                text = { Text("Preset Library (${presets.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                modifier = Modifier.testTag("tab_preset_library")
            )
            Tab(
                selected = selectedStudioTab == 3,
                onClick = { selectedStudioTab = 3 },
                text = { Text("Management Modules", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                modifier = Modifier.testTag("tab_management_modules")
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tab Content
        when (selectedStudioTab) {
            0 -> {
                TuningStudioTab(
                    presetName = presetName,
                    onNameChange = { presetName = it },
                    presetDescription = presetDescription,
                    onDescriptionChange = { presetDescription = it },
                    selectedArchetypeId = selectedArchetypeId,
                    onSelectArchetype = { loadArchetypeDefaults(it) },
                    selectedVoicePackId = selectedVoicePackId,
                    voicePacks = voicePacks,
                    onSelectVoicePack = { selectedVoicePackId = it },
                    firmness = firmness,
                    onFirmnessChange = { firmness = it },
                    obnoxiousness = obnoxiousness,
                    onObnoxiousnessChange = { obnoxiousness = it },
                    alertIntensity = alertIntensity,
                    onIntensityChange = { alertIntensity = it },
                    persistence = persistence,
                    onPersistenceChange = { persistence = it },
                    handshakeDifficulty = handshakeDifficulty,
                    onHandshakeDifficultyChange = { handshakeDifficulty = it },
                    preferredHandshake = preferredHandshake,
                    onPreferredHandshakeChange = { preferredHandshake = it },
                    chainedHandshake = chainedHandshake,
                    onChainedHandshakeChange = { chainedHandshake = it },
                    assignedCategory = assignedCategory,
                    onAssignedCategoryChange = { assignedCategory = it },
                    customPersonaPrompt = customPersonaPrompt,
                    onCustomPersonaPromptChange = { customPersonaPrompt = it },
                    vocabularyRules = vocabularyRules,
                    onVocabularyRulesChange = { vocabularyRules = it },
                    catchphrasesCsv = catchphrasesCsv,
                    onCatchphrasesCsvChange = { catchphrasesCsv = it },
                    reveilleTemplate = reveilleTemplate,
                    onReveilleTemplateChange = { reveilleTemplate = it },
                    starterTemplate = starterTemplate,
                    onStarterTemplateChange = { starterTemplate = it },
                    accountabilityTemplate = accountabilityTemplate,
                    onAccountabilityTemplateChange = { accountabilityTemplate = it },
                    recoveryTemplate = recoveryTemplate,
                    onRecoveryTemplateChange = { recoveryTemplate = it },
                    cadenceStyle = cadenceStyle,
                    onCadenceStyleChange = { cadenceStyle = it },
                    eqPreset = eqPreset,
                    onEqPresetChange = { eqPreset = it },
                    customPitch = customPitch,
                    onCustomPitchChange = { customPitch = it },
                    customSpeechRate = customSpeechRate,
                    onCustomSpeechRateChange = { customSpeechRate = it },
                    vocalGrit = vocalGrit,
                    onVocalGritChange = { vocalGrit = it },
                    aggression = aggression,
                    onAggressionChange = { aggression = it },
                    activeGlobalPreset = activeGlobalPreset,
                    playbackState = playbackState,
                    isAiGeneratingDirective = isAiGeneratingDirective,
                    onTriggerAiDirective = {
                        isAiGeneratingDirective = true
                        onGenerateAiDirective(
                            DEFAULT_PERSONALITIES.find { it.id == selectedArchetypeId }?.name ?: presetName,
                            firmness,
                            obnoxiousness
                        ) { directive ->
                            reveilleTemplate = directive
                            isAiGeneratingDirective = false
                        }
                    },
                    onAuditionVoice = {
                        val boundPack = voicePacks.find { it.id == selectedVoicePackId } ?: voicePacks.firstOrNull()
                        if (boundPack != null) {
                            val line = reveilleTemplate.ifBlank { boundPack.sampleQuote }
                            onPreviewVoice(boundPack.id, line)
                        }
                    },
                    onStopVoice = onStopVoice,
                    onOpenSandbox = { isHandshakeSandboxOpen = true },
                    onSave = { asGlobal ->
                        val pId = editingPresetId ?: "preset_${presetName.lowercase().replace(" ", "_")}_${System.currentTimeMillis() % 10000}"
                        val completeEntity = PersonalityPresetEntity(
                            id = pId,
                            name = presetName,
                            description = presetDescription,
                            basePersonalityId = selectedArchetypeId,
                            voicePackId = selectedVoicePackId,
                            instructionFirmness = firmness,
                            obnoxiousness = obnoxiousness,
                            alertIntensity = alertIntensity,
                            persistence = persistence,
                            handshakeDifficulty = handshakeDifficulty,
                            preferredHandshakeType = preferredHandshake,
                            chainedHandshakeType = chainedHandshake,
                            assignedCategoriesCsv = assignedCategory,
                            customPersonaPrompt = customPersonaPrompt,
                            vocabularyRules = vocabularyRules,
                            catchphrasesCsv = catchphrasesCsv,
                            reveillePhaseTemplate = reveilleTemplate,
                            starterPhaseTemplate = starterTemplate,
                            accountabilityPhaseTemplate = accountabilityTemplate,
                            recoveryPhaseTemplate = recoveryTemplate,
                            cadenceStyle = cadenceStyle,
                            eqPreset = eqPreset,
                            customPitch = customPitch,
                            customSpeechRate = customSpeechRate,
                            vocalGritPercent = vocalGrit,
                            aggressionPercent = aggression,
                            sampleDirective = reveilleTemplate,
                            isActiveGlobal = asGlobal,
                            isPreloaded = false,
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                        onSaveCompletePreset(completeEntity, asGlobal)
                    }
                )
            }

            1 -> {
                AuditionDeckTab(
                    missionTitle = activeAuditionMissionTitle,
                    category = activeAuditionCategory,
                    selectedPhase = auditionPhase,
                    onSelectPhase = onSetAuditionPhase,
                    onTargetMissionChange = onSetAuditionTarget,
                    isAuditionGenerating = isAuditionGenerating,
                    directivesMap = auditionDirectivesMap,
                    playbackState = playbackState,
                    onSynthesizeAll = {
                        onSynthesizeAuditionDirectives(activeAuditionMissionTitle, activeAuditionCategory, auditionPhase)
                    },
                    onSynthesizeSingle = { pId ->
                        onSynthesizeSingleAudition(pId, activeAuditionMissionTitle, activeAuditionCategory, auditionPhase)
                    },
                    onAuditionVoice = { pId, text ->
                        onAuditionVoiceForPersonality(pId, text, null)
                    },
                    onLoadIntoStudio = { pId ->
                        loadArchetypeDefaults(pId)
                        selectedStudioTab = 0
                    }
                )
            }

            2 -> {
                PresetLibraryTab(
                    presets = presets,
                    activeGlobalPreset = activeGlobalPreset,
                    playbackState = playbackState,
                    onCreateNewPreset = {
                        editingPresetId = null
                        presetName = "Custom Tactical Directive"
                        presetDescription = "High-accountability custom profile with tuned DSP and zero-compromise handshakes"
                        loadArchetypeDefaults("custom")
                        selectedStudioTab = 0
                    },
                    onApplyGlobal = onApplyPresetGlobally,
                    onEditPreset = { loadPresetForEditing(it) },
                    onClonePreset = onClonePreset,
                    onDeletePreset = onDeletePreset,
                    onCompareRadar = { radarComparePreset = it },
                    onAuditionPreset = { preset ->
                        val line = preset.sampleDirective.ifBlank { preset.reveillePhaseTemplate.ifBlank { "Eyes on mission! Initiate first five minutes!" } }
                        onAuditionVoiceForPersonality(preset.basePersonalityId, line, preset)
                    }
                )
            }

            3 -> {
                ManagementModulesTab(
                    routingRules = routingRules,
                    escalationPolicies = escalationPolicies,
                    handshakeCalibrations = handshakeCalibrations,
                    blueprints = blueprints,
                    presets = presets,
                    onSaveRoutingRule = onSaveRoutingRule,
                    onDeleteRoutingRule = onDeleteRoutingRule,
                    onSaveEscalationPolicy = onSaveEscalationPolicy,
                    onDeleteEscalationPolicy = onDeleteEscalationPolicy,
                    onSaveHandshakeCalibration = onSaveHandshakeCalibration,
                    onDeleteHandshakeCalibration = onDeleteHandshakeCalibration,
                    onSaveMissionBlueprint = onSaveMissionBlueprint,
                    onDeleteMissionBlueprint = onDeleteMissionBlueprint,
                    onDeployBlueprintToQueue = onDeployBlueprintToQueue,
                    onApplyRoutingRulesToAllMissions = onApplyRoutingRulesToAllMissions,
                    onOpenSandbox = { isHandshakeSandboxOpen = true }
                )
            }
        }
    }

    // Modal: 5-Axis Radar Comparison Dialog
    if (radarComparePreset != null) {
        val target = radarComparePreset!!
        AlertDialog(
            onDismissRequest = { radarComparePreset = null },
            containerColor = TacticalSurfaceElevated,
            title = {
                Text(
                    text = "5-AXIS RADAR COMPARISON",
                    color = CommandAmber,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Comparing '${target.name}' (Orange) vs '${activeGlobalPreset?.name ?: "Global Default"}' (Emerald)",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelTextSecondary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    PentagonRadarComparisonChart(
                        presetAName = target.name,
                        presetAFirmness = target.instructionFirmness,
                        presetAObnoxiousness = target.obnoxiousness,
                        presetAAlertIntensity = target.alertIntensity,
                        presetAPersistence = target.persistence,
                        presetAHandshakeDifficulty = target.handshakeDifficulty,
                        presetBName = activeGlobalPreset?.name ?: "Global Default",
                        presetBFirmness = activeGlobalPreset?.instructionFirmness ?: 4,
                        presetBObnoxiousness = activeGlobalPreset?.obnoxiousness ?: 2,
                        presetBAlertIntensity = activeGlobalPreset?.alertIntensity ?: 4,
                        presetBPersistence = activeGlobalPreset?.persistence ?: 4,
                        presetBHandshakeDifficulty = activeGlobalPreset?.handshakeDifficulty ?: 3,
                        modifier = Modifier
                            .size(240.dp)
                            .padding(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onApplyPresetGlobally(target)
                        radarComparePreset = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CommandAmber, contentColor = Color.Black)
                ) {
                    Text("Apply '${target.name}' as Global", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { radarComparePreset = null }) {
                    Text("Close", color = SteelTextSecondary)
                }
            }
        )
    }

    // Modal: Handshake Sequence Sandbox
    if (isHandshakeSandboxOpen) {
        HandshakeSandboxDialog(
            primaryHandshake = preferredHandshake,
            chainedHandshake = chainedHandshake,
            onDismiss = { isHandshakeSandboxOpen = false }
        )
    }
}

// -------------------------------------------------------------
// TAB 0: 6-PERSONALITY TUNING STUDIO
// -------------------------------------------------------------
@Composable
private fun TuningStudioTab(
    presetName: String,
    onNameChange: (String) -> Unit,
    presetDescription: String,
    onDescriptionChange: (String) -> Unit,
    selectedArchetypeId: String,
    onSelectArchetype: (String) -> Unit,
    selectedVoicePackId: String,
    voicePacks: List<VoicePackEntity>,
    onSelectVoicePack: (String) -> Unit,
    firmness: Int,
    onFirmnessChange: (Int) -> Unit,
    obnoxiousness: Int,
    onObnoxiousnessChange: (Int) -> Unit,
    alertIntensity: Int,
    onIntensityChange: (Int) -> Unit,
    persistence: Int,
    onPersistenceChange: (Int) -> Unit,
    handshakeDifficulty: Int,
    onHandshakeDifficultyChange: (Int) -> Unit,
    preferredHandshake: String,
    onPreferredHandshakeChange: (String) -> Unit,
    chainedHandshake: String,
    onChainedHandshakeChange: (String) -> Unit,
    assignedCategory: String,
    onAssignedCategoryChange: (String) -> Unit,
    customPersonaPrompt: String,
    onCustomPersonaPromptChange: (String) -> Unit,
    vocabularyRules: String,
    onVocabularyRulesChange: (String) -> Unit,
    catchphrasesCsv: String,
    onCatchphrasesCsvChange: (String) -> Unit,
    reveilleTemplate: String,
    onReveilleTemplateChange: (String) -> Unit,
    starterTemplate: String,
    onStarterTemplateChange: (String) -> Unit,
    accountabilityTemplate: String,
    onAccountabilityTemplateChange: (String) -> Unit,
    recoveryTemplate: String,
    onRecoveryTemplateChange: (String) -> Unit,
    cadenceStyle: String,
    onCadenceStyleChange: (String) -> Unit,
    eqPreset: String,
    onEqPresetChange: (String) -> Unit,
    customPitch: Float,
    onCustomPitchChange: (Float) -> Unit,
    customSpeechRate: Float,
    onCustomSpeechRateChange: (Float) -> Unit,
    vocalGrit: Int,
    onVocalGritChange: (Int) -> Unit,
    aggression: Int,
    onAggressionChange: (Int) -> Unit,
    activeGlobalPreset: PersonalityPresetEntity?,
    playbackState: VoicePlaybackState,
    isAiGeneratingDirective: Boolean,
    onTriggerAiDirective: () -> Unit,
    onAuditionVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onOpenSandbox: () -> Unit,
    onSave: (asGlobal: Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Archetype Selector Row
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "1. SELECT BLUEPRINT ARCHETYPE",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CommandAmber
                    )
                    Text(
                        text = "Loads authentic blueprint defaults for firmness, wording style, and delivery DSP.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelTextSecondary,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(DEFAULT_PERSONALITIES) { spec ->
                            val isSelected = selectedArchetypeId == spec.id
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectArchetype(spec.id) },
                                label = { Text(spec.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CommandAmber,
                                    selectedLabelColor = Color.Black,
                                    containerColor = TacticalSurfaceElevated,
                                    labelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }

        // Preset Identification Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "2. PRESET IDENTITY & BINDING",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CommandAmber
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = onNameChange,
                        label = { Text("Preset Name (e.g. Deep Work Focus)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_preset_name"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CommandAmber,
                            focusedLabelColor = CommandAmber,
                            unfocusedBorderColor = Color(0xFF2A313C)
                        ),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = presetDescription,
                        onValueChange = onDescriptionChange,
                        label = { Text("Preset Description & Intended Outcome") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_preset_desc"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CommandAmber,
                            focusedLabelColor = CommandAmber,
                            unfocusedBorderColor = Color(0xFF2A313C)
                        ),
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = assignedCategory,
                            onValueChange = onAssignedCategoryChange,
                            label = { Text("Default Target Category") },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CommandAmber,
                                focusedLabelColor = CommandAmber,
                                unfocusedBorderColor = Color(0xFF2A313C)
                            ),
                            singleLine = true
                        )
                    }
                }
            }
        }

        // Section 3: 5-Axis Intensity Sliders + Live Radar Comparison Chart
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "3. 5-AXIS BEHAVIORAL CALIBRATION & RADAR COMPARISON",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CommandAmber
                    )
                    Text(
                        text = "Compare current slider calibration (Orange) against active global director (Emerald).",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelTextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Embedded Pentagon Radar Comparison Chart
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        PentagonRadarComparisonChart(
                            presetAName = presetName,
                            presetAFirmness = firmness,
                            presetAObnoxiousness = obnoxiousness,
                            presetAAlertIntensity = alertIntensity,
                            presetAPersistence = persistence,
                            presetAHandshakeDifficulty = handshakeDifficulty,
                            presetBName = activeGlobalPreset?.name ?: "Global Default",
                            presetBFirmness = activeGlobalPreset?.instructionFirmness ?: 4,
                            presetBObnoxiousness = activeGlobalPreset?.obnoxiousness ?: 2,
                            presetBAlertIntensity = activeGlobalPreset?.alertIntensity ?: 4,
                            presetBPersistence = activeGlobalPreset?.persistence ?: 4,
                            presetBHandshakeDifficulty = activeGlobalPreset?.handshakeDifficulty ?: 3,
                            modifier = Modifier.size(190.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Sliders
                    AxisSlider(
                        label = "Instruction Firmness",
                        value = firmness,
                        onValueChange = onFirmnessChange,
                        min = 1, max = 5,
                        lowDesc = "1: Gentle Nudge",
                        highDesc = "5: Absolute Command",
                        testTag = "slider_firmness"
                    )
                    AxisSlider(
                        label = "Obnoxiousness",
                        value = obnoxiousness,
                        onValueChange = onObnoxiousnessChange,
                        min = 1, max = 5,
                        lowDesc = "1: Polished & Concise",
                        highDesc = "5: Humorously Demanding",
                        testTag = "slider_obnoxiousness"
                    )
                    AxisSlider(
                        label = "Alert Intensity",
                        value = alertIntensity,
                        onValueChange = onIntensityChange,
                        min = 1, max = 5,
                        lowDesc = "1: Subtle Chime",
                        highDesc = "5: Full MCRD Whistle Surge",
                        testTag = "slider_intensity"
                    )
                    AxisSlider(
                        label = "Persistence",
                        value = persistence,
                        onValueChange = onPersistenceChange,
                        min = 1, max = 5,
                        lowDesc = "1: Single Reminder",
                        highDesc = "5: Relentless Multi-Alarm",
                        testTag = "slider_persistence"
                    )
                    AxisSlider(
                        label = "Handshake Difficulty",
                        value = handshakeDifficulty,
                        onValueChange = onHandshakeDifficultyChange,
                        min = 1, max = 5,
                        lowDesc = "1: Single Tap",
                        highDesc = "5: Chained Tactical Hold + Sequence",
                        testTag = "slider_handshake"
                    )
                }
            }
        }

        // Section 4: Handshake Challenge & Sequence Sandbox
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "4. HANDSHAKE CHALLENGE LINKAGE",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = CommandAmber
                        )
                        Button(
                            onClick = onOpenSandbox,
                            colors = ButtonDefaults.buttonColors(containerColor = CommandAmber, contentColor = Color.Black),
                            modifier = Modifier.testTag("btn_handshake_sandbox")
                        ) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test Sandbox", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Primary Handshake Challenge:",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelTextSecondary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        items(HandshakeType.entries) { type ->
                            FilterChip(
                                selected = preferredHandshake == type.name,
                                onClick = { onPreferredHandshakeChange(type.name) },
                                label = { Text(type.displayName, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CommandAmber,
                                    selectedLabelColor = Color.Black,
                                    containerColor = TacticalSurfaceElevated,
                                    labelColor = Color.White
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Chained Secondary Handshake Step (Optional 2-Step):",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelTextSecondary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        val chainedOptions = listOf("NONE", HandshakeType.SEQUENCE.name, HandshakeType.TYPING.name)
                        items(chainedOptions) { opt ->
                            val label = if (opt == "NONE") "Single Step Only" else opt
                            FilterChip(
                                selected = chainedHandshake == opt,
                                onClick = { onChainedHandshakeChange(opt) },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MissionEmerald,
                                    selectedLabelColor = Color.Black,
                                    containerColor = TacticalSurfaceElevated,
                                    labelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }

        // Section 5: AI Custom Persona & Wording Rules
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "5. AI PERSONA PROMPT, VOCABULARY & CATCHPHRASES",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CommandAmber
                    )
                    Text(
                        text = "Direct the underlying Gemini model on linguistic rules, forbidden phrases, and signature lines.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelTextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = customPersonaPrompt,
                        onValueChange = onCustomPersonaPromptChange,
                        label = { Text("Custom Persona System Directive") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_persona_prompt"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CommandAmber,
                            focusedLabelColor = CommandAmber,
                            unfocusedBorderColor = Color(0xFF2A313C)
                        ),
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vocabularyRules,
                        onValueChange = onVocabularyRulesChange,
                        label = { Text("Vocabulary & Tone Constraints") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CommandAmber,
                            focusedLabelColor = CommandAmber,
                            unfocusedBorderColor = Color(0xFF2A313C)
                        ),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = catchphrasesCsv,
                        onValueChange = onCatchphrasesCsvChange,
                        label = { Text("Signature Catchphrases (Comma separated)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CommandAmber,
                            focusedLabelColor = CommandAmber,
                            unfocusedBorderColor = Color(0xFF2A313C)
                        ),
                        singleLine = true
                    )
                }
            }
        }

        // Section 6: Phase-Specific Script Templates
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "6. PHASE-SPECIFIC SCRIPT TEMPLATES",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = CommandAmber
                        )
                        Button(
                            onClick = onTriggerAiDirective,
                            enabled = !isAiGeneratingDirective,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222B38)),
                            modifier = Modifier.testTag("btn_ai_directive_craft")
                        ) {
                            if (isAiGeneratingDirective) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = CommandAmber)
                            } else {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = CommandAmber, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Gemini Craft", color = CommandAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reveilleTemplate,
                        onValueChange = onReveilleTemplateChange,
                        label = { Text("Reveille Alarm Script Template") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CommandAmber,
                            focusedLabelColor = CommandAmber,
                            unfocusedBorderColor = Color(0xFF2A313C)
                        ),
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = starterTemplate,
                        onValueChange = onStarterTemplateChange,
                        label = { Text("5-Minute Starter Script Template") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CommandAmber,
                            focusedLabelColor = CommandAmber,
                            unfocusedBorderColor = Color(0xFF2A313C)
                        ),
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = accountabilityTemplate,
                        onValueChange = onAccountabilityTemplateChange,
                        label = { Text("Accountability Check-In Template") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CommandAmber,
                            focusedLabelColor = CommandAmber,
                            unfocusedBorderColor = Color(0xFF2A313C)
                        ),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = recoveryTemplate,
                        onValueChange = onRecoveryTemplateChange,
                        label = { Text("Recovery Reset Template") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CommandAmber,
                            focusedLabelColor = CommandAmber,
                            unfocusedBorderColor = Color(0xFF2A313C)
                        ),
                        singleLine = true
                    )
                }
            }
        }

        // Section 7: Vocal Delivery Cadence, Pitch, Grit & DSP Controls
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "7. VOCAL DELIVERY, CADENCE & DSP OVERDRIVE",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CommandAmber
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Cadence Style:", style = MaterialTheme.typography.bodySmall, color = SteelTextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        CadenceStyle.entries.forEach { c ->
                            FilterChip(
                                selected = cadenceStyle == c.name,
                                onClick = { onCadenceStyleChange(c.name) },
                                label = { Text(c.label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CommandAmber,
                                    selectedLabelColor = Color.Black,
                                    containerColor = TacticalSurfaceElevated,
                                    labelColor = Color.White
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Pitch: ${String.format(Locale.US, "%.2f", customPitch)}x", style = MaterialTheme.typography.bodySmall, color = SteelTextSecondary)
                        Text(text = "Rate: ${String.format(Locale.US, "%.2f", customSpeechRate)}x", style = MaterialTheme.typography.bodySmall, color = SteelTextSecondary)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Slider(
                            value = customPitch,
                            onValueChange = onCustomPitchChange,
                            valueRange = 0.60f..1.40f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = CommandAmber, activeTrackColor = CommandAmber)
                        )
                        Slider(
                            value = customSpeechRate,
                            onValueChange = onCustomSpeechRateChange,
                            valueRange = 0.70f..1.50f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = MissionEmerald, activeTrackColor = MissionEmerald)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Vocal Grit: $vocalGrit%", style = MaterialTheme.typography.bodySmall, color = SteelTextSecondary)
                        Text(text = "Compression/Aggression: $aggression%", style = MaterialTheme.typography.bodySmall, color = SteelTextSecondary)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Slider(
                            value = vocalGrit.toFloat(),
                            onValueChange = { onVocalGritChange(it.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = CommandCrimson, activeTrackColor = CommandCrimson)
                        )
                        Slider(
                            value = aggression.toFloat(),
                            onValueChange = { onAggressionChange(it.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = CommandAmber, activeTrackColor = CommandAmber)
                        )
                    }
                }
            }
        }

        // Section 8: Live Audition & Save Action Bar
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurfaceElevated),
                border = BorderStroke(1.dp, CommandAmber),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onAuditionVoice,
                            colors = ButtonDefaults.buttonColors(containerColor = MissionEmerald, contentColor = Color.Black),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_audition_studio_voice")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Audition Voice", fontWeight = FontWeight.Bold)
                        }
                        if (playbackState.isSpeaking) {
                            Button(
                                onClick = onStopVoice,
                                colors = ButtonDefaults.buttonColors(containerColor = CommandCrimson, contentColor = Color.White)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onSave(false) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_save_preset"),
                            border = BorderStroke(1.dp, CommandAmber)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, tint = CommandAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save Preset", color = CommandAmber, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onSave(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = CommandAmber, contentColor = Color.Black),
                            modifier = Modifier
                                .weight(1.3f)
                                .testTag("btn_save_global_preset")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save & Apply Global", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// TAB 1: 6-PERSONALITY AUDITION DECK
// -------------------------------------------------------------
@Composable
private fun AuditionDeckTab(
    missionTitle: String,
    category: String,
    selectedPhase: ExecutionPhase,
    onSelectPhase: (ExecutionPhase) -> Unit,
    onTargetMissionChange: (String, String) -> Unit,
    isAuditionGenerating: Boolean,
    directivesMap: Map<String, String>,
    playbackState: VoicePlaybackState,
    onSynthesizeAll: () -> Unit,
    onSynthesizeSingle: (String) -> Unit,
    onAuditionVoice: (String, String) -> Unit,
    onLoadIntoStudio: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Target Scenario & Phase Controls
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "AUDITION MISSION & EXECUTION PHASE",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CommandAmber
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Select a scenario and phase to see how each of the 6 personalities handles the situation differently.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Preset Scenario Chips
                    Text(text = "Target Scenario:", style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        val scenarios = listOf(
                            "Career Work — Deep Architecture Sprint" to "Career Work",
                            "Morning Conditioning & Mobility" to "Morning Routine",
                            "Quarterly Budget & Tax Review" to "Career Work",
                            "Physical Clean Reset" to "Health & Reset"
                        )
                        items(scenarios) { (title, cat) ->
                            val isSel = missionTitle == title
                            FilterChip(
                                selected = isSel,
                                onClick = { onTargetMissionChange(title, cat) },
                                label = { Text(title, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CommandAmber,
                                    selectedLabelColor = Color.Black,
                                    containerColor = TacticalSurfaceElevated,
                                    labelColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    // Phase Chips
                    Text(text = "Execution Phase:", style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        items(ExecutionPhase.entries) { phase ->
                            val isSel = selectedPhase == phase
                            FilterChip(
                                selected = isSel,
                                onClick = { onSelectPhase(phase) },
                                label = { Text(phase.label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MissionEmerald,
                                    selectedLabelColor = Color.Black,
                                    containerColor = TacticalSurfaceElevated,
                                    labelColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Master Synthesize All Button
                    Button(
                        onClick = onSynthesizeAll,
                        enabled = !isAuditionGenerating,
                        colors = ButtonDefaults.buttonColors(containerColor = CommandAmber, contentColor = Color.Black),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_synthesize_all_personalities")
                    ) {
                        if (isAuditionGenerating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Gemini Synthesizing 6 Directives...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Synthesize All 6 Personalities with Live Gemini AI", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // List the 6 Personality Cards
        val personalities = listOf(
            AuditionArchetypeSpec("drill_sergeant", "Drill Sergeant", "MCRD Bark • 85% Grit", "5 / 5 / 5", "CLIPPED_TACTICAL", CommandCrimson),
            AuditionArchetypeSpec("executive_director", "Executive Director", "Boardroom Precision", "4 / 1 / 4", "PACE_LOCKED", CommandAmber),
            AuditionArchetypeSpec("supportive_coach", "Personal & Life Coach", "Coach Jordan Vance • High Momentum", "4 / 2 / 4", "PACE_LOCKED", MissionEmerald),
            AuditionArchetypeSpec("accountability_partner", "Accountability Partner", "Peer-to-Peer Vigilant", "4 / 3 / 5", "PACE_LOCKED", Color(0xFF38BDF8)),
            AuditionArchetypeSpec("minimalist", "Minimalist", "Clinical Telemetry", "4 / 1 / 2", "DELIBERATE_AI", Color(0xFF94A3B8)),
            AuditionArchetypeSpec("custom", "Custom Director", "Individually Tuned", "User Defined", "CUSTOM", Color(0xFFA855F7))
        )

        items(personalities) { spec ->
            val directiveText = directivesMap[spec.id]
                ?: getDefaultBlueprintDirective(spec.id, selectedPhase, missionTitle)
            val isPlayingThis = playbackState.isSpeaking && playbackState.activeVoicePackId?.contains(spec.id.take(5)) == true

            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (isPlayingThis) spec.accentColor else Color(0xFF1E2530)),
                modifier = Modifier.testTag("card_audition_${spec.id}")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val coachIndividual = com.example.ui.components.resolveCoachIndividual(spec.id)
                            if (spec.id == "drill_sergeant" || spec.id == "supportive_coach") {
                                com.example.ui.components.AnimatedCoachAvatar(
                                    individual = coachIndividual,
                                    isSpeaking = isPlayingThis,
                                    size = 40.dp,
                                    showStatusBadge = false
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(spec.accentColor)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = spec.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Surface(
                            color = spec.accentColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = spec.tagline,
                                color = spec.accentColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Firmness: ${spec.ratings} • Cadence: ${spec.cadence}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelTextSecondary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Live Generated Directive Text
                    Surface(
                        color = TacticalSurfaceElevated,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "\"$directiveText\"",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE2E8F0),
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Card Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onSynthesizeSingle(spec.id) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Regenerate", fontSize = 11.sp)
                        }

                        Button(
                            onClick = { onAuditionVoice(spec.id, directiveText) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPlayingThis) CommandCrimson else spec.accentColor,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier.weight(1.2f)
                        ) {
                            Icon(
                                imageVector = if (isPlayingThis) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isPlayingThis) "Stop" else "Audition Voice", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        IconButton(
                            onClick = { onLoadIntoStudio(spec.id) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit in Studio", tint = SteelTextSecondary)
                        }
                    }
                }
            }
        }
    }
}

private data class AuditionArchetypeSpec(
    val id: String,
    val name: String,
    val tagline: String,
    val ratings: String,
    val cadence: String,
    val accentColor: Color
)

private fun getDefaultBlueprintDirective(personalityId: String, phase: ExecutionPhase, missionTitle: String): String {
    return when (personalityId) {
        "drill_sergeant" -> "EYEBALLS! SNAP TO RIGHT NOW! PUT THE PHONE DOWN, GET TO YOUR COMPUTER AND GIVE ME FIVE MINUTES! MOVE!"
        "executive_director" -> "Schedule block active. Clear secondary tabs, enter the commitment phrase, and initiate your five-minute starter immediately."
        "supportive_coach" -> "I see you on that starting line! Lock in that handshake right now! Give me five focused minutes—let's build momentum!"
        "accountability_partner" -> "Lock it in right now. You put this on your calendar for a reason. Do the handshake and let's get off zero together."
        "minimalist" -> "'$missionTitle' active. Verify handshake token. Begin execution."
        else -> "Sprint block is live! Do not overthink the architecture—get the first function working right now!"
    }
}

// -------------------------------------------------------------
// TAB 2: PRESET LIBRARY (CRUD & RADAR COMPARISON)
// -------------------------------------------------------------
@Composable
private fun PresetLibraryTab(
    presets: List<PersonalityPresetEntity>,
    activeGlobalPreset: PersonalityPresetEntity?,
    playbackState: VoicePlaybackState,
    onCreateNewPreset: () -> Unit,
    onApplyGlobal: (PersonalityPresetEntity) -> Unit,
    onEditPreset: (PersonalityPresetEntity) -> Unit,
    onClonePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onCompareRadar: (PersonalityPresetEntity) -> Unit,
    onAuditionPreset: (PersonalityPresetEntity) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedArchetypeFilter by remember { mutableStateOf("ALL") }
    var presetToDelete by remember { mutableStateOf<PersonalityPresetEntity?>(null) }

    val filteredPresets = remember(presets, searchQuery, selectedArchetypeFilter) {
        presets.filter { preset ->
            val matchesArchetype = when (selectedArchetypeFilter) {
                "ALL" -> true
                else -> preset.basePersonalityId == selectedArchetypeFilter
            }
            val matchesSearch = searchQuery.isBlank() ||
                preset.name.contains(searchQuery, ignoreCase = true) ||
                preset.description.contains(searchQuery, ignoreCase = true) ||
                preset.assignedCategoriesCsv.contains(searchQuery, ignoreCase = true) ||
                preset.preferredHandshakeType.contains(searchQuery, ignoreCase = true) ||
                preset.reveillePhaseTemplate.contains(searchQuery, ignoreCase = true)
            matchesArchetype && matchesSearch
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            // Header with Create Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SAVED PRESET REPOSITORY",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CommandAmber
                    )
                    Text(
                        text = "Blueprint archetypes and custom presets with inline 5-axis radar telemetry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelTextSecondary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onCreateNewPreset,
                    colors = ButtonDefaults.buttonColors(containerColor = CommandAmber, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("btn_create_new_preset")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Preset", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search Filter Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search presets by name, category, or phase prompt...", fontSize = 12.sp, color = SteelTextSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CommandAmber, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = SteelTextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CommandAmber,
                    unfocusedBorderColor = Color(0xFF1E2530),
                    focusedContainerColor = TacticalSurfaceElevated,
                    unfocusedContainerColor = TacticalSurface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_search_presets")
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Archetype Filter Chips Row
            val filterOptions = listOf(
                "ALL" to "All (${presets.size})",
                "drill_sergeant" to "Drill Sergeant",
                "executive_director" to "Executive Director",
                "supportive_coach" to "Personal & Life Coach",
                "accountability_partner" to "Accountability Partner",
                "minimalist" to "Minimalist",
                "custom" to "Custom"
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(filterOptions) { (id, label) ->
                    val isSelected = selectedArchetypeFilter == id
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedArchetypeFilter = id },
                        label = { Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CommandAmber,
                            selectedLabelColor = Color.Black,
                            containerColor = TacticalSurfaceElevated,
                            labelColor = Color.White
                        ),
                        modifier = Modifier.testTag("filter_chip_$id")
                    )
                }
            }
        }

        if (filteredPresets.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = SteelTextSecondary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No Presets Match Filter",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Try adjusting your search query or reset the archetype filter to show all presets.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SteelTextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                searchQuery = ""
                                selectedArchetypeFilter = "ALL"
                            }
                        ) {
                            Text("Reset Filters", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        items(filteredPresets) { preset ->
            val isGlobal = preset.isActiveGlobal || preset.id == activeGlobalPreset?.id
            val isPlayingThis = playbackState.isSpeaking && (
                playbackState.activeVoicePackId == preset.voicePackId ||
                playbackState.activeVoicePackId?.contains(preset.basePersonalityId.take(5)) == true
            )
            val archetypeColor = getPresetArchetypeColor(preset.basePersonalityId)
            val archetypeName = getPresetArchetypeName(preset.basePersonalityId)

            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    width = if (isGlobal) 1.5.dp else 1.dp,
                    color = if (isGlobal) MissionEmerald else Color(0xFF1E2530)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preset_card_${preset.id}")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Top Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (isGlobal) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = MissionEmerald.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "ACTIVE GLOBAL",
                                        color = MissionEmerald,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        // Archetype and Preloaded/Custom Tag
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = archetypeColor.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = archetypeName.uppercase(Locale.ROOT),
                                    color = archetypeColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 8.5.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Surface(
                                color = if (preset.isPreloaded) Color(0xFF334155) else Color(0xFF581C87),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (preset.isPreloaded) "STANDARD" else "CUSTOM",
                                    color = if (preset.isPreloaded) Color(0xFF94A3B8) else Color(0xFFD8B4FE),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 8.5.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Middle Section: Left info vs Right Inline Radar Chart
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Column: Description, Voice DSP, Handshake info, Sample Quote
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = preset.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = SteelTextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // DSP Voice Telemetry row
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = TacticalSurfaceElevated,
                                    shape = RoundedCornerShape(3.dp)
                                ) {
                                    Text(
                                        text = "Rate: ${preset.customSpeechRate}x",
                                        fontSize = 9.sp,
                                        color = Color(0xFFCBD5E1),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                                Surface(
                                    color = TacticalSurfaceElevated,
                                    shape = RoundedCornerShape(3.dp)
                                ) {
                                    Text(
                                        text = "Pitch: ${preset.customPitch}x",
                                        fontSize = 9.sp,
                                        color = Color(0xFFCBD5E1),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                                if (preset.vocalGritPercent > 0) {
                                    Surface(
                                        color = CommandAmber.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(3.dp)
                                    ) {
                                        Text(
                                            text = "Grit: ${preset.vocalGritPercent}%",
                                            fontSize = 9.sp,
                                            color = CommandAmber,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Surface(
                                    color = TacticalSurfaceElevated,
                                    shape = RoundedCornerShape(3.dp)
                                ) {
                                    Text(
                                        text = preset.eqPreset.replace("_", " "),
                                        fontSize = 9.sp,
                                        color = Color(0xFF94A3B8),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Handshake requirement tag
                            val handshakeText = buildString {
                                append("Handshake: ${preset.preferredHandshakeType}")
                                if (preset.chainedHandshakeType != "NONE") {
                                    append(" -> ${preset.chainedHandshakeType}")
                                }
                            }
                            Text(
                                text = handshakeText,
                                fontSize = 9.5.sp,
                                color = CommandAmber,
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            // Directive sample quote preview
                            val quoteSnippet = preset.reveillePhaseTemplate.ifBlank { preset.sampleDirective }
                            if (quoteSnippet.isNotBlank()) {
                                Text(
                                    text = "\"${quoteSnippet.take(80)}${if (quoteSnippet.length > 80) "..." else ""}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }

                        // Right Column: Inline Mini 5-Axis Pentagon Radar Chart
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            MiniPresetRadarChart(
                                firmness = preset.instructionFirmness,
                                obnoxiousness = preset.obnoxiousness,
                                alertIntensity = preset.alertIntensity,
                                persistence = preset.persistence,
                                handshakeDifficulty = preset.handshakeDifficulty,
                                baselineFirmness = activeGlobalPreset?.instructionFirmness ?: 4,
                                baselineObnoxiousness = activeGlobalPreset?.obnoxiousness ?: 2,
                                baselineAlertIntensity = activeGlobalPreset?.alertIntensity ?: 4,
                                baselinePersistence = activeGlobalPreset?.persistence ?: 4,
                                baselineHandshakeDifficulty = activeGlobalPreset?.handshakeDifficulty ?: 3,
                                modifier = Modifier.size(86.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Radar vs Global",
                                fontSize = 8.sp,
                                color = SteelTextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 5-Axis mini telemetry numerical pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        PillTelemetry("Firmness", preset.instructionFirmness)
                        PillTelemetry("Obnoxious", preset.obnoxiousness)
                        PillTelemetry("Intensity", preset.alertIntensity)
                        PillTelemetry("Persistence", preset.persistence)
                        PillTelemetry("Handshake", preset.handshakeDifficulty)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isGlobal) {
                            Button(
                                onClick = { onApplyGlobal(preset) },
                                colors = ButtonDefaults.buttonColors(containerColor = MissionEmerald, contentColor = Color.Black),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_set_global_${preset.id}")
                            ) {
                                Text("Set Global", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        OutlinedButton(
                            onClick = { onAuditionPreset(preset) },
                            shape = RoundedCornerShape(8.dp),
                            colors = if (isPlayingThis) {
                                ButtonDefaults.outlinedButtonColors(contentColor = CommandAmber)
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_audition_${preset.id}")
                        ) {
                            Icon(
                                if (isPlayingThis) Icons.Default.GraphicEq else Icons.Default.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (isPlayingThis) CommandAmber else Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isPlayingThis) "Playing" else "Audition", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = { onCompareRadar(preset) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_radar_diff_${preset.id}")
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Radar Diff", fontSize = 11.sp)
                        }

                        IconButton(
                            onClick = { onEditPreset(preset) },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("btn_edit_${preset.id}")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit in Studio", tint = CommandAmber)
                        }

                        IconButton(
                            onClick = { onClonePreset(preset.id) },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("btn_clone_${preset.id}")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Clone Preset", tint = Color.White)
                        }

                        if (!preset.isPreloaded && !isGlobal) {
                            IconButton(
                                onClick = { presetToDelete = preset },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("btn_delete_${preset.id}")
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Preset", tint = CommandCrimson)
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal: Delete Preset Confirmation Dialog
    if (presetToDelete != null) {
        val target = presetToDelete!!
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            containerColor = TacticalSurfaceElevated,
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = CommandCrimson) },
            title = {
                Text(
                    text = "DELETE PRESET: '${target.name}'?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete this custom personality preset? Any auto-routing rules or mission blueprints bound to this preset will automatically revert to the active global default.",
                    color = SteelTextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeletePreset(target.id)
                        presetToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CommandCrimson, contentColor = Color.White),
                    modifier = Modifier.testTag("btn_confirm_delete_preset")
                ) {
                    Text("Delete Preset", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { presetToDelete = null }) {
                    Text("Cancel", color = SteelTextSecondary)
                }
            }
        )
    }
}

// -------------------------------------------------------------
// HELPER: INLINE MINI 5-AXIS RADAR CHART (CANVAS)
// -------------------------------------------------------------
@Composable
fun MiniPresetRadarChart(
    firmness: Int,
    obnoxiousness: Int,
    alertIntensity: Int,
    persistence: Int,
    handshakeDifficulty: Int,
    baselineFirmness: Int = 4,
    baselineObnoxiousness: Int = 2,
    baselineAlertIntensity: Int = 4,
    baselinePersistence: Int = 4,
    baselineHandshakeDifficulty: Int = 3,
    modifier: Modifier = Modifier.size(86.dp)
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F141C))
            .border(1.dp, Color(0xFF1E2530), RoundedCornerShape(8.dp))
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxR = (minOf(size.width, size.height) / 2f) * 0.70f

            // 5 axis angles starting at top (-PI/2)
            // 0: Top = Firmness (F)
            // 1: Top-Right = Obnoxiousness (O)
            // 2: Bottom-Right = Alert Intensity (I)
            // 3: Bottom-Left = Persistence (P)
            // 4: Top-Left = Handshake Difficulty (H)
            val angles = listOf(
                -Math.PI / 2.0,
                -Math.PI / 2.0 + (2 * Math.PI / 5.0),
                -Math.PI / 2.0 + (4 * Math.PI / 5.0),
                -Math.PI / 2.0 + (6 * Math.PI / 5.0),
                -Math.PI / 2.0 + (8 * Math.PI / 5.0)
            )

            // Draw concentric pentagon grids (levels 2, 4, 5)
            listOf(0.4f, 0.8f, 1.0f).forEach { fraction ->
                val r = maxR * fraction
                val gridPath = Path()
                for (i in 0 until 5) {
                    val angle = angles[i]
                    val x = center.x + (r * Math.cos(angle)).toFloat()
                    val y = center.y + (r * Math.sin(angle)).toFloat()
                    if (i == 0) gridPath.moveTo(x, y) else gridPath.lineTo(x, y)
                }
                gridPath.close()
                drawPath(
                    gridPath,
                    color = Color(0x33475569),
                    style = Stroke(width = 0.8.dp.toPx())
                )
            }

            // Draw radial spokes from center
            for (angle in angles) {
                val x = center.x + (maxR * Math.cos(angle)).toFloat()
                val y = center.y + (maxR * Math.sin(angle)).toFloat()
                drawLine(
                    color = Color(0x33475569),
                    start = center,
                    end = Offset(x, y),
                    strokeWidth = 0.8.dp.toPx()
                )
            }

            // Polygon B (Active Global Baseline - Emerald)
            val valuesB = listOf(
                baselineFirmness,
                baselineObnoxiousness,
                baselineAlertIntensity,
                baselinePersistence,
                baselineHandshakeDifficulty
            )
            val pathB = Path()
            for (i in 0 until 5) {
                val fraction = (valuesB[i].coerceIn(1, 5) / 5f)
                val r = maxR * fraction
                val angle = angles[i]
                val x = center.x + (r * Math.cos(angle)).toFloat()
                val y = center.y + (r * Math.sin(angle)).toFloat()
                if (i == 0) pathB.moveTo(x, y) else pathB.lineTo(x, y)
            }
            pathB.close()
            drawPath(pathB, color = MissionEmerald.copy(alpha = 0.20f))
            drawPath(pathB, color = MissionEmerald.copy(alpha = 0.7f), style = Stroke(width = 1.dp.toPx()))

            // Polygon A (Target Preset - CommandAmber)
            val valuesA = listOf(
                firmness,
                obnoxiousness,
                alertIntensity,
                persistence,
                handshakeDifficulty
            )
            val pathA = Path()
            for (i in 0 until 5) {
                val fraction = (valuesA[i].coerceIn(1, 5) / 5f)
                val r = maxR * fraction
                val angle = angles[i]
                val x = center.x + (r * Math.cos(angle)).toFloat()
                val y = center.y + (r * Math.sin(angle)).toFloat()
                if (i == 0) pathA.moveTo(x, y) else pathA.lineTo(x, y)
            }
            pathA.close()
            drawPath(pathA, color = CommandAmber.copy(alpha = 0.35f))
            drawPath(pathA, color = CommandAmber, style = Stroke(width = 1.6.dp.toPx()))

            // Draw vertex dots on target preset
            for (i in 0 until 5) {
                val fraction = (valuesA[i].coerceIn(1, 5) / 5f)
                val r = maxR * fraction
                val angle = angles[i]
                val x = center.x + (r * Math.cos(angle)).toFloat()
                val y = center.y + (r * Math.sin(angle)).toFloat()
                drawCircle(color = CommandAmber, radius = 2.dp.toPx(), center = Offset(x, y))
            }

            // Draw outer axis single-letter labels (F, O, I, P, H)
            val labels = listOf("F", "O", "I", "P", "H")
            val labelR = maxR + 7.dp.toPx()
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(220, 148, 163, 184)
                textSize = 7.5.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            drawContext.canvas.nativeCanvas.apply {
                for (i in 0 until 5) {
                    val angle = angles[i]
                    val x = center.x + (labelR * Math.cos(angle)).toFloat()
                    val y = center.y + (labelR * Math.sin(angle)).toFloat() + 3.dp.toPx()
                    drawText(labels[i], x, y, textPaint)
                }
            }
        }
    }
}

private fun getPresetArchetypeColor(basePersonalityId: String): Color {
    return when (basePersonalityId) {
        "drill_sergeant" -> CommandCrimson
        "executive_director" -> CommandAmber
        "supportive_coach" -> MissionEmerald
        "accountability_partner" -> Color(0xFF38BDF8)
        "minimalist" -> Color(0xFF94A3B8)
        "custom" -> Color(0xFFA855F7)
        else -> CommandAmber
    }
}

private fun getPresetArchetypeName(basePersonalityId: String): String {
    return when (basePersonalityId) {
        "drill_sergeant" -> "Drill Sergeant"
        "executive_director" -> "Executive Director"
        "supportive_coach" -> "Personal & Life Coach"
        "accountability_partner" -> "Accountability Partner"
        "minimalist" -> "Minimalist"
        "custom" -> "Custom"
        else -> "Tactical"
    }
}

@Composable
private fun PillTelemetry(label: String, value: Int) {
    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = "$label: $value/5",
            fontSize = 9.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

// -------------------------------------------------------------
// TAB 3: MANAGEMENT MODULES HUB
// -------------------------------------------------------------
@Composable
private fun ManagementModulesTab(
    routingRules: List<PresetRoutingRuleEntity>,
    escalationPolicies: List<EscalationPolicyEntity>,
    handshakeCalibrations: List<HandshakeCalibrationEntity>,
    blueprints: List<MissionBlueprintEntity>,
    presets: List<PersonalityPresetEntity>,
    onSaveRoutingRule: (PresetRoutingRuleEntity) -> Unit,
    onDeleteRoutingRule: (Long) -> Unit,
    onSaveEscalationPolicy: (EscalationPolicyEntity) -> Unit,
    onDeleteEscalationPolicy: (String) -> Unit,
    onSaveHandshakeCalibration: (HandshakeCalibrationEntity) -> Unit,
    onDeleteHandshakeCalibration: (String) -> Unit,
    onSaveMissionBlueprint: (MissionBlueprintEntity) -> Unit,
    onDeleteMissionBlueprint: (String) -> Unit,
    onDeployBlueprintToQueue: (MissionBlueprintEntity) -> Unit,
    onApplyRoutingRulesToAllMissions: () -> Unit,
    onOpenSandbox: () -> Unit
) {
    var subModuleTab by remember { mutableIntStateOf(0) } // 0: Auto-Routing Rules, 1: Escalation Policies, 2: Handshake Sequence, 3: Routine Blueprints

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = subModuleTab,
            containerColor = TacticalSurface,
            contentColor = CommandAmber,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
        ) {
            Tab(selected = subModuleTab == 0, onClick = { subModuleTab = 0 }, text = { Text("Auto-Routing", fontSize = 11.sp, fontWeight = FontWeight.Bold) })
            Tab(selected = subModuleTab == 1, onClick = { subModuleTab = 1 }, text = { Text("Escalation", fontSize = 11.sp, fontWeight = FontWeight.Bold) })
            Tab(selected = subModuleTab == 2, onClick = { subModuleTab = 2 }, text = { Text("Handshake", fontSize = 11.sp, fontWeight = FontWeight.Bold) })
            Tab(selected = subModuleTab == 3, onClick = { subModuleTab = 3 }, text = { Text("Blueprints", fontSize = 11.sp, fontWeight = FontWeight.Bold) })
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (subModuleTab) {
            0 -> AutoRoutingRulesModule(
                rules = routingRules,
                presets = presets,
                onSaveRule = onSaveRoutingRule,
                onDeleteRule = onDeleteRoutingRule,
                onApplyToMissions = onApplyRoutingRulesToAllMissions
            )
            1 -> EscalationPolicyModule(
                policies = escalationPolicies,
                onSavePolicy = onSaveEscalationPolicy,
                onDeletePolicy = onDeleteEscalationPolicy
            )
            2 -> HandshakeCalibratorModule(
                calibrations = handshakeCalibrations,
                onSaveCalibration = onSaveHandshakeCalibration,
                onDeleteCalibration = onDeleteHandshakeCalibration,
                onOpenSandbox = onOpenSandbox
            )
            3 -> RoutineBlueprintModule(
                blueprints = blueprints,
                presets = presets,
                onSaveBlueprint = onSaveMissionBlueprint,
                onDeleteBlueprint = onDeleteMissionBlueprint,
                onDeploy = onDeployBlueprintToQueue
            )
        }
    }
}

@Composable
private fun AutoRoutingRulesModule(
    rules: List<PresetRoutingRuleEntity>,
    presets: List<PersonalityPresetEntity>,
    onSaveRule: (PresetRoutingRuleEntity) -> Unit,
    onDeleteRule: (Long) -> Unit,
    onApplyToMissions: () -> Unit
) {
    var isAddRuleDialogOpen by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "CATEGORY & TIME-WINDOW AUTO-ROUTING",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CommandAmber
                    )
                    Text(
                        text = "Missions without explicit overrides automatically receive the matched preset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelTextSecondary
                    )
                }
                Button(
                    onClick = { isAddRuleDialogOpen = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CommandAmber, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Add Rule", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onApplyToMissions,
                colors = ButtonDefaults.buttonColors(containerColor = TacticalSurfaceElevated, contentColor = CommandAmber),
                border = BorderStroke(1.dp, CommandAmber),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Evaluate & Apply Rules to Current Queue", fontWeight = FontWeight.Bold)
            }
        }

        items(rules) { rule ->
            val targetPreset = presets.find { it.id == rule.targetPresetId }

            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(rule.ruleName, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = if (rule.isEnabled) MissionEmerald.copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (rule.isEnabled) "ACTIVE" else "DISABLED",
                                    color = if (rule.isEnabled) MissionEmerald else Color.Gray,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Category: ${rule.targetCategory} • Window: ${String.format("%02d:00", rule.startHour)} - ${String.format("%02d:00", rule.endHour)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = SteelTextSecondary
                        )
                        Text(
                            text = "Target Preset: ${targetPreset?.name ?: rule.targetPresetId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = CommandAmber,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Switch(
                        checked = rule.isEnabled,
                        onCheckedChange = { onSaveRule(rule.copy(isEnabled = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MissionEmerald,
                            checkedTrackColor = MissionEmerald.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }
    }

    if (isAddRuleDialogOpen) {
        var ruleName by remember { mutableStateOf("Evening Study Sprint") }
        var category by remember { mutableStateOf("Career Work") }
        var startHour by remember { mutableIntStateOf(18) }
        var endHour by remember { mutableIntStateOf(22) }
        var targetPresetId by remember { mutableStateOf(presets.firstOrNull()?.id ?: "preset_deep_work_focus") }

        AlertDialog(
            onDismissRequest = { isAddRuleDialogOpen = false },
            containerColor = TacticalSurfaceElevated,
            title = { Text("NEW AUTO-ROUTING RULE", color = CommandAmber, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = ruleName, onValueChange = { ruleName = it }, label = { Text("Rule Name") }, singleLine = true)
                    OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Target Category (or ALL)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startHour.toString(),
                            onValueChange = { startHour = it.toIntOrNull()?.coerceIn(0, 23) ?: 0 },
                            label = { Text("Start Hour (0-23)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endHour.toString(),
                            onValueChange = { endHour = it.toIntOrNull()?.coerceIn(0, 23) ?: 23 },
                            label = { Text("End Hour (0-23)") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text("Select Preset:", style = MaterialTheme.typography.bodySmall, color = SteelTextSecondary)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(presets) { p ->
                            FilterChip(
                                selected = targetPresetId == p.id,
                                onClick = { targetPresetId = p.id },
                                label = { Text(p.name, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveRule(
                            PresetRoutingRuleEntity(
                                ruleName = ruleName,
                                targetCategory = category,
                                startHour = startHour,
                                endHour = endHour,
                                targetPresetId = targetPresetId,
                                priorityOrder = 1,
                                isEnabled = true
                            )
                        )
                        isAddRuleDialogOpen = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CommandAmber, contentColor = Color.Black)
                ) {
                    Text("Save Rule", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { isAddRuleDialogOpen = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EscalationPolicyModule(
    policies: List<EscalationPolicyEntity>,
    onSavePolicy: (EscalationPolicyEntity) -> Unit,
    onDeletePolicy: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "ESCALATION & GRACE WINDOW MANAGER",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = CommandAmber
            )
            Text(
                text = "Calibrate audio volume ramp curves, re-alarm cadence intervals, and clean reset strike thresholds.",
                style = MaterialTheme.typography.bodySmall,
                color = SteelTextSecondary
            )
        }

        items(policies) { policy ->
            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(policy.policyName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Surface(
                            color = CommandAmber.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = policy.volumeRampCurve,
                                color = CommandAmber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Grace Window: ${policy.graceWindowMinutes} min • Repeat Interval: ${policy.repeatIntervalSeconds}s • Max Strikes: ${policy.maxStrikesBeforeRecovery}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun HandshakeCalibratorModule(
    calibrations: List<HandshakeCalibrationEntity>,
    onSaveCalibration: (HandshakeCalibrationEntity) -> Unit,
    onDeleteCalibration: (String) -> Unit,
    onOpenSandbox: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "HANDSHAKE CHALLENGE SEQUENCE CALIBRATOR",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CommandAmber
                    )
                    Text(
                        text = "Configure single or 2-step chained deliberate acknowledgment sequences.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelTextSecondary
                    )
                }
                Button(
                    onClick = onOpenSandbox,
                    colors = ButtonDefaults.buttonColors(containerColor = CommandAmber, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Live Test Sandbox", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }

        items(calibrations) { calib ->
            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(calib.profileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        text = "Step 1: ${calib.primaryHandshakeType} (${calib.holdSecondsRequired}s) $\rightarrow$ Step 2: ${calib.secondaryHandshakeType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CommandAmber,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Verification phrase: '${calib.requiredVoicePhrase}' • Math difficulty: ${calib.mathComplexityLevel}/5",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutineBlueprintModule(
    blueprints: List<MissionBlueprintEntity>,
    presets: List<PersonalityPresetEntity>,
    onSaveBlueprint: (MissionBlueprintEntity) -> Unit,
    onDeleteBlueprint: (String) -> Unit,
    onDeploy: (MissionBlueprintEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "MISSION TEMPLATES & ROUTINE BLUEPRINTS",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = CommandAmber
            )
            Text(
                text = "Pre-configured execution missions ready to deploy to today's queue with bound coaching presets.",
                style = MaterialTheme.typography.bodySmall,
                color = SteelTextSecondary
            )
        }

        items(blueprints) { bp ->
            val preset = presets.find { it.id == bp.assignedPresetId }

            Card(
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(bp.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                        Button(
                            onClick = { onDeploy(bp) },
                            colors = ButtonDefaults.buttonColors(containerColor = MissionEmerald, contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Deploy to Queue", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Category: ${bp.category} • Duration: ${bp.defaultDurationMinutes}m", style = MaterialTheme.typography.bodySmall, color = SteelTextSecondary)
                    Text(text = "First Step: ${bp.firstActionInstruction}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFCBD5E1))
                    Text(text = "Bound Preset: ${preset?.name ?: bp.assignedPresetId}", style = MaterialTheme.typography.bodySmall, color = CommandAmber, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// -------------------------------------------------------------
// HELPER: 5-AXIS PENTAGON RADAR COMPARISON CANVAS
// -------------------------------------------------------------
@Composable
fun PentagonRadarComparisonChart(
    presetAName: String,
    presetAFirmness: Int,
    presetAObnoxiousness: Int,
    presetAAlertIntensity: Int,
    presetAPersistence: Int,
    presetAHandshakeDifficulty: Int,
    presetBName: String = "Active Global",
    presetBFirmness: Int = 4,
    presetBObnoxiousness: Int = 2,
    presetBAlertIntensity: Int = 4,
    presetBPersistence: Int = 4,
    presetBHandshakeDifficulty: Int = 3,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = (minOf(size.width, size.height) / 2f) * 0.85f

            // 5 axis angles starting at top (-PI/2)
            val angles = listOf(
                -PI / 2.0,                  // Vertex 0: Top = Firmness
                -PI / 2.0 + (2 * PI / 5.0), // Vertex 1: Top-Right = Obnoxiousness
                -PI / 2.0 + (4 * PI / 5.0), // Vertex 2: Bottom-Right = Alert Intensity
                -PI / 2.0 + (6 * PI / 5.0), // Vertex 3: Bottom-Left = Persistence
                -PI / 2.0 + (8 * PI / 5.0)  // Vertex 4: Top-Left = Handshake Difficulty
            )

            // Draw concentric pentagon grids (levels 1..5)
            for (level in 1..5) {
                val r = radius * (level / 5f)
                val gridPath = Path()
                for (i in 0 until 5) {
                    val angle = angles[i]
                    val x = center.x + (r * cos(angle)).toFloat()
                    val y = center.y + (r * sin(angle)).toFloat()
                    if (i == 0) gridPath.moveTo(x, y) else gridPath.lineTo(x, y)
                }
                gridPath.close()
                drawPath(gridPath, color = Color(0x33475569), style = Stroke(width = 1.dp.toPx()))
            }

            // Draw radial spokes from center
            for (angle in angles) {
                val x = center.x + (radius * cos(angle)).toFloat()
                val y = center.y + (radius * sin(angle)).toFloat()
                drawLine(
                    color = Color(0x44475569),
                    start = center,
                    end = Offset(x, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Polygon B (Global Active Baseline - Emerald)
            val valuesB = listOf(
                presetBFirmness,
                presetBObnoxiousness,
                presetBAlertIntensity,
                presetBPersistence,
                presetBHandshakeDifficulty
            )
            val pathB = Path()
            for (i in 0 until 5) {
                val fraction = (valuesB[i].coerceIn(1, 5) / 5f)
                val r = radius * fraction
                val angle = angles[i]
                val x = center.x + (r * cos(angle)).toFloat()
                val y = center.y + (r * sin(angle)).toFloat()
                if (i == 0) pathB.moveTo(x, y) else pathB.lineTo(x, y)
            }
            pathB.close()
            drawPath(pathB, color = MissionEmerald.copy(alpha = 0.25f))
            drawPath(pathB, color = MissionEmerald, style = Stroke(width = 1.5.dp.toPx()))

            // Polygon A (Current / Target Preset - CommandAmber)
            val valuesA = listOf(
                presetAFirmness,
                presetAObnoxiousness,
                presetAAlertIntensity,
                presetAPersistence,
                presetAHandshakeDifficulty
            )
            val pathA = Path()
            for (i in 0 until 5) {
                val fraction = (valuesA[i].coerceIn(1, 5) / 5f)
                val r = radius * fraction
                val angle = angles[i]
                val x = center.x + (r * cos(angle)).toFloat()
                val y = center.y + (r * sin(angle)).toFloat()
                if (i == 0) pathA.moveTo(x, y) else pathA.lineTo(x, y)
            }
            pathA.close()
            drawPath(pathA, color = CommandAmber.copy(alpha = 0.35f))
            drawPath(pathA, color = CommandAmber, style = Stroke(width = 2.dp.toPx()))
        }

        // Radar Chart Legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(8.dp).background(CommandAmber, CircleShape))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = presetAName.take(16), style = MaterialTheme.typography.bodySmall, color = CommandAmber, fontSize = 10.sp)
            Spacer(modifier = Modifier.width(14.dp))
            Box(modifier = Modifier.size(8.dp).background(MissionEmerald, CircleShape))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = presetBName.take(16), style = MaterialTheme.typography.bodySmall, color = MissionEmerald, fontSize = 10.sp)
        }
    }
}

// -------------------------------------------------------------
// HELPER: AXIS SLIDER COMPONENT
// -------------------------------------------------------------
@Composable
private fun AxisSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int,
    max: Int,
    lowDesc: String,
    highDesc: String,
    testTag: String
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(text = "$value / $max", style = MaterialTheme.typography.bodySmall, color = CommandAmber, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min) - 1,
            colors = SliderDefaults.colors(
                thumbColor = CommandAmber,
                activeTrackColor = CommandAmber,
                inactiveTrackColor = Color(0xFF232A34)
            ),
            modifier = Modifier.testTag(testTag)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = lowDesc, fontSize = 9.sp, color = SteelTextSecondary)
            Text(text = highDesc, fontSize = 9.sp, color = SteelTextSecondary)
        }
    }
}

// -------------------------------------------------------------
// HELPER: INTERACTIVE 2-STEP HANDSHAKE SANDBOX DIALOG
// -------------------------------------------------------------
@Composable
private fun HandshakeSandboxDialog(
    primaryHandshake: String,
    chainedHandshake: String,
    onDismiss: () -> Unit
) {
    var stepIndex by remember { mutableIntStateOf(1) } // 1 = Primary, 2 = Secondary, 3 = Success
    var holdSecondsRemaining by remember { mutableIntStateOf(3) }
    var isHolding by remember { mutableStateOf(false) }
    var typedInput by remember { mutableStateOf("") }
    var sequenceTaps by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(isHolding) {
        if (isHolding && stepIndex == 1) {
            holdSecondsRemaining = 3
            while (holdSecondsRemaining > 0 && isHolding) {
                delay(1000L)
                if (isHolding) holdSecondsRemaining -= 1
            }
            if (isHolding && holdSecondsRemaining == 0) {
                if (chainedHandshake != "NONE") {
                    stepIndex = 2
                } else {
                    stepIndex = 3
                }
            }
        } else {
            holdSecondsRemaining = 3
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TacticalSurfaceElevated,
        title = {
            Text(
                text = "LIVE HANDSHAKE SANDBOX",
                color = CommandAmber,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (stepIndex == 3) "HANDSHAKE CHAIN VERIFIED" else "Step $stepIndex of ${if (chainedHandshake == "NONE") 1 else 2}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (stepIndex == 3) MissionEmerald else CommandAmber,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                when (stepIndex) {
                    1 -> {
                        Text(
                            text = "PRIMARY CHALLENGE: $primaryHandshake",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .clip(CircleShape)
                                .background(if (isHolding) CommandAmber else TacticalSurface)
                                .border(2.dp, CommandAmber, CircleShape)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            isHolding = true
                                            tryAwaitRelease()
                                            isHolding = false
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Fingerprint, contentDescription = null, tint = if (isHolding) Color.Black else CommandAmber, modifier = Modifier.size(36.dp))
                                Text(
                                    text = if (isHolding) "${holdSecondsRemaining}s" else "HOLD 3S",
                                    color = if (isHolding) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    2 -> {
                        Text(
                            text = "CHAINED SECONDARY STEP: $chainedHandshake",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        if (chainedHandshake == HandshakeType.TYPING.name) {
                            Text("Type commitment phrase 'SNAP TO':", style = MaterialTheme.typography.bodySmall, color = SteelTextSecondary)
                            OutlinedTextField(
                                value = typedInput,
                                onValueChange = {
                                    typedInput = it
                                    if (it.trim().equals("SNAP TO", ignoreCase = true)) {
                                        stepIndex = 3
                                    }
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MissionEmerald)
                            )
                        } else {
                            Text("Tap 4 tactical nodes ($sequenceTaps/4):", style = MaterialTheme.typography.bodySmall, color = SteelTextSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                (1..4).forEach { idx ->
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(if (sequenceTaps >= idx) MissionEmerald else TacticalSurface, CircleShape)
                                            .border(1.dp, MissionEmerald, CircleShape)
                                            .clickable {
                                                if (sequenceTaps == idx - 1) {
                                                    sequenceTaps += 1
                                                    if (sequenceTaps == 4) stepIndex = 3
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("$idx", color = if (sequenceTaps >= idx) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    3 -> {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MissionEmerald, modifier = Modifier.size(54.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Handshake sequence validated! Reveille alarm dismissed. 5-minute starter activated.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFE2E8F0),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CommandAmber, contentColor = Color.Black)
            ) {
                Text(if (stepIndex == 3) "Complete" else "Close Sandbox")
            }
        }
    )
}
