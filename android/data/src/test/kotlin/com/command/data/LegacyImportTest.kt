package com.command.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.domain.dials.Flexibility
import com.command.domain.dials.Personality
import com.command.domain.dials.PresetOrigin
import com.command.domain.model.MissionSource
import com.command.domain.model.StartKind
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.OccurrenceState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyImportTest {

    private lateinit var context: Context
    private lateinit var f: Fixture
    private lateinit var importer: LegacyImporter
    private var legacy: LegacyDb? = null

    private val hour = 3_600_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        f = Fixture(context)
        importer = LegacyImporter(context, f.db, f.clock)
    }

    @After
    fun tearDown() {
        legacy?.close()
        f.close()
    }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    /** The previous app's first-run rows, byte for byte (title and first action), plus the two events it invented. */
    private fun LegacyDb.insertPreviousAppSeeds() {
        insert("missions", "title" to "Career Work — Deep Architecture Sprint", "firstActionPrompt" to "EYEBALLS! GET TO THE COMPUTER! OPEN THE PROJECT AND GIVE ME FIVE MINUTES RIGHT NOW!", "scheduledEpochMs" to f.t0 + hour, "status" to "SCHEDULED", "isFromGoogleCalendar" to true, "isRecurring" to true)
        insert("missions", "title" to "Product Strategy Review (Google Calendar Sync)", "firstActionPrompt" to "Open strategy doc and write the top 3 bullet points before joining.", "scheduledEpochMs" to f.t0 + hour, "status" to "SCHEDULED", "isFromGoogleCalendar" to true)
        insert("missions", "title" to "Physical Conditioning & Mobility Block", "firstActionPrompt" to "Put on training shoes and start the 5-minute dynamic warm-up.", "scheduledEpochMs" to f.t0 + hour, "status" to "SCHEDULED")
        insert("missions", "title" to "Daily Engineering Standup & Triage", "firstActionPrompt" to "Open GitHub notifications and write your 3 standup items now.", "scheduledEpochMs" to f.t0 + hour, "status" to "SCHEDULED", "isFromGoogleCalendar" to true)
        insert("missions", "title" to "Weekly Core Architecture Review", "firstActionPrompt" to "Open system architecture diagram and review pending interface proposals.", "scheduledEpochMs" to f.t0 + hour, "status" to "SCHEDULED", "isFromGoogleCalendar" to true)
    }

    private fun LegacyDb.insertUserMissions() {
        insert("missions", "title" to "Gym session", "firstActionPrompt" to "Put on your shoes", "category" to "Health", "scheduledEpochMs" to f.t0 + 2 * hour, "durationMinutes" to 45, "status" to "SCHEDULED", "isMissionLocked" to true)
        insert("missions", "title" to "Old dentist reminder", "firstActionPrompt" to "Call them", "scheduledEpochMs" to f.t0 - 5 * hour, "status" to "SCHEDULED")
        insert("missions", "title" to "Finished report", "firstActionPrompt" to "Send it", "scheduledEpochMs" to f.t0 + 3 * hour, "status" to "COMPLETED")
        insert("missions", "title" to "Board meeting", "firstActionPrompt" to "Open the deck", "scheduledEpochMs" to f.t0 + 4 * hour, "status" to "SCHEDULED", "isFromGoogleCalendar" to true)
    }

    private fun LegacyDb.insertDefaultStreakProfile() {
        insert("streak_profile", "id" to 1, "selectedPersonalityId" to "drill_sergeant", "instructionFirmness" to 5, "obnoxiousness" to 5, "alertIntensity" to 5, "persistence" to 4, "handshakeDifficulty" to 3, "scheduleRigidity" to 4, "recoveryStructure" to 4, "commandCredits" to 125)
    }

    @Test
    fun `the previous app's invented rows are skipped and the user's own missions come across`() = run {
        legacy = LegacyDb.create(context, wal = false).also {
            it.insertPreviousAppSeeds(); it.insertUserMissions(); it.insertDefaultStreakProfile()
        }
        val report = importer.importIfNeeded()!!
        assertEquals(3, report.imported)
        assertEquals("the one seed that was not flagged as a calendar event", 1, report.skippedSeed)
        assertEquals("four invented or imported calendar events plus the user's own calendar-flagged meeting", 5, report.droppedCalendar)
        assertEquals(setOf("Gym session", "Old dentist reminder", "Finished report"), f.db.missions().all().map { it.title }.toSet())
    }

    @Test
    fun `calendar-flagged missions are dropped and seeds never reappear`() = run {
        legacy = LegacyDb.create(context, wal = false).also {
            it.insertPreviousAppSeeds(); it.insertUserMissions(); it.insertDefaultStreakProfile()
        }
        importer.importIfNeeded()
        val titles = f.db.missions().all().map { it.title }
        assertFalse(titles.any { it.contains("Career Work") || it.contains("Standup") || it.contains("Board meeting") || it.contains("Conditioning") })
        assertEquals("nothing in the new database says it came from a calendar", emptyList<MissionSource>(), f.db.missions().all().map { it.source }.filter { it != MissionSource.MANUAL })
    }

    @Test
    fun `past and finished missions come across switched off with no occurrence`() = run {
        legacy = LegacyDb.create(context, wal = false).also { it.insertUserMissions(); it.insertDefaultStreakProfile() }
        val report = importer.importIfNeeded()!!
        assertEquals(3, report.imported)
        assertEquals("the past one and the completed one", 2, report.disabledPast)
        assertEquals("only the future scheduled mission gets an occurrence", 1, f.db.occurrences().count())
        val gym = f.db.occurrences().since(0).single()
        assertEquals(f.t0 + 2 * hour, gym.scheduledAtUtc)
        assertEquals(OccurrenceState.SCHEDULED, gym.state)
        assertEquals(listOf("Gym session"), f.db.missions().enabled().map { it.title })
        assertEquals(3, f.db.missions().count())
    }

    @Test
    fun `locked missions become rigid and everything is a manual one-off`() = run {
        legacy = LegacyDb.create(context, wal = false).also { it.insertUserMissions(); it.insertDefaultStreakProfile() }
        importer.importIfNeeded()
        val gym = f.db.missions().enabled().single()
        assertEquals(Flexibility.RIGID, gym.flexibility)
        assertEquals(MissionSource.MANUAL, gym.source)
        assertEquals(StartKind.ONE_OFF, gym.startKind)
        assertEquals(45, gym.durationMin)
        assertEquals("Put on your shoes", gym.firstAction)
        assertNull(gym.lockPolicy)
    }

    @Test
    fun `default dials create no profile, changed dials create a custom one that becomes active`() = run {
        legacy = LegacyDb.create(context, wal = false).also { it.insertDefaultStreakProfile() }
        assertFalse(importer.importIfNeeded()!!.customProfileCreated)
        assertEquals("\"builtin_steady\"", f.db.settings().get("active_profile_id"))
        legacy!!.close(); f.db.settings().remove("legacy_import_done")

        legacy = LegacyDb.create(context, wal = false).also {
            it.insert("streak_profile", "id" to 1, "selectedPersonalityId" to "supportive_coach", "instructionFirmness" to 2, "obnoxiousness" to 3, "alertIntensity" to 4, "persistence" to 1, "handshakeDifficulty" to 5, "scheduleRigidity" to 2, "recoveryStructure" to 3)
        }
        val report = importer.importIfNeeded()!!
        assertTrue(report.customProfileCreated)
        val active = f.db.settings().get("active_profile_id")!!.trim('"')
        val profile = f.db.profiles().get(active)!!
        assertEquals(PresetOrigin.CUSTOM, profile.origin)
        assertEquals(Personality.SUPPORTIVE_COACH, profile.personality)
        assertEquals(listOf(2, 3, 4, 1, 5, 2, 3), listOf(profile.firmness, profile.obnoxiousness, profile.alertIntensity, profile.followUpPersistence, profile.handshakeDifficulty, profile.scheduleRigidity, profile.recoveryStructure))
        assertFalse(profile.isBuiltin)
    }

    @Test
    fun `only user-made presets are imported`() = run {
        legacy = LegacyDb.create(context, wal = false).also {
            it.insertDefaultStreakProfile()
            it.insert("personality_presets", "id" to "builtin_1", "name" to "Reveille MAX", "basePersonalityId" to "drill_sergeant", "isPreloaded" to true, "instructionFirmness" to 5)
            it.insert("personality_presets", "id" to "mine_1", "name" to "My Monday", "basePersonalityId" to "minimalist", "isPreloaded" to false, "instructionFirmness" to 2, "obnoxiousness" to 1, "alertIntensity" to 3, "persistence" to 2, "handshakeDifficulty" to 2, "scheduleRigidity" to 1, "recoveryStructure" to 2)
        }
        val report = importer.importIfNeeded()!!
        assertEquals(1, report.presets)
        val mine = f.db.profiles().all().single { it.name == "My Monday" }
        assertEquals(Personality.MINIMALIST, mine.personality)
        assertEquals(2, mine.firmness)
        assertEquals(PresetOrigin.CUSTOM, mine.origin)
    }

    @Test
    fun `streaks, credits and badges leave nothing behind`() = run {
        legacy = LegacyDb.create(context, wal = false).also {
            it.insert("streak_profile", "id" to 1, "selectedPersonalityId" to "drill_sergeant", "instructionFirmness" to 5, "obnoxiousness" to 5, "alertIntensity" to 5, "persistence" to 4, "handshakeDifficulty" to 3, "scheduleRigidity" to 4, "recoveryStructure" to 4, "currentStreakDays" to 12, "commandCredits" to 999, "unlockedBadgesCsv" to "badge_3,badge_7")
        }
        importer.importIfNeeded()
        val settings = listOf("active_profile_id", "next_request_code", "alarm_route_policy", "ai_daily_budget", "legacy_import_done")
        for (key in settings) assertNotNull(key, f.db.settings().get(key))
        assertEquals(setOf("builtin_nudge", "builtin_steady", "builtin_command", "builtin_reveille_max"), f.db.profiles().all().map { it.id }.toSet())
    }

    @Test
    fun `rows still in the write-ahead log are imported`() = run {
        legacy = LegacyDb.create(context, wal = true).also { it.insertUserMissions(); it.insertDefaultStreakProfile() }
        // The connection stays open, so the rows live in the -wal file and have not been folded into the main file.
        assertTrue(File(legacy!!.file.path + "-wal").length() > 0)
        val report = importer.importIfNeeded()!!
        assertEquals(3, report.imported)
        assertEquals("Gym session", f.db.missions().enabled().single().title)
    }

    @Test
    fun `the legacy database is left exactly where it was`() = run {
        legacy = LegacyDb.create(context, wal = false).also { it.insertUserMissions(); it.insertDefaultStreakProfile() }
        val before = legacy!!.file.length()
        importer.importIfNeeded()
        assertTrue(legacy!!.file.isFile)
        assertTrue(legacy!!.file.length() >= before)
        val count = legacy!!.db.rawQuery("SELECT COUNT(*) FROM missions", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(4, count)
    }

    @Test
    fun `the import runs once`() = run {
        legacy = LegacyDb.create(context, wal = false).also { it.insertUserMissions(); it.insertDefaultStreakProfile() }
        assertNotNull(importer.importIfNeeded())
        assertNull(importer.importIfNeeded())
        assertEquals(3, f.db.missions().count())
    }

    @Test
    fun `an install with no previous data completes quietly`() = run {
        val report = importer.importIfNeeded()!!
        assertEquals(0, report.imported)
        assertNull(importer.importIfNeeded())
        assertEquals(0, f.db.missions().count())
    }

    @Test
    fun `the import is audited with counts only`() = run {
        legacy = LegacyDb.create(context, wal = false).also { it.insertPreviousAppSeeds(); it.insertUserMissions(); it.insertDefaultStreakProfile() }
        importer.importIfNeeded()
        val event = f.db.events().ofType(EventType.LEGACY_IMPORTED).single()
        val payload = Codec.decodePayload(event.payloadJson)
        assertEquals("3", payload["imported"])
        assertFalse(event.payloadJson.contains("Gym"))
    }
}
