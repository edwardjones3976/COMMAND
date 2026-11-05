package com.command.data

import android.content.Context
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The database and its files, for the one job that has to end it: erasing everything. The other modules never touch the database
 * directly; this is what the erase needs of it, so that the order is kept in one place: read what the alarms are keyed by, and only then
 * close the database and delete the files it lives in (this version's and the one the previous version left).
 */
@Singleton
open class DatabaseFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: CommandDb,
) {
    /** True once [closeAndDelete] has run: the database is closed for good (Room refuses to be used after it), and there is nothing in it to read. */
    @Volatile
    var isGone: Boolean = false
        private set

    /** Every occurrence there is: the platform alarms, rings and notices are keyed by them, so they are what has to be taken back. */
    open suspend fun occurrences(): List<OccurrenceRow> = if (isGone) emptyList() else db.occurrences().all()

    /**
     * Closes the database and deletes its files, with the journal and write-ahead files beside them, and the previous version's database.
     * Whatever uses the database afterwards fails, because Room cannot be used once it is closed: the caller ends the process next.
     */
    fun closeAndDelete() {
        isGone = true
        runCatching { db.close() }
        for (name in listOf(CommandDb.NAME, LegacyImporter.LEGACY_DB_NAME)) runCatching { context.deleteDatabase(name) }
    }
}
