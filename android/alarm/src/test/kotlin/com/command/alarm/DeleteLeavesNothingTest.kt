package com.command.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.content.Context
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.command.data.AppSettings
import com.command.data.ConsentStore
import com.command.data.DatabaseFiles
import com.command.data.LegacyAlarmCanceller
import com.command.data.NoOpConsentRevocationListener
import com.command.data.OccurrenceRepository
import com.command.data.VolumeRestore
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
import com.command.domain.alarm.AlarmScheduler
import com.command.domain.alarm.CheckInPrompt
import com.command.domain.alarm.RingController
import com.command.domain.alarm.WindowRoller
import com.command.domain.dials.CheckInSchedule
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.testing.FakeClock
import com.command.testing.FakeWorkEnqueuer
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.testMission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The promise behind "Erase everything": after it, nothing of COMMAND's is left on the phone that could ring, ask, show, or hold what
 * the person recorded. Everything here is real except the platform itself (Robolectric's): a database in a real file with the one the
 * previous version left beside it, real alarms and notifications, queued work, a raised alarm volume, and files in every folder the app
 * is given. Each promise is its own test, and each looks at the phone, not at what the eraser says it did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34, 36], shadows = [VolumeRefusalShadow::class])
class DeleteLeavesNothingTest {

    private lateinit var context: Context
    private lateinit var db: CommandDb
    private lateinit var scheduler: AndroidAlarmScheduler
    private lateinit var latch: ErasureLatch
    private lateinit var legacy: LegacyAlarmCanceller
    private lateinit var repo: OccurrenceRepository
    private lateinit var settings: AppSettings
    private lateinit var eraser: DataEraser
    private lateinit var policy: AlarmVolumePolicy
    private lateinit var session: RingSession
    private lateinit var poster: AndroidCheckInPoster
    private lateinit var audio: AudioManager
    private lateinit var alarms: AlarmManager
    private lateinit var notifications: NotificationManager
    private lateinit var occurrences: List<OccurrenceRow>

