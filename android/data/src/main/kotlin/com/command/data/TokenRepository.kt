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
 * Where the codes and tags a handshake step checks against are registered. A code or tag is kept only as its fingerprint ([TokenHash.of]
 * a code's payload, [TokenHash.ofTag] a tag's id), with the person's label: never the payload or the tag id, which are never stored or
 * logged, and the hash is never handed out. The label goes nowhere but this table and the export.
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
     *
     * Codes only. Tags go through [registerTag] / [matchesTag], which take a fingerprint made on the reader thread. For any [mode] but QR
     * this returns [RegisterResult.BlankPayload] without hashing or touching the database; it never throws for a mode.
     */
    suspend fun register(mode: HandshakeMode, label: String, payload: String): RegisterResult {
        if (mode != HandshakeMode.QR || payload.isBlank()) return RegisterResult.BlankPayload
        val name = labelOrNull(label) ?: return RegisterResult.BadLabel
        return registerHash(mode, name, TokenHash.of(payload))
    }

    /**
     * Saves a tag by its fingerprint ([TokenHash.ofTag], made on the reader thread: this never sees the tag id). [RegisterResult.BlankPayload]
     * when [hash] is not shaped like a hash ([TokenHash.isHash]). The same lock and transaction as a code: a duplicate is told before a full
     * way, [MAX_PER_MODE] is counted for tags alone, and the label rule is the same.
     */
    suspend fun registerTag(label: String, hash: String): RegisterResult {
        if (!TokenHash.isHash(hash)) return RegisterResult.BlankPayload
        val name = labelOrNull(label) ?: return RegisterResult.BadLabel
        return registerHash(HandshakeMode.NFC, name, hash)
    }

    /** True when [hash] is a registered tag's fingerprint. False for a malformed hash; never throws. */
    suspend fun matchesTag(hash: String): Boolean = TokenHash.isHash(hash) && matchesHash(HandshakeMode.NFC, hash)

    private suspend fun registerHash(mode: HandshakeMode, name: String, hash: String): RegisterResult = gate.withLock {
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

    private suspend fun matchesHash(mode: HandshakeMode, hash: String): Boolean = db.handshakeTokens().labelOf(mode, hash) != null

    /** Gives the code [id] the label [label], trimmed. Its hash is not touched. */
    suspend fun rename(id: String, label: String): RenameResult {
        val name = labelOrNull(label) ?: return RenameResult.BadLabel
        return if (db.handshakeTokens().rename(id, name) == 0) RenameResult.Gone else RenameResult.Renamed
    }

    /** Deletes the code [id]. One that is already gone is nothing to do. */
    suspend fun delete(id: String) {
        db.handshakeTokens().delete(id)
    }

    /**
     * Whether [payload], trimmed, is a code registered for [mode]. A blank payload is no code and matches nothing. Codes only: for any
     * [mode] but QR this is false, without hashing or touching the database (tags go through [matchesTag]); it never throws for a mode.
     */
    suspend fun matches(mode: HandshakeMode, payload: String): Boolean {
        if (mode != HandshakeMode.QR || payload.isBlank()) return false
        return matchesHash(mode, TokenHash.of(payload))
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
