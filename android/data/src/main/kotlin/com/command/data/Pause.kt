package com.command.data

import androidx.room.withTransaction
import com.command.data.db.CommandDb
import com.command.data.db.SettingRow
import com.command.domain.model.SettingKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A global pause: nothing that is due from [sinceUtcMs] up to [untilUtcMs] rings. It is not a state of any occurrence. It is two settings,
 * and the occurrences it holds back stay scheduled, unarmed, until it ends and the reconcile settles them: the ones that came due inside it
 * are recorded as missed because of it, the ones still to come are armed again. [sinceUtcMs] is kept so that only what came due inside
 * the pause is blamed on it.
 */
data class Pause(val sinceUtcMs: Long, val untilUtcMs: Long) {
    /** A pause whose end has passed is not in force, even if nothing has closed it yet. */
    fun isOver(nowMs: Long): Boolean = untilUtcMs <= nowMs
}

internal object PauseStore {

    suspend fun read(db: CommandDb): Pause? {
        val settings = db.settings()
        return of(settings.get(SettingKeys.GLOBAL_PAUSE_SINCE), settings.get(SettingKeys.GLOBAL_PAUSE_UNTIL))
    }

    /** The pause in force at [nowMs], or null. One that has run out but not been closed yet is not in force. */
    suspend fun active(db: CommandDb, nowMs: Long): Pause? = read(db)?.takeUnless { it.isOver(nowMs) }

    suspend fun write(db: CommandDb, pause: Pause) {
        db.withTransaction {
            db.settings().put(SettingRow(SettingKeys.GLOBAL_PAUSE_SINCE, pause.sinceUtcMs.toString()))
            db.settings().put(SettingRow(SettingKeys.GLOBAL_PAUSE_UNTIL, pause.untilUtcMs.toString()))
        }
    }

    suspend fun clear(db: CommandDb) {
        db.withTransaction {
            db.settings().remove(SettingKeys.GLOBAL_PAUSE_SINCE)
            db.settings().remove(SettingKeys.GLOBAL_PAUSE_UNTIL)
        }
    }

    fun observe(db: CommandDb): Flow<Pause?> =
        combine(db.settings().observe(SettingKeys.GLOBAL_PAUSE_SINCE), db.settings().observe(SettingKeys.GLOBAL_PAUSE_UNTIL), ::of).distinctUntilChanged()

    /** Without an end there is no pause; without a start (never written that way) the pause holds back what is still to come and blames nothing on itself. */
    private fun of(since: String?, until: String?): Pause? {
        val end = until?.trim()?.toLongOrNull() ?: return null
        val start = since?.trim()?.toLongOrNull() ?: end
        return Pause(minOf(start, end), end)
    }
}
