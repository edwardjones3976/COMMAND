package com.command.domain.handshake

import java.security.MessageDigest

/**
 * The hash a registered code is kept as: the payload itself is never stored. It is the lowercase hex SHA-256 of the UTF-8 bytes of
 * [PREFIX] followed by the payload, trimmed of surrounding whitespace. The prefix names the version, so a later way of hashing cannot be
 * taken for this one.
 */
object TokenHash {
    const val PREFIX = "COMMAND-QR-v1\n"

    /** The hash of [payload]. An empty or blank payload is not a code, and is refused with an [IllegalArgumentException]. */
    fun of(payload: String): String {
        val trimmed = payload.trim()
        require(trimmed.isNotEmpty()) { "a blank payload is not a code" }
        val digest = MessageDigest.getInstance("SHA-256").digest((PREFIX + trimmed).toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return hex.toString()
    }

    private const val HEX = "0123456789abcdef"
}
