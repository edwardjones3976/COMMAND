package com.command.domain

import com.command.domain.calendar.Conflict
import com.command.domain.calendar.ConflictDetector
import com.command.domain.calendar.TimeInterval
import com.command.domain.model.ConflictKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ConflictDetectorTest {
    private fun i(id: String, start: Long, end: Long) = TimeInterval(id, start, end)

    @Test
    fun `apart or touching is no conflict`() {
        assertEquals(emptyList<Conflict>(), ConflictDetector.detect(listOf(i("a", 0, 10), i("b", 10, 20), i("c", 30, 40))))
    }

    @Test
    fun `a partial overlap is found whatever order the intervals arrive in`() {
        assertEquals(listOf(Conflict("a", "b", ConflictKind.OVERLAP)), ConflictDetector.detect(listOf(i("b", 5, 15), i("a", 0, 10))))
    }

    @Test
    fun `a short interval nested in a long one does not hide the next one`() {
        val found = ConflictDetector.detect(listOf(i("long", 0, 100), i("short", 10, 20), i("late", 50, 150)))
        assertEquals(
            listOf(Conflict("long", "short", ConflictKind.NESTED), Conflict("long", "late", ConflictKind.OVERLAP)),
            found,
        )
    }

    @Test
    fun `equal start puts the longer first and calls the shorter nested`() {
        assertEquals(listOf(Conflict("long", "short", ConflictKind.NESTED)), ConflictDetector.detect(listOf(i("short", 0, 5), i("long", 0, 9))))
    }

    @Test
    fun `an interval without length conflicts with nothing`() {
        assertEquals(emptyList<Conflict>(), ConflictDetector.detect(listOf(i("a", 0, 10), i("empty", 5, 5))))
    }

    @Test
    fun `a new interval is compared with every one still open`() {
        val found = ConflictDetector.detect(listOf(i("long", 0, 100), i("mid", 10, 60), i("late", 50, 150)))
        assertEquals(setOf("long" to "mid", "long" to "late", "mid" to "late"), found.map { it.a to it.b }.toSet())
    }
}
