package com.command.alarm

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.command.data.DatabaseFiles
import com.command.data.LegacyAlarmCanceller
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
 * [volumeLeft] says that the alarm volume is one of them: it is not what the person had, and clearing the app's data does not put it right.
 */
data class ErasureReport(val occurrences: Int, val leftovers: List<String>, val problems: List<String>, val volumeLeft: Boolean = false) {
    /**
     * No file of COMMAND's is left, and every step worked. (The folders Android makes for every app, and WorkManager's own files, which the
     * library keeps open until the app's data is cleared, hold nothing of the person's and are not looked for.)
     */
    val complete: Boolean get() = leftovers.isEmpty() && problems.isEmpty()
}

/**
 * Takes everything COMMAND holds off the phone, from a list of what it holds: every alarm that was set, every ring and every notice,
 * the queued work, the alarm volume if a ring had raised it, both databases (this version's and the previous version's), and every file
 * the app has written (its own folders, the caches, the preferences, the external folders, among them the voice engine's debug captures). The list is the point: the answer to "did
 * it delete everything?" is a test that sets one of each and looks (DeleteLeavesNothingTest), and the report says what is left, if
 * anything is, so that the screen never says more than is true.
 *
 * The platform alarms are found by the occurrences in the database, so a database that cannot be read is reported and the rest goes on (the
 * process that ends afterwards takes whatever alarms the platform still holds). The previous version's alarms are found by its own database
 * in the same way. The first thing it does is close the [ErasureLatch], so that nothing that is already running can arm an alarm or start a
 * ring behind it. It cannot be cancelled half-way, because a person who leaves the screen must not leave half an erase, and a second call
 * while one runs waits and then finds nothing to do, except to say again what could not be undone and cannot be tried again.
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
    private val latch: ErasureLatch,
    private val legacy: LegacyAlarmCanceller,
) {
    private val gate = Mutex()

    /**
     * Problems that a second run cannot find again, because what they were about went with the databases (the list of occurrences, the
     * previous version's missions, a volume whose record is lost): they are said again each time, so that "Try again" never turns an
     * unfinished erase into a finished one.
     */
    private val carried = LinkedHashSet<String>()

    /** What deletes a file: a seam for the one test that needs a file that will not go, which no file system lets a test make the same way. */
    internal var delete: (File) -> Boolean = { it.delete() }

    /** How long the ring service is given to go quiet, in milliseconds: a seam, for the test of a ring that will not end. */
    internal var ringEndWaitMs: Int = RING_END_WAIT_MS

    /** What tells a link from a file: a seam, for the test of a link that will not go, which a file system that cannot make links cannot be given. */
    internal var linkTest: (File) -> Boolean = { onDiskIsLink(it) }

    suspend fun erase(): ErasureReport = gate.withLock { withContext(NonCancellable + Dispatchers.IO) { run() } }

    private suspend fun run(): ErasureReport {
        // First of all: from now on nothing arms an alarm and no ring starts, so that a reconcile, a check-in or an alarm that is already on
        // its way cannot put back what is taken back below.
        latch.seal()
        volume.eraseBegan()
        // Then silence, before anything that takes time: whatever is sounding is ended without waiting for the database to say which alarm
        // it is (or whether it can be read at all), and before the whole history has been gone through.
        session.endActive()
        try {
            return erasing()
        } catch (e: Throwable) {
            // It failed before it deleted anything: the app goes on as it was, and must go on ringing. (Whatever was taken back is set again
            // by the next reconcile.) Once the database is gone there is no way back, and the latch stays closed.
            if (!database.isGone) {
                latch.unseal()
                volume.eraseAbandoned()
            }
            throw e
        }
    }

    private suspend fun erasing(): ErasureReport {
        val problems = ArrayList<String>()
        val occurrences = try {
            database.occurrences()
        } catch (e: Exception) {
            problems += UNREADABLE
            emptyList()
        }
        takeBackAlarms(occurrences)
        // The previous version's alarms were keyed by its missions, which only its database can name: they are looked for before it goes.
        val looked = try {
            legacy.cancelNow()
        } catch (e: Exception) {
            false
        }
        if (!looked) problems += LEGACY
        endRings(occurrences)
        // Ending a ring is a message to the main thread, and a delivery that was already being checked has to finish. A moment is allowed
        // for both, so that the ring puts the volume back itself and nothing is left sounding when the database is closed.
        var waited = 0
        while (session.isBusy && waited < ringEndWaitMs) {
            delay(POLL_MS)
            waited += POLL_MS.toInt()
        }
        if (session.isBusy) problems += STILL_RINGING
        // The record of a raised volume is in the database. Once that is gone (a second erase) what could not be put back the first time is
        // tried again from memory, and there is nothing else to put back.
        val restored = try {
            if (database.isGone) volume.retryUnfinishedRestore() else volume.restoreNow()
        } catch (e: Exception) {
            false
        }
        if (!restored) problems += VOLUME
        step(problems, WORK) { cancelWork() }
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
        // Whatever was in the middle of arming an alarm when the latch closed has finished by now; take back anything it managed to set.
        takeBackAlarms(occurrences)

        removeEverything()
        // A write that arrived after the database was closed opens it again, and a file can be recreated by something that was running:
        // look, and go over it once more if anything came back.
        var leftovers = leftovers()
        if (leftovers.isNotEmpty()) {
            removeEverything()
            leftovers = leftovers()
        }
        // What cannot be tried again is said again: the databases that held the facts are gone, so a second run would find nothing wrong. (A
        // volume that was kept for a retry is not: the retry says whether it worked.)
        carried += problems.filter { it == UNREADABLE || it == LEGACY || (it == VOLUME && !volume.hasUnfinishedRestore) }
        val all = (problems + carried).distinct()
        return ErasureReport(occurrences.size, leftovers, all, volumeLeft = VOLUME in all)
    }

    private inline fun step(problems: MutableList<String>, failure: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            problems += failure
        }
    }

    /** Every platform alarm an occurrence can have. */
    private fun takeBackAlarms(occurrences: List<OccurrenceRow>) {
        for (o in occurrences) {
            scheduler.cancel(AlarmRequest(o.id, o.requestCode, AlarmKind.FIRE, 0))
            scheduler.cancel(AlarmRequest(o.id, o.requestCode, AlarmKind.CYCLE_END, 0))
            // Index MAX_COUNT is the final-timeout alarm, so the highest index in use is MAX_COUNT itself (as in OccurrenceRepository).
            for (index in 0..CheckInSchedule.MAX_COUNT) {
                scheduler.cancel(AlarmRequest(o.id, o.requestCode, AlarmKind.CHECK_IN, 0, index))
            }
        }
    }

    /** The ring of each occurrence and the notices it left. */
    private fun endRings(occurrences: List<OccurrenceRow>) {
        val notifications = NotificationManagerCompat.from(context)
        for (o in occurrences) {
            // A ring that is sounding, and an alarm that has just gone off and is being checked (it is still armed in the database, and its
            // tone may already be going): both are told to stop. The rest have no ring to end.
            if (o.state == OccurrenceState.FIRING || o.state == OccurrenceState.ARMED) rings.stop(o.id, o.requestCode)
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
            // A link that is still there could not be removed: it is named, never followed.
            if (isLink(file)) {
                found += shown(file)
                return
            }
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
    private fun isLink(file: File): Boolean = linkTest(file)

    private fun onDiskIsLink(file: File): Boolean = try {
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
        const val UNREADABLE = "the occurrences could not be read"
        const val LEGACY = "the previous version's alarms could not be looked for"
        const val VOLUME = "the alarm volume could not be put back"
        const val WORK = "queued work could not be cancelled"
        const val STILL_RINGING = "a ring was still sounding"
        const val RING_END_WAIT_MS = 3_000
        const val POLL_MS = 50L
        const val WORK_WAIT_SECONDS = 10L
        const val WORK_MANAGER_PREFIX = "androidx.work."

        /** The platform's own code cache and the link to the app's native libraries are not data. */
        val KEPT_DIRECTORIES = setOf("code_cache", "lib")
    }
}
