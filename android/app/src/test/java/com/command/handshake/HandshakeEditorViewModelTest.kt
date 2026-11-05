package com.command.handshake

import android.os.Looper
import com.command.data.OccurrenceRepository
import com.command.data.ProfileRepository
import com.command.data.db.CommandDb
import com.command.domain.dials.ChainLimits
import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.DialEffects
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.PresetOrigin
import com.command.domain.dials.StepSpec
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/** Editing the handshake of a profile through the view model and a real database: what the draft does, when it can be saved, and what the ring asks for after. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class HandshakeEditorViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb

    private lateinit var vm: HandshakeEditorViewModel

    @Before
    fun setUp() {
        hilt.inject()
        vm = HandshakeEditorViewModel(profiles)
    }

    private fun open(id: String) {
        vm.open(id)
        awaitUntil(message = "the profile is shown") { vm.state.value is HandshakeEditorUiState.Editing }
    }

    private fun editing() = vm.state.value as HandshakeEditorUiState.Editing
    private fun modes() = editing().steps.map { it.mode }
    private fun stored(id: String) = runBlocking { profiles.get(id)!! }

    /** A profile of the person's own, copied from Steady. */
    private fun ownProfile(id: String): String {
        runBlocking {
            db.profiles().upsert(
                db.profiles().get("builtin_steady")!!.copy(id = id, name = "Mine", origin = PresetOrigin.CUSTOM, handshakeChainJson = null, isBuiltin = false),
            )
        }
        return id
    }

    private fun deleteProfile(id: String) = db.openHelper.writableDatabase.execSQL("DELETE FROM execution_profiles WHERE id = ?", arrayOf<Any>(id))

    /** Lets the main looper work for a moment, for a test of something that must not happen. */
    private fun pumpFor(millis: Long) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun awaitSaved(message: String = "saved") = awaitUntil(message = message) { (vm.state.value as? HandshakeEditorUiState.Editing)?.saved == true }

    @Test
    fun `a profile that asks for the standard handshake shows it, with nothing changed and nothing to save`() {
        open("builtin_steady")

        val e = editing()
        assertEquals("Steady", e.profileName)
        assertEquals(ChainOp.OR, e.op)
        assertEquals(listOf(HandshakeMode.HOLD), modes())
        assertEquals(DialEffects.defaultChain(2).steps, e.steps.map { it.toSpec() })
        assertTrue(e.usingStandard)
        assertFalse(e.storedUnusable)
        assertFalse(e.changed)
        assertFalse(e.canSave)
        assertTrue(e.issues.isEmpty())
    }

    @Test
    fun `a profile that is not there is not found`() {
        vm.open("no-such-profile")
        awaitUntil(message = "not found") { vm.state.value == HandshakeEditorUiState.NotFound }
    }

    @Test
    fun `adding a step offers the first way that is not in the chain yet, up to four steps`() {
        open("builtin_steady")

        vm.addStep()
        assertEquals(listOf(HandshakeMode.HOLD, HandshakeMode.CONFIRM_SEQUENCE), modes())
        vm.addStep()
        vm.addStep()
        assertEquals(listOf(HandshakeMode.HOLD, HandshakeMode.CONFIRM_SEQUENCE, HandshakeMode.TYPING, HandshakeMode.TWO_STEP), modes())
        assertFalse("four is the most", editing().canAddStep)

        vm.addStep()
        assertEquals("a fifth is not added", ChainLimits.MAX_STEPS, editing().steps.size)
        assertTrue(editing().changed)
        assertTrue(editing().issues.isEmpty())
    }

    @Test
    fun `the last step cannot be removed, and a removed one takes its place with the others`() {
        open("builtin_steady")
        vm.removeStep(editing().steps.single().key)
        assertEquals("a handshake needs one", 1, editing().steps.size)

        vm.addStep()
        vm.addStep()
        val middle = editing().steps[1].key
        vm.removeStep(middle)

        assertEquals(listOf(HandshakeMode.HOLD, HandshakeMode.TYPING), modes())
    }

    @Test
    fun `steps move up and down, and not past either end`() {
        open("builtin_steady")
        vm.addStep()
        vm.addStep()
        val keys = editing().steps.map { it.key }

        vm.moveStep(keys[2], up = true)
        assertEquals(listOf(keys[0], keys[2], keys[1]), editing().steps.map { it.key })
        vm.moveStep(keys[0], up = true)
        assertEquals("the first cannot go further up", listOf(keys[0], keys[2], keys[1]), editing().steps.map { it.key })
        vm.moveStep(keys[1], up = false)
        assertEquals("the last cannot go further down", listOf(keys[0], keys[2], keys[1]), editing().steps.map { it.key })
        vm.moveStep(keys[0], up = false)
        assertEquals(listOf(keys[2], keys[0], keys[1]), editing().steps.map { it.key })
    }

    @Test
    fun `a way that another step already uses, or that is only planned, cannot be chosen`() {
        open("builtin_steady")
        vm.addStep()
        val first = editing().steps[0].key

        vm.setMode(first, HandshakeMode.CONFIRM_SEQUENCE) // the second step has it
        assertEquals(HandshakeMode.HOLD, editing().steps[0].mode)
        vm.setMode(first, HandshakeMode.QR) // planned
        assertEquals(HandshakeMode.HOLD, editing().steps[0].mode)
        vm.setMode(first, HandshakeMode.TYPING)
        assertEquals(HandshakeMode.TYPING, editing().steps[0].mode)
    }

    @Test
    fun `a step that becomes a hold starts with a press of three seconds, and one that stops being a hold has no press`() {
        open("builtin_command")
        val key = editing().steps.single().key
        assertEquals(0, editing().steps.single().holdSeconds)

        vm.setMode(key, HandshakeMode.HOLD)
        assertEquals(3, editing().steps.single().holdSeconds)
        vm.setMode(key, HandshakeMode.TWO_STEP)
        assertEquals(0, editing().steps.single().holdSeconds)
    }

    @Test
    fun `tries, time and press stay inside the limits, however often they are asked for more or less`() {
        open("builtin_steady")
        val key = editing().steps.single().key

        repeat(20) { vm.changeTries(key, +1) }
        assertEquals(ChainLimits.MAX_TRIES, editing().steps.single().tries)
        repeat(20) { vm.changeTries(key, -1) }
        assertEquals(ChainLimits.MIN_TRIES, editing().steps.single().tries)

        repeat(40) { vm.changeTimeout(key, +1) }
        assertEquals(ChainLimits.MAX_TIMEOUT_SECONDS, editing().steps.single().timeoutSec)
        repeat(40) { vm.changeTimeout(key, -1) }
        assertEquals(ChainLimits.MIN_TIMEOUT_SECONDS, editing().steps.single().timeoutSec)
        vm.changeTimeout(key, +1)
        assertEquals("ten seconds at a time", ChainLimits.MIN_TIMEOUT_SECONDS + 10, editing().steps.single().timeoutSec)

        repeat(20) { vm.changeHold(key, +1) }
        assertEquals(ChainLimits.MAX_HOLD_SECONDS, editing().steps.single().holdSeconds)
        repeat(20) { vm.changeHold(key, -1) }
        assertEquals(ChainLimits.MIN_HOLD_SECONDS, editing().steps.single().holdSeconds)
        assertTrue("whatever was done, the chain can still be saved", editing().issues.isEmpty())
    }

    @Test
    fun `the length of a press is only for a hold`() {
        open("builtin_command")
        val key = editing().steps.single().key

        vm.changeHold(key, +1)

        assertEquals(0, editing().steps.single().holdSeconds)
    }

    @Test
    fun `saving writes the draft as the profile's own, and the next ring asks for it`() {
        open("builtin_steady")
        vm.addStep()
        vm.setOp(ChainOp.SEQUENCE)
        vm.changeTries(editing().steps[1].key, +2)
        val draft = ChainSpec(editing().op, editing().steps.map { it.toSpec() })
        assertTrue(editing().canSave)

        vm.save()

        awaitUntil(message = "saved") { (vm.state.value as? HandshakeEditorUiState.Editing)?.saved == true }
        val e = editing()
        assertFalse(e.changed)
        assertFalse(e.usingStandard)
        assertFalse(e.canSave)
        assertEquals(draft, stored("builtin_steady").own)

        // A mission that names no profile, which is every mission the app can make, has the active one's (Steady, until another is chosen).
        val mission = testMission()
        runBlocking { db.missions().insert(mission) }
        val occurrence = runBlocking { repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence }
        assertEquals(draft, runBlocking { repo.handshakeOf(occurrence) }.chain)
    }

    @Test
    fun `a change after saving clears the note and can be saved again`() {
        open("builtin_steady")
        vm.addStep()
        vm.save()
        awaitUntil(message = "saved") { (vm.state.value as? HandshakeEditorUiState.Editing)?.saved == true }

        vm.changeTries(editing().steps[0].key, -1)

        assertFalse(editing().saved)
        assertTrue(editing().canSave)
        vm.save()
        awaitUntil(message = "saved again") { (vm.state.value as? HandshakeEditorUiState.Editing)?.saved == true }
    }

    @Test
    fun `a draft that is changed back to what is in force has nothing to save`() {
        open("builtin_steady")
        val key = editing().steps.single().key

        vm.changeTries(key, +1)
        assertTrue(editing().canSave)
        vm.changeTries(key, -1)

        assertFalse(editing().changed)
        assertFalse(editing().canSave)
    }

    @Test
    fun `going back to the standard clears the chain of its own and shows the standard one`() {
        open("builtin_steady")
        vm.addStep()
        vm.save()
        awaitUntil(message = "saved") { (vm.state.value as? HandshakeEditorUiState.Editing)?.saved == true }

        vm.useStandard()

        awaitUntil(message = "standard") { (vm.state.value as? HandshakeEditorUiState.Editing)?.usingStandard == true }
        assertNull(stored("builtin_steady").own)
        assertEquals(DialEffects.defaultChain(2).steps, editing().steps.map { it.toSpec() })
        assertFalse(editing().changed)
    }

    @Test
    fun `something stored that cannot be used is said so, shows the standard chain, and saving replaces it`() {
        runBlocking { db.profiles().upsert(db.profiles().get("builtin_command")!!.copy(handshakeChainJson = "not a chain")) }
        open("builtin_command")

        assertTrue(editing().storedUnusable)
        assertTrue(editing().usingStandard)
        assertEquals(DialEffects.defaultChain(3).steps, editing().steps.map { it.toSpec() })

        vm.addStep()
        vm.save()

        awaitUntil(message = "saved") { (vm.state.value as? HandshakeEditorUiState.Editing)?.saved == true }
        assertFalse(editing().storedUnusable)
        assertEquals(2, stored("builtin_command").own!!.steps.size)
    }

    @Test
    fun `opening another profile shows that one's handshake`() {
        open("builtin_steady")
        vm.addStep()

        vm.open("builtin_reveille_max")
        awaitUntil(message = "the other profile") { (vm.state.value as? HandshakeEditorUiState.Editing)?.profileName == "Reveille MAX" }

        assertEquals(ChainOp.AND, editing().op)
        assertEquals(listOf(HandshakeMode.CONFIRM_SEQUENCE, HandshakeMode.TYPING), modes())
        assertFalse(editing().changed)
    }

    @Test
    fun `the same step is the same step after it has been moved, whatever is changed in it`() {
        open("builtin_steady")
        vm.addStep()
        val second = editing().steps[1].key

        vm.moveStep(second, up = true)
        vm.changeTries(second, +1)

        assertEquals(second, editing().steps[0].key)
        assertEquals(DialEffects.defaultChain(2).steps.single().maxRetries, editing().steps[1].tries)
        assertEquals(HandshakeMode.CONFIRM_SEQUENCE, editing().steps[0].mode)
    }

    // --- how the steps combine ------------------------------------------------------------------------------------------

    @Test
    fun `changing only how the steps combine is a change that can be saved, and it is what is stored`() {
        open("builtin_reveille_max") // the numbers and typing, all needed, in any order
        assertEquals(ChainOp.AND, editing().op)

        vm.setOp(ChainOp.SEQUENCE)

        assertTrue("the steps are the same, and that is a change", editing().changed)
        assertTrue(editing().canSave)
        vm.save()
        awaitSaved()
        val own = stored("builtin_reveille_max").own!!
        assertEquals(ChainOp.SEQUENCE, own.op)
        assertEquals("and the steps are as they were", DialEffects.defaultChain(4).steps, own.steps)
    }

    @Test
    fun `how the steps combine, changed and changed back, leaves nothing to save`() {
        open("builtin_reveille_max")

        vm.setOp(ChainOp.OR)
        assertTrue(editing().canSave)
        vm.setOp(ChainOp.AND)

        assertFalse(editing().changed)
        assertFalse(editing().canSave)
    }

    @Test
    fun `with one step left how the steps combine is not shown, so it is not a change either`() {
        open("builtin_steady")
        vm.addStep()
        vm.setOp(ChainOp.SEQUENCE)
        vm.removeStep(editing().steps[1].key)

        assertEquals(1, editing().steps.size)
        assertEquals("the draft keeps it", ChainOp.SEQUENCE, editing().op)
        assertFalse("but with one step it decides nothing", editing().changed)
        assertFalse(editing().canSave)
    }

    // --- what a save says ------------------------------------------------------------------------------------------------

    @Test
    fun `after a save the chain in force is the one that was saved, so a change and its reverse leave nothing to save`() {
        open("builtin_steady")
        vm.addStep()
        vm.save()
        awaitSaved()
        val second = editing().steps[1].key

        vm.changeTries(second, +1)
        assertTrue(editing().canSave)
        vm.changeTries(second, -1)

        assertFalse("it is what was saved", editing().changed)
        assertFalse(editing().canSave)
    }

    @Test
    fun `a change made while a save is being written stays a change to save, and the note does not say it was kept`() {
        open("builtin_steady")
        vm.addStep()
        val second = editing().steps[1].key
        val written = ChainSpec(editing().op, editing().steps.map { it.toSpec() })
        val triesWritten = editing().steps[1].tries

        vm.save()
        vm.changeTries(second, +1) // before the answer of the write has been taken in

        awaitUntil(message = "the answer of the write has been taken in") { !editing().usingStandard }
        assertEquals("what was written is the draft of the moment of the tap", written, stored("builtin_steady").own)
        val now = editing()
        assertEquals("the screen shows the newer draft", triesWritten + 1, now.steps[1].tries)
        assertFalse("which was not written, so it is not 'saved'", now.saved)
        assertTrue("and there is something to save", now.canSave)

        vm.save()
        awaitSaved("saved again")
        assertEquals(triesWritten + 1, stored("builtin_steady").own!!.steps[1].maxRetries)
    }

    @Test
    fun `saving with nothing changed writes nothing, so the profile stays on the standard handshake of its difficulty`() {
        open("builtin_steady")

        vm.save()
        pumpFor(300)

        assertNull(stored("builtin_steady").own)
        assertFalse(editing().saved)
        assertTrue(editing().usingStandard)
    }

    @Test
    fun `a profile that is gone when its draft is saved is not found`() {
        val id = ownProfile("mine")
        open(id)
        vm.addStep()
        deleteProfile(id)

        vm.save()

        awaitUntil(message = "not found") { vm.state.value == HandshakeEditorUiState.NotFound }
    }

    @Test
    fun `a profile that is gone when the standard handshake is asked for is not found`() {
        val id = ownProfile("mine")
        open(id)
        deleteProfile(id)

        vm.useStandard()

        awaitUntil(message = "not found") { vm.state.value == HandshakeEditorUiState.NotFound }
    }

    @Test
    fun `a profile that was asked for first does not take the place of the one asked for after it`() {
        vm.open("builtin_steady")
        vm.open("builtin_reveille_max") // before the first answer has been taken in
        awaitUntil(message = "the second profile") { (vm.state.value as? HandshakeEditorUiState.Editing)?.profileName == "Reveille MAX" }

        pumpFor(300) // anything the first one left behind has arrived by now

        assertEquals("Reveille MAX", editing().profileName)
        assertEquals(ChainOp.AND, editing().op)
        // The first profile was not even built, which is what a draft's keys show: they count the steps that were ever drawn up, and this profile's are the first two.
        // (Whichever answer arrives last wins the screen, so the shown profile alone would not tell: only a profile that was never built leaves no trace in the keys.)
        assertEquals(listOf(0, 1), editing().steps.map { it.key })
    }

    // --- the steps themselves --------------------------------------------------------------------------------------------

    @Test
    fun `choosing the way a step has already changes nothing, so a press that was lengthened keeps its length`() {
        open("builtin_steady")
        val key = editing().steps.single().key
        vm.changeHold(key, +4)
        val lengthened = editing().steps.single().holdSeconds
        assertTrue("it was lengthened", lengthened > 3)

        vm.setMode(key, HandshakeMode.HOLD)

        assertEquals(lengthened, editing().steps.single().holdSeconds)
    }

    // --- which profiles alarms are fired with ---------------------------------------------------------------------------

    @Test
    fun `the profile alarms are fired with is in use, another is not, and a mission can name one`() {
        open("builtin_steady")
        assertTrue("the active profile", editing().inUse)

        vm.open("builtin_command")
        awaitUntil(message = "command") { (vm.state.value as? HandshakeEditorUiState.Editing)?.profileName == "Command" }
        assertFalse("no mission names it", editing().inUse)

        runBlocking { db.missions().insert(testMission(profileId = "builtin_reveille_max")) }
        vm.open("builtin_reveille_max")
        awaitUntil(message = "reveille max") { (vm.state.value as? HandshakeEditorUiState.Editing)?.profileName == "Reveille MAX" }
        assertTrue("a mission names it", editing().inUse)
    }

    @Test
    fun `a profile that no alarm uses can be saved, and the state says that nothing asks for it yet`() {
        open("builtin_command")
        vm.addStep()

        vm.save()

        awaitSaved()
        assertFalse(editing().inUse)
        assertEquals(2, stored("builtin_command").own!!.steps.size)
    }

    @Test
    fun `whether alarms use the profile follows the database while the editor is open, so the line at the top and the note of a save are true now`() {
        open("builtin_command") // no alarm uses it
        assertFalse(editing().inUse)

        val mission = testMission(profileId = "builtin_command")
        runBlocking { db.missions().insert(mission) } // a mission names it now
        awaitUntil(message = "the editor knows") { editing().inUse }
        vm.addStep()
        vm.save()
        awaitSaved()
        assertTrue("the note of the save follows too", editing().inUse)

        runBlocking { db.missions().softDelete(mission.id, FakeAlarmModule.T0) } // and it is gone again
        awaitUntil(message = "the editor knows") { !editing().inUse }
        assertTrue("the draft that was saved is not touched by it", editing().saved)
    }

    @Test
    fun `and going back to the standard shows what is true then`() {
        open("builtin_command") // no alarm uses it
        vm.addStep()
        vm.save()
        awaitSaved()
        runBlocking { db.missions().insert(testMission(profileId = "builtin_command")) } // a mission names it now
        awaitUntil(message = "the editor knows") { editing().inUse }

        vm.useStandard()
        awaitUntil(message = "back on the standard") { (vm.state.value as? HandshakeEditorUiState.Editing)?.restored == true }

        assertTrue(editing().inUse)
    }

    @Test
    fun `and the other way round when a mission that named the profile is gone`() {
        val mission = testMission(profileId = "builtin_command")
        runBlocking { db.missions().insert(mission) }
        open("builtin_command")
        assertTrue(editing().inUse)
        vm.addStep()
        vm.save()
        awaitSaved()
        runBlocking { db.missions().softDelete(mission.id, FakeAlarmModule.T0) }
        awaitUntil(message = "the editor knows") { !editing().inUse }

        vm.useStandard()
        awaitUntil(message = "back on the standard") { (vm.state.value as? HandshakeEditorUiState.Editing)?.restored == true }

        assertFalse(editing().inUse)
    }

    @Test
    fun `going back to the standard says so until the next change, and it is not a save`() {
        open("builtin_steady")
        vm.addStep()
        vm.save()
        awaitSaved()
        assertFalse(editing().restored)

        vm.useStandard()
        awaitUntil(message = "back on the standard") { (vm.state.value as? HandshakeEditorUiState.Editing)?.restored == true }
        assertFalse("a reset is not a save", editing().saved)
        assertTrue(editing().usingStandard)

        vm.setMode(editing().steps[0].key, editing().steps[0].mode) // the way it has already: nothing changed
        assertTrue(editing().restored)

        vm.changeTries(editing().steps[0].key, +1)
        assertFalse("a change takes the note away", editing().restored)
    }

    @Test
    fun `a profile that was just opened has neither note, and an edit after going back to the standard takes the note of the reset away`() {
        open("builtin_steady")
        assertFalse(editing().saved)
        assertFalse(editing().restored)
        vm.addStep()
        vm.save()
        awaitSaved()
        vm.useStandard()
        awaitUntil(message = "back on the standard") { (vm.state.value as? HandshakeEditorUiState.Editing)?.restored == true }
        vm.addStep()
        assertFalse("the first edit takes the note away", editing().restored)

        vm.save()

        awaitSaved()
        assertFalse(editing().restored)
    }

    @Test
    fun `a save whose answer comes after another profile was opened does not touch that profile`() {
        open("builtin_steady")
        vm.addStep()
        val gate = CompletableDeferred<Unit>()
        vm.beforeSaveWrite = { gate.await() }
        vm.save()

        vm.open("builtin_reveille_max")
        awaitUntil(message = "the other profile is shown") { (vm.state.value as? HandshakeEditorUiState.Editing)?.profileName == "Reveille MAX" }
        gate.complete(Unit)
        awaitUntil(message = "the write for the first profile has landed") { stored("builtin_steady").own != null }
        pumpFor(300) // the answer of that save, if it were applied, would have reached the state by now

        assertEquals("Reveille MAX", editing().profileName)
        assertFalse("nothing was saved here", editing().saved)
        assertFalse("and nothing here has changed", editing().changed)
        assertTrue("it still asks for the standard handshake", editing().usingStandard)
    }

    @Test
    fun `a save whose write comes after the return to the standard does not leave the note of the reset up over a draft that is not the standard`() {
        open("builtin_steady")
        vm.addStep()
        val gate = CompletableDeferred<Unit>()
        vm.beforeSaveWrite = { gate.await() } // a slow write: it will come after the person has gone back to the standard

        vm.save()
        vm.useStandard()
        awaitUntil(message = "back on the standard") { (vm.state.value as? HandshakeEditorUiState.Editing)?.restored == true }
        assertFalse("nothing is changed in the draft, which is the standard", editing().changed)
        gate.complete(Unit)

        awaitUntil(message = "the answer of the save") { (vm.state.value as? HandshakeEditorUiState.Editing)?.changed == true }
        assertFalse("the save came last, so the profile is not back on the standard", editing().restored)
        assertFalse("and the draft is not what was written", editing().saved)
        assertEquals("what the save wrote is in force", 2, stored("builtin_steady").own!!.steps.size)
    }

    @Test
    fun `an edit that changes nothing leaves the note of the save, and one that changes something takes it away`() {
        open("builtin_steady")
        vm.addStep()
        vm.save()
        awaitSaved()
        val first = editing().steps[0]

        vm.setMode(first.key, first.mode) // the way it has already
        vm.setOp(editing().op) // the combination it has already
        vm.removeStep(9_999) // a step that is not there
        vm.changeTries(9_999, +1)
        vm.moveStep(9_999, up = true)

        assertTrue("nothing changed, so the draft is still what was written", editing().saved)
        assertFalse(editing().changed)

        vm.changeTries(first.key, +1)
        assertFalse(editing().saved)
    }

    @Test
    fun `a step that is added starts with 3 tries of 90 seconds, and an added hold with the short press of 3 seconds`() {
        open("builtin_steady")

        vm.addStep() // the numbers
        vm.addStep() // typing
        vm.addStep() // the two-step

        for (step in editing().steps.drop(1)) {
            assertEquals("${step.mode} tries", 3, step.tries)
            assertEquals("${step.mode} time", 90, step.timeoutSec)
            assertEquals("${step.mode} has no press", 0, step.holdSeconds)
        }
        // And a hold that is added: remove the first one, so that a hold can be added again.
        vm.removeStep(editing().steps[0].key)
        vm.addStep()
        val hold = editing().steps.last()
        assertEquals(HandshakeMode.HOLD, hold.mode)
        assertEquals(StepSpec(HandshakeMode.HOLD, 3, 90, 3), hold.toSpec())
    }

    // --- a way that is only planned is judged by the way that will be asked ---------------------------------------------

    private fun openWithPlanned(vararg modes: HandshakeMode) {
        val spec = ChainSpec(ChainOp.OR, modes.map { StepSpec(it, 3, 90, if (it == HandshakeMode.HOLD) 3 else 0) })
        runBlocking { profiles.saveChain("builtin_steady", spec) }
        open("builtin_steady")
    }

    @Test
    fun `adding steps to a chain with a planned way never repeats the way it runs as`() {
        openWithPlanned(HandshakeMode.QR) // runs as typing

        vm.addStep()
        vm.addStep()
        vm.addStep()

        assertEquals("typing is not added, because the first step runs as typing", listOf(HandshakeMode.QR, HandshakeMode.HOLD, HandshakeMode.CONFIRM_SEQUENCE, HandshakeMode.TWO_STEP), modes())
        assertTrue("and the draft has no problem that the person did nothing to cause", editing().issues.isEmpty())
    }

    @Test
    fun `and a way cannot be chosen for a step when another step runs as that way`() {
        openWithPlanned(HandshakeMode.QR, HandshakeMode.HOLD)
        val hold = editing().steps[1]

        vm.setMode(hold.key, HandshakeMode.TYPING) // the first step runs as typing

        assertEquals(HandshakeMode.HOLD, editing().steps[1].mode)
        assertTrue(editing().issues.isEmpty())
    }

    @Test
    fun `the planned step itself can be changed to the way it runs as`() {
        openWithPlanned(HandshakeMode.QR, HandshakeMode.HOLD)

        vm.setMode(editing().steps[0].key, HandshakeMode.TYPING)

        assertEquals(listOf(HandshakeMode.TYPING, HandshakeMode.HOLD), modes())
    }

    @Suppress("unused")
    private val unused = StepSpec(HandshakeMode.HOLD, 1, 10)
}
