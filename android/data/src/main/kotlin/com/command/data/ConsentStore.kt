package com.command.data

import androidx.room.withTransaction
import com.command.data.db.CommandDb
import com.command.data.db.ConsentRow
import com.command.domain.model.ConsentCascade
import com.command.domain.model.ConsentKey
import com.command.domain.model.ConsentSource
import com.command.domain.model.EventSource
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.SkipReason
import com.command.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The platform half of a revocation: stop workers, delete cached files. Called after the database has committed. */
interface ConsentRevocationListener {
    suspend fun onRevoked(key: ConsentKey, purgedFilePaths: List<String>)
}

object NoOpConsentRevocationListener : ConsentRevocationListener {
    override suspend fun onRevoked(key: ConsentKey, purgedFilePaths: List<String>) = Unit
}

/**
 * Append-only consent history. A key is granted if its latest row says so. Withdrawing a consent withdraws what depends
 * on it and removes the data it allowed: cached AI directives, cached cloud-voice audio, imported calendar occurrences
 * that have not fired, or queued cloud writes.
 */
@Singleton
class ConsentStore @Inject constructor(
    private val db: CommandDb,
    private val clock: Clock,
    private val occurrences: OccurrenceRepository,
    private val listener: ConsentRevocationListener,
) {
    suspend fun isGranted(key: ConsentKey): Boolean = db.consents().latest(key)?.granted == true

    fun observe(key: ConsentKey): Flow<Boolean> = db.consents().observeLatest(key).map { it?.granted == true }

    suspend fun history(key: ConsentKey): List<ConsentRow> = db.consents().history(key)

    suspend fun grant(
        key: ConsentKey,
        source: ConsentSource = ConsentSource.USER,
        boundaryVersion: Int = 1,
        shownTextHash: String? = null,
    ) {
        db.consents().insert(ConsentRow(key = key, granted = true, atUtc = clock.nowMs(), source = source, boundaryVersion = boundaryVersion, shownTextHash = shownTextHash))
    }

    /** Revokes [key] and everything that depends on it. Keys that were not granted are left alone. */
    suspend fun revoke(key: ConsentKey, source: ConsentSource = ConsentSource.USER) {
        val revoked = ArrayList<ConsentKey>()
        val purgedFiles = HashMap<ConsentKey, List<String>>()
        db.withTransaction {
            for (k in ConsentCascade.withDependents(key)) {
                val current = db.consents().latest(k)
                if (current?.granted != true) continue
                db.consents().insert(ConsentRow(key = k, granted = false, atUtc = clock.nowMs(), source = source, boundaryVersion = current.boundaryVersion, shownTextHash = null))
                revoked += k
                when (k) {
                    ConsentKey.GEMINI_TEXT -> db.maintenance().clearDirectiveCache()
                    ConsentKey.GEMINI_VOICE -> {
                        purgedFiles[k] = db.maintenance().geminiRenderPaths()
                        db.maintenance().clearGeminiRenders()
                    }
                    ConsentKey.CLOUD_SYNC -> db.maintenance().clearSyncOutbox()
                    else -> Unit
                }
            }
        }
        if (ConsentKey.CALENDAR_READ in revoked) removeImportedCalendarOccurrences()
        for (k in revoked) listener.onRevoked(k, purgedFiles[k].orEmpty())
    }

    /** Imported occurrences that have not fired disappear as skipped (never as completed); fired ones are history. */
    private suspend fun removeImportedCalendarOccurrences() {
        for (id in db.maintenance().linkedOccurrenceIds()) {
            occurrences.apply(id, OccurrenceCommand.Skip(SkipReason.CALENDAR_REMOVED), EventSource.SYSTEM)
        }
        db.maintenance().clearCalendarLinks()
        db.maintenance().deselectCalendars()
    }
}
