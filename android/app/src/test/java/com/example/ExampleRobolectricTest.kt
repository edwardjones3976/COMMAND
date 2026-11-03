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
    fun `every seeded voice pack resolves to a director voice with a style brief`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = VoiceDirectorEngine(context, CoroutineScope(Dispatchers.Unconfined))
        val packs = CommandRepository.defaultVoicePacks()

        val expected = mapOf(
            "pack_major_stone" to ("drill_sergeant" to false),
            "pack_coach_ray" to ("male_fitness" to false),
            "pack_eleanor_vance" to ("female_executive" to true),
            "pack_coach_maya" to ("motivational_coach" to true)
        )
        expected.forEach { (packId, idAndGender) ->
            val director = engine.resolveDirectorVoice(packs.first { it.id == packId })
            assertEquals(idAndGender.first, director.id)
            assertEquals(idAndGender.second, director.isFemale)
        }

        packs.forEach { pack ->
            val director = engine.resolveDirectorVoice(pack)
            assertTrue("${pack.id} needs a Gemini voice", director.geminiVoice.isNotBlank())
            assertTrue("${pack.id} needs a style brief", director.styleBrief.length > 40)

            val prompt = engine.buildTtsPrompt(director, pack, "Lock in the first action.")
            assertTrue(prompt.trimEnd().endsWith("Lock in the first action."))
            assertTrue(prompt.contains("## TRANSCRIPT"))
        }

        engine.shutdown()
    }
}
