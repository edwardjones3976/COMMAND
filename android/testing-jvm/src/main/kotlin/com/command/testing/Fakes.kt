package com.command.testing

import com.command.domain.alarm.AlarmRequest
import com.command.domain.alarm.AlarmScheduler
import com.command.domain.alarm.PauseEffects
import com.command.domain.alarm.RingController
import com.command.domain.alarm.WorkEnqueuer
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.WorkKind
import com.command.domain.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock the test moves by hand. */
class FakeClock(
    var nowMs: Long = 0L,
    var elapsedMs: Long = 0L,
    var zone: ZoneId = ZoneOffset.UTC,
) : Clock {
    override fun nowMs(): Long = nowMs
    override fun elapsedRealtimeMs(): Long = elapsedMs
    override fun zone(): ZoneId = zone

    /** Moves wall-clock and monotonic time forward together. */
    fun advance(ms: Long) {
        nowMs += ms
        elapsedMs += ms
    }
}

/** Remembers what is armed, keyed by identity exactly like platform alarms, and records every call. */
class FakeAlarmScheduler(var exactAllowed: Boolean = true) : AlarmScheduler {
    val armed: MutableMap<String, AlarmRequest> = java.util.Collections.synchronizedMap(LinkedHashMap())
    val calls: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    override fun canScheduleExact(): Boolean = exactAllowed

    override fun arm(request: AlarmRequest): ArmMode {
        val mode = if (exactAllowed) ArmMode.EXACT else ArmMode.INEXACT
        armed[request.identity] = request
        calls += "arm ${request.identity} $mode"
        return mode
    }

    override fun cancel(request: AlarmRequest) {
        armed.remove(request.identity)
        calls += "cancel ${request.identity}"
    }

    override fun isArmed(request: AlarmRequest): Boolean = request.identity in armed
}

class FakeWorkEnqueuer : WorkEnqueuer {
    val enqueued: MutableList<Pair<WorkKind, String>> = ArrayList()

    override fun enqueue(kind: WorkKind, occurrenceId: String) {
        enqueued += kind to occurrenceId
    }
}

/** Remembers what a pause asked of the platform: the end of each pause that began, and how many ended by running out or by being ended. */
class FakePauseEffects : PauseEffects {
    val startedUntil: MutableList<Long> = ArrayList()
    var expirations = 0
        private set
    var resumptions = 0
        private set

    override fun started(untilUtcMs: Long) {
        startedUntil += untilUtcMs
    }

    override fun expired() {
        expirations++
    }

    override fun resumed() {
        resumptions++
    }
}

class FakeRingController : RingController {
    val stopped: MutableList<String> = ArrayList()

    val stoppedCodes: MutableList<Int> = ArrayList()

    override fun stop(occurrenceId: String, requestCode: Int) {
        stopped += occurrenceId
        stoppedCodes += requestCode
    }
}
