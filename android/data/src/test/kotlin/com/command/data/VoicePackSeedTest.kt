package com.command.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.command.data.db.CommandDb
import com.command.data.db.VoicePackRow
import com.command.domain.dials.Personality
import com.command.domain.model.VoiceEngine
import com.command.domain.voice.BuiltInVoicePacks
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

/** The ten packs are seeded whenever the database opens, on a new database and on a v1 database made before there was any seed of them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoicePackSeedTest {

    private lateinit var context: Context
    private lateinit var dbFile: File

    private val builtinPacks = mapOf(
        "builtin_nudge" to "pack_coach_ray",
        "builtin_steady" to "pack_eleanor_vance",
        "builtin_command" to "pack_sgt_brody",
        "builtin_reveille_max" to "pack_major_stone",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(CommandDb.NAME)
        dbFile.parentFile!!.mkdirs()
        context.deleteDatabase(CommandDb.NAME)
    }

    @After fun tearDown() { context.deleteDatabase(CommandDb.NAME) }

    private fun <T> withDb(block: suspend (CommandDb) -> T): T = runBlocking {
        val db = CommandDb.open(context)
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    /** A v1 database as M3 to INT left it: the built-ins with no pack, and no packs at all. */
    private fun makeItOld(extra: SQLiteDatabase.() -> Unit = {}) {
        withDb { it.missions().count() }
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DELETE FROM voice_packs")
            raw.execSQL("UPDATE execution_profiles SET voice_pack_id = NULL")
            raw.extra()
        }
    }

    @Test
    fun `a new database has the ten packs with the settings the voice has always had, and each built-in profile has its pack`() {
        withDb { db ->
            val packs = db.voicePacks().all().associateBy { it.id }
            assertEquals(BuiltInVoicePacks.all.map { it.id }.toSet(), packs.keys)
            assertEquals(10, packs.size)
            for (pack in BuiltInVoicePacks.all) {
                val row = packs.getValue(pack.id)
                assertEquals(pack.name, row.name)
                assertEquals(pack.languageTag, row.locale)
                assertEquals(pack.pitch, row.pitch, 0f)
                assertEquals(pack.speechRate, row.rate, 0f)
                assertEquals(BuiltInVoicePacks.trimDb(pack), row.loudnessTrimDb, 0f)
                assertEquals(VoiceEngine.LOCAL_TTS, row.engine)
                assertFalse(row.isCustom)
                assertNull("no credit line claims anything", row.attribution)
                assertTrue("every pack adds to the loudness, none takes away", row.loudnessTrimDb > 0f)
            }
            for ((profile, pack) in builtinPacks) assertEquals(pack, db.profiles().get(profile)!!.voicePackId)
        }
    }

    @Test
    fun `each pack goes with the personality the record gives it, and each built-in profile's pack with the profile's own words`() {
        withDb { db ->
            val personality = db.voicePacks().all().associate { it.id to it.defaultPersonality }
            assertEquals(Personality.DRILL_SERGEANT, personality["pack_major_stone"])
            assertEquals(Personality.DRILL_SERGEANT, personality["pack_sgt_miller"])
            assertEquals(Personality.EXECUTIVE_DIRECTOR, personality["pack_eleanor_vance"])
            assertEquals(Personality.EXECUTIVE_DIRECTOR, personality["pack_marcus_vance"])
            assertEquals(Personality.EXECUTIVE_DIRECTOR, personality["pack_elena_rostova"])
            assertEquals(Personality.SUPPORTIVE_COACH, personality["pack_coach_ray"])
            assertEquals(Personality.SUPPORTIVE_COACH, personality["pack_coach_maya"])
            assertEquals(Personality.ACCOUNTABILITY_PARTNER, personality["pack_sgt_brody"])
            assertEquals(Personality.MINIMALIST, personality["pack_aris_thorne"])
            assertEquals(Personality.MINIMALIST, personality["pack_iron_sentinel"])
            for (profile in builtinPacks.keys) {
                val row = db.profiles().get(profile)!!
                assertEquals(row.personality, personality[row.voicePackId])
            }
        }
    }

    @Test
    fun `an existing v1 database with no packs gets them when it opens, and keeps everything it had`() {
        val mission = withDb { db ->
            val m = Fixture.missionRow(title = "Kept")
            db.missions().insert(m)
            m
        }
        makeItOld()

        withDb { db ->
            assertEquals(10, db.voicePacks().all().size)
            for ((profile, pack) in builtinPacks) assertEquals(pack, db.profiles().get(profile)!!.voicePackId)
            assertEquals("Kept", db.missions().get(mission.id)!!.title)
            assertEquals("no second set of profiles", 4, db.profiles().all().size)
        }
    }

    @Test
    fun `opening again changes nothing, and what the person changed stays`() {
        withDb { db ->
            val stone = db.voicePacks().get("pack_major_stone")!!
            db.voicePacks().upsert(stone.copy(loudnessTrimDb = 8f, pitch = 1.1f))
            db.voicePacks().upsert(VoicePackRow("mine", "Mine", VoiceEngine.LOCAL_TTS, null, null, "en-GB", 1f, 1f, 2f, Personality.CUSTOM, true, null))
            val steady = db.profiles().get("builtin_steady")!!
            db.profiles().upsert(steady.copy(voicePackId = "mine", personality = Personality.CUSTOM))
            val other = db.profiles().get("builtin_nudge")!!.copy(id = "their_own", name = "Theirs", voicePackId = null, isBuiltin = false)
            db.profiles().upsert(other)
        }

        repeat(2) {
            withDb { db ->
                assertEquals(11, db.voicePacks().all().size)
                val stone = db.voicePacks().get("pack_major_stone")!!
                assertEquals("the person's trim is not put back", 8f, stone.loudnessTrimDb, 0f)
                assertEquals(1.1f, stone.pitch, 0f)
                assertNotNull(db.voicePacks().get("mine"))
                assertEquals("a chosen pack is not put back", "mine", db.profiles().get("builtin_steady")!!.voicePackId)
                assertEquals(Personality.CUSTOM, db.profiles().get("builtin_steady")!!.personality)
                assertNull("a profile of the person's own is left as it is", db.profiles().get("their_own")!!.voicePackId)
            }
        }
    }

    @Test
    fun `a v1 database whose built-in profile already names a pack keeps it, and only the empty ones are filled`() {
        makeItOld {
            execSQL("UPDATE execution_profiles SET voice_pack_id = 'pack_coach_maya' WHERE id = 'builtin_nudge'")
        }
        withDb { db ->
            assertEquals("pack_coach_maya", db.profiles().get("builtin_nudge")!!.voicePackId)
            assertEquals("pack_eleanor_vance", db.profiles().get("builtin_steady")!!.voicePackId)
        }
    }

    @Test
    fun `a seed that cannot be written costs the packs, not the database, because a ring is waiting to read it`() {
        makeItOld {
            execSQL("CREATE TRIGGER no_seed BEFORE INSERT ON voice_packs WHEN NEW.id = 'pack_aris_thorne' BEGIN SELECT RAISE(ABORT, 'disk trouble'); END")
            execSQL("INSERT INTO settings (`key`, value_json) VALUES ('still_here', '1')")
        }
        withDb { db ->
            assertEquals("the database opened and reads", "1", db.settings().get("still_here"))
            assertEquals(0, db.voicePacks().all().size)
            assertNull("all or nothing: no profile names a pack that was not written", db.profiles().get("builtin_steady")!!.voicePackId)
        }
    }
}
