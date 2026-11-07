package com.command.editor

import androidx.lifecycle.SavedStateHandle
import com.command.data.DraftProblem
import com.command.data.MissionDraft
import com.command.data.MissionRepository
import com.command.data.OccurrenceRepository
import com.command.data.ProfileRepository
import com.command.data.RestWindowRepository
import com.command.data.SaveResult
import com.command.data.ScheduleDraft
import com.command.data.db.ActivityProfileRow
import com.command.data.db.CommandDb
import com.command.domain.dials.Flexibility
import com.command.domain.model.MissionSource
import com.command.domain.model.RestKind
import com.command.domain.model.StartKind
import com.command.domain.occurrence.OccurrenceState
import com.command.nav.Routes
import com.command.testing.FakeAlarmScheduler
import com.command.testing.FakeClock
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class MissionEditorViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var missions: MissionRepository
    @Inject lateinit var occurrences: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var clock: FakeClock
    @Inject lateinit var scheduler: FakeAlarmScheduler

    @Before
    fun setUp() = hilt.inject()

    private fun editor(id: String? = null) =
        MissionEditorViewModel(SavedStateHandle(if (id == null) emptyMap() else mapOf(Routes.ARG_ID to id)), missions, ProfileRepository(db), RestWindowRepository(db), clock)

    private fun MissionEditorViewModel.await(message: String, check: (EditorState) -> Boolean) = awaitUntil(message = message) { check(state.value) }

    private fun ready(id: String? = null): MissionEditorViewModel = editor(id).also { it.await("loaded") { s -> !s.loading } }

    @Test
    fun `a new mission starts at the next full hour, flexible, once`() {
        val s = ready().state.value
        assertEquals(LocalDate.of(2026, 11, 2), s.date)
        assertEquals(LocalTime.of(10, 0), s.time)
        assertEquals(Flexibility.FLEXIBLE, s.flexibility)
        assertEquals(RepeatKind.ONCE, s.repeat)
        assertFalse(s.editing)
        assertFalse(s.notFound)
    }

    @Test
    fun `saving with nothing filled in lists the problems, stores nothing, and typing clears them`() {
        val vm = ready()
        vm.save()
        vm.await("problems") { it.problems.isNotEmpty() && !it.busy }
        assertEquals(setOf(DraftProblem.TITLE_EMPTY, DraftProblem.FIRST_ACTION_EMPTY), vm.state.value.problems)
        assertEquals(0, runBlocking { db.missions().count() })

        vm.onTitle("Run")
        assertEquals(setOf(DraftProblem.FIRST_ACTION_EMPTY), vm.state.value.problems)
        vm.onFirstAction("Put on shoes")
        assertTrue(vm.state.value.problems.isEmpty())
    }

    @Test
    fun `a time that has passed is refused`() {
        val vm = ready()
        vm.onTitle("Run")
        vm.onFirstAction("Put on shoes")
        vm.onTime(LocalTime.of(8, 0)) // the clock stands at 09:00 today
        vm.save()
        vm.await("problem") { DraftProblem.IN_THE_PAST in it.problems }
        vm.onDate(LocalDate.of(2026, 11, 3))
        assertTrue("choosing another day clears it", DraftProblem.IN_THE_PAST !in vm.state.value.problems)
    }

    @Test
    fun `a filled-in mission is saved, scheduled and the editor finishes`() {
        val vm = ready()
        vm.onTitle("Run")
        vm.onFirstAction("Put on shoes")
        vm.onFlexibility(Flexibility.RIGID)
        vm.save()
        vm.await("finished") { it.finished }

        val mission = runBlocking { db.missions().all() }.single()
        assertEquals("Run", mission.title)
        assertEquals(Flexibility.RIGID, mission.flexibility)
        val occurrences = runBlocking { db.occurrences().forMission(mission.id) }
        assertEquals(1, occurrences.size)
        assertEquals(OccurrenceState.ARMED, occurrences.single().state)
        assertTrue(scheduler.armed.keys.any { it.startsWith("command://occurrence/") })
    }

    @Test
    fun `a weekly mission needs a day, and days toggle on and off`() {
        val vm = ready()
        vm.onTitle("Run")
        vm.onFirstAction("Put on shoes")
        vm.onRepeat(RepeatKind.WEEKLY)
        vm.save()
        vm.await("problem") { DraftProblem.NO_DAYS_SELECTED in it.problems }

        vm.onDay(DayOfWeek.MONDAY)
        vm.onDay(DayOfWeek.FRIDAY)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), vm.state.value.days)
        assertTrue(DraftProblem.NO_DAYS_SELECTED !in vm.state.value.problems)
        vm.onDay(DayOfWeek.MONDAY)
        assertEquals(setOf(DayOfWeek.FRIDAY), vm.state.value.days)

        vm.save()
        vm.await("finished") { it.finished }
        assertEquals(1, runBlocking { db.missions().count() })
    }

    @Test
    fun `an existing mission opens filled in, and saving changes it in place`() {
        val id = runBlocking {
            val draft = MissionDraft(
                title = "Standup", firstAction = "Open the notes", flexibility = Flexibility.RIGID,
                schedule = ScheduleDraft.Once(LocalDateTime.of(2026, 11, 2, 11, 0)), starterMinutes = 10,
            )
            (missions.save(draft) as SaveResult.Saved).missionId
        }
        val vm = ready(id)
        val s = vm.state.value
        assertTrue(s.editing)
        assertEquals("Standup", s.title)
        assertEquals("Open the notes", s.firstAction)
        assertEquals(Flexibility.RIGID, s.flexibility)
        assertEquals(LocalTime.of(11, 0), s.time)
        assertEquals(10, s.starterMinutes)

        vm.onTitle("Daily standup")
        vm.save()
        vm.await("finished") { it.finished }
        assertEquals(1, runBlocking { db.missions().count() })
        assertEquals("Daily standup", runBlocking { db.missions().get(id)!!.title })
    }

    @Test
    fun `a mission that no longer exists is reported, not shown empty`() {
        val vm = editor("no-such-mission")
        vm.await("not found") { it.notFound }
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `deleting hides the mission and the editor finishes`() {
        val id = runBlocking {
            (missions.save(MissionDraft(title = "Standup", firstAction = "Open the notes", flexibility = Flexibility.FLEXIBLE, schedule = ScheduleDraft.Once(LocalDateTime.of(2026, 11, 2, 11, 0)))) as SaveResult.Saved).missionId
        }
        val vm = ready(id)
        vm.delete()
        vm.await("finished") { it.finished }
        assertNull(runBlocking { missions.get(id) })
    }

    @Test
    fun `a test ring arms a real alarm with what is typed, says so once, and the mission is not saved`() {
        val vm = ready()
        vm.onTitle("Standup")
        vm.onFirstAction("Open the notes")
        vm.testRing()
        vm.await("message") { it.message == EditorMessage.TEST_STARTED }

        val hidden = runBlocking { db.missions().get(MissionRepository.TEST_MISSION_ID) }!!
        assertEquals("Standup", hidden.title)
        assertEquals("Open the notes", hidden.firstAction)
        assertEquals(1, runBlocking { db.occurrences().forMission(hidden.id) }.size)
        assertEquals("the typed mission itself is not stored", 1, runBlocking { db.missions().count() })

        vm.messageShown()
        assertNull(vm.state.value.message)
    }

    private fun filled(vm: MissionEditorViewModel) {
        vm.onTitle("Standup")
        vm.onFirstAction("Open the notes")
    }

    private fun stored(): com.command.data.db.MissionRow = runBlocking { db.missions().all().single { it.id != MissionRepository.TEST_MISSION_ID } }

    @Test
    fun `the editor offers every profile by name, and a new mission names none and is in the default category`() {
        val vm = ready()
        vm.await("profiles") { it.profiles.isNotEmpty() }
        assertEquals(
            listOf(ProfileChoice("builtin_nudge", "Nudge"), ProfileChoice("builtin_steady", "Steady"), ProfileChoice("builtin_command", "Command"), ProfileChoice("builtin_reveille_max", "Reveille MAX")),
            vm.state.value.profiles,
        )
        assertNull(vm.state.value.profileId)
        assertEquals("General", vm.state.value.category)
    }

    @Test
    fun `the profile and the category chosen in the editor are what is saved`() {
        val vm = ready()
        filled(vm)
        vm.onProfile("builtin_command")
        vm.onCategory("Work")
        vm.save()
        vm.await("finished") { it.finished }
        assertEquals("builtin_command", stored().profileId)
        assertEquals("Work", stored().category)
    }

    @Test
    fun `choosing automatic takes a profile away again`() {
        val id = runBlocking {
            val draft = MissionDraft(
                title = "Standup", firstAction = "Open the notes", flexibility = Flexibility.FLEXIBLE,
                schedule = ScheduleDraft.Once(LocalDateTime.of(2026, 11, 2, 11, 0)), profileId = "builtin_command",
            )
            (missions.save(draft) as SaveResult.Saved).missionId
        }
        val vm = ready(id)
        assertEquals("builtin_command", vm.state.value.profileId)
        vm.onProfile(null)
        vm.save()
        vm.await("finished") { it.finished }
        assertNull(stored().profileId)
    }

    @Test
    fun `the word kept for test alarms is refused as a category, and typing another clears the problem`() {
        val vm = ready()
        filled(vm)
        vm.onCategory(MissionRepository.TEST_CATEGORY)
        vm.save()
        vm.await("problem") { it.problems.isNotEmpty() && !it.busy }
        assertEquals(setOf(DraftProblem.CATEGORY_RESERVED), vm.state.value.problems)
        vm.onCategory("Work")
        assertTrue(vm.state.value.problems.isEmpty())
    }

    @Test
    fun `a test ring takes the category and the profile as they stand, so it rings as the saved mission would`() {
        runBlocking { db.activityProfiles().upsert(ActivityProfileRow("r", "Work", null, "builtin_command")) }
        val vm = ready()
        filled(vm)
        vm.onCategory("Work")
        vm.testRing()
        vm.await("message") { it.message == EditorMessage.TEST_STARTED }
        assertEquals("builtin_command", runBlocking { db.missions().get(MissionRepository.TEST_MISSION_ID)!!.profileId })

        vm.messageShown()
        clock.advance(60_000)
        vm.onProfile("builtin_nudge")
        vm.testRing()
        vm.await("message") { it.message == EditorMessage.TEST_STARTED }
        assertEquals("builtin_nudge", runBlocking { db.missions().get(MissionRepository.TEST_MISSION_ID)!!.profileId })
    }

    @Test
    fun `a title-only edit keeps the category and the profile`() {
        val id = runBlocking {
            val draft = MissionDraft(
                title = "Standup", firstAction = "Open the notes", flexibility = Flexibility.FLEXIBLE,
                schedule = ScheduleDraft.Once(LocalDateTime.of(2026, 11, 2, 11, 0)), category = "Work", profileId = "builtin_command",
            )
            (missions.save(draft) as SaveResult.Saved).missionId
        }
        val vm = ready(id)
        vm.onTitle("Daily standup")
        vm.save()
        vm.await("finished") { it.finished }

        val stored = runBlocking { db.missions().get(id)!! }
        assertEquals("Daily standup", stored.title)
        assertEquals("Work", stored.category)
        assertEquals("builtin_command", stored.profileId)
    }

    @Test
    fun `the test ring uses the mission's own profile, so the preview rings the way the mission will`() {
        val id = runBlocking {
            val draft = MissionDraft(
                title = "Standup", firstAction = "Open the notes", flexibility = Flexibility.RIGID,
                schedule = ScheduleDraft.Once(LocalDateTime.of(2026, 11, 2, 11, 0)), profileId = "builtin_reveille_max",
            )
            (missions.save(draft) as SaveResult.Saved).missionId
        }
        val vm = ready(id)
        vm.testRing()
        vm.await("message") { it.message == EditorMessage.TEST_STARTED }
        assertEquals("builtin_reveille_max", runBlocking { db.missions().get(MissionRepository.TEST_MISSION_ID)!!.profileId })
    }

    @Test
    fun `the editor knows the rest windows, and follows them while it is open`() {
        val vm = ready()
        assertEquals(emptyList<DayOfWeek>(), vm.state.value.restWarningDays.toList())
        val at = vm.state.value
        runBlocking { RestWindowRepository(db).save(emptyList(), setOf(at.date.dayOfWeek), at.time, at.time.plusMinutes(30), RestKind.LEISURE) }
        vm.await("the warning") { s -> s.restWarningDays == setOf(at.date.dayOfWeek) }
    }

    @Test
    fun `a mission inside a rest window is saved and armed like any other`() {
        val vm = ready()
        val at = vm.state.value
        runBlocking { RestWindowRepository(db).save(emptyList(), setOf(at.date.dayOfWeek), at.time, at.time.plusMinutes(30), RestKind.SLEEP) }
        vm.await("the warning") { s -> s.restWarningDays.isNotEmpty() }
        vm.onTitle("Stretch")
        vm.onFirstAction("Stand up")
        vm.save()
        vm.await("saved") { s -> s.finished }
        assertTrue(scheduler.armed.isNotEmpty())
    }

    @Test
    fun `a calendar series is warned about on a later day, though the mission holds only its first start`() {
        val monday = clock.nowMs() + 60 * 60_000L
        val mission = testMission(title = "Series").copy(source = MissionSource.DEVICE_CALENDAR, startKind = StartKind.ONE_OFF, startAtUtc = monday)
        runBlocking {
            db.missions().insert(mission)
            occurrences.createOccurrence(mission.id, monday, "UTC")
            occurrences.createOccurrence(mission.id, monday + 24 * 60 * 60_000L, "UTC")
            val at = ready(mission.id).state.value.comingAlarms.last()
            assertEquals(DayOfWeek.TUESDAY, at.dayOfWeek)
            RestWindowRepository(db).save(emptyList(), setOf(DayOfWeek.TUESDAY), at.toLocalTime(), at.toLocalTime().plusMinutes(30), RestKind.LEISURE)
        }
        val vm = ready(mission.id)
        vm.await("the warning") { s -> s.restWarningDays == setOf(DayOfWeek.TUESDAY) }
    }
}
