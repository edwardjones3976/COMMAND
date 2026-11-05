package com.command.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import com.command.domain.calendar.CalendarInstance
import com.command.domain.calendar.CalendarRead
import com.command.domain.calendar.CalendarReader
import com.command.domain.calendar.DeviceCalendar
import com.command.domain.calendar.ReadFailure
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the phone's calendars and writes nothing to them. The description of an event is not in any projection here: import does not
 * need it. An instance with no cursor or a refused query is a failed read, which says nothing about any event.
 */
class DeviceCalendarReader(private val context: Context) : CalendarReader {
    private val resolver get() = context.contentResolver

    override fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun calendars(): List<DeviceCalendar>? = withContext(Dispatchers.IO) {
        try {
            readCalendars()
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override suspend fun read(fromMs: Long, toMs: Long, calendarIds: Set<Long>, askAbout: Set<Long>): CalendarRead = withContext(Dispatchers.IO) {
        try {
            val calendars = readCalendars() ?: return@withContext CalendarRead.Failed(ReadFailure.NO_CURSOR)
            val instances = if (calendarIds.isEmpty()) emptyList() else readInstances(fromMs, toMs, calendarIds) ?: return@withContext CalendarRead.Failed(ReadFailure.NO_CURSOR)
            val alive = if (askAbout.isEmpty()) emptySet() else readAlive(askAbout) ?: return@withContext CalendarRead.Failed(ReadFailure.NO_CURSOR)
            CalendarRead.Ok(calendars, instances, askAbout - alive)
        } catch (e: SecurityException) {
            if (hasPermission()) CalendarRead.Failed(ReadFailure.REFUSED) else CalendarRead.PermissionLost
        } catch (e: IllegalArgumentException) {
            CalendarRead.Failed(ReadFailure.BAD_QUERY)
        }
    }

    private fun readCalendars(): List<DeviceCalendar>? =
        resolver.query(CalendarContract.Calendars.CONTENT_URI, CALENDAR_PROJECTION, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(DeviceCalendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty()))
            }
        }

    private fun readInstances(fromMs: Long, toMs: Long, calendarIds: Set<Long>): List<CalendarInstance>? {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uri, fromMs)
        ContentUris.appendId(uri, toMs)
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",")})"
        return resolver.query(uri.build(), INSTANCE_PROJECTION, selection, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        CalendarInstance(
                            calendarId = c.getLong(5),
                            eventId = c.getLong(0),
                            beginMs = c.getLong(1),
                            endMs = c.getLong(2),
                            title = c.getString(3).orEmpty(),
                            allDay = c.getInt(4) != 0,
                            eventTz = c.getString(6),
                            originalId = c.longOrNull(7),
                            originalInstanceMs = c.longOrNull(8),
                            cancelled = !c.isNull(9) && c.getInt(9) == CalendarContract.Events.STATUS_CANCELED,
                            deleted = c.getInt(10) != 0,
                        ),
                    )
                }
            }
        }
    }

    private fun readAlive(ids: Set<Long>): Set<Long>? {
        val selection = "${CalendarContract.Events._ID} IN (${ids.joinToString(",")}) AND ${CalendarContract.Events.DELETED} = 0 " +
            "AND (${CalendarContract.Events.STATUS} IS NULL OR ${CalendarContract.Events.STATUS} != ${CalendarContract.Events.STATUS_CANCELED})"
        return resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID), selection, null, null)?.use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

    internal companion object {
        val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        )

        val INSTANCE_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.EVENT_TIMEZONE,
            CalendarContract.Instances.ORIGINAL_ID,
            CalendarContract.Instances.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Instances.STATUS,
            CalendarContract.Events.DELETED,
        )
    }
}

/** Its own module, so that a test can put a fake reader in its place and keep the rest of the data layer. */
@Module
@InstallIn(SingletonComponent::class)
object CalendarReaderModule {
    @Provides
    fun calendarReader(@ApplicationContext context: Context): CalendarReader = DeviceCalendarReader(context)
}
