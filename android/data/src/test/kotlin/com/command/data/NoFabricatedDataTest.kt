package com.command.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.data.db.CalendarSourceRow
import com.command.domain.calendar.CalendarRead
import com.command.domain.calendar.DeviceCalendar
import com.command.domain.dials.Flexibility
import com.command.domain.model.ConsentKey
import com.command.testing.FakeCalendarReader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The previous version invented calendar events when it found none. An empty reader must give exactly nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoFabricatedDataTest {
    private lateinit var f: Fixture

    @Before
    fun setUp() {
        f = Fixture(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() = f.close()

    @Test
    fun `an empty reader yields zero rows`() = runBlocking {
        val reader = FakeCalendarReader()
        reader.result = CalendarRead.Ok(listOf(DeviceCalendar(7, "me@example.test", "Work")), emptyList(), emptySet())
        val missions = MissionRepository(f.db, f.repo, f.scheduler, f.clock, f.pauseEffects)
        val consents = ConsentStore(f.db, f.clock, f.repo, NoOpConsentRevocationListener)
        consents.grant(ConsentKey.CALENDAR_READ)
        f.db.calendarDao().insertSource(CalendarSourceRow(7, "me@example.test", "Work", true, null, Flexibility.FLEXIBLE, null))

        val outcome = CalendarSync(f.db, f.repo, missions, consents, reader, f.clock).run()

        assertEquals(CalendarSyncOutcome.DONE, outcome)
        assertEquals(1, reader.reads)
        assertEquals(0, f.db.missions().count())
        assertEquals(0, f.db.occurrences().all().size)
        assertEquals(0, f.db.calendarDao().linkCount())
        assertEquals(0, f.scheduler.armed.size)
    }
}