    private val hour = 3_600_000L
    private val t0 = FakeAlarmModule.T0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        audio = context.getSystemService(AudioManager::class.java)
        alarms = context.getSystemService(AlarmManager::class.java)
        notifications = context.getSystemService(NotificationManager::class.java)
        db = CommandDb.open(context)
        val clock = FakeClock(nowMs = t0, elapsedMs = 5_000)
        latch = ErasureLatch()
        legacy = LegacyAlarmCanceller(context, db)
        scheduler = AndroidAlarmScheduler(context, latch)
        session = RingSession()
        poster = AndroidCheckInPoster(context, latch)
        val ringer = AndroidRingController(context, session)
        repo = OccurrenceRepository(db, clock, scheduler, FakeWorkEnqueuer(), poster, WindowRoller { }, ringer)
        settings = AppSettings(db)
        policy = AlarmVolumePolicy(context, ConsentStore(db, clock, repo, NoOpConsentRevocationListener), settings, repo, clock, session)
        eraser = DataEraser(context, DatabaseFiles(context, db), scheduler, ringer, poster, policy, session, latch, legacy)
    }

    @After
    fun tearDown() {
        VolumeRefusalShadow.refuse = false
        VolumeRefusalShadow.floor = 0
        runCatching { db.close() }
    }

    private fun requestsOf(o: OccurrenceRow): List<AlarmRequest> =
        listOf(AlarmRequest(o.id, o.requestCode, AlarmKind.FIRE, o.scheduledAtUtc), AlarmRequest(o.id, o.requestCode, AlarmKind.CYCLE_END, o.scheduledAtUtc)) +
            (0..CheckInSchedule.MAX_COUNT).map { AlarmRequest(o.id, o.requestCode, AlarmKind.CHECK_IN, o.scheduledAtUtc, it) }

    /**
     * The alarms that are COMMAND's. WorkManager keeps one of its own on Android 7 to 10: the sentinel with which it notices a force-stop
     * (from Android 11 it asks the system instead). It belongs to the library, as its files do, and it goes when the app's data is cleared.
     */
    private fun ourAlarms() = shadowOf(alarms).scheduledAlarms.filterNot { alarm ->
        alarm.operation?.let { shadowOf(it).savedIntent.component?.className?.startsWith("androidx.work.") } == true
    }

    private fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText("what the person recorded")
    }

    private fun erase(): ErasureReport = runBlocking { eraser.erase() }

    /** Every regular file under the app's data directory that is not the platform's own, and under every external folder: what could hold the person's data. */
    private fun filesOnThePhone(): List<String> {
        val found = ArrayList<String>()
        fun walk(file: File) {
            if (file.isDirectory) file.listFiles()?.forEach(::walk) else if (!file.name.startsWith("androidx.work.")) found += file.path
        }
        context.dataDir.listFiles()?.filter { it.name != "code_cache" && it.name != "lib" }?.forEach(::walk)
        for (dir in context.getExternalFilesDirs(null) + context.externalCacheDirs) dir?.listFiles()?.forEach(::walk)
        return found.sorted()
    }

    /** The phone as the app leaves it after some weeks: alarms in four stages of an alarm's life, their notices, queued runs, files everywhere. */
    private fun populate() = runBlocking {
        val mission = testMission()
        db.missions().insert(mission)
        suspend fun occurrence(offsetMs: Long, vararg commands: OccurrenceCommand): OccurrenceRow {
            val o = repo.createOccurrence(mission.id, t0 + offsetMs, "UTC").occurrence
            for (c in commands) repo.apply(o.id, c, EventSource.USER)
            return repo.get(o.id)!!
        }
        val arm = OccurrenceCommand.Arm(ArmMode.EXACT)
        val armed = occurrence(hour, arm)
        val ringing = occurrence(2 * hour, arm, OccurrenceCommand.Fire)
        val following = occurrence(3 * hour, arm, OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance, OccurrenceCommand.ReportStarted)
        val missed = occurrence(4 * hour, arm, OccurrenceCommand.Fire, OccurrenceCommand.Miss(MissReason.TIMEOUT))
        occurrences = listOf(armed, ringing, following, missed)

        // What the platform holds that the database only implies: a stray alarm of every kind for every occurrence, and the notices of each stage.
        val compat = NotificationManagerCompat.from(context)
        val notice = { NotificationCompat.Builder(context, AlarmContract.CHANNEL_ALARM).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("x").build() }
        for (o in occurrences) for (request in requestsOf(o)) scheduler.arm(request.copy(atUtcMs = t0 + 10 * hour))
        compat.notify(ringing.requestCode, notice())
        compat.notify(ringing.id, AlarmContract.ID_FALLBACK, notice())
        AndroidCheckInPoster(context, ErasureLatch()).post(CheckInPrompt(following.id, following.requestCode, 0, "Write the report", started = true))
        RingNotifications.post(context, missed.id, AlarmContract.ID_MISSED, RingNotifications.missed(context, missed.id, missed.requestCode))
        PauseNotifications.show(context, t0 + 2 * hour)
        ReconcileScheduler.enqueuePauseEnd(context, t0 + 2 * hour, t0)
        ReconcileScheduler.ensurePeriodic(context)

        write(File(context.filesDir, "notes/deep/er/file.txt"))
        write(File(context.cacheDir, "director_voice/line.wav"))
        write(File(context.cacheDir, "utterance-1.wav"))
        write(File(context.noBackupFilesDir, "token.bin"))
        write(File(context.getExternalFilesDir(com.command.voice.VoiceDirectorEngine.DEBUG_CAPTURE_FOLDER)!!, "capture.wav"))
        write(File(context.getExternalFilesDir(null)!!, "other.txt"))
        write(File(context.externalCacheDir!!, "tmp.bin"))
        write(File(context.dataDir, "app_webview/Default/Cookies"))
        context.getSharedPreferences("command_calendar_sync_prefs", Context.MODE_PRIVATE).edit().putString("calendar", "work").commit()
        // The database the previous version left, which the importer reads and leaves in place, and what it armed: one alarm for each of its
        // missions (the request code was the mission's row id), the repeating calendar wake-up, and its notification channel.
        context.openOrCreateDatabase("project_command.db", Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE missions (id INTEGER PRIMARY KEY, title TEXT)")
            old.execSQL("INSERT INTO missions VALUES (41, 'Write the report')")
        }
        armPrevious(41, LegacyAlarmCanceller.MISSION_RECEIVER, null)
        armPrevious(LegacyAlarmCanceller.CALENDAR_REQUEST_CODE, LegacyAlarmCanceller.CALENDAR_RECEIVER, LegacyAlarmCanceller.CALENDAR_ACTION)
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(LegacyAlarmCanceller.CHANNEL_ID, "Reveille", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun armPrevious(requestCode: Int, receiver: String, action: String?) {
        val intent = Intent().setComponent(ComponentName(context.packageName, receiver))
        if (action != null) intent.action = action
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setExact(AlarmManager.RTC_WAKEUP, t0 + 10 * hour, pending)
    }

    /** The alarms the previous version armed that are still set: its components are named in the old app's package. */
    private fun previousAlarms() = ourAlarms().mapNotNull { shadowOf(it.operation).savedIntent.component?.className }.filter { it.startsWith("com.example.") }

    /** A ring raised the alarm volume from 3 to the loudest and left the record of it. Returns the loudest. */
    private fun raiseVolume(): Int {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
        val loudest = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, loudest, 0)
        runBlocking { settings.saveVolumeRestore(VolumeRestore(occurrences[1].id, original = 3, set = loudest, atUtc = t0)) }
        return loudest
    }

    private val volumeProblem = "the alarm volume could not be put back"

    // --- the promises ---------------------------------------------------------------------------------------------------------

    @Test
    fun `the report says everything is gone, and how many occurrences it took back`() {
        populate()

        val report = erase()

        assertTrue("the report: $report", report.complete)
        assertEquals(4, report.occurrences)
    }

    @Test
    fun `no alarm of any occurrence is left with the platform`() {
        populate()
        assertTrue("alarms are set", ourAlarms().isNotEmpty())
        for (o in occurrences) assertTrue("the platform holds the alarms of ${o.id}", requestsOf(o).all { scheduler.isArmed(it) })

        erase()

        assertEquals("alarms are still set", emptyList<Any>(), ourAlarms().map { shadowOf(it.operation).savedIntent })
        for (o in occurrences) for (request in requestsOf(o)) assertFalse("the platform still holds $request", scheduler.isArmed(request))
    }

    @Test
    fun `the previous version's alarms and notification channel are taken back too`() {
        populate()
        assertEquals("the previous version's two alarms are set", 2, previousAlarms().size)
        if (Build.VERSION.SDK_INT >= 26) assertNotNull(notifications.getNotificationChannel(LegacyAlarmCanceller.CHANNEL_ID))

        val report = erase()

        assertTrue(report.toString(), report.complete)
        assertEquals(emptyList<String>(), previousAlarms())
        if (Build.VERSION.SDK_INT >= 26) assertNull(notifications.getNotificationChannel(LegacyAlarmCanceller.CHANNEL_ID))
    }

    @Test
    fun `a previous version's database that cannot be read is reported, because its alarms cannot be found, and said again when the erase is asked for again`() {
        populate()
        context.getDatabasePath("project_command.db").writeText("this is not a database")

        val first = erase()
        val second = erase()

        assertFalse(first.toString(), first.complete)
        assertEquals(listOf("the previous version's alarms could not be looked for"), first.problems)
        assertFalse("asking again does not make it complete: $second", second.complete)
        assertEquals(listOf("the previous version's alarms could not be looked for"), second.problems)
        assertEquals("everything else went", emptyList<String>(), filesOnThePhone())
    }

    @Test
    fun `no notice is left showing`() {
        populate()
        assertTrue("notices are showing: " + notifications.activeNotifications.size, notifications.activeNotifications.size >= 5)

        erase()

        assertEquals("notices are still showing", emptyList<Int>(), notifications.activeNotifications.map { it.id })
    }

    @Test
    fun `no queued run is left`() {
        populate()
        val work = WorkManager.getInstance(context)
        val names = listOf(ReconcileAlarmsWorker.NAME_PAUSE_END, ReconcileAlarmsWorker.NAME_PERIODIC)
        for (name in names) assertTrue("work is queued under $name", work.getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.ENQUEUED })

        erase()

        for (name in names) {
            val left = work.getWorkInfosForUniqueWork(name).get()
            assertEquals("the queue still holds a record under $name (cancelled work is pruned, not only cancelled): $left", emptyList<WorkInfo>(), left)
        }
    }

    @Test
    fun `neither database is left, nor the files beside them`() {
        populate()
        assertTrue("the database is a file", context.getDatabasePath(CommandDb.NAME).exists())
        assertTrue("and so is the previous version's", context.getDatabasePath("project_command.db").exists())

        erase()

        for (name in listOf(CommandDb.NAME, "project_command.db")) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) assertFalse("$name$suffix is still there", File(context.getDatabasePath(name).path + suffix).exists())
        }
        assertEquals(emptyList<String>(), context.databaseList().filter { it.startsWith("command.db") || it.startsWith("project_command.db") })
    }

    @Test
    fun `the alarm volume is the person's again`() {
        populate()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
        val loudest = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, loudest, 0) // a ring raised it
        runBlocking { settings.saveVolumeRestore(VolumeRestore(occurrences[1].id, original = 3, set = loudest, atUtc = t0)) }
        assertEquals(loudest, audio.getStreamVolume(AudioManager.STREAM_ALARM))

        erase()

        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun `no file is left in any folder the app is given`() {
        populate()
        assertTrue("there are files to delete: " + filesOnThePhone(), filesOnThePhone().size >= 7)

        erase()

        assertEquals("files are still there", emptyList<String>(), filesOnThePhone())
    }

    @Test
    fun `the preferences are empty, on disk and in memory`() {
        populate()
        val prefs = context.getSharedPreferences("command_calendar_sync_prefs", Context.MODE_PRIVATE)
        assertEquals("work", prefs.getString("calendar", null))

        erase()

        assertTrue("in memory", context.getSharedPreferences("command_calendar_sync_prefs", Context.MODE_PRIVATE).all.isEmpty())
        assertFalse("on disk", File(context.dataDir, "shared_prefs/command_calendar_sync_prefs.xml").exists())
    }

    @Test
    fun `a ring that is sounding and an alarm that is being checked are told to stop before the database is closed and before the volume is put back, and no other`() {
        populate()
        runBlocking { settings.saveVolumeRestore(VolumeRestore(occurrences[1].id, original = 3, set = 7, atUtc = t0)) }
        val files = DatabaseFiles(context, db)
        val stops = ArrayList<Triple<String, Boolean, Boolean>>()
        val recording = RingController { occurrenceId, _ -> stops += Triple(occurrenceId, files.isGone, runBlocking { settings.volumeRestore() != null }) }

        runBlocking { DataEraser(context, files, scheduler, recording, poster, policy, session, latch, legacy).erase() }

        assertEquals(
            "the occurrence that was ringing and the one that was armed (its alarm may have just gone off, and the service is checking it), " +
                "each once, while the database was still there and the volume had not been put back yet; not the ones that were over",
            setOf(Triple(occurrences[0].id, false, true), Triple(occurrences[1].id, false, true)), stops.toSet(),
        )
        assertEquals("each once", 2, stops.size)
    }

    @Test
    fun `an alarm that something sets while the erase runs is refused, so nothing is left armed`() {
        populate()
        val o = occurrences[0]
        var tried = false
        var answer: ArmMode? = null
        eraser.delete = { file ->
            if (!tried) {
                tried = true
                // A reconcile, a check-in or an app start that was already running when the erase began.
                answer = scheduler.arm(AlarmRequest(o.id, o.requestCode, AlarmKind.FIRE, t0 + 10 * hour))
            }
            file.delete()
        }

        val report = erase()

        assertTrue("the arm was tried while the erase ran", tried)
        assertEquals(ArmMode.NONE, answer)
        assertTrue(report.toString(), report.complete)
        assertEquals("no alarm of ours is left", 0, ourAlarms().size)
    }

    @Test
    fun `a ring that will not go quiet is reported, because the database is about to be closed under it`() {
        populate()
        val busy = object : RingSession() {
            override val isBusy: Boolean get() = true
        }
        val impatient = DataEraser(context, DatabaseFiles(context, db), scheduler, AndroidRingController(context, busy), poster, policy, busy, latch, legacy)
        impatient.ringEndWaitMs = 150

        val report = runBlocking { impatient.erase() }

        assertFalse(report.toString(), report.complete)
        assertEquals(listOf("a ring was still sounding"), report.problems)
        assertFalse("a ring is not the volume", report.volumeLeft)
        assertEquals("and everything else still went", emptyList<String>(), filesOnThePhone())
    }

    @Test
    fun `a ring that goes quiet while the erase waits for it is not reported`() {
        populate()
        var asked = 0
        val settling = object : RingSession() {
            // Busy for the first three questions, one every fifty milliseconds, and quiet after that: counted, not timed, so that how long the
            // erase took to get here cannot decide the outcome.
            override val isBusy: Boolean get() = ++asked <= 3
        }
        val patient = DataEraser(context, DatabaseFiles(context, db), scheduler, AndroidRingController(context, settling), poster, policy, settling, latch, legacy)
        patient.ringEndWaitMs = 3_000

        val report = runBlocking { patient.erase() }

        assertTrue("it waited for the ring, and the ring went quiet: $report", report.complete)
    }

    @Test
    fun `the latch is closed and whatever is sounding is silenced before the database is read, before the first alarm is taken back and before anything else`() {
        populate()
        val steps = ArrayList<String>()
        val files = object : DatabaseFiles(context, db) {
            override suspend fun occurrences(): List<OccurrenceRow> {
                steps += "occurrences read, sealed=${latch.sealed}"
                return super.occurrences()
            }
        }
        val noting = object : AlarmScheduler by scheduler {
            override fun cancel(request: AlarmRequest) {
                if (steps.none { it.startsWith("first alarm") }) steps += "first alarm taken back, sealed=${latch.sealed}"
                scheduler.cancel(request)
            }
        }
        val silencing = object : RingSession() {
            override fun endActive() {
                steps += "silenced, sealed=${latch.sealed}"
            }
        }
        val e = DataEraser(context, files, noting, AndroidRingController(context, silencing), poster, policy, silencing, latch, legacy)

        runBlocking { e.erase() }

        assertEquals(
            listOf("silenced, sealed=true", "occurrences read, sealed=true", "first alarm taken back, sealed=true"),
            steps.take(3),
        )
    }

    @Test
    fun `the erase waits for the ring only after both stops are issued, and while the database and the volume record are still there`() {
        populate()
        runBlocking { settings.saveVolumeRestore(VolumeRestore(occurrences[1].id, original = 3, set = 7, atUtc = t0)) }
        val files = DatabaseFiles(context, db)
        val stops = ArrayList<String>()
        val recording = RingController { occurrenceId, _ -> stops += occurrenceId }
        val polls = ArrayList<List<Any>>()
        val refused = ArrayList<ArmMode>()
        val observing = object : RingSession() {
            var asked = 0
            override val isBusy: Boolean
                get() {
                    polls += listOf(stops.size, files.isGone, runBlocking { settings.volumeRestore() != null })
                    // And, all the while it waits, an alarm that something tries to set is refused.
                    refused += scheduler.arm(AlarmRequest(occurrences[0].id, occurrences[0].requestCode, AlarmKind.FIRE, t0 + 10 * hour))
                    return ++asked <= 2
                }
        }
        val e = DataEraser(context, files, scheduler, recording, poster, policy, observing, latch, legacy)
        e.ringEndWaitMs = 3_000

        runBlocking { e.erase() }

        assertTrue("it asked", polls.isNotEmpty())
        for (poll in polls) {
            assertEquals("at every poll: both stops are issued, the database is there, and so is the record of the volume", listOf<Any>(2, false, true), poll)
        }
        assertEquals("and every alarm that was tried meanwhile was refused", List(polls.size) { ArmMode.NONE }, refused)
        assertEquals(0, ourAlarms().size)
    }

    @Test
    fun `a ring that was slow to stop is told once and not carried, so when it is quiet the next erase is complete`() {
        populate()
        val slow = object : RingSession() {
            @Volatile var sounding = true
            override val isBusy: Boolean get() = sounding
        }
        val e = DataEraser(context, DatabaseFiles(context, db), scheduler, AndroidRingController(context, slow), poster, policy, slow, latch, legacy)
        e.ringEndWaitMs = 100

        val first = runBlocking { e.erase() }
        slow.sounding = false
        val second = runBlocking { e.erase() }

        assertEquals(listOf("a ring was still sounding"), first.problems)
        assertTrue("it is quiet now, and what a second look finds is nothing: $second", second.complete)
    }

    @Test
    fun `an erase that fails before it has deleted anything opens the latch again, so that the app goes on ringing`() {
        populate()
        val o = occurrences[0]
        val failing = object : AlarmScheduler by scheduler {
            override fun cancel(request: AlarmRequest) {
                throw IllegalStateException("the platform could not be reached")
            }
        }
        val e = DataEraser(context, DatabaseFiles(context, db), failing, AndroidRingController(context, session), poster, policy, session, latch, legacy)

        val failure = runCatching { runBlocking { e.erase() } }.exceptionOrNull()

        assertTrue("$failure", failure is IllegalStateException)
        assertFalse("the app goes on as it was", latch.sealed)
        assertNotEquals("and alarms can be set again", ArmMode.NONE, scheduler.arm(AlarmRequest(o.id, o.requestCode, AlarmKind.FIRE, t0 + 10 * hour)))
        assertNotNull("the database is still there", runBlocking { repo.get(o.id) })
    }

    @Test
    fun `an erase that fails after it deleted the database keeps the latch closed`() {
        populate()
        eraser.delete = { throw IllegalStateException("could not delete") }

        val failure = runCatching { erase() }.exceptionOrNull()

        assertTrue("$failure", failure is IllegalStateException)
        assertTrue("there is no way back, so nothing may be armed in this process", latch.sealed)
    }

    @Test
    fun `leaving the screen does not cancel an erase that has begun`() {
        populate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // A ring that is slow to go quiet makes the eraser wait for it (a delay, which a cancelled caller would cut short): the first look at
        // it is held until the screen has gone, and the ring then goes quiet after three more.
        val asked = AtomicInteger()
        val slow = object : RingSession() {
            override val isBusy: Boolean
                get() {
                    if (asked.getAndIncrement() == 0) {
                        entered.countDown()
                        release.await(20, TimeUnit.SECONDS)
                    }
                    return asked.get() <= 3
                }
        }
        val e = DataEraser(context, DatabaseFiles(context, db), scheduler, AndroidRingController(context, slow), poster, policy, slow, latch, legacy)
        val screen = CoroutineScope(Dispatchers.Default)
        val job = screen.launch { e.erase() }
        assertTrue("the erase is under way", entered.await(20, TimeUnit.SECONDS))

        job.cancel() // the screen is gone
        release.countDown()
        runBlocking { job.join() }

        assertEquals("it ran on to its end all the same", emptyList<String>(), filesOnThePhone())
        assertFalse(context.getDatabasePath(CommandDb.NAME).exists())
    }

    @Test
    fun `a second erase asked for while one runs waits for it, and then finds nothing to do`() {
        populate()
        val entries = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = RingController { _, _ ->
            entries.incrementAndGet()
            entered.countDown()
            release.await(20, TimeUnit.SECONDS)
        }
        val e = DataEraser(context, DatabaseFiles(context, db), scheduler, blocking, poster, policy, session, latch, legacy)
        val callers = CoroutineScope(Dispatchers.Default)
        val first = callers.async { e.erase() }
        assertTrue("the first is under way", entered.await(20, TimeUnit.SECONDS))
        val second = callers.async { e.erase() }

        Thread.sleep(400) // long enough for a second erase that did not wait to be well into its own stops
        assertEquals("the second has not begun: it is waiting its turn", 1, entries.get())
        assertFalse(second.isCompleted)
        release.countDown()

        val one = runBlocking { first.await() }
        val two = runBlocking { second.await() }
        assertTrue("$one", one.complete)
        assertTrue("$two", two.complete)
        assertEquals("the second found nothing: it came after the first", 0, two.occurrences)
    }

    @Test
    fun `an alarm that slipped past the latch while the first pass ran is taken back by the second pass`() {
        populate()
        val o = occurrences[0]
        val unsealed = AndroidAlarmScheduler(context, ErasureLatch()) // stands for an arm that had already passed the latch's check
        var slipped = false
        val straggling = object : RingSession() {
            // Asked after the first pass has taken the alarms back and before the second.
            override val isBusy: Boolean
                get() {
                    if (!slipped) {
                        slipped = true
                        unsealed.arm(AlarmRequest(o.id, o.requestCode, AlarmKind.FIRE, t0 + 10 * hour))
                    }
                    return false
                }
        }
        val e = DataEraser(context, DatabaseFiles(context, db), scheduler, AndroidRingController(context, straggling), poster, policy, straggling, latch, legacy)

        val report = runBlocking { e.erase() }

        assertTrue("the straggler was armed between the passes", slipped)
        assertTrue(report.toString(), report.complete)
        assertEquals("and the second pass took it back", 0, ourAlarms().size)
    }

    // --- the edges ----------------------------------------------------------------------------------------------------------

    @Test
    fun `erasing again finds nothing to do and says so`() {
        populate()

        val first = erase()
        val second = erase()

        assertTrue("the first: $first", first.complete)
        assertTrue("the second: $second", second.complete)
        assertEquals(0, second.occurrences)
        assertEquals("nothing came back", emptyList<String>(), filesOnThePhone())
        assertFalse("and the database was not left behind by the second", context.getDatabasePath(CommandDb.NAME).exists())
    }

    @Test
    fun `something that comes back while it erases is removed by the second pass`() {
        populate()
        write(File(context.filesDir, "first.txt"))
        eraser.delete = { file ->
            val gone = file.delete()
            if (file.name == "first.txt") write(File(context.filesDir, "came-back.txt")) // made again by something that was running
            gone
        }

        val report = erase()

        assertTrue(report.toString(), report.complete)
        assertEquals(emptyList<String>(), filesOnThePhone())
    }

    @Test
    fun `a phone with nothing on it erases cleanly`() {
        val report = erase()

        assertTrue(report.toString(), report.complete)
        assertEquals(0, report.occurrences)
    }

    @Test
    fun `a volume the platform will not put back is reported, because nothing will put it back once the record is gone`() {
        populate()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
        val loudest = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, loudest, 0)
        runBlocking { settings.saveVolumeRestore(VolumeRestore(occurrences[1].id, original = 3, set = loudest, atUtc = t0)) }
        VolumeRefusalShadow.refuse = true // Do Not Disturb says no

        val report = erase()

        assertFalse("the report must not claim a complete erase: $report", report.complete)
        assertEquals(listOf("the alarm volume could not be put back"), report.problems)
        assertTrue("and it says it is the volume, which closing the app does not put right", report.volumeLeft)
        assertEquals("and everything else still went", emptyList<String>(), filesOnThePhone())
    }

    @Test
    fun `a volume the platform refuses to put back is tried again when the erase is asked for again, and the erase is complete once it worked`() {
        populate()
        val loudest = raiseVolume()
        VolumeRefusalShadow.refuse = true // Do Not Disturb says no

        val first = erase()
        val second = erase()

        assertEquals(listOf(volumeProblem), first.problems)
        assertFalse(first.complete)
        assertEquals("still refused, still said, and never called complete: $second", listOf(volumeProblem), second.problems)
        assertEquals("the volume is still raised", loudest, audio.getStreamVolume(AudioManager.STREAM_ALARM))

        VolumeRefusalShadow.refuse = false // Do Not Disturb is switched off
        val third = erase()

        assertTrue("the third try put it back: $third", third.complete)
        assertEquals("the volume is the person's again", 3, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun `a volume the person sets themselves after a refused restore is theirs, and the erase does not undo it`() {
        populate()
        raiseVolume()
        VolumeRefusalShadow.refuse = true
        val first = erase()
        assertFalse(first.toString(), first.complete)

        VolumeRefusalShadow.refuse = false
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0) // the person lowers it by hand
        val second = erase()

        assertTrue("what they chose is theirs, so there is nothing left to put back: $second", second.complete)
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun `a volume the platform keeps above its floor is reported each time, and put back once the platform allows it`() {
        populate()
        raiseVolume()
        VolumeRefusalShadow.floor = 5 // the platform keeps the alarm stream at 5 or more for an app like this

        val first = erase()
        val second = erase()

        assertEquals("it went as low as it could", 5, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals(listOf(volumeProblem), first.problems)
        assertEquals("and asking again does not make it complete: $second", listOf(volumeProblem), second.problems)
        assertFalse(second.complete)

        VolumeRefusalShadow.floor = 0
        val third = erase()

        assertTrue("$third", third.complete)
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun `a volume refused, then kept above a floor, then allowed, is put back at the last try`() {
        populate()
        raiseVolume()

        VolumeRefusalShadow.refuse = true
        val first = erase()
        VolumeRefusalShadow.refuse = false
        VolumeRefusalShadow.floor = 5
        val second = erase()
        VolumeRefusalShadow.floor = 0
        val third = erase()

        assertEquals(listOf(volumeProblem), first.problems)
        assertEquals("the platform let it go as low as 5, which is not what the person had: $second", listOf(volumeProblem), second.problems)
        assertTrue("and what the platform left is not taken for the person's own choice: $third", third.complete)
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun `a volume the person raises above the floor after a shortfall is theirs and is left alone`() {
        populate()
        raiseVolume()
        VolumeRefusalShadow.floor = 5
        val first = erase()
        assertFalse(first.toString(), first.complete)

        VolumeRefusalShadow.floor = 0
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 6, 0) // above the floor, by hand
        val second = erase()

        assertTrue("$second", second.complete)
        assertEquals(6, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun `a database that cannot be read is still reported when the erase is asked for again`() {
        populate()
        db.close()

        val first = erase()
        val second = erase()

        assertTrue("it says so: ${first.problems}", "the occurrences could not be read" in first.problems)
        assertFalse(second.toString(), second.complete)
        // Nothing could be read, so neither the occurrences nor the record of a raised volume were found; both are said again.
        assertEquals("and again", listOf("the occurrences could not be read", volumeProblem), second.problems)
        assertEquals("the files went all the same", emptyList<String>(), filesOnThePhone())
    }

    @Test
    fun `a database that cannot be read is reported, and the rest still goes`() {
        populate()
        db.close() // Room cannot be used once it is closed

        val report = erase()

        assertFalse(report.toString(), report.complete)
        assertTrue("it says so: ${report.problems}", "the occurrences could not be read" in report.problems)
        assertEquals("the files went all the same", emptyList<String>(), filesOnThePhone())
        assertFalse(context.getDatabasePath(CommandDb.NAME).exists())
    }

    @Test
    fun `closing the database deletes both files and their journals, and says it is gone`() {
        populate()
        val files = DatabaseFiles(context, db)
        assertFalse(files.isGone)

        files.closeAndDelete()

        assertTrue(files.isGone)
        for (name in listOf(CommandDb.NAME, "project_command.db")) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) assertFalse("$name$suffix is still there", File(context.getDatabasePath(name).path + suffix).exists())
        }
    }

    @Test
    fun `a link is not followed, and what it points at is left alone`() {
        populate()
        val outside = File(context.cacheDir.parentFile!!.parentFile, "outside-" + System.nanoTime()).apply { mkdirs() }
        File(outside, "precious.txt").writeText("not the app's")
        val link = File(context.filesDir, "link")
        val linked = runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        org.junit.Assume.assumeTrue("this file system lets a link be made", linked)

        try {
            val report = erase()

            assertTrue(report.toString(), report.complete)
            assertTrue("what the link pointed at is still there", File(outside, "precious.txt").exists())
            assertFalse("and the link itself is gone", link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath()))
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `a link that cannot be removed is named in the report, not hidden`() {
        populate()
        write(File(context.filesDir, "stuck-link"))
        eraser.linkTest = { it.name == "stuck-link" } // stands for a link, which this test cannot always make
        eraser.delete = { file -> file.name != "stuck-link" && file.delete() }

        val report = erase()

        assertFalse("the report must not claim a complete erase: $report", report.complete)
        assertEquals(listOf("files" + File.separator + "stuck-link"), report.leftovers)
    }

    @Test
    fun `a link to a folder that cannot be removed is named itself, and what is behind it is not listed`() {
        populate()
        write(File(context.filesDir, "stuck-link/inside.txt")) // a folder standing for a link to one
        eraser.linkTest = { it.name == "stuck-link" }
        eraser.delete = { file -> file.name != "stuck-link" && file.delete() }

        val report = erase()

        assertEquals(listOf("files" + File.separator + "stuck-link"), report.leftovers)
    }

    @Test
    fun `a file that cannot be deleted is reported, not hidden`() {
        populate()
        write(File(context.filesDir, "stuck/kept.txt"))
        write(File(context.filesDir, "gone.txt"))
        eraser.delete = { file -> file.name != "kept.txt" && file.delete() }

        val report = erase()

        assertFalse("the report must not claim a complete erase: $report", report.complete)
        assertEquals("it names what is left, and only that: ${report.leftovers}", listOf("files" + File.separator + "stuck" + File.separator + "kept.txt"), report.leftovers)
        assertFalse("everything else went", File(context.filesDir, "gone.txt").exists())
    }
}
