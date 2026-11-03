package com.command.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.command.data.db.CommandDb
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Version 1 is the baseline. These tests pin what a future migration must not break: the exported schema matches the
 * code, and a database the code cannot migrate fails loudly instead of being wiped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(CommandDb.NAME)
        dbFile.parentFile!!.mkdirs()
        listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).forEach { it.delete() }
    }

    @After
    fun tearDown() {
        context.deleteDatabase(CommandDb.NAME)
    }

    private fun exportedSchema(version: Int): JSONObject {
        val file = File("schemas/com.command.data.db.CommandDb/$version.json")
        assertTrue("schema $version must be committed: ${file.absolutePath}", file.isFile)
        return JSONObject(file.readText()).getJSONObject("database")
    }

    private fun createDatabaseWithOneMission() = runBlocking {
        val db = CommandDb.open(context)
        val fixtureMission = Fixture.missionRow()
        db.missions().insert(fixtureMission)
        db.close()
    }

    private fun missionCountOnDisk(): Int =
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT COUNT(*) FROM missions", null).use { it.moveToFirst(); it.getInt(0) }
        }

    @Test
    fun `version 1 is exported and its identity hash is what the database writes`() {
        val schema = exportedSchema(1)
        assertEquals(1, schema.getInt("version"))
        assertEquals(19, schema.getJSONArray("entities").length())
        val db = CommandDb.open(context)
        try {
            val hash = db.openHelper.writableDatabase.query("SELECT identity_hash FROM room_master_table").use { it.moveToFirst(); it.getString(0) }
            assertEquals(schema.getString("identityHash"), hash)
            assertEquals(1, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `no destructive migration is configured anywhere in the main sources`() {
        val offenders = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("fallbackToDestructiveMigration") }.map { it.path }.toList()
        assertTrue("destructive migration found in $offenders", offenders.isEmpty())
    }

    @Test
    fun `a database from a newer version is refused, not wiped`() {
        createDatabaseWithOneMission()
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 2 }
        try {
            CommandDb.open(context).also { it.openHelper.writableDatabase }
            fail("a downgrade with no migration must fail")
        } catch (expected: Exception) {
            // Room refuses; the point is what is left on disk.
        }
        assertEquals("the missions are still there", 1, missionCountOnDisk())
    }

    @Test
    fun `a schema that no longer matches its version is refused, not rewritten`() {
        createDatabaseWithOneMission()
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE room_master_table SET identity_hash = 'tampered'")
        }
        try {
            CommandDb.open(context).also { it.openHelper.writableDatabase }
            fail("an identity-hash mismatch must fail")
        } catch (expected: Exception) {
            // Room refuses to open a database whose schema it cannot vouch for.
        }
        assertEquals(1, missionCountOnDisk())
    }

    @Test
    fun `reopening the same database keeps its data`() = runBlocking {
        createDatabaseWithOneMission()
        val db = CommandDb.open(context)
        try {
            assertEquals(1, db.missions().count())
            assertEquals("seed rows are not inserted twice", 4, db.profiles().all().size)
        } finally {
            db.close()
        }
    }
}
