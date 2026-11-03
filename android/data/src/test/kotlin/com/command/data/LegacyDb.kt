package com.command.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File

/**
 * Builds a database that is byte-for-byte the previous app's Room schema v6, from the schema JSON that Room exported when
 * that version was built (src/test/resources/legacy/legacy_v6_schema.json), and fills it with synthetic rows.
 */
class LegacyDb private constructor(val file: File, val db: SQLiteDatabase, private val schema: Map<String, List<Column>>) {

    class Column(val name: String, val affinity: String, val notNull: Boolean)

    /** Inserts a row, giving every column that is not mentioned a neutral value so NOT NULL constraints hold. */
    fun insert(table: String, vararg values: Pair<String, Any?>): Long {
        val given = values.toMap()
        val cv = ContentValues()
        for (c in schema.getValue(table)) {
            val v = if (c.name in given) given[c.name] else defaultFor(c)
            when (v) {
                null -> cv.putNull(c.name)
                is Boolean -> cv.put(c.name, if (v) 1 else 0)
                is Int -> cv.put(c.name, v)
                is Long -> cv.put(c.name, v)
                is Float -> cv.put(c.name, v)
                is Double -> cv.put(c.name, v)
                else -> cv.put(c.name, v.toString())
            }
        }
        return db.insertOrThrow(table, null, cv)
    }

    private fun defaultFor(c: Column): Any? = when {
        !c.notNull -> null
        c.name == "id" && c.affinity == "INTEGER" -> null // an INTEGER PRIMARY KEY: let SQLite assign the row id
        c.affinity == "INTEGER" -> 0
        c.affinity == "REAL" -> 0.0
        else -> ""
    }

    fun close() = db.close()

    companion object {
        fun create(context: Context, wal: Boolean): LegacyDb {
            val file = context.getDatabasePath(LegacyImporter.LEGACY_DB_NAME)
            file.parentFile!!.mkdirs()
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm")).forEach { it.delete() }
            val schemaText = LegacyDb::class.java.getResourceAsStream("/legacy/legacy_v6_schema.json")!!.bufferedReader().readText()
            val database = JSONObject(schemaText).getJSONObject("database")
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            if (wal) db.enableWriteAheadLogging()
            val tables = LinkedHashMap<String, List<Column>>()
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val name = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = e.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
                val fields = e.getJSONArray("fields")
                tables[name] = (0 until fields.length()).map {
                    val f = fields.getJSONObject(it)
                    Column(f.getString("columnName"), f.getString("affinity"), f.optBoolean("notNull", false))
                }
            }
            val setup = database.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = 6
            return LegacyDb(file, db, tables)
        }
    }
}
