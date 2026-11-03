package com.command.voice

/** Reads the audio an Android speech engine writes: a RIFF file of 16-bit samples, mono or stereo. */
internal object Wav {

    /** The samples as 16-bit mono (stereo is averaged), or null when the file is not something this can play. */
    fun read(bytes: ByteArray): SpokenAudio? {
        if (bytes.size < HEADER_MIN || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") return null
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataLength = 0
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = ascii(bytes, at, 4)
            val declared = le32(bytes, at + 4)
            val body = at + 8
            if (id == "fmt " && body + 16 <= bytes.size) {
                format = le16(bytes, body)
                channels = le16(bytes, body + 2)
                rate = le32(bytes, body + 4)
                bits = le16(bytes, body + 14)
            } else if (id == "data") {
                // A speech engine that streams its output cannot know the length when it writes the header, and leaves 0 or
                // all ones there; the file itself then says where the audio ends.
                val remaining = bytes.size - body
                dataStart = body
                dataLength = if (declared <= 0 || declared > remaining) remaining else declared
                break
            }
            // Chunks start on even offsets. The sum is made in Long: a size near the top of the range must end the walk, not wrap round.
            val next = body.toLong() + (declared.toLong() and 0xFFFFFFFFL) + (declared and 1)
            if (next > bytes.size) break
            at = next.toInt()
        }
        val plainPcm = format == PCM || format == EXTENSIBLE
        if (dataStart < 0 || !plainPcm || bits != 16 || channels !in 1..2 || rate <= 0) return null
        val frames = dataLength / (2 * channels)
        if (frames == 0) return null
        val pcm = ShortArray(frames) { i ->
            val first = sample(bytes, dataStart + i * 2 * channels)
            val mixed = if (channels == 1) first else (first + sample(bytes, dataStart + i * 2 * channels + 2)) / 2
            mixed.toShort()
        }
        return SpokenAudio(pcm, rate)
    }

    private fun sample(b: ByteArray, at: Int): Int = ((b[at + 1].toInt() shl 8) or (b[at].toInt() and 0xFF)).toShort().toInt()

    private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Int = le16(b, at) or (le16(b, at + 2) shl 16)

    private fun ascii(b: ByteArray, at: Int, length: Int): String = String(b, at, length, Charsets.US_ASCII)

    private const val HEADER_MIN = 44
    private const val PCM = 1
    private const val EXTENSIBLE = 0xFFFE
}
