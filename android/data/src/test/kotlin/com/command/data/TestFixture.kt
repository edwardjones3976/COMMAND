package com.command.data

import android.content.Context
import com.command.data.db.CommandDb
import com.command.data.db.MissionRow
import com.command.data.db.RestWindowRow
import com.command.domain.alarm.CheckInPoster
import com.command.domain.alarm.WindowRoller
import com.command.domain.dials.Flexibility
import com.command.domain.dials.LockPolicy
import com.command.domain.model.MissionSource
import com.command.domain.model.RestKind
import com.command.domain.model.StartKind
import com.command.domain.model.ZoneMode
import com.command.testing.FakeAlarmScheduler
import com.command.testing.FakeClock
import com.command.testing.FakeRingController
import com.command.testing.FakeWorkEnqueuer
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

class RecordingPoster : CheckInPoster {
    val posted = ArrayList<Triple<String, Int, Int>>()
    override fun post(occurrenceId: String, requestCode: Int, index: Int) {
        posted += Triple(occurrenceId, requestCode, index)
    }
}

class CountingRoller : WindowRoller {
    var rolls = 0
    override fun roll() { rolls++ }
}

/** An in-memory database, a clock that stands still at a known instant, and fakes for every platform port. */
class Fixture(context: Context) {
    /** Monday 2026-11-02 09:00 UTC. */
    val t0: Long = ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    val clock = FakeClock(nowMs = t0, elapsedMs = 5_000)
    val scheduler = FakeAlarmScheduler()
    val work = FakeWorkEnqueuer()
    val poster = RecordingPoster()
    val roller = CountingRoller()
    val ringer = FakeRingController()
    val db: CommandDb = CommandDb.inMemory(context)
    val repo = OccurrenceRepository(db, clock, scheduler, work, poster, roller, ringer)

    suspend fun mission(
        title: String = "Write the report",
        flexibility: Flexibility = Flexibility.FLEXIBLE,
        lock: LockPolicy? = null,
        profileId: String? = null,
        source: MissionSource = MissionSource.MANUAL,
    ): MissionRow = missionRow(title, flexibility, lock, profileId, source, startAt = t0).also { db.missions().insert(it) }

    suspend fun restWindowCoveringNow() {
        // t0 is Monday 09:00 UTC; the clock zone is UTC.
        db.restWindows().insert(RestWindowRow(weekday = 1, startLocal = "08:00", endLocal = "10:00", kind = RestKind.SLEEP))
    }

    fun close() = db.close()

    companion object {
        fun missionRow(
            title: String = "Write the report",
            flexibility: Flexibility = Flexibility.FLEXIBLE,
            lock: LockPolicy? = null,
            profileId: String? = null,
            source: MissionSource = MissionSource.MANUAL,
            startAt: Long = 0L,
        ) = MissionRow(
            id = UUID.randomUUID().toString(), title = title, firstAction = "Open the document", category = "Work", priority = 2,
            flexibility = flexibility, rigidWeekdays = null, source = source, startKind = StartKind.ONE_OFF, startAtUtc = startAt,
            dtstartLocal = null, rrule = null, zoneMode = ZoneMode.DEVICE, zoneId = null, durationMin = 30, starterMinutes = 5,
            profileId = profileId, lockPolicy = lock, launcherPackage = null, firstActionVariantsJson = "[]", enabled = true,
            deletedAt = null, syncRev = 0, syncDirty = false,
        )
    }
}
