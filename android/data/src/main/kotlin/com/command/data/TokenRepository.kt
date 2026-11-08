package com.command.data

import androidx.room.withTransaction
import com.command.data.db.CommandDb
import com.command.data.db.HandshakeTokenRow
import com.command.domain.dials.HandshakeMode
import com.command.domain.handshake.TokenHash
import com.command.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A registered code as the screens see it: its label and when it was registered. The hash stays in the table. */
data class Token(val id: String, val label: String, val createdAt: Long)

/** What became of registering a code. Each one has its own words on the screen. */
sealed interface RegisterResult {
    data class Saved(val id: String) : RegisterResult

    /** The code read as nothing but whitespace, which is not a code. */
    data object BlankPayload : RegisterResult

    /** The label, trimmed, is empty or longer than [TokenRepository.MAX_LABEL] characters. */
    data object BadLabel : RegisterResult

    /** The same code is already registered for this way, under [existingLabel]. */
    data class Duplicate(val existingLabel: String) : RegisterResult

    /** This way already has [TokenRepository.MAX_PER_MODE] codes. */
    data object Full : RegisterResult
}

/** What became of renaming a code: renamed, refused for its label as [RegisterResult.BadLabel] is, or deleted meanwhile. */
enum class RenameResult { Renamed, BadLabel, Gone }

/**
 * Where the codes a handshake step checks against are registered. A code is kept only as [TokenHash.of] its payload, with the person's
 * label: the payload is never stored or logged, and the hash is never handed out. The label goes nowhere but this table and the export.
 */
@Singleton
class TokenRepository @Inject constructor(private val db: CommandDb, private val clock: Clock) {
    private val gate = Mutex()

    /** The codes of [mode], in the order they were registered. */
    fun observe(mode: HandshakeMode): Flow<List<Token>> = db.handshakeTokens().observe(mode).map { rows -> rows.map { it.toToken() } }

    suspend fun count(mode: HandshakeMode): Int = db.handshakeTokens().count(mode)

    /**
     * Registers the code [payload] for [mode] under [label], both trimmed. A duplicate is told before a full way, since a code that is
     * already there needs no room. The checks and the insert are one transaction under one lock, so two registrations at once cannot
     * both pass them.
     */
    suspend fun register(mode: HandshakeMode, label: String, payload: String): RegisterResult {
        if (payload.isBlank()) return RegisterResult.BlankPayload
        val name = labelOrNull(label) ?: return RegisterResult.BadLabel
        val hash = TokenHash.of(payload)
        return gate.withLock {
            db.withTransaction {
                val dao = db.handshakeTokens()
                val existing = dao.labelOf(mode, hash)
                when {
                    existing != null -> RegisterResult.Duplicate(existing)
                    dao.count(mode) >= MAX_PER_MODE -> RegisterResult.Full
                    else -> {
                        val id = UUID.randomUUID().toString()
                        dao.insert(HandshakeTokenRow(id = id, mode = mode, label = name, hash = hash, createdAt = clock.nowMs()))
                        RegisterResult.Saved(id)
                    }
                }
            }
        }
    }

    /** Gives the code [id] the label [label], trimmed. Its hash is not touched. */
    suspend fun rename(id: String, label: String): RenameResult {
        val name = labelOrNull(label) ?: return RenameResult.BadLabel
        return if (db.handshakeTokens().rename(id, name) == 0) RenameResult.Gone else RenameResult.Renamed
    }

    /** Deletes the code [id]. One that is already gone is nothing to do. */
    suspend fun delete(id: String) {
        db.handshakeTokens().delete(id)
    }

    /** Whether [payload], trimmed, is a code registered for [mode]. A blank payload is no code and matches nothing. */
    suspend fun matches(mode: HandshakeMode, payload: String): Boolean {
        if (payload.isBlank()) return false
        return db.handshakeTokens().labelOf(mode, TokenHash.of(payload)) != null
    }

    private fun labelOrNull(label: String): String? {
        val name = label.trim()
        return name.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_LABEL }
    }

    private fun HandshakeTokenRow.toToken() = Token(id = id, label = label, createdAt = createdAt)

    companion object {
        /** The most characters a label may have, once trimmed. */
        const val MAX_LABEL = 40

        /** The most codes one way may have. */
        const val MAX_PER_MODE = 20
    }
}
