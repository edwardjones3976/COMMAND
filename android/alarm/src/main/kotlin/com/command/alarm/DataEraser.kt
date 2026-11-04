package com.command.alarm

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.command.data.DatabaseFiles
import com.command.data.db.OccurrenceRow
import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
import com.command.domain.alarm.AlarmScheduler
import com.command.domain.alarm.CheckInPoster
import com.command.domain.alarm.RingController
import com.command.domain.dials.CheckInSchedule
import com.command.domain.occurrence.OccurrenceState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What an erase did. [leftovers] are files that are still there (paths below the app's data directory, or a whole path for the external
 * folders) because they could not be deleted or came back; [problems] are steps that failed, in words for a log and never the person's own.
 */
data class ErasureReport(val occurrences: Int, val leftovers: List<String>, val problems: List<String>) {
    /** Nothing of COMMAND's is left, and every step worked. */
    val complete: Boolean get() = leftovers.isEmpty() && problems.isEmpty()
}

/**
 * Takes everything COMMAND holds off the phone, from a list of what it holds: every alarm that was set, every ring and every notice,
 * the queued work, the alarm volume if a ring had raised it, both databases (this version's and the previous version's), and every file
 * the app has written (its own folders, the caches, the preferences, the external folders). The list is the point: the answer to "did
 * it delete everything?" is a test that sets one of each and looks (DeleteLeavesNothingTest), and the report says what is left, if
 * anything is, so that the screen never says more than is true.
 *
 * The platform alarms are found by the occurrences in the database, so a database that cannot be read is reported and the rest goes on (the
 * process that ends afterwards takes whatever alarms the platform still holds). It cannot be cancelled half-way, because a person who leaves
 * the screen must not leave half an erase, and a second call while one runs waits and then finds nothing to do.
 *
 * The process is not ended here. Whoever calls it ends the process afterwards (the screen asks Android to clear the app's data, which also
 * revokes the permissions): until then the singletons still hold a closed database.
 */
