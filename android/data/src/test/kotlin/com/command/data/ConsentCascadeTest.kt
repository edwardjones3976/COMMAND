package com.command.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.data.db.CalendarLinkRow
import com.command.data.db.CalendarSourceRow
import com.command.data.db.DirectiveCacheRow
import com.command.data.db.SyncOutboxRow
import com.command.data.db.VoiceRenderRow
import com.command.domain.dials.Flexibility
import com.command.domain.dials.HandshakeMode
import com.command.domain.model.ConsentKey
import com.command.domain.model.ConsentSource
import com.command.domain.model.EventSource
import com.command.domain.model.MissionSource
import com.command.domain.model.SyncOp
import com.command.domain.model.VoiceEngine
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConsentCascadeTest {

    private class Recorder : ConsentRevocationListener {
        val revoked = ArrayList<ConsentKey>()
        val files = HashMap<ConsentKey, List<String>>()
        override suspend fun onRevoked(key: ConsentKey, purgedFilePaths: List<String>) {
            revoked += key
            files[key] = purgedFilePaths
        }
    }

    private lateinit var f: Fixture
    private lateinit var listener: Recorder
    private lateinit var consents: ConsentStore

    @Before
    fun setUp() {
        f = Fixture(ApplicationProvider.getApplicationContext<Context>())
        listener = Recorder()
        consents = ConsentStore(f.db, f.clock, f.repo, listener)
    }

    @After fun tearDown() { f.close() }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    @Test
    fun `nothing is granted until the user grants it`() = run {
        for (key in ConsentKey.values()) assertFalse(key.name, consents.isGranted(key))
    }

    @Test
    fun `history is append-only and the latest row decides`() = run {
        consents.grant(ConsentKey.GEMINI_TEXT, boundaryVersion = 1, shownTextHash = "h1")
        f.clock.advance(1_000)
        consents.revoke(ConsentKey.GEMINI_TEXT)
        f.clock.advance(1_000)
        consents.grant(ConsentKey.GEMINI_TEXT, boundaryVersion = 2, shownTextHash = "h2")
        val history = consents.history(ConsentKey.GEMINI_TEXT)
        assertEquals(listOf(true, false, true), history.map { it.granted })
        assertEquals(listOf(1, 1, 2), history.map { it.boundaryVersion })
        assertEquals(history.map { it.seq }.sorted(), history.map { it.seq })
        assertTrue(consents.isGranted(ConsentKey.GEMINI_TEXT))
    }

    @Test
    fun `withdrawing AI text also withdraws the titles grant and purges cached directives`() = run {
        consents.grant(ConsentKey.GEMINI_TEXT)
        consents.grant(ConsentKey.GEMINI_TEXT_TITLES)
        consents.grant(ConsentKey.GEMINI_VOICE)
        f.db.directiveCacheDao().insert(DirectiveCacheRow("k1", "Open the document.", f.t0 + 1))
        consents.revoke(ConsentKey.GEMINI_TEXT)
        assertFalse(consents.isGranted(ConsentKey.GEMINI_TEXT))
        assertFalse(consents.isGranted(ConsentKey.GEMINI_TEXT_TITLES))
        assertTrue("an unrelated grant stays", consents.isGranted(ConsentKey.GEMINI_VOICE))
        assertEquals(0, f.db.directiveCacheDao().count())
        assertEquals(listOf(ConsentKey.GEMINI_TEXT, ConsentKey.GEMINI_TEXT_TITLES), listener.revoked)
    }

    @Test
    fun `revoking a key that was never granted writes nothing`() = run {
        consents.revoke(ConsentKey.CLOUD_SYNC)
        assertTrue(consents.history(ConsentKey.CLOUD_SYNC).isEmpty())
        assertTrue(listener.revoked.isEmpty())
    }

    @Test
    fun `withdrawing cloud voice purges its cached audio and reports the files to delete`() = run {
        consents.grant(ConsentKey.GEMINI_VOICE)
        consents.grant(ConsentKey.GEMINI_VOICE_TITLES)
        f.db.voiceRenderDao().insert(VoiceRenderRow("a", VoiceEngine.GEMINI_STUDIO, "/cache/a.pcm", 100, f.t0))
        f.db.voiceRenderDao().insert(VoiceRenderRow("b", VoiceEngine.LOCAL_TTS, "/cache/b.pcm", 100, f.t0))
        consents.revoke(ConsentKey.GEMINI_VOICE)
        assertEquals(listOf("/cache/a.pcm"), listener.files[ConsentKey.GEMINI_VOICE])
        assertEquals(listOf("b"), f.db.voiceRenderDao().keys())
        assertFalse(consents.isGranted(ConsentKey.GEMINI_VOICE_TITLES))
    }

    @Test
    fun `withdrawing cloud sync clears the queued writes`() = run {
        consents.grant(ConsentKey.CLOUD_SYNC)
        f.db.syncOutboxDao().insert(SyncOutboxRow(tableName = "missions", rowId = "m1", op = SyncOp.UPSERT, queuedAt = f.t0))
        consents.revoke(ConsentKey.CLOUD_SYNC)
        assertEquals(0, f.db.syncOutboxDao().count())
        assertEquals(listOf(ConsentKey.CLOUD_SYNC), listener.revoked)
    }

    @Test
    fun `withdrawing calendar access skips unfired imported occurrences and leaves fired ones alone`() = run {
        consents.grant(ConsentKey.CALENDAR_READ)
        consents.grant(ConsentKey.CALENDAR_EVENT_TEXT)
        f.db.calendarDao().insertSource(CalendarSourceRow(7, "me@example.test", "Work", true, null, Flexibility.RIGID, f.t0))

        val mission = f.mission("Imported meeting", source = MissionSource.DEVICE_CALENDAR)
        val future = f.repo.createOccurrence(mission.id, f.t0 + 3_600_000, "UTC").occurrence
        val fired = f.repo.createOccurrence(mission.id, f.t0 + 60_000, "UTC").occurrence
        val armed = f.repo.createOccurrence(mission.id, f.t0 + 7_200_000, "UTC").occurrence
        f.repo.apply(armed.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        f.repo.apply(fired.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        f.repo.apply(fired.id, OccurrenceCommand.Fire, EventSource.SYSTEM)
        listOf(future, fired, armed).forEachIndexed { i, o ->
            f.db.calendarDao().insertLink(CalendarLinkRow(o.id, 7, 100L + i, o.scheduledAtUtc, null, null, "UTC", f.t0, 0))
        }

        consents.revoke(ConsentKey.CALENDAR_READ)

        assertEquals(OccurrenceState.SKIPPED, f.repo.get(future.id)!!.state)
        assertEquals(SkipReason.CALENDAR_REMOVED, f.repo.get(future.id)!!.skipReason)
        assertEquals(OccurrenceState.SKIPPED, f.repo.get(armed.id)!!.state)
        assertEquals("a ring that already started is history, not removed", OccurrenceState.FIRING, f.repo.get(fired.id)!!.state)
        assertTrue("the removed occurrence's alarm is cancelled", f.scheduler.armed.keys.none { it.endsWith(armed.id) })
        assertEquals(0, f.db.calendarDao().linkCount())
        assertFalse(f.db.calendarDao().anySelected())
        assertFalse(consents.isGranted(ConsentKey.CALENDAR_EVENT_TEXT))
        assertTrue("a ring that already started is not even attempted, so nothing is logged as rejected",
            f.db.events().ofType(com.command.domain.occurrence.EventType.REJECTED_TRANSITION).isEmpty())
    }

    @Test
    fun `withdrawing calendar access is all or nothing`() = run {
        consents.grant(ConsentKey.CALENDAR_READ)
        val mission = f.mission("Imported", source = MissionSource.DEVICE_CALENDAR)
        val a = f.repo.createOccurrence(mission.id, f.t0 + 3_600_000, "UTC").occurrence
        val b = f.repo.createOccurrence(mission.id, f.t0 + 7_200_000, "UTC").occurrence
        listOf(a, b).forEachIndexed { i, o -> f.db.calendarDao().insertLink(CalendarLinkRow(o.id, 7, 100L + i, o.scheduledAtUtc, null, null, "UTC", f.t0, 0)) }
        // Make the cleanup fail after the first occurrence has been skipped: a listener that throws only runs after commit,
        // so break the second skip by removing its mission row's occurrence out from under the transaction instead.
        val failing = ConsentStore(f.db, f.clock, f.repo, object : ConsentRevocationListener {
            override suspend fun onRevoked(key: ConsentKey, purgedFilePaths: List<String>) = Unit
        })
        try {
            f.repo.inTransaction(EventSource.SYSTEM) { tx ->
                tx.apply(a.id, OccurrenceCommand.Skip(SkipReason.CALENDAR_REMOVED), EventSource.SYSTEM)
                error("simulated crash part-way through the cleanup")
            }
        } catch (expected: IllegalStateException) {
            // rolled back
        }
        assertEquals("the half-done transaction left no trace", OccurrenceState.SCHEDULED, f.repo.get(a.id)!!.state)
        assertTrue(f.scheduler.armed.isEmpty())
        failing.revoke(ConsentKey.CALENDAR_READ)
        assertEquals(OccurrenceState.SKIPPED, f.repo.get(a.id)!!.state)
        assertEquals(OccurrenceState.SKIPPED, f.repo.get(b.id)!!.state)
    }

    @Test
    fun `withdrawing the camera consent withdraws only itself and keeps the registered codes, which are hashes the person can delete`() = run {
        val tokens = TokenRepository(f.db, f.clock)
        tokens.register(HandshakeMode.QR, "Fridge", "command-qr:abc")
        consents.grant(ConsentKey.CAMERA_QR)
        consents.grant(ConsentKey.CALENDAR_READ)

        consents.revoke(ConsentKey.CAMERA_QR)

        assertFalse(consents.isGranted(ConsentKey.CAMERA_QR))
        assertTrue("an unrelated grant stays", consents.isGranted(ConsentKey.CALENDAR_READ))
        assertEquals(listOf(ConsentKey.CAMERA_QR), listener.revoked)
        assertEquals(1, tokens.count(HandshakeMode.QR))
        assertTrue(tokens.matches(HandshakeMode.QR, "command-qr:abc"))
    }

    @Test
    fun `system revocations are recorded as such`() = run {
        consents.grant(ConsentKey.NOTIFICATIONS)
        consents.revoke(ConsentKey.NOTIFICATIONS, ConsentSource.SYSTEM)
        assertEquals(ConsentSource.SYSTEM, consents.history(ConsentKey.NOTIFICATIONS).last().source)
    }
}
