package com.example.service

import android.util.Log
import com.example.data.PersonalityPresetEntity
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "CommandAiService"

enum class ExecutionPhase(val label: String, val shortDesc: String) {
    REVEILLE("Reveille Alarm", "Wake-up attention capture and prompt"),
    HANDSHAKE_CHALLENGE("Handshake Verification", "Deliberate acknowledgment directive"),
    FIVE_MINUTE_STARTER("5-Minute Starter", "Immediate single first-action launch"),
    ACCOUNTABILITY_CHECKIN("Accountability Check-In", "Mid-block progress and focus check"),
    RECOVERY_RESET("Recovery Reset", "Zero-guilt clean reset and scope adjustment")
}

class CommandAiService {

    private val scriptCache = ConcurrentHashMap<String, String>()

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.7f
            }
        )
    }

    suspend fun generatePhaseCoachingDirective(
        personalityId: String,
        preset: PersonalityPresetEntity?,
        missionTitle: String,
        category: String,
        firstActionPrompt: String = "",
        phase: ExecutionPhase = ExecutionPhase.REVEILLE
    ): Result<String> {
        val cacheKey = "${personalityId.lowercase()}_${phase.name}_${preset?.id ?: "def"}_${missionTitle.hashCode()}"
        scriptCache[cacheKey]?.let { cached ->
            return Result.success(cached)
        }

        val firmness = preset?.instructionFirmness ?: 5
        val obnoxiousness = preset?.obnoxiousness ?: 5
        val persistence = preset?.persistence ?: 4
        val customPersonaPrompt = preset?.customPersonaPrompt.orEmpty()
        val vocabRules = preset?.vocabularyRules.orEmpty()
        val catchphrases = preset?.catchphrasesCsv.orEmpty()

        val phaseTemplate = when (phase) {
            ExecutionPhase.REVEILLE -> preset?.reveillePhaseTemplate.orEmpty()
            ExecutionPhase.HANDSHAKE_CHALLENGE -> ""
            ExecutionPhase.FIVE_MINUTE_STARTER -> preset?.starterPhaseTemplate.orEmpty()
            ExecutionPhase.ACCOUNTABILITY_CHECKIN -> preset?.accountabilityPhaseTemplate.orEmpty()
            ExecutionPhase.RECOVERY_RESET -> preset?.recoveryPhaseTemplate.orEmpty()
        }

        val archetypeInstructions = when {
            personalityId.contains("drill", ignoreCase = true) || personalityId.contains("stone", ignoreCase = true) -> """
                Archetype: USMC Drill Sergeant (GySgt. Marcus Stone).
                Tone: Direct, loud, impatient, humorously obnoxious, commanding frog-voice bark.
                Firmness: $firmness/5. Obnoxiousness: $obnoxiousness/5.
                Style: Short staccato barks, all-caps emphasis on action words, imperative mood, zero tolerance for excuses.
                Example: 'EYEBALLS! SNAP TO RIGHT NOW! PUT THE PHONE DOWN, GET TO YOUR COMPUTER AND GIVE ME FIVE MINUTES! MOVE!'
            """.trimIndent()

            personalityId.contains("executive", ignoreCase = true) || personalityId.contains("eleanor", ignoreCase = true) -> """
                Archetype: Corporate Executive Director (Director Eleanor Vance).
                Tone: Velvet-textured authority, surgical intellect, bottom-line strategic clarity, articulate Fortune 500 executive presence.
                Firmness: $firmness/5. Obnoxiousness: $obnoxiousness/5.
                Style: Structured, decisive, zero filler words, treats commitments as high-stakes deliverables with calm command.
                Example: 'Let's look at the bottom line. Here is how we deliver on this, and we move forward with intention. Clear your workspace and execute step one.'
            """.trimIndent()

            personalityId.contains("fitness", ignoreCase = true) || personalityId.contains("jordan", ignoreCase = true) || personalityId.contains("supportive", ignoreCase = true) -> """
                Archetype: Personal & Life Coach (Coach Jordan Vance).
                Tone: Smooth, commanding rich baritone with a warm smile. Believes in easing clients' minds while keeping them focused on the tasks. Not a slow talker, but doesn't rush either.
                Firmness: $firmness/5. Obnoxiousness: $obnoxiousness/5.
                Style: Grounded, reassuring, steady momentum, breaks intimidation, focuses on taking the rep one solid step at a time.
                Example: 'Take a breath, reset your shoulders, and lock in. We are not rushing this rep—we are owning it. You already showed up; now let us execute one solid set.'
            """.trimIndent()

            personalityId.contains("motivational", ignoreCase = true) || personalityId.contains("maya", ignoreCase = true) -> """
                Archetype: Radiant Motivational Coach (Coach Maya Lin).
                Tone: Magnetic optimism, vibrant morning energy, rhythmic cadence, empowering high-stakes clarity.
                Firmness: $firmness/5. Obnoxiousness: $obnoxiousness/5.
                Style: Inspiring, dynamic, makes ambitious goals feel inevitable, high-frequency positivity and bold forward momentum.
                Example: 'Own your energy! Claim this morning and step into your power right now! Make ambitious goals feel completely inevitable!'
            """.trimIndent()

            personalityId.contains("accountability", ignoreCase = true) -> """
                Archetype: Accountability Partner.
                Tone: Conversational, steady, peer-to-peer, vigilant, relentless.
                Firmness: $firmness/5. Obnoxiousness: $obnoxiousness/5.
                Style: Calls out rationalizations directly, friendly but refuses to let the user slide.
                Example: 'Lock it in. You put this on your calendar for a reason. I am right here with you—open the workspace and let us knock out the first five minutes.'
            """.trimIndent()

            personalityId.contains("minimalist", ignoreCase = true) -> """
                Archetype: Minimalist.
                Tone: Ultra-terse, clinical, zero fluff, robotic telemetry precision.
                Firmness: $firmness/5. Obnoxiousness: 1/5.
                Style: Under 12 words maximum. State the time, the commitment, and the first action.
                Example: 'Time reached. Handshake confirmed. Begin task execution now.'
            """.trimIndent()

            else -> """
                Archetype: Custom Coaching Director (${preset?.name ?: "Custom"}).
                User Persona Guidance: $customPersonaPrompt
                Vocabulary Rules: $vocabRules
                Signature Catchphrases: $catchphrases
                Firmness: $firmness/5. Obnoxiousness: $obnoxiousness/5. Persistence: $persistence/5.
            """.trimIndent()
        }

        val phaseContext = when (phase) {
            ExecutionPhase.REVEILLE -> "Execution Phase: REVEILLE ALARM (Initial attention capture and commitment announcement)."
            ExecutionPhase.HANDSHAKE_CHALLENGE -> "Execution Phase: HANDSHAKE CHALLENGE (Direct user to complete acknowledgment challenge to dismiss alarm)."
            ExecutionPhase.FIVE_MINUTE_STARTER -> "Execution Phase: 5-MINUTE STARTER (Command user to perform first step '${firstActionPrompt.ifBlank { "first concrete action" }}' for 5 minutes)."
            ExecutionPhase.ACCOUNTABILITY_CHECKIN -> "Execution Phase: ACCOUNTABILITY CHECK-IN (Ask whether they started, are still working, or need a clean reset)."
            ExecutionPhase.RECOVERY_RESET -> "Execution Phase: RECOVERY RESET (Zero-guilt recovery guidance to downscale scope and restart momentum without shame)."
        }

        val prompt = """
            You are Project COMMAND's execution director.
            $archetypeInstructions
            $phaseContext
            Target Scheduled Commitment: '$missionTitle'
            Category: '$category'
            ${if (firstActionPrompt.isNotBlank()) "First 5-Min Action: '$firstActionPrompt'" else ""}
            ${if (phaseTemplate.isNotBlank()) "Phase Template Requirement: $phaseTemplate" else ""}

            CRITICAL RULES:
            1. Generate a single cohesive spoken coaching instruction (1-2 sentences, max 30 words).
            2. Match the exact tone, firmness, and linguistic personality specified.
            3. Return ONLY the spoken text, without markdown, quotes, emojis, or explanations.
        """.trimIndent()

        return try {
            val response = withTimeout(45_000L) {
                model.generateContent(prompt)
            }
            val text = response.text?.trim()?.replace("\"", "")
            if (!text.isNullOrBlank()) {
                scriptCache[cacheKey] = text
                Result.success(text)
            } else {
                val fallback = synthesizeDynamicMatrixFallback(personalityId, preset, phase, missionTitle, firstActionPrompt)
                scriptCache[cacheKey] = fallback
                Result.success(fallback)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Gemini generation timed out for $personalityId / $phase, using dynamic matrix fallback", e)
            val fallback = synthesizeDynamicMatrixFallback(personalityId, preset, phase, missionTitle, firstActionPrompt)
            scriptCache[cacheKey] = fallback
            Result.success(fallback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini generation failed for $personalityId / $phase, using dynamic matrix fallback", e)
            val fallback = synthesizeDynamicMatrixFallback(personalityId, preset, phase, missionTitle, firstActionPrompt)
            scriptCache[cacheKey] = fallback
            Result.success(fallback)
        }
    }

    suspend fun generateSixPersonalityAuditionComparison(
        missionTitle: String,
        category: String,
        phase: ExecutionPhase = ExecutionPhase.REVEILLE,
        customPreset: PersonalityPresetEntity? = null
    ): Map<String, String> = coroutineScope {
        val personalities = listOf(
            "drill_sergeant",
            "executive_director",
            "supportive_coach",
            "accountability_partner",
            "minimalist",
            "custom"
        )

        val deferredResults = personalities.map { pId ->
            async {
                val result = generatePhaseCoachingDirective(
                    personalityId = pId,
                    preset = if (pId == "custom") customPreset else null,
                    missionTitle = missionTitle,
                    category = category,
                    phase = phase
                )
                pId to (result.getOrNull() ?: synthesizeDynamicMatrixFallback(pId, customPreset, phase, missionTitle, ""))
            }
        }

        deferredResults.associate { it.await() }
    }

    /**
     * Dynamic Matrix Offline Fallback Engine:
     * Assembles context-aware scripts combining personality verbs, callout prefixes, and urgency tiers.
     */
    fun synthesizeDynamicMatrixFallback(
        personalityId: String,
        preset: PersonalityPresetEntity?,
        phase: ExecutionPhase,
        missionTitle: String,
        firstActionPrompt: String = ""
    ): String {
        val firmness = preset?.instructionFirmness ?: 5
        val obnoxiousness = preset?.obnoxiousness ?: 4
        val actionText = firstActionPrompt.ifBlank { "open your workspace and execute step one" }

        return when (personalityId.lowercase()) {
            "drill_sergeant" -> {
                val callout = if (obnoxiousness >= 4) "EYEBALLS! SNAP TO RIGHT NOW!" else "LISTEN UP!"
                val imperative = if (firmness >= 4) "NO NEGOTIATING ON MY WATCH!" else "LET'S GET OFF THE STARTING LINE!"
                when (phase) {
                    ExecutionPhase.REVEILLE -> "$callout $imperative TIME FOR '$missionTitle'! GET OFF THAT BED AND MOVE!"
                    ExecutionPhase.HANDSHAKE_CHALLENGE -> "HANDSHAKE VERIFICATION! PRESS AND HOLD THAT SCREEN OR THIS ALARM NEVER STOPS! MOVE!"
                    ExecutionPhase.FIVE_MINUTE_STARTER -> "HANDSHAKE LOCKED! NOW GIVE ME FIVE MINUTES OF UNINTERRUPTED EFFORT ON '$actionText'! MOVE, MOVE, MOVE!"
                    ExecutionPhase.ACCOUNTABILITY_CHECKIN -> "CHECK-IN! ARE YOU EXECUTING OR SITTING ON YOUR HANDS? REPORT OPERATIONAL STATUS!"
                    ExecutionPhase.RECOVERY_RESET -> "SHAKE IT OFF! NO CRYING IN MY SQUAD BAY! TAKE TWO MINUTES, CLEAR YOUR DESK, AND ATTACK IT AGAIN!"
                }
            }

            "executive_director" -> {
                val opener = if (firmness >= 4) "Commitment window locked." else "Scheduled milestone active."
                when (phase) {
                    ExecutionPhase.REVEILLE -> "$opener Initialize workspace for '$missionTitle' and eliminate all context switching."
                    ExecutionPhase.HANDSHAKE_CHALLENGE -> "Verify executive handshake token to formalize schedule alignment."
                    ExecutionPhase.FIVE_MINUTE_STARTER -> "Handshake confirmed. Execute five focused minutes on: $actionText."
                    ExecutionPhase.ACCOUNTABILITY_CHECKIN -> "Deliverable status check: Confirm active progress on '$missionTitle'."
                    ExecutionPhase.RECOVERY_RESET -> "Obstacle registered. Downscale scope to minimum viable deliverable and resume immediately."
                }
            }

            "supportive_coach", "fitness_coach", "jordan_vance" -> {
                when (phase) {
                    ExecutionPhase.REVEILLE -> "Take a breath, reset your shoulders, and lock in. Your coaching block for '$missionTitle' is live right now."
                    ExecutionPhase.HANDSHAKE_CHALLENGE -> "Hit that handshake with an easy smile—you've already done the hard part by showing up."
                    ExecutionPhase.FIVE_MINUTE_STARTER -> "Handshake locked in. We're not rushing this rep—we're owning it. Let's execute five smooth minutes on: $actionText."
                    ExecutionPhase.ACCOUNTABILITY_CHECKIN -> "Steady progress check. Reset your posture, breathe easy, and keep that momentum going."
                    ExecutionPhase.RECOVERY_RESET -> "No sweat at all. Drop the tension, take a clean five-minute reset, and ease right back into the set."
                }
            }

            "motivational_guru", "motivational_coach", "maya_lin" -> {
                when (phase) {
                    ExecutionPhase.REVEILLE -> "Eyes up! Own your energy and claim this morning! Your mission for '$missionTitle' starts right now!"
                    ExecutionPhase.HANDSHAKE_CHALLENGE -> "Hit that handshake with total conviction and step straight into your power!"
                    ExecutionPhase.FIVE_MINUTE_STARTER -> "Outstanding! Handshake locked! Dive straight into your first five minutes on: $actionText! Make this inevitable!"
                    ExecutionPhase.ACCOUNTABILITY_CHECKIN -> "Momentum check! Feel that unstoppable surge of progress! Stay locked on your greatness!"
                    ExecutionPhase.RECOVERY_RESET -> "Every champion resets without shame! Reclaim your focus, raise your energy, and restart cleanly right now!"
                }
            }

            "accountability_partner" -> {
                val partnerHook = if (firmness >= 4) "You put this on your calendar for a reason." else "I am right here with you."
                when (phase) {
                    ExecutionPhase.REVEILLE -> "Hey! '$missionTitle' is scheduled right now. $partnerHook Let's get off zero."
                    ExecutionPhase.HANDSHAKE_CHALLENGE -> "Confirm the handshake so we both know you are locked into this block."
                    ExecutionPhase.FIVE_MINUTE_STARTER -> "Handshake verified. Let us knock out this five-minute starter together: $actionText."
                    ExecutionPhase.ACCOUNTABILITY_CHECKIN -> "Mid-block check: Are you flowing or hit a blocker? Keep me in the loop."
                    ExecutionPhase.RECOVERY_RESET -> "We hit a bump, but we are not abandoning the day. Let us adjust scope and resume."
                }
            }

            "minimalist" -> {
                when (phase) {
                    ExecutionPhase.REVEILLE -> "'$missionTitle' active. Begin."
                    ExecutionPhase.HANDSHAKE_CHALLENGE -> "Verify handshake token."
                    ExecutionPhase.FIVE_MINUTE_STARTER -> "Handshake verified. Execute: $actionText."
                    ExecutionPhase.ACCOUNTABILITY_CHECKIN -> "Report execution status."
                    ExecutionPhase.RECOVERY_RESET -> "Reset logged. Resume task."
                }
            }

            else -> {
                val signature = preset?.catchphrasesCsv?.split(",")?.firstOrNull()?.trim()
                val prefix = if (!signature.isNullOrBlank()) "$signature " else ""
                when (phase) {
                    ExecutionPhase.REVEILLE -> "${prefix}Initiating '$missionTitle' immediately. Acknowledge readiness."
                    ExecutionPhase.HANDSHAKE_CHALLENGE -> "Acknowledge handshake token to unlock execution phase."
                    ExecutionPhase.FIVE_MINUTE_STARTER -> "Handshake confirmed. Launching starter block on: $actionText."
                    ExecutionPhase.ACCOUNTABILITY_CHECKIN -> "Accountability checkpoint: Confirm operational progress."
                    ExecutionPhase.RECOVERY_RESET -> "Clean recovery protocol active. Downscale deliverables and restart."
                }
            }
        }
    }

    suspend fun generateFullMultiStageBriefing(
        personalityId: String,
        preset: PersonalityPresetEntity? = null,
        missionTitle: String,
        category: String,
        firstActionPrompt: String = ""
    ): Map<ExecutionPhase, String> {
        val phases = listOf(
            ExecutionPhase.REVEILLE,
            ExecutionPhase.HANDSHAKE_CHALLENGE,
            ExecutionPhase.FIVE_MINUTE_STARTER,
            ExecutionPhase.ACCOUNTABILITY_CHECKIN,
            ExecutionPhase.RECOVERY_RESET
        )
        val result = mutableMapOf<ExecutionPhase, String>()
        for (phase in phases) {
            val directive = generatePhaseCoachingDirective(
                personalityId = personalityId,
                preset = preset,
                missionTitle = missionTitle,
                category = category,
                firstActionPrompt = firstActionPrompt,
                phase = phase
            ).getOrElse {
                synthesizeDynamicMatrixFallback(personalityId, preset, phase, missionTitle, firstActionPrompt)
            }
            result[phase] = directive
        }
        return result
    }

    fun getBlueprintFallback(personalityId: String, phase: ExecutionPhase, missionTitle: String): String {
        return synthesizeDynamicMatrixFallback(personalityId, null, phase, missionTitle, "")
    }

    suspend fun generateTacticalDirective(
        personalityName: String,
        firmness: Int,
        obnoxiousness: Int,
        missionTitle: String,
        category: String
    ): Result<String> {
        val pId = when {
            personalityName.contains("Drill", ignoreCase = true) -> "drill_sergeant"
            personalityName.contains("Executive", ignoreCase = true) -> "executive_director"
            personalityName.contains("Coach", ignoreCase = true) -> "supportive_coach"
            personalityName.contains("Accountability", ignoreCase = true) -> "accountability_partner"
            personalityName.contains("Minimalist", ignoreCase = true) -> "minimalist"
            else -> "custom"
        }
        return generatePhaseCoachingDirective(
            personalityId = pId,
            preset = null,
            missionTitle = missionTitle,
            category = category,
            firstActionPrompt = "",
            phase = ExecutionPhase.REVEILLE
        )
    }

    suspend fun generateFiveMinuteStarter(
        missionTitle: String,
        category: String
    ): Result<String> {
        return generatePhaseCoachingDirective(
            personalityId = "drill_sergeant",
            preset = null,
            missionTitle = missionTitle,
            category = category,
            firstActionPrompt = "",
            phase = ExecutionPhase.FIVE_MINUTE_STARTER
        )
    }

    fun streamRecoveryCoPilot(
        diagnosticReason: String,
        minutesRemaining: Int,
        missionTitle: String
    ): Flow<String> = flow {
        val prompt = """
            Project COMMAND Recovery Intelligence:
            The user reported obstacle '$diagnosticReason' for scheduled task '$missionTitle'.
            They have approximately $minutesRemaining minutes remaining in this commitment block.
            Provide a zero-guilt, high-velocity recovery micro-plan in 2 short bullet points:
            1. An immediate 5-minute micro-action to reset momentum right now.
            2. A realistic scope adjustment to protect the rest of the schedule.
            Be supportive, practical, and action-oriented. No shaming or guilt.
        """.trimIndent()

        try {
            withTimeout(45_000L) {
                model.generateContentStream(prompt).collect { chunk ->
                    val text = chunk.text.orEmpty()
                    if (text.isNotEmpty()) {
                        emit(text)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            emit("\n[Recovery plan generation timed out - taking default 5-minute micro reset]")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Recovery streaming failed", e)
            emit("Pause for 5 minutes. Downscale task scope to minimum viable deliverable and start cleanly.")
        }
    }
}
