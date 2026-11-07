package com.command.alarm

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.command.data.AppSettings
import com.command.data.ConsentStore
import com.command.data.OccurrenceRepository
import com.command.data.VolumeRestore
import com.command.data.db.OccurrenceRow
import com.command.domain.model.ConsentKey
import com.command.domain.model.EventSource
import com.command.domain.occurrence.EventPayload
import com.command.domain.occurrence.EventType
import com.command.domain.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raises the alarm volume for the length of a ring, and only if the person agreed to it (the volume-control consent, which is
 * off until they turn it on). The volume the phone had is written down before it is touched and put back afterwards unless the
 * person has moved it since, so what they chose is never lost: not by a crash (the record is undone the next time the app runs),
 * not by two rings, and not by a refusal of the platform to let the app do it at all. Nothing but the alarm stream is touched, and
 * the ring and media volumes never are.
 */
@Singleton
class AlarmVolumePolicy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consents: ConsentStore,
    private val settings: AppSettings,
    private val repository: OccurrenceRepository,
    private val clock: Clock,
    private val session: RingSession,
) {
    private val lock = Mutex()

    /** Restores outlive the service that asked for them: it is already being torn down when the ring ends. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val audio: AudioManager? get() = context.getSystemService(AudioManager::class.java)

    /** Raises the alarm volume to its highest for [row]'s ring, if allowed and not already there. True if it did. */
    suspend fun raiseFor(row: OccurrenceRow): Boolean = lock.withLock {
        if (!consents.isGranted(ConsentKey.VOLUME_CONTROL)) return false
        val manager = audio ?: return false
        val highest = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val current = manager.getStreamVolume(AudioManager.STREAM_ALARM)
        if (current >= highest) {
            // Already as loud as it goes. If that is what an earlier ring of this run raised, the raised volume now belongs to this ring:
            // the earlier ring's delayed clean-up then finds a record that is not its own and leaves the volume alone while this one sounds.
            val record = settings.volumeRestore()
            if (record != null && record.occurrenceId != row.id && record.set == current) {
                settings.saveVolumeRestore(record.copy(occurrenceId = row.id))
            }
            return false
        }
        // Written first, so that a ring that dies half-way leaves what is needed to undo it.
        settings.saveVolumeRestore(VolumeRestore(row.id, original = current, set = highest, atUtc = clock.nowMs()))
        try {
            manager.setStreamVolume(AudioManager.STREAM_ALARM, highest, 0)
        } catch (e: RuntimeException) {
            if (!isRefusal(e)) throw e
            settings.clearVolumeRestore()
            repository.logEvent(EventType.VOLUME_SET_REFUSED, row, EventSource.SERVICE, EventPayload.EMPTY)
            return false
        }
        true
    }

    /** Puts the volume back after [occurrenceId]'s ring, unless the person has changed it since. */
    suspend fun restoreFor(occurrenceId: String) {
        lock.withLock { undo(only = occurrenceId) }
    }

    /** From the service, which cannot wait for the database: the work is done on the policy's own scope. */
    fun restoreSoonFor(occurrenceId: String) {
        launchSafely { restoreFor(occurrenceId) }
    }

    /**
     * Puts back whatever a ring that is over left raised, whichever ring it was. A ring's end queues it, so by the time it runs another ring
     * may be sounding, and what that one raised is its own to put back: like [restoreStale] it looks at the live service under the lock and
     * leaves the record alone while anything is ringing.
     */
    fun restoreAnySoon() {
        launchSafely { restoreStale() }
    }

    /**
     * Nothing here may take the process down: it runs while an alarm may be ringing. A restore that fails for a reason nobody
     * expected leaves its record in place, so the next reconcile tries again.
     */
    private fun launchSafely(work: suspend () -> Unit) {
        scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the alarm volume could not be put back this time; the next reconcile tries again", e)
            }
        }
    }

    /**
     * After a crash, a kill, a force-stop or a reboot in the middle of a ring: undoes what a ring that no longer exists raised. While a
     * ring is sounding in this process, whichever it is, what it raised is kept, so opening the app to answer an alarm never turns the
     * alarm down. What the database says about the occurrence cannot decide this: a ring that was killed stays "firing" there.
     */
    suspend fun restoreStale() {
        lock.withLock {
            settings.volumeRestore() ?: return
            if (session.isRinging) return
            undo(only = null)
        }
    }

    /**
     * What an erase could not finish putting back, kept in memory: the record of it is in the database, which the erase deletes, and trying
     * again needs to know what the volume was. [seen] is what the volume stood at when the attempt ended, so that a level the person sets
     * themselves afterwards is told from the one the platform left. Guarded by [lock].
     */
    private class Unfinished(val original: Int, val seen: Int)

    @Volatile private var unfinished: Unfinished? = null

    /**
     * An erase is under way. Only then is a restore that falls short remembered, whoever did the restore: the ring's own clean-up runs on
     * its own scope as the erase ends the ring, and the erase must still be able to say that the volume is not what the person had. (An
     * ordinary ring that ends on a phone with a floor leaves nothing behind.)
     */
    @Volatile private var erasing = false

    fun eraseBegan() {
        erasing = true
    }

    /** The erase failed before it deleted anything: the app goes on as it was. */
    fun eraseAbandoned() {
        erasing = false
    }

    /** Whether an erase left the volume unrestored, so that [retryUnfinishedRestore] has something to try. */
    val hasUnfinishedRestore: Boolean get() = unfinished != null

    /**
     * Puts the volume back now, whatever is ringing: for erasing everything, which has ended every ring first and is about to delete the
     * record that says what the volume was. False when the volume is not what the person had, because the platform refused to put it back
     * or would only take it as low as its own floor. What was wanted is kept, so that [retryUnfinishedRestore] can try again once the record
     * is gone; the caller says so in the meantime.
     */
    suspend fun restoreNow(): Boolean = lock.withLock {
        val record = settings.volumeRestore()
        if (record == null) {
            // Nothing is recorded: nothing was raised, or the ring's own clean-up has already put it back. If that fell short, it noted so
            // (an erase is under way), and the volume is tried again from the note.
            return@withLock retryLocked()
        }
        val exact = undo(only = null)
        if (exact) {
            unfinished = null
        } else if (unfinished == null) {
            // A refusal keeps the record; the note of what was wanted is made here (a level only reached its floor was noted by the restore).
            unfinished = Unfinished(record.original, audio?.getStreamVolume(AudioManager.STREAM_ALARM) ?: record.set)
        }
        exact
    }

    /**
     * After an erase that could not put the volume back and has since deleted its record: tries again, from what was kept. True when it is
     * done: the volume is back, or the person has set it themselves since (what they chose is theirs). False when the platform refuses
     * still, or still keeps it above its floor. Needs no database.
     */
    suspend fun retryUnfinishedRestore(): Boolean = lock.withLock { retryLocked() }

    /** [retryUnfinishedRestore], for a caller that holds [lock]. */
    private fun retryLocked(): Boolean {
        val left = unfinished ?: return true
        val manager = audio
        if (manager == null || manager.getStreamVolume(AudioManager.STREAM_ALARM) != left.seen) {
            unfinished = null
            return true
        }
        try {
            manager.setStreamVolume(AudioManager.STREAM_ALARM, left.original, 0)
        } catch (e: RuntimeException) {
            if (!isRefusal(e)) throw e
            return false
        }
        val now = manager.getStreamVolume(AudioManager.STREAM_ALARM)
        return if (now == left.original) {
            unfinished = null
            true
        } else {
            unfinished = Unfinished(left.original, now)
            false
        }
    }

    /**
     * Puts back what the ring of [only] (or any ring, when it is null) raised. True when the volume is as the person would want it
     * afterwards: put back exactly, moved by the person since (what they chose is theirs), or there was nothing to put back. False when the
     * platform refused (the record stays, so that the next reconcile tries again) or took it only as far as its own floor allows (the record
     * is cleared, because nothing more can be done, and the log says what was wanted and what came out).
     */
    private suspend fun undo(only: String?): Boolean {
        val record = settings.volumeRestore() ?: return true
        if (only != null && record.occurrenceId != only) return true
        var exact = true
        val manager = audio
        if (manager != null) {
            if (manager.getStreamVolume(AudioManager.STREAM_ALARM) == record.set) {
                try {
                    manager.setStreamVolume(AudioManager.STREAM_ALARM, record.original, 0)
                } catch (e: RuntimeException) {
                    if (!isRefusal(e)) throw e
                    // Refused (Do Not Disturb can do that). The record stays, so that the next reconcile puts the volume back.
                    repository.get(record.occurrenceId)?.let {
                        repository.logEvent(EventType.VOLUME_SET_REFUSED, it, EventSource.SERVICE, EventPayload.of("phase" to "restore"))
                    }
                    return false
                }
                val now = manager.getStreamVolume(AudioManager.STREAM_ALARM)
                if (now == record.set && now != record.original) {
                    // Nothing moved: the platform dropped the change without an error, which is what Android 17's background-audio hardening does
                    // to a call from outside a foreground service (measured on API 37: the reconcile after a ring that died). Like a refusal, the
                    // record stays, so that a later reconcile, or the app's own screen, puts the volume back (amendment 39).
                    repository.get(record.occurrenceId)?.let {
                        repository.logEvent(EventType.VOLUME_SET_REFUSED, it, EventSource.SERVICE, EventPayload.of("phase" to "restore", "wanted" to record.original, "got" to now))
                    }
                    return false
                }
                if (now != record.original) {
                    // The platform keeps an app without its privileged audio permission above a floor of its own, so a level below it cannot be
                    // put back. The record is cleared (nothing more can be done) but the log says what was wanted and what came out, and the
                    // caller is told that the volume is not what the person had.
                    exact = false
                    if (erasing) unfinished = Unfinished(record.original, now)
                    repository.get(record.occurrenceId)?.let {
                        repository.logEvent(EventType.VOLUME_SET_REFUSED, it, EventSource.SERVICE, EventPayload.of("phase" to "restore", "wanted" to record.original, "got" to now))
                    }
                }
            } else {
                // The person has moved it since: what they chose now is theirs, so it is left as it is.
                repository.get(record.occurrenceId)?.let { repository.logEvent(EventType.VOLUME_RESTORE_SKIPPED, it, EventSource.SERVICE, EventPayload.EMPTY) }
            }
        }
        settings.clearVolumeRestore()
        return exact
    }

    private companion object {
        const val TAG = "AlarmVolumePolicy"

        /**
         * The platform saying no to a volume change: a SecurityException (Do Not Disturb can do that), or the IllegalStateException that Android
         * 17's background-audio hardening throws when it is set to throw (measured on API 37). Either is handled as a refusal, never let through,
         * because the reconcile that asks for a restore re-arms alarms after it (amendment 39).
         */
        fun isRefusal(e: RuntimeException): Boolean = e is SecurityException || e is IllegalStateException
    }
}
