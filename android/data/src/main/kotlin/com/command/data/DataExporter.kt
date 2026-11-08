package com.command.data

import android.content.Context
import android.database.Cursor
import android.util.Base64
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.command.data.db.CommandDb
import com.command.domain.history.HistoryOccurrence
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import com.command.domain.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** What an export wrote: the rows of each table, and the lines of the history. */
data class ExportSummary(val tables: Map<String, Int>, val historyRows: Int) {
    val rows: Int get() = tables.values.sum()
}

/**
 * How much there is, in the words a person would use, for the screen that offers the export and the erase: the missions they can see, every
 * alarm with what happened to it, the entries of the log, and every record in every table (what an export holds, less the caches).
 */
data class DataOverview(val missions: Int, val alarms: Int, val entries: Int, val records: Int)

/**
 * Everything COMMAND has recorded, as files a person can read, in one zip: every table as JSON lines (one object per row, the column names
 * as keys), and the history as a spreadsheet. It is written from one consistent snapshot of the database, to a file first, so that a slow
 * destination never holds the database open; nothing in it leaves the phone unless the person puts it somewhere. The snapshot is a write
 * transaction (Room has no other kind), so other writers wait for the length of the dump: about a second for tens of thousands of log
 * entries, which is years of use, and nothing that makes an alarm ring waits for the database (the tone starts before it is read). The caches (rendered
 * voice lines, cached directives) and the sync outbox are left out: they can be rebuilt and hold nothing the person wrote.
 */
