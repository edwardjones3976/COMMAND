package com.command.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.data.MissionBrief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the ring says first: the app's own words around the person's, which are kept in segments of their own. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReveilleLinesTest {

    private val lines = ReveilleLines(ApplicationProvider.getApplicationContext<Context>())

    private fun brief(title: String, step: String) = MissionBrief(title, step, starterMinutes = 5)

    @Test
    fun `a mission with a title and a first step is read as both, and the person's words are marked as theirs`() {
        val line = lines.reveille(brief("Write the report", "Open the document"))
        assertEquals("Time to start: Write the report. First step: Open the document.", line.text)
        assertEquals(listOf(false, true, false, true), line.segments.map { it.carriesUserText })
    }

    @Test
    fun `without a first step only the title is read`() {
        val line = lines.reveille(brief("Write the report", "   "))
        assertEquals("Time to start: Write the report.", line.text)
        assertEquals(listOf(false, true), line.segments.map { it.carriesUserText })
    }

    @Test
    fun `without a title it still says it is time, and then the first step`() {
        val line = lines.reveille(brief("", "Open the document"))
        assertEquals("Time to start. First step: Open the document.", line.text)
        assertEquals(listOf(false, false, true), line.segments.map { it.carriesUserText })
    }

    @Test
    fun `with nothing known about the mission it says only that it is time`() {
        val line = lines.reveille(null)
        assertEquals("Time to start.", line.text)
        assertEquals(listOf(false), line.segments.map { it.carriesUserText })
    }

    @Test
    fun `spaces are trimmed and a closing full stop is not said twice`() {
        assertEquals("Time to start: Call mum. First step: Dial.", lines.reveille(brief("  Call mum.  ", "Dial..")).text)
        assertEquals("a title of nothing but full stops is no title", "Time to start.", lines.reveille(brief("...", "")).text)
    }

    @Test
    fun `the person's words are only ever in the segments marked as theirs`() {
        val line = lines.reveille(brief("Zebra crossing", "Hold the pen"))
        val ours = line.segments.filterNot { it.carriesUserText }.joinToString(" ") { it.text }
        assertFalse(ours.contains("Zebra"))
        assertFalse(ours.contains("pen"))
        assertEquals(listOf("Zebra crossing.", "Hold the pen."), line.segments.filter { it.carriesUserText }.map { it.text })
    }

    @Test
    fun `a title that ends in a question mark or an exclamation mark is not given a full stop as well`() {
        assertEquals("Time to start: Ready? First step: Go!", lines.reveille(brief("Ready?", "Go!")).text)
        assertEquals("trailing full stops after the mark are dropped", "Time to start: Done?", lines.reveille(brief("Done?..", "")).text)
    }
}
