package com.command.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
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
import com.command.data.NoOpConsentRevocationListener
import com.command.data.OccurrenceRepository
import com.command.data.VolumeRestore
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

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
        scheduler = AndroidAlarmScheduler(context)
        session = RingSession()
        poster = AndroidCheckInPoster(context)
        val ringer = AndroidRingController(context, session)
        repo = OccurrenceRepository(db, clock, scheduler, FakeWorkEnqueuer(), poster, WindowRoller { }, ringer)
        settings = AppSettings(db)
        policy = AlarmVolumePolicy(context, ConsentStore(db, clock, repo, NoOpConsentRevocationListener), settings, repo, clock, session)
        eraser = DataEraser(context, DatabaseFiles(context, db), scheduler, ringer, poster, policy, session)
    }

    @After
    fun tearDown() {
        VolumeRefusalShadow.refuse = false
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
        AndroidCheckInPoster(context).post(CheckInPrompt(following.id, following.requestCode, 0, "Write the report", started = true))
        RingNotifications.post(context, missed.id, AlarmContract.ID_MISSED, RingNotifications.missed(context, missed.id, missed.requestCode))
        PauseNotifications.show(context, t0 + 2 * hour)
        ReconcileScheduler.enqueuePauseEnd(context, t0 + 2 * hour, t0)
        ReconcileScheduler.ensurePeriodic(context)

        write(File(context.filesDir, "notes/deep/er/file.txt"))
        write(File(context.cacheDir, "director_voice/line.wav"))
        write(File(context.cacheDir, "utterance-1.wav"))
        write(File(context.noBackupFilesDir, "token.bin"))
        write(File(context.getExternalFilesDir("voice_debug")!!, "capture.wav"))
        write(File(context.getExternalFilesDir(null)!!, "other.txt"))
        write(File(context.externalCacheDir!!, "tmp.bin"))
        write(File(context.dataDir, "app_webview/Default/Cookies"))
        context.getSharedPreferences("command_calendar_sync_prefs", Context.MODE_PRIVATE).edit().putString("calendar", "work").commit()
        // The database the previous version left, which the importer reads and leaves in place.
        context.openOrCreateDatabase("project_command.db", Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL("CREATE TABLE missions (title TEXT)")
            legacy.execSQL("INSERT INTO missions VALUES ('Write the report')")
        }
    }

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
    fun `a ring that is sounding is told to stop before the database is closed and before the volume is put back, and only that one`() {
        populate()
        runBlocking { settings.saveVolumeRestore(VolumeRestore(occurrences[1].id, original = 3, set = 7, atUtc = t0)) }
        val files = DatabaseFiles(context, db)
        val stops = ArrayList<Triple<String, Boolean, Boolean>>()
        val recording = RingController { occurrenceId, _ -> stops += Triple(occurrenceId, files.isGone, runBlocking { settings.volumeRestore() != null }) }

        runBlocking { DataEraser(context, files, scheduler, recording, poster, policy, session).erase() }

        assertEquals(
            "only the occurrence that was ringing, while the database was still there and the volume had not been put back yet",
            listOf(Triple(occurrences[1].id, false, true)), stops,
        )
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
        assertEquals("and everything else still went", emptyList<String>(), filesOnThePhone())
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
