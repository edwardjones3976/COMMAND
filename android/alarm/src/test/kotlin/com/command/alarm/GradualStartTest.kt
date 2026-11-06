package com.command.alarm

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.command.testing.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GradualStartTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(nowMs = 1_000_000, elapsedMs = 5_000)
    private val preferences = RingPreferences(context)

    @Before
    fun setUp() {
        ShadowMediaPlayer.setMediaInfoProvider { _: DataSource -> ShadowMediaPlayer.MediaInfo(60_000, 0) }
    }

    private fun output() = AndroidRingOutput(context, preferences, clock)

    private fun gain(o: AndroidRingOutput): Float = shadowOf(o.tone!!).leftVolume

    private fun step(ms: Long) {
        clock.advance(ms)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    // --- the curve -----------------------------------------------------------------------------------------------------

    @Test
    fun `the gain is audible at the start, only ever rises, and is full from ten seconds on`() {
        assertEquals(0.063f, GradualStart.gainAt(0), 0.001f)
        var last = 0f
        for (ms in 0L..10_000L step 250) {
            val g = GradualStart.gainAt(ms)
            assertTrue("at $ms", g > last)
            last = g
        }
        assertEquals(1f, GradualStart.gainAt(10_000), 0f)
        assertEquals(1f, GradualStart.gainAt(600_000), 0f)
        assertEquals("time cannot run back past the start", GradualStart.gainAt(0), GradualStart.gainAt(-5_000), 0f)
    }

    // --- off, which is the default -----------------------------------------------------------------------------------------

    @Test
    fun `it is off until the person turns it on, and then the tone is at full from its first moment`() {
        assertFalse(preferences.gradualStart)
        val o = output()
        o.start()
        assertNotNull("a tone is playing", o.tone)
        assertEquals(1f, gain(o), 0f)
        o.stop()
    }

    @Test
    fun `off, a spoken line turns the tone down as far as it always has, and back to full`() {
        val o = output()
        o.start()
        o.duck(true)
        assertEquals(0.3f, gain(o), 0f)
        o.duck(false)
        assertEquals(1f, gain(o), 0f)
        o.stop()
    }

    // --- on --------------------------------------------------------------------------------------------------------------

    @Test
    fun `on, the tone starts quiet and rises to full in about ten seconds`() {
        preferences.setGradualStart(true)
        val o = output()
        o.start()
        assertEquals(GradualStart.gainAt(0), gain(o), 0f)
        step(5_000)
        assertEquals(GradualStart.gainAt(5_000), gain(o), 0.01f)
        step(5_000)
        assertEquals(1f, gain(o), 0f)
        o.stop()
    }

    @Test
    fun `the alarm stream is not touched by the rise`() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
        preferences.setGradualStart(true)
        val o = output()
        o.start()
        step(11_000)
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        o.stop()
    }

    @Test
    fun `the next cycle of the same ring is at full, and is not started quiet again`() {
        preferences.setGradualStart(true)
        val o = output()
        o.start()
        step(30_000)
        o.stop() // the gap between two cycles
        step(5_000)
        o.start()
        assertEquals(1f, gain(o), 0f)
        o.stop()
    }

    @Test
    fun `a gap inside the rise does not start it again`() {
        preferences.setGradualStart(true)
        val o = output()
        o.start()
        step(4_000)
        o.stop()
        step(2_000)
        o.start()
        assertEquals(GradualStart.gainAt(6_000), gain(o), 0.01f)
        o.stop()
    }

    @Test
    fun `a line spoken during the rise never pushes the tone below where the rise has it, and the rise goes on`() {
        preferences.setGradualStart(true)
        val o = output()
        o.start()
        o.duck(true)
        assertEquals("below the level of a duck, it stays where it is", GradualStart.gainAt(0), gain(o), 0f)
        step(9_000)
        assertEquals("above it, it waits at that level", 0.3f, gain(o), 0.001f)
        o.duck(false)
        assertEquals(GradualStart.gainAt(9_000), gain(o), 0.01f)
        step(1_000)
        assertEquals(1f, gain(o), 0f)
        o.stop()
    }

    @Test
    fun `stopping ends the rise`() {
        preferences.setGradualStart(true)
        val o = output()
        o.start()
        o.stop()
        step(11_000) // nothing is left to run into a player that is gone
        assertEquals(null, o.tone)
    }

    // --- the setting ---------------------------------------------------------------------------------------------------

    @Test
    fun `the setting survives a restart, and no database is needed to read it`() {
        preferences.setGradualStart(true)
        assertTrue(RingPreferences(context).gradualStart)
        preferences.setGradualStart(false)
        assertFalse(RingPreferences(context).gradualStart)
    }

    @Test
    fun `a change reaches the next ring, and one already rising is left to finish`() {
        val o = output()
        preferences.setGradualStart(true)
        o.start()
        preferences.setGradualStart(false)
        assertEquals(GradualStart.gainAt(0), gain(o), 0f)
        o.stop()
        preferences.setGradualStart(false)
        val next = output()
        next.start()
        assertEquals(1f, gain(next), 0f)
        next.stop()
    }
}
