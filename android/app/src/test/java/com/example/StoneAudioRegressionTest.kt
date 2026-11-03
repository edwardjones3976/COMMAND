package com.example

import com.example.oldchain.OldClonedSpectralDsp
import com.example.service.VoiceDirectorEngine
import com.example.service.VoiceMastering
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Same-input comparison of the old and new audio chains at Stone's settings (boost 145%). Writes WAVs for
 * measurement and listening; it only runs when STONE_INPUT_WAV (a 16-bit mono recording) and
 * STONE_OUTPUT_DIR are set, so ordinary test runs skip it.
 */
class StoneAudioRegressionTest {

    @Test
    fun `render stone's reveille through the old and new chains`() {
        val input = System.getenv("STONE_INPUT_WAV")
        val outDir = System.getenv("STONE_OUTPUT_DIR")
        assumeTrue("set STONE_INPUT_WAV and STONE_OUTPUT_DIR to run", input != null && outDir != null)
        val out = File(outDir!!).apply { mkdirs() }

        val (pcm, rate) = VoiceDirectorEngine.parseWav(File(input!!).readBytes())!!
        render(pcm, rate, out, "stone_reveille")

        // A lone click shows any echo the chain adds (the old one had a 28 ms slapback).
        val impulse = ShortArray(rate) { if (it == rate / 10) 20_000 else 0 }
        render(impulse, rate, out, "impulse")
    }

    private fun render(pcm: ShortArray, rate: Int, out: File, name: String) {
        val stoneBoostPercent = 145
        val new = VoiceMastering.master(pcm, rate, VoiceDirectorEngine.boostDbFor(stoneBoostPercent)).pcm
        val old = OldClonedSpectralDsp.process(pcm, rate, OldClonedSpectralDsp.stone, stoneBoostPercent / 100f)
        writeWav(File(out, "${name}_input.wav"), pcm, rate)
        writeWav(File(out, "${name}_new.wav"), new, rate)
        writeWav(File(out, "${name}_old.wav"), old, rate)
    }

    private fun writeWav(file: File, pcm: ShortArray, rate: Int) {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        pcm.forEach { b.putShort(it) }
        file.writeBytes(b.array())
    }
}
