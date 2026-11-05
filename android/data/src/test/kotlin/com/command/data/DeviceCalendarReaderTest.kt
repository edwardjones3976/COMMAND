package com.command.data

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.command.domain.calendar.CalendarRead
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** A calendar provider that answers from fixed rows and writes down what it was asked. */
class FakeCalendarProvider : ContentProvider() {
    var refuse = false
    var noCursorFor: String? = null
    val asked = ArrayList<Uri>()
    var eventsSelection: String? = null
    val projections = ArrayList<Array<out String>>()

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (refuse) throw SecurityException("no")
        asked += uri
        projections += projection!!
        if (noCursorFor != null && uri.path!!.startsWith(noCursorFor!!)) return null
        val c = MatrixCursor(projection)
        when {
            uri.path!!.startsWith("/calendars") -> c.addRow(arrayOf<Any?>(7L, "me@example.test", "Work"))
            uri.path!!.startsWith("/instances") -> {
                c.addRow(arrayOf<Any?>(100L, 1000L, 2000L, "Standup", 0, 7L, "UTC", null, null, CalendarContract.Events.STATUS_CONFIRMED, 0))
                c.addRow(arrayOf<Any?>(101L, 3000L, 4000L, null, 1, 7L, null, 100L, 1000L, CalendarContract.Events.STATUS_CANCELED, 1))
            }
            uri.path!!.startsWith("/events") -> {
                eventsSelection = selection
                c.addRow(arrayOf<Any?>(100L))
            }
        }
        return c
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("the calendar is never written to")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("the calendar is never written to")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("the calendar is never written to")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceCalendarReaderTest {
    private lateinit var provider: FakeCalendarProvider
    private lateinit var reader: DeviceCalendarReader
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        reader = DeviceCalendarReader(app)
    }

    private fun read() = runBlocking { reader.read(500, 5000, setOf(7), setOf(100, 300)) }

    @Test
    fun `a read gives calendars, instances with their exceptions, and the events that are gone`() {
        val ok = read() as CalendarRead.Ok
        assertEquals("Work", ok.calendars.single().displayName)
        assertEquals(setOf(300L), ok.goneEventIds)
        val (plain, exception) = ok.instances
        assertEquals("Standup", plain.title)
        assertEquals(null, plain.originalId)
        assertFalse(plain.cancelled || plain.deleted || plain.allDay)
        assertEquals(100L, exception.originalId)
        assertEquals(1000L, exception.originalInstanceMs)
        assertTrue(exception.cancelled && exception.deleted && exception.allDay)
    }

    @Test
    fun `the window is in the uri and no projection asks for a description`() {
        read()
        val instances = provider.asked.single { it.path!!.startsWith("/instances") }
        assertEquals(listOf("500", "5000"), instances.pathSegments.takeLast(2))
        for (p in provider.projections) assertFalse(p.toList().toString(), p.any { it.equals(CalendarContract.Events.DESCRIPTION, ignoreCase = true) })
    }

    @Test
    fun `no cursor is a failed read`() {
        for (path in listOf("/calendars", "/instances", "/events")) {
            provider.noCursorFor = path
            assertTrue(path, read() is CalendarRead.Failed)
        }
    }

    @Test
    fun `a refusal with the permission held is a failed read, without it a lost permission`() {
        provider.refuse = true
        assertTrue(read() is CalendarRead.Failed)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        assertEquals(CalendarRead.PermissionLost, read())
    }

    @Test
    fun `an event the calendar cancelled counts as gone, because the provider may not expand it at all`() {
        read()
        assertTrue(provider.eventsSelection!!, provider.eventsSelection!!.contains("${CalendarContract.Events.STATUS} != ${CalendarContract.Events.STATUS_CANCELED}"))
    }
}
