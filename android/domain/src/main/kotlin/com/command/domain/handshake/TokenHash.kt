package com.command.domain.handshake

import java.security.MessageDigest

/**
 * The hash a registered code or tag is kept as: the payload or the tag id itself is never stored. It is the lowercase hex SHA-256 of the
 * UTF-8 bytes of a versioned prefix followed by what was read: [PREFIX] and the code's payload, trimmed of surrounding whitespace, for a
 * code; [NFC_PREFIX] and the lowercase hex of the id for a tag. The prefix names the kind and the version, so a later way of hashing, or a
 * code whose text happens to be a tag's hex, cannot be taken for this one.
 *
 * The hash is not keyed, on purpose: a per-install key would not survive a backup restore or an export, and every registered tag would stop
 * matching. A tag's id is not a secret (anyone holding the tag can read it); the tag proves the person got up.
 */
object TokenHash {
    const val PREFIX = "COMMAND-QR-v1\n"
    const val NFC_PREFIX = "COMMAND-NFC-v1\n"

    /** The hash of [payload]. An empty or blank payload is not a code, and is refused with an [IllegalArgumentException]. */
    fun of(payload: String): String {
        val trimmed = payload.trim()
        require(trimmed.isNotEmpty()) { "a blank payload is not a code" }
        return sha256Hex(PREFIX + trimmed)
    }

    /** Lowercase hex SHA-256 of UTF-8([NFC_PREFIX] + lowercase hex of the id). A tag with no id is refused with an [IllegalArgumentException]. */
    fun ofTag(id: TagId): String {
        require(!id.isEmpty) { "a tag with no id cannot be hashed" }
        return sha256Hex(NFC_PREFIX + id.hex())
    }

    /** True for a 64-character lowercase hex string (the shape every hash here has). */
    fun isHash(text: String): Boolean = text.length == 64 && text.all { it in HEX }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return hex.toString()
    }

    private const val HEX = "0123456789abcdef"
}