@Singleton
class DataEraser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DatabaseFiles,
    private val scheduler: AlarmScheduler,
    private val rings: RingController,
    private val checkIns: CheckInPoster,
    private val volume: AlarmVolumePolicy,
    private val session: RingSession,
) {
    private val gate = Mutex()

    /** What deletes a file: a seam for the one test that needs a file that will not go, which no file system lets a test make the same way. */
    internal var delete: (File) -> Boolean = { it.delete() }

    suspend fun erase(): ErasureReport = gate.withLock { withContext(NonCancellable + Dispatchers.IO) { run() } }

    private suspend fun run(): ErasureReport {
        val problems = ArrayList<String>()
        val occurrences = try {
            database.occurrences()
        } catch (e: Exception) {
            problems += "the occurrences could not be read"
            emptyList()
        }
        takeBackAlarms(occurrences)
        // Ending a ring is a message to the main thread. A moment is allowed for it, so that the ring puts the volume back itself.
        var waited = 0
        while (session.isRinging && waited < RING_END_WAIT_MS) {
            delay(POLL_MS)
            waited += POLL_MS.toInt()
        }
        // The record of a raised volume is in the database: once that is gone (a second erase), there is nothing to put back.
        if (!database.isGone) {
            val restored = try {
                volume.restoreNow()
            } catch (e: Exception) {
                false
            }
            if (!restored) problems += "the alarm volume could not be put back"
        }
        step(problems, "queued work could not be cancelled") { cancelWork() }
        runCatching { NotificationManagerCompat.from(context).cancelAll() }

        removeEverything()
        // A write that arrived after the database was closed opens it again, and a file can be recreated by something that was running:
        // look, and go over it once more if anything came back.
        var leftovers = leftovers()
        if (leftovers.isNotEmpty()) {
            removeEverything()
            leftovers = leftovers()
        }
        return ErasureReport(occurrences.size, leftovers, problems)
    }

    private inline fun step(problems: MutableList<String>, failure: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            problems += failure
        }
    }

    /** Every platform alarm an occurrence can have, its ring and its notices. */
    private fun takeBackAlarms(occurrences: List<OccurrenceRow>) {
        val notifications = NotificationManagerCompat.from(context)
        for (o in occurrences) {
            scheduler.cancel(AlarmRequest(o.id, o.requestCode, AlarmKind.FIRE, 0))
            scheduler.cancel(AlarmRequest(o.id, o.requestCode, AlarmKind.CYCLE_END, 0))
            // Index MAX_COUNT is the final-timeout alarm, so the highest index in use is MAX_COUNT itself (as in OccurrenceRepository).
            for (index in 0..CheckInSchedule.MAX_COUNT) {
                scheduler.cancel(AlarmRequest(o.id, o.requestCode, AlarmKind.CHECK_IN, 0, index))
            }
            if (o.state == OccurrenceState.FIRING) rings.stop(o.id, o.requestCode)
            checkIns.cancel(o.id)
            runCatching { notifications.cancel(o.id, AlarmContract.ID_MISSED) }
        }
    }

    /** The queued runs (the reconcile for the end of a pause, the periodic backstop, and whatever else was waiting) and the record of finished ones. */
    private fun cancelWork() {
        val work = WorkManager.getInstance(context)
        work.cancelAllWork().result.get(WORK_WAIT_SECONDS, TimeUnit.SECONDS)
        work.pruneWork().result.get(WORK_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private fun removeEverything() {
        database.closeAndDelete()
        // By the platform's own call, so that the copy of each preference file that the process holds in memory goes with its file.
        File(dataDir(), "shared_prefs").listFiles()?.forEach { file ->
            if (file.name.endsWith(".xml")) runCatching { context.deleteSharedPreferences(file.name.removeSuffix(".xml")) }
        }
        dataDir().listFiles()?.forEach { child -> if (child.name !in KEPT_DIRECTORIES) clear(child) }
        for (dir in context.getExternalFilesDirs(null) + context.externalCacheDirs) dir?.listFiles()?.forEach(::clear)
    }

    /** Deletes [file] and everything under it, except what the platform libraries keep for themselves. A link is removed, never followed. */
    private fun clear(file: File) {
        if (ownedByLibraries(file)) return
        if (isLink(file) || !file.isDirectory) {
            delete(file)
            return
        }
        file.listFiles()?.forEach(::clear)
        // The folder itself is the platform's to keep (files, cache, databases and the rest are created for the app), but any other is ours.
        if (file.parentFile?.absolutePath != dataDir().absolutePath || file.name.startsWith("app_")) delete(file)
    }

    private fun leftovers(): List<String> {
        val found = ArrayList<String>()
        fun walk(file: File, shown: (File) -> String) {
            if (isLink(file)) return
            if (file.isDirectory) file.listFiles()?.forEach { walk(it, shown) } else if (!ownedByLibraries(file)) found += shown(file)
        }
        val data = dataDir()
        data.listFiles()?.forEach { child -> if (child.name !in KEPT_DIRECTORIES) walk(child) { it.relativeTo(data).path } }
        for (dir in context.getExternalFilesDirs(null) + context.externalCacheDirs) dir?.let { walk(it) { file -> file.path } }
        return found.sorted()
    }

    /**
     * Whether [file] is a symbolic link: its path, with the folder it is in resolved, is not its own canonical path. (java.nio.file does this
     * in one call but needs API 26 and the app supports 24.) A path that cannot be resolved is taken for a link, so that it is not followed.
     */
    private fun isLink(file: File): Boolean = try {
        val parent = file.parentFile
        val inCanonicalDir = if (parent == null) file else File(parent.canonicalFile, file.name)
        inCanonicalDir.canonicalFile != inCanonicalDir.absoluteFile
    } catch (e: IOException) {
        true
    }

    /** WorkManager's queue and its settings: [cancelWork] emptied them, and the library keeps the files open while the process lives. */
    private fun ownedByLibraries(file: File): Boolean = file.name.startsWith(WORK_MANAGER_PREFIX)

    private fun dataDir(): File = File(context.applicationInfo.dataDir)

    private companion object {
        const val RING_END_WAIT_MS = 3_000
        const val POLL_MS = 50L
        const val WORK_WAIT_SECONDS = 10L
        const val WORK_MANAGER_PREFIX = "androidx.work."

        /** The platform's own code cache and the link to the app's native libraries are not data. */
        val KEPT_DIRECTORIES = setOf("code_cache", "lib")
    }
}
