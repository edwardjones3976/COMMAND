package com.command.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.domain.dials.Dial
import com.command.domain.dials.Personality
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.voice.BuiltInVoicePacks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the voice screen reads and writes: every pack with its trim, and a trim that stays in amendment 2's range and reaches the next ring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileVoiceTest {

    private lateinit var f: Fixture
    private lateinit var profiles: ProfileRepository

    @Before
    fun setUp() {
        f = Fixture(ApplicationProvider.getApplicationContext<Context>())
        profiles = ProfileRepository(f.db)
    }

    @After fun tearDown() { f.close() }

    private suspend fun trimOf(pack: String) = f.db.voicePacks().get(pack)!!.loudnessTrimDb

    @Test
    fun `the screen is given all ten packs with what the ring would use of them, and the profile's own choice`() = runBlocking {
        val voice = profiles.observeVoice("builtin_steady").first()!!

        assertEquals(BuiltInVoicePacks.all.map { it.id }.toSet(), voice.packs.map { it.id }.toSet())
        assertEquals(f.db.profiles().get("builtin_steady")!!.voicePackId, voice.voicePackId)
        val stone = voice.packs.single { it.id == "pack_major_stone" }
        assertEquals(4.5f, stone.trimDb, 0f)
        assertEquals(Personality.DRILL_SERGEANT, stone.personality)
        assertEquals(0.86f, stone.voice.pitch, 0f)
        assertEquals(1.28f, stone.voice.rate, 0f)
        assertEquals("en-US", stone.voice.languageTag)
    }

    @Test
    fun `a profile that does not exist has no voice to show`() = runBlocking {
        assertNull(profiles.observeVoice("nobody").first())
    }

    @Test
    fun `what the screen shows follows a change of the trim, of the choice and of the alert intensity`() = runBlocking {
        profiles.chooseDirector("pack_coach_maya", "builtin_steady")
        profiles.setVoiceTrim("pack_coach_maya", 7f)
        profiles.setDial("builtin_steady", Dial.ALERT_INTENSITY, 2)

        val voice = profiles.observeVoice("builtin_steady").first()!!
        assertEquals("pack_coach_maya", voice.voicePackId)
        assertEquals(7f, voice.packs.single { it.id == "pack_coach_maya" }.trimDb, 0f)
        assertEquals(2, voice.dials.dials.alertIntensity)
    }

    @Test
    fun `the trim runs from nothing to eight decibels, and anything else writes nothing`() = runBlocking {
        val before = trimOf("pack_sgt_miller")

        for (bad in listOf(-0.5f, 8.5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse("$bad", profiles.setVoiceTrim("pack_sgt_miller", bad))
            assertEquals(before, trimOf("pack_sgt_miller"), 0f)
        }

        assertTrue(profiles.setVoiceTrim("pack_sgt_miller", 0f))
        assertEquals(0f, trimOf("pack_sgt_miller"), 0f)
        assertTrue(profiles.setVoiceTrim("pack_sgt_miller", BuiltInVoicePacks.MAX_TRIM_DB))
        assertEquals(8f, trimOf("pack_sgt_miller"), 0f)
    }

    @Test
    fun `a trim is the trim of one pack, and a pack that does not exist is told apart`() = runBlocking {
        val others = f.db.voicePacks().all().filter { it.id != "pack_sgt_miller" }

        assertTrue(profiles.setVoiceTrim("pack_sgt_miller", 6.5f))

        assertEquals(others, f.db.voicePacks().all().filter { it.id != "pack_sgt_miller" })
        assertFalse(profiles.setVoiceTrim("pack_nobody", 2f))
    }

    @Test
    fun `the trim set on the screen reaches the level of the next ring of a profile with that pack`() = runBlocking {
        profiles.chooseDirector("pack_aris_thorne", "builtin_steady")
        profiles.setVoiceTrim("pack_aris_thorne", 7.5f)

        val mission = f.mission(profileId = "builtin_steady")
        val o = f.repo.createOccurrence(mission.id, f.clock.nowMs() + 60_000, "UTC").occurrence
        f.repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        f.repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SYSTEM)

        assertEquals(7.5f, f.repo.speechLevelOf(f.repo.get(o.id)!!).trimDb, 0f)
    }

    /** Starts [first] and [second] in order with the first held up, as a slow disk might, and waits for both. */
    private fun firstHeldUp(first: suspend () -> Unit, second: suspend () -> Unit) = runBlocking {
        val hold = CompletableDeferred<Unit>()
        var calls = 0
        profiles.beforeVoiceWrite = { if (calls++ == 0) hold.await() }
        val writes = listOf(launch(Dispatchers.Unconfined) { first() }, launch(Dispatchers.Unconfined) { second() })
        // Time enough for the second to overtake, if anything lets it.
        delay(300)
        hold.complete(Unit)
        writes.joinAll()
    }

    @Test
    fun `the trim set last is the one that stays, however slow the one before it was`() {
        firstHeldUp({ profiles.setVoiceTrim("pack_coach_ray", 1f) }, { profiles.setVoiceTrim("pack_coach_ray", 5f) })

        assertEquals(5f, runBlocking { trimOf("pack_coach_ray") }, 0f)
    }

    @Test
    fun `the voice chosen last is the one that stays, however slow the one before it was`() {
        firstHeldUp({ profiles.chooseDirector("pack_coach_maya", "builtin_steady") }, { profiles.chooseDirector("pack_coach_ray", "builtin_steady") })

        assertEquals("pack_coach_ray", runBlocking { f.db.profiles().get("builtin_steady")!!.voicePackId })
    }
}