@Singleton
class DataExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: CommandDb,
    private val clock: Clock,
) {
    /**
     * Reads everything, then asks for the destination with [destination] and copies the zip into it, closing it. The destination is only
     * opened once the data has been read: a slow or failing one never holds the database, and a dump that fails never leaves it opened
     * (and, for a document, emptied) for nothing. All of it runs on the IO dispatcher, so [destination] may block.
     */
    suspend fun export(destination: () -> OutputStream): ExportSummary = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("export", ".zip", context.cacheDir)
        try {
            val summary = temp.outputStream().use { sink -> db.withTransaction { write(sink) } }
            destination().use { out ->
                temp.inputStream().use { it.copyTo(out) }
                out.flush()
            }
            summary
        } finally {
            temp.delete()
        }
    }

    suspend fun overview(): DataOverview = withContext(Dispatchers.IO) {
        DataOverview(
            missions = count("SELECT COUNT(*) FROM missions WHERE deleted_at IS NULL"),
            alarms = count("SELECT COUNT(*) FROM occurrences"),
            entries = count("SELECT COUNT(*) FROM events"),
            records = tables().sumOf { count("SELECT COUNT(*) FROM `$it`") },
        )
    }

    private fun count(sql: String): Int = db.query(SimpleSQLiteQuery(sql)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun write(sink: OutputStream): ExportSummary {
        val counts = LinkedHashMap<String, Int>()
        var historyRows = 0
        ZipOutputStream(sink).use { zip ->
            val tables = tables()
            zip.text("README.txt", readme(tables))
            for (table in tables) counts[table] = dump(table, zip)
            historyRows = history(zip)
        }
        return ExportSummary(counts, historyRows)
    }

    private fun readme(tables: List<String>): String = buildString {
        append("COMMAND data export, ").append(Instant.ofEpochMilli(clock.nowMs())).append("\n\n")
        append("What COMMAND has recorded on this phone, as files you can read. (The database of the version before this one, if it left one, is not part of this copy.)\n\n")
        append("data/<table>.jsonl   one file for each of: ").append(tables.joinToString(", ")).append(".\n")
        append("                      One JSON object per line, one line per row, the column names as keys.\n")
        append("                      Columns whose names end in _utc or _at, and instance_begin_ms and original_instance_ms, hold milliseconds since 1970 (UTC).\n")
        append("                      The others: elapsed_realtime is milliseconds since the phone started, late_ms is a duration in milliseconds, columns that end in\n")
        append("                      _local hold the clock time as text, and columns that end in _min or _minutes hold minutes.\n")
        append("                      A column that holds bytes is written as {\"base64\": \"...\"}.\n")
        append("history.csv           every alarm that rang or is still planned, with its mission, its time in UTC and in the zone it was set in, its state and how\n")
        append("                      History tells it ($UNKNOWN_OUTCOME when the state or the reason stored for it could not be read). An alarm that was planned and then\n")
        append("                      removed when its mission was edited or deleted never rang and is not in it.\n")
        append("                      The file is UTF-8 with a byte-order mark, so that a spreadsheet reads the accents. A title that starts with = + - or @ has a '\n")
        append("                      in front of it there, so that a spreadsheet shows it as text.\n\n")
        append("Left out: the caches (").append(SKIPPED.joinToString(", ")).append("), which can be rebuilt and hold nothing you wrote.\n")
        append("This file contains the titles of your missions and what you did with each alarm, and the names of the codes you registered, ")
        append("each with a fingerprint of it, never the code itself. It stays wherever you put it.\n")
    }

    /** Every table of the database, so that one added later is exported without anyone remembering to list it. */
    private fun tables(): List<String> = db.query(
        SimpleSQLiteQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND substr(name, 1, 7) != 'sqlite_' AND substr(name, 1, 8) != 'android_' " +
                "AND substr(name, 1, 5) != 'room_' ORDER BY name",
        ),
    ).use { c ->
        val names = ArrayList<String>()
        while (c.moveToNext()) c.getString(0).takeIf { it !in SKIPPED }?.let(names::add)
        names
    }

    private fun dump(table: String, zip: ZipOutputStream): Int {
        zip.putNextEntry(entry("data/$table.jsonl"))
        var rows = 0
        db.query(SimpleSQLiteQuery("SELECT * FROM `$table` ORDER BY rowid")).use { c ->
            while (c.moveToNext()) {
                zip.write(oneLine(rowJson(c)).toByteArray(Charsets.UTF_8))
                zip.write('\n'.code)
                rows++
            }
        }
        zip.closeEntry()
        return rows
    }

    private fun rowJson(c: Cursor): JSONObject {
        val row = JSONObject()
        for (i in 0 until c.columnCount) {
            row.put(
                c.getColumnName(i),
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).takeIf { it.isFinite() } ?: JSONObject.NULL
                    Cursor.FIELD_TYPE_BLOB -> JSONObject().put("base64", Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP))
                    else -> c.getString(i)
                },
            )
        }
        return row
    }

    /**
     * A record is one line: JSON puts a line break inside a string as an escape, but it leaves U+2028 and U+2029 as they are, and many
     * readers (Python's splitlines among them) take those for line breaks too.
     */
    private fun oneLine(row: JSONObject): String = row.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun history(zip: ZipOutputStream): Int {
        zip.putNextEntry(entry("history.csv"))
        val lines = StringBuilder("\uFEFFoccurrence_id,mission_id,mission,category,mission_deleted,scheduled_at_utc,scheduled_local,zone,state,outcome\r\n")
        var rows = 0
        db.query(
            SimpleSQLiteQuery(
                "SELECT o.id, o.mission_id, m.title, m.category, m.deleted_at, o.scheduled_at_utc, o.zone_id, o.state, o.skip_reason, o.miss_reason " +
                    "FROM occurrences o LEFT JOIN missions m ON m.id = o.mission_id ORDER BY o.scheduled_at_utc, o.id",
            ),
        ).use { c ->
            while (c.moveToNext()) {
                val at = c.getLong(5)
                val zoneId = c.getString(6)
                val zone = try { ZoneId.of(zoneId) } catch (e: Exception) { ZoneOffset.UTC }
                val state = c.getString(7)
                val outcome = runCatching {
                    HistoryOccurrence(
                        at, zone, OccurrenceState.valueOf(state),
                        c.getString(8)?.let { SkipReason.valueOf(it) }, c.getString(9)?.let { MissReason.valueOf(it) },
                    ).reason.name
                }.getOrDefault(UNKNOWN_OUTCOME)
                lines.append(
                    listOf(
                        c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "", (!c.isNull(4)).toString(), at.toString(),
                        Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime().toString(), zoneId, state, outcome,
                    ).joinToString(",") { csv(it) },
                ).append("\r\n")
                rows++
            }
        }
        zip.write(lines.toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        return rows
    }

    private fun entry(name: String): ZipEntry = ZipEntry(name).apply { time = clock.nowMs() }

    private fun ZipOutputStream.text(name: String, text: String) {
        putNextEntry(entry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /**
     * RFC 4180: a field with a comma, a quote or a line break is quoted, and a quote inside it is doubled. A field that a spreadsheet would
     * take for a formula (it starts with =, +, - or @, or a tab or a return) gets an apostrophe in front, so that it is shown as the text it
     * is: a title can come from someone else's calendar invitation. The JSON files hold every value exactly as it was.
     */
    private fun csv(field: String): String {
        val text = if (field.isNotEmpty() && field[0] in FORMULA_STARTS) "'$field" else field
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }

    companion object {
        /** Rebuilt on demand, and holding nothing the person wrote. */
        val SKIPPED = setOf("voice_renders", "directive_cache", "sync_outbox")

        /** What the history says for an alarm whose stored state or reason is not one this version knows: not a blank, which would read as "nothing happened". */
        const val UNKNOWN_OUTCOME = "UNKNOWN"

        private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')
    }
}
