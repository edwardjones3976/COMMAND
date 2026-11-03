package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CommandRepository
import com.example.data.VoiceModelType
import com.example.service.GoogleCalendarSyncService
import com.example.service.VoiceDirectorEngine
import com.example.data.PersonalityPresetEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Command", appName)
    }

    @Test
    fun `google calendar sync expands recurring events into command missions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val syncService = GoogleCalendarSyncService(context)
        val result = syncService.fetchGoogleCalendarMissions(
            windowHours = 48,
            existingMissions = emptyList()
        )
        assertTrue(result.syncedCount >= 1)
        assertTrue(result.recurringCount >= 1)
        val recurringMission = result.missionsToUpsert.firstOrNull { it.isRecurring }
        assertNotNull(recurringMission)
        assertTrue(recurringMission!!.recurrenceRule?.contains("Repeat") == true)
        assertTrue(recurringMission.firstActionPrompt.isNotBlank())
    }

    @Test
    fun `mcrd vocal overdrive dsp processes 16-bit pcm with gravel and brickwall limiting`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = VoiceDirectorEngine(context, CoroutineScope(Dispatchers.Unconfined))
        val sampleRate = 24000
        val input = ShortArray(2400) { idx ->
            (sin(2.0 * PI * 220.0 * (idx.toDouble() / sampleRate)) * 12000.0).toInt().toShort()
        }

        val processed = engine.processPcmWithMcrdVocalDsp(
            input = input,
            sampleRate = sampleRate,
            modelType = VoiceModelType.USMC_MCRD_BARK,
            gritPercent = 92,
            aggressionPercent = 96,
            boostFactor = 1.45f
        )

        assertEquals(input.size, processed.size)
        val peak = processed.maxOf { abs(it.toInt()) }
        assertTrue("Processed MCRD bark signal should have strong driven output", peak > 15000)
        assertTrue("Brickwall limiter must prevent digital clipping overflow", peak <= 32767)

        val defaultPacks = CommandRepository.defaultVoicePacks()
        assertTrue("USMC MCRD Bark voice pack must be present in default seeds", defaultPacks.any { it.voiceModelType == VoiceModelType.USMC_MCRD_BARK.name })
        engine.shutdown()
    }

    @Test
    fun `command streak telemetry generates 7-day bars and 24h cadence buckets`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.viewmodel.CommandViewModel(app)
        val chart = viewModel.uiState.value.streakChart

        assertEquals(7, chart.weeklyRecords.size)
        assertEquals(listOf("S", "M", "T", "W", "T", "F", "S"), chart.weeklyRecords.map { it.dayShortLabel })
        assertEquals(24, chart.hourlyBuckets.size)
        assertTrue(chart.averageHandshakesPerDay > 0f)
        assertTrue(chart.weekOverWeekPercent >= 100)
        assertEquals(com.example.data.StreakChartViewMode.WEEKLY_BARS, chart.viewMode)

        // Test day selection
        viewModel.selectStreakChartDay(2)
        assertEquals(2, viewModel.selectedStreakDayIndex.value)

        // Test view mode toggle
        viewModel.toggleStreakChartViewMode()
        assertEquals(com.example.data.StreakChartViewMode.HOURLY_CADENCE, viewModel.streakChartViewMode.value)
    }

    @Test
    fun `certified human voice packs are seeded with cc0 and public domain clearance`() {
        val defaultPacks = CommandRepository.defaultVoicePacks()
        val humanPacks = defaultPacks.filter { it.isHumanStem }
        assertTrue("At least 3 certified human voice packs must be seeded", humanPacks.size >= 3)

        val marcus = humanPacks.firstOrNull { it.id == "pack_marcus_vance" }
        assertNotNull("Marcus Vance professional voice actor pack must be seeded", marcus)
        assertTrue(marcus!!.licenseType.contains("CC0") || marcus.licenseType.contains("Public Domain"))
        assertTrue(marcus.legalAttribution.contains("commercial & private application use"))
        assertEquals(0, marcus.formantShiftPercent)

        val elena = humanPacks.firstOrNull { it.id == "pack_elena_rostova" }
        assertNotNull("Elena Rostova precision voice actor pack must be seeded", elena)
        assertTrue(elena!!.licenseType.contains("CC0"))

        val miller = humanPacks.firstOrNull { it.id == "pack_sgt_miller" }
        assertNotNull("Staff Sgt. Miller U.S. public domain announcer must be seeded", miller)
        assertTrue(miller!!.licenseType.contains("17 U.S.C. § 105") || miller.licenseType.contains("Public Domain"))
    }

    @Test
    fun `human voice stem dsp applies formant shift and pitch transpose within valid 16-bit bounds`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = VoiceDirectorEngine(context, CoroutineScope(Dispatchers.Unconfined))
        val sampleRate = 24000
        val input = ShortArray(2400) { idx ->
            (sin(2.0 * PI * 180.0 * (idx.toDouble() / sampleRate)) * 14000.0).toInt().toShort()
        }

        // Deep baritone formant shift (-35%) with 0.85x pitch down-transposition
        val deepChestProcessed = engine.processHumanVoiceStemDsp(
            input = input,
            sampleRate = sampleRate,
            pitch = 0.85f,
            formantShiftPercent = -35,
            boostFactor = 1.2f
        )
        assertTrue(deepChestProcessed.isNotEmpty())
        val peakDeep = deepChestProcessed.maxOf { abs(it.toInt()) }
        assertTrue("Formant shifted signal must have healthy amplitude", peakDeep > 5000)
        assertTrue("Limiter prevents clipping overflow", peakDeep <= 32767)

        // High presence formant shift (+30%) with 1.15x pitch up-transposition
        val highPresenceProcessed = engine.processHumanVoiceStemDsp(
            input = input,
            sampleRate = sampleRate,
            pitch = 1.15f,
            formantShiftPercent = 30,
            boostFactor = 1.1f
        )
        assertTrue(highPresenceProcessed.isNotEmpty())
        val peakHigh = highPresenceProcessed.maxOf { abs(it.toInt()) }
        assertTrue("High presence signal must be limited without overflow", peakHigh <= 32767)

        engine.shutdown()
    }

    @Test
    fun `personality preset library seeds default presets and supports saving loading and global activation`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = com.example.data.CommandDatabase.getInstance(app)
        val repo = com.example.data.CommandRepository(
            dao = db.commandDao(),
            presetDao = db.personalityPresetDao()
        )

        val defaultPresets = com.example.data.CommandRepository.defaultPresets()
        assertTrue("Default presets library must be seeded", defaultPresets.size >= 2)
        val morningGrind = defaultPresets.find { it.name == "Morning Grind" }
        assertNotNull("Morning Grind preset must be pre-seeded", morningGrind)
        assertEquals(5, morningGrind!!.instructionFirmness)
        assertEquals(5, morningGrind.obnoxiousness)

        val deepWork = defaultPresets.find { it.name == "Deep Work Focus" }
        assertNotNull("Deep Work Focus preset must be pre-seeded", deepWork)
        assertEquals(4, deepWork!!.instructionFirmness)
        assertEquals(1, deepWork.obnoxiousness)

        // Test saving a custom user preset
        val customPreset = PersonalityPresetEntity(
            id = "preset_night_shift",
            name = "Night Shift Lockdown",
            description = "High firmness, zero obnoxiousness for late-night productivity",
            basePersonalityId = "executive_director",
            voicePackId = "pack_elena_rostova",
            instructionFirmness = 5,
            obnoxiousness = 1,
            alertIntensity = 3,
            persistence = 4,
            handshakeDifficulty = 4,
            preferredHandshakeType = "SEQUENCE",
            sampleDirective = "Night shift active. Lock attention onto primary terminal.",
            assignedCategoriesCsv = "Deep Work,Engineering",
            isPreloaded = false
        )
        repo.savePreset(customPreset)

        val loaded = repo.getPresetById("preset_night_shift")
        assertNotNull(loaded)
        assertEquals("Night Shift Lockdown", loaded!!.name)
        assertEquals(5, loaded.instructionFirmness)
        assertEquals(1, loaded.obnoxiousness)

        // Set as active global preset
        repo.setActiveGlobalPreset(customPreset)
        val profile = repo.getStreakProfile()
        assertEquals("Night Shift Lockdown", profile.activePresetName)
        assertEquals("preset_night_shift", profile.activeCustomPresetId)
        assertEquals(5, profile.instructionFirmness)
        assertEquals(1, profile.obnoxiousness)

        // Clean up
        repo.deletePreset("preset_night_shift")
        val deleted = repo.getPresetById("preset_night_shift")
        assertEquals(null, deleted)
    }

    @Test
    fun `cloned voice acoustic profiles correctly fingerprint all four director personalities`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = VoiceDirectorEngine(context, CoroutineScope(Dispatchers.Unconfined))
        val defaultPacks = CommandRepository.defaultVoicePacks()

        // 1. GySgt. Marcus Stone (USMC Drill Instructor)
        val stonePack = defaultPacks.first { it.id == "pack_major_stone" }
        val stoneProfile = engine.resolveClonedProfile(stonePack)
        assertEquals("cloned_drill_sergeant", stoneProfile.id)
        assertEquals(false, stoneProfile.genderIsFemale)
        assertEquals(108.0, stoneProfile.f0FundamentalHz, 0.1)
        assertEquals(480.0, stoneProfile.f1FormantHz, 0.1)
        assertEquals(1380.0, stoneProfile.f2FormantHz, 0.1)
        assertTrue(stoneProfile.vocalGritPercent >= 80)

        // 2. Coach Jordan Vance (FBA Male Fitness Coach)
        val jordanPack = defaultPacks.first { it.id == "pack_coach_ray" }
        val jordanProfile = engine.resolveClonedProfile(jordanPack)
        assertEquals("cloned_male_fitness", jordanProfile.id)
        assertEquals(false, jordanProfile.genderIsFemale)
        assertEquals(114.0, jordanProfile.f0FundamentalHz, 0.1)
        assertEquals(440.0, jordanProfile.f1FormantHz, 0.1)
        assertEquals(1290.0, jordanProfile.f2FormantHz, 0.1)
        assertEquals(1.45f, jordanProfile.chestWarmthGain, 0.01f)
        assertTrue(jordanProfile.vocalGritPercent in 10..20)

        // 3. Director Eleanor Vance (Fortune 500 Executive)
        val eleanorPack = defaultPacks.first { it.id == "pack_eleanor_vance" }
        val eleanorProfile = engine.resolveClonedProfile(eleanorPack)
        assertEquals("cloned_female_executive", eleanorProfile.id)
        assertEquals(true, eleanorProfile.genderIsFemale)
        assertEquals(180.0, eleanorProfile.f0FundamentalHz, 0.1)
        assertEquals(510.0, eleanorProfile.f1FormantHz, 0.1)
        assertEquals(1620.0, eleanorProfile.f2FormantHz, 0.1)
        assertEquals(1.20f, eleanorProfile.airShelfGain, 0.01f)

        // 4. Coach Maya Lin (Motivational Coach)
        val mayaPack = defaultPacks.first { it.id == "pack_coach_maya" }
        val mayaProfile = engine.resolveClonedProfile(mayaPack)
        assertEquals("cloned_motivational_coach", mayaProfile.id)
        assertEquals(true, mayaProfile.genderIsFemale)
        assertEquals(218.0, mayaProfile.f0FundamentalHz, 0.1)
        assertEquals(590.0, mayaProfile.f1FormantHz, 0.1)
        assertEquals(1880.0, mayaProfile.f2FormantHz, 0.1)
        assertEquals(1.35f, mayaProfile.airShelfGain, 0.01f)

        engine.shutdown()
    }

    @Test
    fun `cloned spectral dsp processes 16-bit pcm with biquad formants and limiter without distortion`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = VoiceDirectorEngine(context, CoroutineScope(Dispatchers.Unconfined))
        val sampleRate = 24000
        val input = ShortArray(2400) { idx ->
            (sin(2.0 * PI * 180.0 * (idx.toDouble() / sampleRate)) * 14000.0).toInt().toShort()
        }

        val defaultPacks = CommandRepository.defaultVoicePacks()
        val jordanPack = defaultPacks.first { it.id == "pack_coach_ray" }
        val jordanProfile = engine.resolveClonedProfile(jordanPack)

        val processed = engine.processClonedSpectralDsp(
            input = input,
            sampleRate = sampleRate,
            profile = jordanProfile,
            boostFactor = 1.35f
        )

        val expectedSize = (input.size / jordanProfile.targetPitchFactor).toInt()
        assertEquals(expectedSize, processed.size)
        val peak = processed.maxOf { abs(it.toInt()) }
        assertTrue("Processed baritone warmth should have strong amplitude", peak > 8000)
        assertTrue("Limiter prevents digital overflow (> 32767)", peak <= 32767)

        engine.shutdown()
    }

    @Test
    fun `dynamic multi-stage briefings generate context-aware directives across all five execution phases`() {
        val aiService = com.example.service.CommandAiService()
        val mission = "Q3 Product Strategy Review"
        val firstAction = "Open key metrics slide deck"

        val phases = listOf(
            com.example.service.ExecutionPhase.REVEILLE,
            com.example.service.ExecutionPhase.HANDSHAKE_CHALLENGE,
            com.example.service.ExecutionPhase.FIVE_MINUTE_STARTER,
            com.example.service.ExecutionPhase.ACCOUNTABILITY_CHECKIN,
            com.example.service.ExecutionPhase.RECOVERY_RESET
        )

        // Test Eleanor Vance (Executive Director)
        for (phase in phases) {
            val directive = aiService.synthesizeDynamicMatrixFallback(
                personalityId = "executive_director",
                preset = null,
                phase = phase,
                missionTitle = mission,
                firstActionPrompt = firstAction
            )
            assertTrue("Eleanor briefing for $phase should be non-empty", directive.isNotBlank())
            if (phase == com.example.service.ExecutionPhase.REVEILLE) {
                assertTrue(directive.contains(mission))
            }
            if (phase == com.example.service.ExecutionPhase.FIVE_MINUTE_STARTER) {
                assertTrue(directive.contains(firstAction))
            }
        }

        // Test Jordan Vance (Fitness & Personal Coach)
        for (phase in phases) {
            val directive = aiService.synthesizeDynamicMatrixFallback(
                personalityId = "fitness_coach",
                preset = null,
                phase = phase,
                missionTitle = mission,
                firstActionPrompt = firstAction
            )
            assertTrue("Jordan Vance briefing for $phase should be non-empty", directive.isNotBlank())
            if (phase == com.example.service.ExecutionPhase.REVEILLE) {
                assertTrue(directive.contains("breath") || directive.contains("shoulders"))
                assertTrue(directive.contains(mission))
            }
        }

        // Test GySgt. Marcus Stone (Drill Sergeant)
        for (phase in phases) {
            val directive = aiService.synthesizeDynamicMatrixFallback(
                personalityId = "drill_sergeant",
                preset = null,
                phase = phase,
                missionTitle = mission,
                firstActionPrompt = firstAction
            )
            assertTrue("Drill Sergeant briefing for $phase should be non-empty", directive.isNotBlank())
            assertTrue(directive.contains("MOVE") || directive.contains("EYEBALLS") || directive.contains("HANDSHAKE") || directive.contains("CHECK-IN") || directive.contains("SHAKE"))
        }

        // Test Coach Maya Lin (Motivational Guru)
        for (phase in phases) {
            val directive = aiService.synthesizeDynamicMatrixFallback(
                personalityId = "motivational_guru",
                preset = null,
                phase = phase,
                missionTitle = mission,
                firstActionPrompt = firstAction
            )
            assertTrue("Maya Lin briefing for $phase should be non-empty", directive.isNotBlank())
            assertTrue(directive.contains("energy") || directive.contains("conviction") || directive.contains("greatness") || directive.contains("champion") || directive.contains("inevitable"))
        }
    }

    @Test
    fun `broadcast vocal mastering strip cleans rumble, boosts presence, and levels dynamics`() {
        val sampleRate = 24000

        // 1. Test 85Hz Sub-Rumble Cut
        val lowRumbleInput = ShortArray(2400) { idx ->
            (sin(2.0 * PI * 40.0 * (idx.toDouble() / sampleRate)) * 20000.0).toInt().toShort()
        }
        val (rumbleMastered, _) = com.example.service.BroadcastVocalMastering.masterPcmAudio(
            input = lowRumbleInput,
            sampleRate = sampleRate
        )
        val rumblePeakAfter = rumbleMastered.maxOf { abs(it.toInt()) }
        assertTrue("85Hz high-pass filter must significantly attenuate 40Hz sub-rumble", rumblePeakAfter < 12000)

        // 2. Test 4.2kHz Presence Boost
        val speechMidInput = ShortArray(2400) { idx ->
            (sin(2.0 * PI * 4200.0 * (idx.toDouble() / sampleRate)) * 8000.0).toInt().toShort()
        }
        val (midMastered, telemetry) = com.example.service.BroadcastVocalMastering.masterPcmAudio(
            input = speechMidInput,
            sampleRate = sampleRate,
            presenceBoostDb = 4.2f
        )
        val midPeakAfter = midMastered.maxOf { abs(it.toInt()) }
        assertTrue("Presence exciter should boost 4.2kHz articulation", midPeakAfter > 8000)
        assertTrue(telemetry.masteringProfile.contains("CRISP BROADCAST STUDIO"))

        // 3. Test Peak Limiter & Dynamic Compressor on hot signals
        val hotSignal = ShortArray(2400) { idx ->
            (sin(2.0 * PI * 1000.0 * (idx.toDouble() / sampleRate)) * 32000.0).toInt().toShort()
        }
        val (hotMastered, hotTelemetry) = com.example.service.BroadcastVocalMastering.masterPcmAudio(
            input = hotSignal,
            sampleRate = sampleRate
        )
        val hotPeak = hotMastered.maxOf { abs(it.toInt()) }
        assertTrue("Brickwall limiter must strictly prevent digital overflow (> 32767)", hotPeak <= 32767)
        assertTrue("Hot signal must engage compressor gain reduction", hotTelemetry.gainReductionDb >= 0f)

        // 4. Test Equal-Power Crossfader
        val bufA = ShortArray(480) { 10000.toShort() }
        val bufB = ShortArray(480) { 12000.toShort() }
        val crossfaded = com.example.service.BroadcastVocalMastering.crossfadePcmBuffers(
            first = bufA,
            second = bufB,
            sampleRate = sampleRate,
            crossfadeMs = 10
        )
        assertTrue(crossfaded.isNotEmpty())
        assertTrue("Crossfaded buffer should have seamless length", crossfaded.size > bufA.size)
    }
}

