package com.command.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class WavTest {

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun wav(
        samples: ShortArray,
        rate: Int = 24_000,
        channels: Int = 1,
        bits: Int = 16,
        format: Int = 1,
        declaredData: Int? = null,
        extraChunk: ByteArray? = null,
    ): ByteArray {
        val data = ByteArrayOutputStream().also { out -> for (s in samples) out.write(le16(s.toInt())) }.toByteArray()
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray())
        body.write("fmt ".toByteArray()); body.write(le32(16)); body.write(le16(format)); body.write(le16(channels))
        body.write(le32(rate)); body.write(le32(rate * channels * bits / 8)); body.write(le16(channels * bits / 8)); body.write(le16(bits))
        if (extraChunk != null) {
            body.write("LIST".toByteArray()); body.write(le32(extraChunk.size)); body.write(extraChunk)
            if (extraChunk.size % 2 == 1) body.write(0)
        }
        body.write("data".toByteArray()); body.write(le32(declaredData ?: data.size)); body.write(data)
        val all = ByteArrayOutputStream()
        all.write("RIFF".toByteArray()); all.write(le32(body.size())); all.write(body.toByteArray())
        return all.toByteArray()
    }

    @Test
    fun `mono 16-bit audio is read back sample for sample, with its rate`() {
        val samples = shortArrayOf(0, 1000, -1000, 32767, -32768, 5)
        val audio = Wav.read(wav(samples))!!
        assertArrayEquals(samples, audio.pcm)
        assertEquals(24_000, audio.sampleRate)
    }

    @Test
    fun `stereo is averaged to mono`() {
        val audio = Wav.read(wav(shortArrayOf(100, 300, -200, 200), channels = 2))!!
        assertArrayEquals(shortArrayOf(200, 0), audio.pcm)
    }

    @Test
    fun `an engine that streams leaves the length unknown, and the end of the file is the end of the audio`() {
        val samples = shortArrayOf(1, 2, 3, 4)
        assertArrayEquals(samples, Wav.read(wav(samples, declaredData = 0))!!.pcm)
        assertArrayEquals(samples, Wav.read(wav(samples, declaredData = -1))!!.pcm)
        assertArrayEquals(samples, Wav.read(wav(samples, declaredData = 1_000_000))!!.pcm)
    }

    @Test
    fun `another chunk before the audio is skipped, including one of odd length`() {
        val samples = shortArrayOf(7, 8, 9)
        assertArrayEquals(samples, Wav.read(wav(samples, extraChunk = ByteArray(10)))!!.pcm)
        assertArrayEquals(samples, Wav.read(wav(samples, extraChunk = ByteArray(7)))!!.pcm)
    }

    @Test
    fun `the extensible format that some engines write is accepted`() {
        assertNotNull(Wav.read(wav(shortArrayOf(1, 2, 3), format = 0xFFFE)))
    }

    @Test
    fun `anything that is not 16-bit PCM audio is refused rather than played as noise`() {
        assertNull(Wav.read(ByteArray(0)))
        assertNull(Wav.read(ByteArray(100)))
        assertNull(Wav.read("RIFFxxxxWAVEnope".toByteArray() + ByteArray(40)))
        assertNull("8-bit", Wav.read(wav(shortArrayOf(1, 2, 3, 4), bits = 8)))
        assertNull("float", Wav.read(wav(shortArrayOf(1, 2, 3, 4), format = 3)))
        assertNull("six channels", Wav.read(wav(shortArrayOf(1, 2, 3, 4, 5, 6), channels = 6)))
        assertNull("no audio at all", Wav.read(wav(ShortArray(0))))
    }

    @Test
    fun `a chunk that claims to be almost four gigabytes ends the walk instead of wrapping round it`() {
        val header = wav(shortArrayOf(1, 2, 3), extraChunk = ByteArray(0))
        // Rewrite the LIST chunk's size (right after the format chunk) to a value near the top of the range.
        val listAt = String(header, Charsets.US_ASCII).indexOf("LIST")
        val broken = header.copyOf()
        le32(0x7FFFFFF0).copyInto(broken, listAt + 4)
        assertNull(Wav.read(broken))
        le32(-8).copyInto(broken, listAt + 4)
        assertNull(Wav.read(broken))
    }
}
