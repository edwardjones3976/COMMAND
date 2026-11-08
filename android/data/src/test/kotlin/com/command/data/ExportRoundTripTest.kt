package com.command.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.command.data.db.CalendarLinkRow
import com.command.data.db.CalendarSourceRow
import com.command.data.db.ChallengeRow
import com.command.data.db.CheckInRow
import com.command.data.db.CommandDb
import com.command.data.db.ConsentRow
import com.command.data.db.DirectiveCacheRow
import com.command.data.db.MissionRow
import com.command.data.db.SyncOutboxRow
import com.command.data.db.VoicePackRow
import com.command.data.db.VoiceRenderRow
import com.command.domain.dials.Flexibility
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.Personality
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.CheckInAnswer
import com.command.domain.model.ConsentKey
import com.command.domain.model.ConsentSource
import com.command.domain.model.EventSource
import com.command.domain.model.SyncOp
import com.command.domain.model.VoiceEngine
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.SkipReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * What a person gets when they ask for a copy of everything: every table as lines of JSON, the history as a spreadsheet, the caches left
 * out, and nothing lost on the way. The proof is the round trip: the files are read back into a second database and every row of every
 * table is the same as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExportRoundTripTest {

    private lateinit var context: Context
    private lateinit var f: Fixture
    private lateinit var exporter: DataExporter

    private val hour = 3_600_000L

    /** A title with everything that breaks a careless exporter: a comma, quotes, a line break, a line separator, right-to-left text, an emoji. */
    private val trickyTitle = "Write \"the\" report, now\nsecond line   خطة 🚀 café"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        f = Fixture(context)
        exporter = DataExporter(context, f.db, f.clock)
    }

    @After
    fun tearDown() = f.close()

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    /** What a copy of the data comes to, file by file. */
    private class Export(val summary: ExportSummary, val files: Map<String, ByteArray>) {
        fun text(name: String): String = String(files.getValue(name), Charsets.UTF_8)
        fun lines(table: String): List<String> = text("data/$table.jsonl").split("\n").filter { it.isNotEmpty() }
    }

    private suspend fun export(): Export {
        val out = ByteArrayOutputStream()
        val summary = exporter.export { out }
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes()
            }
        }
        return Export(summary, files)
    }

    private suspend fun occurrence(mission: MissionRow, offsetMs: Long, zone: String, vararg commands: OccurrenceCommand): String {
        val o = f.repo.createOccurrence(mission.id, f.t0 + offsetMs, zone).occurrence
        for (c in commands) f.repo.apply(o.id, c, EventSource.USER)
        return o.id
    }

    /** One row in every table that holds the person's own records, and one in every cache. Returns the ids of the occurrences, by outcome. */
    private suspend fun populate(): Map<String, String> {
        val main = f.mission(title = trickyTitle)
        val gone = f.mission(title = "Old habit")
        val arm = OccurrenceCommand.Arm(ArmMode.EXACT)
        val ids = mapOf(
            "started" to occurrence(main, hour, "UTC", arm, OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance, OccurrenceCommand.ReportStarted),
            "missed" to occurrence(main, 2 * hour, "Asia/Kolkata", arm, OccurrenceCommand.Fire, OccurrenceCommand.Miss(MissReason.TIMEOUT)),
            "silenced" to occurrence(main, 3 * hour, "America/New_York", arm, OccurrenceCommand.Fire, OccurrenceCommand.SafetySilence),
            "skipped" to occurrence(gone, 4 * hour, "UTC", arm, OccurrenceCommand.Fire, OccurrenceCommand.Skip(SkipReason.USER, FrictionEvidence.None)),
            "future" to occurrence(main, 5 * hour, "UTC"),
        )
        // Deleting a mission keeps its row (and its history); it is marked, not removed.
        f.db.openHelper.writableDatabase.execSQL("UPDATE missions SET deleted_at = 1, enabled = 0 WHERE id = '${gone.id}'")
        f.db.checkIns().upsert(CheckInRow(ids.getValue("started"), 0, f.t0, f.t0 + 1_000, CheckInAnswer.STARTED, null, 1_500))
        f.db.challenges().insert(ChallengeRow(0, ids.getValue("started"), 0, HandshakeMode.HOLD, 1, ChallengeOutcome.SUCCESS, f.t0, f.t0 + 3_000))
        f.db.consents().insert(ConsentRow(0, ConsentKey.NOTIFICATIONS, true, f.t0, ConsentSource.USER, 1, "ab12"))
        f.db.calendarDao().insertSource(CalendarSourceRow(7, "me@example.org", "Work, \"main\"", true, null, Flexibility.FLEXIBLE, f.t0))
        f.db.calendarDao().insertLink(CalendarLinkRow(ids.getValue("future"), 7, 99, f.t0, null, null, "Europe/Paris", f.t0, 0))
        f.db.voicePacks().upsert(VoicePackRow("pack1", "Local voice", VoiceEngine.LOCAL_TTS, null, null, "en-US", 1.0f, 0.9f, 3.5f, Personality.MINIMALIST, false, null))
        f.restWindowCoveringNow()
        val raw = f.db.openHelper.writableDatabase
        raw.execSQL("INSERT INTO handshake_tokens (id, mode, label, hash, created_at) VALUES ('t1', 'QR', 'Fridge', 'abc', 5)")
        raw.execSQL("INSERT INTO activity_profiles (id, match_category, match_title_contains, profile_id) VALUES ('a1', 'Work', NULL, 'builtin_steady')")
        raw.execSQL("INSERT INTO template_overrides (personality, phase, variant, text) VALUES ('DRILL_SERGEANT', 'REVEILLE', 0, 'Up. Now.')")
        raw.execSQL("INSERT INTO conflicts (occ_a, occ_b, kind) VALUES ('${ids.getValue("started")}', '${ids.getValue("missed")}', 'OVERLAP')")
        // The caches: rebuilt on demand, and not the person's.
        f.db.voiceRenderDao().insert(VoiceRenderRow("render1", VoiceEngine.GEMINI_STUDIO, "/cache/a.wav", 10, f.t0))
        f.db.directiveCacheDao().insert(DirectiveCacheRow("directive1", "Do the thing.", f.t0 + hour))
        f.db.syncOutboxDao().insert(SyncOutboxRow(0, "missions", main.id, SyncOp.UPSERT, f.t0))
        return ids
    }

    private val recordTables = listOf(
        "activity_profiles", "calendar_links", "calendar_sources", "challenges", "check_ins", "conflicts", "consents", "events",
        "execution_profiles", "handshake_tokens", "missions", "occurrences", "rest_windows", "settings", "template_overrides", "voice_packs",
    )

    // --- what is in the zip ---------------------------------------------------------------------------------------

    @Test
    fun `the zip holds a README, the history and one file for each kind of record, and none for the caches`() = run {
        populate()
        val e = export()

        val expected = setOf("README.txt", "history.csv") + recordTables.map { "data/$it.jsonl" }
        assertEquals(
            "every table is either exported or a named cache; a new table has to be decided here: " + (e.files.keys - expected) + (expected - e.files.keys),
            expected, e.files.keys,
        )
        assertEquals(recordTables.toSet(), e.summary.tables.keys)
        for (cache in DataExporter.SKIPPED) assertTrue("the cache $cache is named in the README", e.text("README.txt").contains(cache))
    }

    @Test
    fun `every table that holds the person's records has something in it in this test, so the round trip means something`() = run {
        populate()
        val empty = recordTables.filter { table -> rows(f.db, table).isEmpty() }
        assertTrue("these tables are empty in the test data: $empty", empty.isEmpty())
    }

    @Test
    fun `the summary counts what the files hold`() = run {
        populate()
        val e = export()

        for (table in recordTables) {
            assertEquals(table, e.lines(table).size, e.summary.tables.getValue(table))
            assertEquals(table, rows(f.db, table).size, e.summary.tables.getValue(table))
        }
        assertEquals(recordTables.sumOf { e.summary.tables.getValue(it) }, e.summary.rows)
        assertEquals(5, e.summary.historyRows)
    }

    // --- nothing lost ---------------------------------------------------------------------------------------------

    @Test
    fun `read back into a second database, every row of every table is the same`() = run {
        populate()
        val e = export()

        val copy = CommandDb.inMemory(context)
        try {
            val raw = copy.openHelper.writableDatabase
            raw.execSQL("PRAGMA foreign_keys = OFF")
            for (table in recordTables) {
                raw.execSQL("DELETE FROM `$table`")
                for (line in e.lines(table)) {
                    assertTrue("a row of $table was not written back: $line", raw.insert(table, SQLiteDatabase.CONFLICT_ABORT, contentValues(JSONObject(line))) != -1L)
                }
            }
            for (table in recordTables) assertEquals("the rows of $table", rows(f.db, table), rows(copy, table))
        } finally {
            copy.close()
        }
    }

    @Test
    fun `a record is one line, whatever the text holds`() = run {
        populate()
        val e = export()

        val lines = e.lines("missions")
        assertEquals(2, lines.size)
        val titles = lines.map { JSONObject(it).getString("title") }
        assertTrue("the title came back exactly: $titles", trickyTitle in titles)
        for (separator in listOf(" ", " ", "\r")) assertFalse("a line holds a raw " + separator.codePointAt(0), lines.any { separator in it })
    }

    @Test
    fun `bytes are written as base64 and a number JSON cannot hold as null`() = run {
        val raw = f.db.openHelper.writableDatabase
        raw.execSQL("CREATE TABLE zz_scratch (b BLOB, d REAL, t TEXT, n INTEGER)")
        raw.execSQL("INSERT INTO zz_scratch VALUES (x'000102FF', 1e999, 'x', 9223372036854775807)")
        val e = export()

        val row = JSONObject(e.lines("zz_scratch").single())
        assertEquals("AAEC/w==", row.getJSONObject("b").getString("base64"))
        assertTrue("an infinite number is written as null", row.isNull("d"))
        assertEquals("x", row.getString("t"))
        assertEquals("the largest integer survives", Long.MAX_VALUE, row.getLong("n"))
    }

    @Test
    fun `an empty database exports cleanly`() = run {
        val e = export()

        assertTrue(e.text("README.txt").startsWith("COMMAND data export"))
        assertTrue(
            "the README says what it holds of the registered codes, and what it never holds",
            e.text("README.txt").contains(
                "This file contains the titles of your missions and what you did with each alarm, and the names of the codes you registered, " +
                    "each with a fingerprint of it, never the code itself. It stays wherever you put it.\n",
            ),
        )
        assertEquals(0, e.summary.historyRows)
        assertEquals("\uFEFFoccurrence_id,mission_id,mission,category,mission_deleted,scheduled_at_utc,scheduled_local,zone,state,outcome\r\n", e.text("history.csv"))
        assertTrue(e.lines("occurrences").isEmpty())
    }

    // --- the history -----------------------------------------------------------------------------------------------

    @Test
    fun `the history is a spreadsheet with every alarm, its local time and how History tells it`() = run {
        val ids = populate()
        val table = parseCsv(export().text("history.csv").removePrefix("\uFEFF"))

        assertEquals(listOf("occurrence_id", "mission_id", "mission", "category", "mission_deleted", "scheduled_at_utc", "scheduled_local", "zone", "state", "outcome"), table.first())
        val byId = table.drop(1).associateBy { it[0] }
        assertEquals(5, byId.size)

        val started = byId.getValue(ids.getValue("started"))
        assertEquals("the title came back through the quoting", trickyTitle, started[2])
        assertEquals("Work", started[3])
        assertEquals("false", started[4])
        assertEquals("STARTED_SELF_REPORTED", started[8])
        assertEquals("STARTED", started[9])

        val missed = byId.getValue(ids.getValue("missed"))
        assertEquals("the phone's clock, in the zone it was set in (UTC+5:30)", "2026-11-02T16:30", missed[6])
        assertEquals("Asia/Kolkata", missed[7])
        assertEquals("TIMED_OUT", missed[9])

        assertEquals("America/New_York", byId.getValue(ids.getValue("silenced"))[7])
        assertEquals("SILENCED", byId.getValue(ids.getValue("silenced"))[9])

        val skipped = byId.getValue(ids.getValue("skipped"))
        assertEquals("a mission that was deleted is still in the history, and says so", "true", skipped[4])
        assertEquals("SKIPPED_BY_USER", skipped[9])

        assertEquals("OPEN", byId.getValue(ids.getValue("future"))[9])
    }

    @Test
    fun `an alarm whose stored state or reason cannot be read says UNKNOWN in the history, not a blank, and the export still works`() = run {
        val ids = populate()
        val raw = f.db.openHelper.writableDatabase
        raw.execSQL("UPDATE occurrences SET state = 'FROM_A_NEWER_VERSION' WHERE id = '${ids.getValue("started")}'")
        raw.execSQL("UPDATE occurrences SET miss_reason = 'NOT_A_REASON' WHERE id = '${ids.getValue("missed")}'")

        val table = parseCsv(export().text("history.csv").removePrefix("\uFEFF"))
        val byId = table.drop(1).associateBy { it[0] }

        val started = byId.getValue(ids.getValue("started"))
        assertEquals("the state is exported as it is stored", "FROM_A_NEWER_VERSION", started[8])
        assertEquals("UNKNOWN", started[9])
        assertEquals("UNKNOWN", byId.getValue(ids.getValue("missed"))[9])
        assertEquals("the others are told as before", "SILENCED", byId.getValue(ids.getValue("silenced"))[9])
        assertEquals(DataExporter.UNKNOWN_OUTCOME, "UNKNOWN")
        assertTrue("and the README tells the person the same word", export().text("README.txt").contains("(UNKNOWN when the state or the reason"))
    }

    @Test
    fun `a title a spreadsheet would take for a formula is shown as text in the history and exact in the data`() = run {
        val formula = "=HYPERLINK(\"http://example.org\",\"Open\")"
        val mission = f.mission(title = formula)
        occurrence(mission, hour, "UTC")
        val e = export()

        val title = parseCsv(e.text("history.csv").removePrefix("\uFEFF"))[1][2]
        assertEquals("a quote in front, so that it is text", "'" + formula, title)
        assertTrue("the data holds it as it was", e.lines("missions").any { JSONObject(it).getString("title") == formula })
    }

    @Test
    fun `the destination is not asked for when the data cannot be read, and not left behind when it cannot be opened`() = run {
        populate()
        var asked = false
        f.db.close() // Room cannot be used once it is closed: the dump fails

        val failed = runCatching { exporter.export { asked = true; ByteArrayOutputStream() } }

        assertTrue("the export failed", failed.isFailure)
        assertFalse("and the destination was never opened", asked)
        assertEquals("no file of the export is left in the cache", emptyList<String>(), context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("export") }.map { it.name })
    }

    @Test
    fun `a destination that cannot be opened fails the export and leaves nothing in the cache`() = run {
        populate()

        val failed = runCatching { exporter.export { throw IOException("the document cannot be written") } }

        assertTrue(failed.exceptionOrNull() is IOException)
        assertEquals(emptyList<String>(), context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("export") }.map { it.name })
    }

    // --- the shape of the job --------------------------------------------------------------------------------------

    @Test
    fun `the destination is only written once the snapshot is complete, so a slow one never holds the database`() = run {
        populate()
        var blockedWriter = false
        val slow = object : OutputStream() {
            private var first = true
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (first) {
                    first = false
                    // A write from another thread: if the transaction that read the data were still open, it would have to wait for it.
                    val writer = thread {
                        runBlocking(Dispatchers.IO) { f.db.consents().insert(ConsentRow(0, ConsentKey.EXACT_ALARM, true, f.t0, ConsentSource.SYSTEM, 1, null)) }
                    }
                    writer.join(5_000)
                    blockedWriter = writer.isAlive
                }
            }
        }

        exporter.export { slow }

        assertFalse("a write waited for the export while it was being copied out", blockedWriter)
    }

    @Test
    fun `the overview says what there is in words`() = run {
        populate()
        val overview = exporter.overview()

        assertEquals("the mission that was deleted is not counted among the missions", 1, overview.missions)
        assertEquals(5, overview.alarms)
        assertEquals(rows(f.db, "events").size, overview.entries)
        assertEquals("every table that is exported", export().summary.rows, overview.records)
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** A table as maps of column to value, in row order, with bytes as base64, for comparing two databases. */
    private fun rows(db: CommandDb, table: String): List<Map<String, Any?>> = runBlocking(Dispatchers.IO) {
        db.query(SimpleSQLiteQuery("SELECT * FROM `$table` ORDER BY rowid")).use { c ->
            val out = ArrayList<Map<String, Any?>>()
            while (c.moveToNext()) {
                out += (0 until c.columnCount).associate { i ->
                    c.getColumnName(i) to when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP)
                        else -> c.getString(i)
                    }
                }
            }
            out
        }
    }

    private fun contentValues(row: JSONObject): ContentValues {
        val values = ContentValues()
        for (name in row.keys()) {
            when (val v = row.get(name)) {
                JSONObject.NULL -> values.putNull(name)
                is Number -> if (v is Double || v is Float) values.put(name, v.toDouble()) else values.put(name, v.toLong())
                is JSONObject -> values.put(name, Base64.decode(v.getString("base64"), Base64.NO_WRAP))
                else -> values.put(name, v.toString())
            }
        }
        return values
    }

    /** RFC 4180: quoted fields may hold commas, quotes (doubled) and line breaks; records end in CRLF. */
    private fun parseCsv(text: String): List<List<String>> {
        val records = ArrayList<List<String>>()
        var record = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && ch == '"' -> quoted = false
                quoted -> field.append(ch)
                ch == '"' -> quoted = true
                ch == ',' -> { record.add(field.toString()); field.setLength(0) }
                ch == '\r' && text.getOrNull(i + 1) == '\n' -> {
                    record.add(field.toString()); field.setLength(0)
                    records.add(record); record = ArrayList()
                    i++
                }
                else -> field.append(ch)
            }
            i++
        }
        assertFalse("a quoted field was left open", quoted)
        assertTrue("the last record ends with a line break", field.isEmpty() && record.isEmpty())
        return records
    }
}
