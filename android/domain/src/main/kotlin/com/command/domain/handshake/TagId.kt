package com.command.domain.handshake

/**
 * A tag's low-level id (its UID), held only long enough to hash it. It is copied in, so the caller's array can be cleared or reused, and it
 * is never logged, stored or shown: [toString] is redacted, and only [TokenHash.ofTag] reads its hex.
 */
class TagId(bytes: ByteArray) {
    private val bytes = bytes.copyOf()

    /** The tag gave no id at all. */
    val isEmpty: Boolean get() = bytes.isEmpty()

    /** ISO 14443-3 random NFC-A UID: 4 bytes, first byte 0x08. It changes on every touch, so it can never be a registered tag. */
    val isRandom: Boolean get() = bytes.size == 4 && bytes[0] == RANDOM_UID_FIRST_BYTE

    /** Lowercase hex, no separators. Used only by [TokenHash.ofTag]. */
    internal fun hex(): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    override fun equals(other: Any?): Boolean = other is TagId && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "TagId(redacted)"

    private companion object {
        const val HEX = "0123456789abcdef"
        const val RANDOM_UID_FIRST_BYTE: Byte = 0x08
    }
}

/** What one touch produced. Only this crosses from the reader thread to the rest of the app: never the id itself. */
sealed interface TagRead {
    /** The tag gave no id (null or empty). Not a try; cannot be registered. */
    data object NoId : TagRead

    /** A random id (see [TagId.isRandom]). Not a try; cannot be registered. */
    data object Changing : TagRead

    /** A usable tag, carried only as its fingerprint ([TokenHash.ofTag]). */
    class Seen(val hash: String) : TagRead {
        override fun equals(other: Any?): Boolean = other is Seen && other.hash == hash

        override fun hashCode(): Int = hash.hashCode()

        override fun toString(): String = "TagRead.Seen(redacted)"
    }

    companion object {
        /** Copies, classifies and hashes. Call this on the reader thread; the bytes are not kept. */
        fun of(id: ByteArray?): TagRead {
            val tag = TagId(id ?: ByteArray(0))
            return when {
                tag.isEmpty -> NoId
                tag.isRandom -> Changing
                else -> Seen(TokenHash.ofTag(tag))
            }
        }
    }
}
