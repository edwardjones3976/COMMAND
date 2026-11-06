package com.command.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.data.db.ProfileRow
import com.command.data.db.SettingRow
import com.command.data.db.VoicePackRow
import com.command.domain.dials.Personality
import com.command.domain.model.EventSource
import com.command.domain.model.SettingKeys
import com.command.domain.model.VoiceEngine
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.voice.SpeechLevel
import com.command.domain.voice.SpeechVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Choosing a director (#10, #20): the words and the voice change together, one profile is active, and the next ring is theirs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileActivationTest {

    private lateinit var f: Fixture
    private lateinit var profiles: ProfileRepository

    @Before
    fun setUp() {
        f = Fixture(ApplicationProvider.getApplicationContext<Context>())
        profiles = ProfileRepository(f.db)
    }

    @After fun tearDown() { f.close() }

    private fun run(block: suspend CoroutineScope.() -> Unit) = runBlocking { block() }

    private suspend fun fired(profileId: String? = null): com.command.data.db.OccurrenceRow {
        val m = f.mission(profileId = profileId)
        val o = f.repo.createOccurrence(m.id, f.clock.nowMs() + 60_000, "UTC").occurrence
        f.repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        f.repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SYSTEM)
        f.clock.advance(120_000)
        return f.repo.get(o.id)!!
    }

    @Test
    fun `choosing a director sets the personality and the pack of the active profile, and of nothing else`() = run {
        val others = f.db.profiles().all().filter { it.id != "builtin_steady" }
        val before = f.db.profiles().get("builtin_steady")!!

        val result = profiles.chooseDirector("pack_major_stone")

        assertEquals(DirectorResult.Chosen("builtin_steady", Personality.DRILL_SERGEANT, "pack_major_stone"), result)
        assertEquals(before.copy(personality = Personality.DRILL_SERGEANT, voicePackId = "pack_major_stone"), f.db.profiles().get("builtin_steady"))
        assertEquals(others, f.db.profiles().all().filter { it.id != "builtin_steady" })
    }

    @Test
    fun `a director can be chosen for a named profile, which then need not be the active one`() = run {
        assertEquals(
            DirectorResult.Chosen("builtin_nudge", Personality.MINIMALIST, "pack_aris_thorne"),
            profiles.chooseDirector("pack_aris_thorne", "builtin_nudge"),
        )
        assertEquals(Personality.MINIMALIST, f.db.profiles().get("builtin_nudge")!!.personality)
        assertEquals("the active profile keeps its own", Personality.EXECUTIVE_DIRECTOR, f.db.profiles().get("builtin_steady")!!.personality)
    }

    @Test
    fun `a pack that is not there, or no profile to give it to, changes nothing and says which`() = run {
        val before = f.db.profiles().all()
        assertEquals(DirectorResult.NoSuchPack, profiles.chooseDirector("pack_gone"))
        assertEquals(DirectorResult.NoProfile, profiles.chooseDirector("pack_major_stone", "builtin_ghost"))
        f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"\""))
        assertEquals(DirectorResult.NoProfile, profiles.chooseDirector("pack_major_stone"))
        assertEquals(before, f.db.profiles().all())
    }

    @Test
    fun `when the write is refused halfway neither the words nor the voice have changed`() = run {
        val before = f.db.profiles().get("builtin_steady")!!
        // Refuses the row once the pack would change; a write of the personality on its own would get through this and stay.
        f.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER no_pack BEFORE UPDATE OF voice_pack_id ON execution_profiles WHEN NEW.voice_pack_id = 'pack_major_stone' " +
                "BEGIN SELECT RAISE(ABORT, 'refused'); END",
        )
        assertTrue(runCatching { profiles.chooseDirector("pack_major_stone") }.isFailure)
        assertEquals(before, f.db.profiles().get("builtin_steady"))
    }

    @Test
    fun `however choices interleave, the words and the voice a profile ends with are of one director`() = run {
        val packs = listOf("pack_major_stone", "pack_eleanor_vance", "pack_coach_maya", "pack_sgt_brody", "pack_iron_sentinel")
        val personalityOf = f.db.voicePacks().all().associate { it.id to it.defaultPersonality }
        val writers = (0 until 40).map { i ->
            async(Dispatchers.Default) { profiles.chooseDirector(packs[i % packs.size]) }
        }
        val results = writers.awaitAll()

        assertTrue(results.all { it is DirectorResult.Chosen })
        val row: ProfileRow = f.db.profiles().get("builtin_steady")!!
        assertEquals(personalityOf[row.voicePackId], row.personality)
    }

    @Test
    fun `exactly one profile is active, after any choice of it, and one that is not there cannot be`() = run {
        assertTrue(profiles.activate("builtin_command"))
        assertEquals(setOf("builtin_command"), profiles.observeInUse().first())
        val writers = f.db.profiles().all().map { p -> async(Dispatchers.Default) { profiles.activate(p.id) } }
        assertTrue(writers.awaitAll().all { it })
        assertEquals("one id, however they interleaved", 1, profiles.observeInUse().first().size)

        val setting = f.db.settings().get(SettingKeys.ACTIVE_PROFILE_ID)
        assertFalse(profiles.activate("builtin_ghost"))
        assertEquals("the setting did not move", setting, f.db.settings().get(SettingKeys.ACTIVE_PROFILE_ID))
    }

    @Test
    fun `the setting is written the way the ring reads it`() = run {
        profiles.activate("builtin_command")
        assertEquals("\"builtin_command\"", f.db.settings().get(SettingKeys.ACTIVE_PROFILE_ID))
    }

    @Test
    fun `the next ring of a mission with no profile of its own has the director's words, voice and trim, which is issue 10`() = run {
        profiles.chooseDirector("pack_major_stone")
        val ring = fired()

        assertEquals(Personality.DRILL_SERGEANT, f.repo.handshakeOf(ring).personality)
        assertEquals(SpeechVoice("en-US", 0.86f, 1.28f, female = false), f.repo.speechVoiceOf(ring))
        assertEquals(4.5f, f.repo.speechLevelOf(ring).trimDb, 0f)
    }

    @Test
    fun `a mission's own profile keeps its director`() = run {
        profiles.chooseDirector("pack_major_stone")
        val ring = fired("builtin_nudge")
        assertEquals(Personality.SUPPORTIVE_COACH, f.repo.handshakeOf(ring).personality)
        assertEquals(1.10f, f.repo.speechVoiceOf(ring).rate, 0f)
    }

    @Test
    fun `a ring already sounding keeps the voice it was fired with, whoever is chosen meanwhile`() = run {
        val ring = fired()
        val voice = f.repo.speechVoiceOf(ring)
        val level = f.repo.speechLevelOf(ring)
        val plan = f.repo.handshakeOf(ring)

        profiles.chooseDirector("pack_major_stone")

        assertEquals(voice, f.repo.speechVoiceOf(ring))
        assertEquals(level, f.repo.speechLevelOf(ring))
        assertEquals(plan, f.repo.handshakeOf(ring))
    }

    @Test
    fun `before it fires a ring follows the profile, and a pack that is edited is heard`() = run {
        f.db.voicePacks().upsert(VoicePackRow("p", "Mine", VoiceEngine.LOCAL_TTS, null, null, "fr-CA", 1.1f, 0.9f, 7f, Personality.CUSTOM, true, null))
        profiles.chooseDirector("p")
        val m = f.mission()
        val o = f.repo.createOccurrence(m.id, f.clock.nowMs() + 60_000, "UTC").occurrence

        assertEquals("a person's own pack does not say what kind of voice it wants", SpeechVoice("fr-CA", 1.1f, 0.9f, null), f.repo.speechVoiceOf(o))
        assertEquals(SpeechLevel(f.db.profiles().get("builtin_steady")!!.alertIntensity, 7f), f.repo.speechLevelOf(o))

        f.db.voicePacks().upsert(VoicePackRow("p", "Mine", VoiceEngine.LOCAL_TTS, null, null, "fr-CA", 1.05f, 0.9f, 7f, Personality.CUSTOM, true, null))
        assertEquals(1.05f, f.repo.speechVoiceOf(o).pitch, 0f)
    }

    @Test
    fun `no pack, a pack that is gone, and a ring from before voices were pinned all still have a voice`() = run {
        val noPack = f.db.profiles().get("builtin_steady")!!.copy(id = "none", name = "None", voicePackId = null, isBuiltin = false)
        f.db.profiles().upsert(noPack)
        f.db.profiles().upsert(noPack.copy(id = "gone", name = "Gone", voicePackId = "pack_gone"))
        assertEquals(SpeechVoice.DEFAULT, f.repo.speechVoiceOf(fired("none")))
        assertEquals(SpeechVoice.DEFAULT, f.repo.speechVoiceOf(fired("gone")))

        // Fired before this version: the loudness is pinned and the voice is not, so it follows the profile.
        val old = fired()
        val session = Codec.decodeSession(old.sessionJson)
        val raw = Codec.encodeSession(session.copy(voiceLanguageTag = null, voicePitch = null, voiceRate = null, voiceFemale = null))
        f.db.occurrences().compareAndSet(old.id, old.stateVersion, old.state, raw, old.skipReason, old.missReason, old.armMode, old.ringMode)
        profiles.chooseDirector("pack_major_stone")
        assertEquals(0.86f, f.repo.speechVoiceOf(f.repo.get(old.id)!!).pitch, 0f)
    }

    @Test
    fun `a pinned voice with no pack stays no pack, even if the profile gets one while it sounds`() = run {
        val noPack = f.db.profiles().get("builtin_steady")!!.copy(id = "none", name = "None", voicePackId = null, isBuiltin = false)
        f.db.profiles().upsert(noPack)
        val ring = fired("none")
        profiles.chooseDirector("pack_major_stone", "none")
        assertEquals(SpeechVoice.DEFAULT, f.repo.speechVoiceOf(ring))
    }

    @Test
    fun `a voice that cannot be read costs the setting, never the line`() = run {
        val m = f.mission()
        val o = f.repo.createOccurrence(m.id, f.clock.nowMs() + 60_000, "UTC").occurrence
        f.db.openHelper.writableDatabase.execSQL("UPDATE execution_profiles SET personality = 'FROM_THE_FUTURE'")
        assertEquals(SpeechVoice.DEFAULT, f.repo.speechVoiceOf(o))
    }

    @Test
    fun `a pack row that cannot be read does not stop the ring, which fires with the phone's own voice and no trim`() = run {
        f.db.openHelper.writableDatabase.execSQL("UPDATE voice_packs SET default_personality = 'FROM_THE_FUTURE'")
        val ring = fired()
        assertEquals(com.command.domain.occurrence.OccurrenceState.FIRING, ring.state)
        assertEquals(SpeechVoice.DEFAULT, f.repo.speechVoiceOf(ring))
        assertEquals(0f, f.repo.speechLevelOf(ring).trimDb, 0f)
    }
}
