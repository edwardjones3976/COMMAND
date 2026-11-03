package com.command.data

import androidx.test.core.app.ApplicationProvider
import com.command.domain.model.ConsentKey
import com.command.domain.model.ConsentSource
import com.command.domain.model.SettingKeys
import com.command.domain.occurrence.EventType
import com.command.domain.reliability.Precondition
import com.command.domain.reliability.PreconditionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PreconditionRecorderTest {

    private lateinit var f: Fixture
    private lateinit var recorder: PreconditionRecorder
    private lateinit var consents: ConsentStore

    @Before
    fun setUp() {
        f = Fixture(ApplicationProvider.getApplicationContext())
        recorder = PreconditionRecorder(f.db, f.clock)
        consents = ConsentStore(f.db, f.clock, f.repo, NoOpConsentRevocationListener)
    }

    @After
    fun tearDown() = f.close()

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private fun healthy() = PreconditionSnapshot(
        mapOf(
            Precondition.EXACT_ALARMS to "granted", Precondition.NOTIFICATIONS to "granted", Precondition.FULL_SCREEN_INTENT to "granted",
            Precondition.ALARM_CHANNEL to "HIGH", Precondition.CHECK_IN_CHANNEL to "HIGH", Precondition.ALARM_VOLUME to "audible", Precondition.DO_NOT_DISTURB to "free",
            Precondition.BATTERY_OPTIMIZATION to "optimized",
            Precondition.BACKGROUND_RESTRICTED to "no", Precondition.STANDBY_BUCKET to "ACTIVE",
        ),
    )

    private fun healthyWith(vararg changed: Pair<Precondition, String>) = PreconditionSnapshot(healthy().values + mapOf(*changed))

    private suspend fun types(type: EventType) = f.db.events().ofType(type)

    @Test
    fun `the first reading is a baseline, with every value logged, the permissions recorded as consents and no permission change`() = run {
        val changes = recorder.record(healthy())

        assertEquals(Precondition.values().size, changes.size)
        assertEquals(Precondition.values().size, types(EventType.PRECONDITION).size)
        assertTrue("nothing changed yet", types(EventType.PERMISSION_CHANGED).isEmpty())
        for (key in listOf(ConsentKey.EXACT_ALARM, ConsentKey.NOTIFICATIONS, ConsentKey.FULL_SCREEN)) {
            val row = f.db.consents().latest(key)
            assertNotNull(key.name, row)
            assertTrue(row!!.granted)
            assertEquals(ConsentSource.SYSTEM, row.source)
        }
        assertNotNull(recorder.last())
    }

    @Test
    fun `a baseline with a permission denied records the denial too`() = run {
        recorder.record(healthyWith(Precondition.NOTIFICATIONS to "denied"))
        assertFalse(consents.isGranted(ConsentKey.NOTIFICATIONS))
        assertEquals(false, f.db.consents().latest(ConsentKey.NOTIFICATIONS)!!.granted)
    }

    @Test
    fun `reading the same thing again writes nothing`() = run {
        recorder.record(healthy())
        val events = f.db.events().all().size
        assertTrue(recorder.record(healthy()).isEmpty())
        assertEquals(events, f.db.events().all().size)
        assertEquals(1, f.db.consents().history(ConsentKey.NOTIFICATIONS).size)
    }

    @Test
    fun `a permission turned off in settings is a change, a consent withdrawn by the system, and says which`() = run {
        recorder.record(healthy())
        val changes = recorder.record(healthyWith(Precondition.NOTIFICATIONS to "denied"))

        assertEquals(1, changes.size)
        val change = types(EventType.PERMISSION_CHANGED).single()
        assertTrue(change.payloadJson.contains("NOTIFICATIONS") && change.payloadJson.contains("denied") && change.payloadJson.contains("granted"))
        assertEquals(2, types(EventType.PRECONDITION).count { it.payloadJson.contains("NOTIFICATIONS") })
        assertFalse(consents.isGranted(ConsentKey.NOTIFICATIONS))
        val history = f.db.consents().history(ConsentKey.NOTIFICATIONS)
        assertEquals(listOf(true, false), history.map { it.granted })
        assertEquals(ConsentSource.SYSTEM, history.last().source)

        // And turned back on.
        recorder.record(healthy())
        assertTrue(consents.isGranted(ConsentKey.NOTIFICATIONS))
        assertEquals(2, types(EventType.PERMISSION_CHANGED).size)
    }

    @Test
    fun `a value that is not a permission is logged as a change but never touches consents`() = run {
        recorder.record(healthy())
        val consentRows = f.db.consents().all().size
        recorder.record(healthyWith(Precondition.STANDBY_BUCKET to "RARE"))
        assertEquals(2, types(EventType.PRECONDITION).count { it.payloadJson.contains("STANDBY_BUCKET") })
        assertTrue(types(EventType.PERMISSION_CHANGED).isEmpty())
        assertEquals(consentRows, f.db.consents().all().size)
    }

    @Test
    fun `an alarm stream that becomes silent is flagged once, when it happens`() = run {
        recorder.record(healthy())
        assertTrue(types(EventType.DND_MAY_SUPPRESS).isEmpty())
        recorder.record(healthyWith(Precondition.ALARM_VOLUME to "muted"))
        assertEquals(1, types(EventType.DND_MAY_SUPPRESS).size)
        recorder.record(healthyWith(Precondition.ALARM_VOLUME to "muted"))
        assertEquals("still one: it is flagged when it changes, not every time it is read", 1, types(EventType.DND_MAY_SUPPRESS).size)
    }

    @Test
    fun `a stored snapshot that cannot be read is treated as a first reading`() = run {
        f.db.settings().put(com.command.data.db.SettingRow(SettingKeys.PERMISSION_SNAPSHOT, "not json at all"))
        assertNull(recorder.last())
        assertEquals(Precondition.values().size, recorder.record(healthy()).size)
    }

    @Test
    fun `two readings that arrive together write each change once`() = run {
        recorder.record(healthy())
        val flips = 25
        for (i in 1..flips) {
            val reading = healthyWith(Precondition.NOTIFICATIONS to if (i % 2 == 1) "denied" else "granted")
            coroutineScope {
                listOf(async(Dispatchers.Default) { recorder.record(reading) }, async(Dispatchers.Default) { recorder.record(reading) }).awaitAll()
            }
        }
        assertEquals("every flip is one change, however many readings saw it", flips, types(EventType.PERMISSION_CHANGED).size)
        assertEquals("and one consent row", flips + 1, f.db.consents().history(ConsentKey.NOTIFICATIONS).size)
    }
}
