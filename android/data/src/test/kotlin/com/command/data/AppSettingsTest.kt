package com.command.data

import androidx.test.core.app.ApplicationProvider
import com.command.data.db.ConsentRow
import com.command.domain.model.ConsentKey
import com.command.domain.model.ConsentSource
import kotlinx.coroutines.flow.first
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
class AppSettingsTest {

    private lateinit var f: Fixture
    private lateinit var settings: AppSettings
    private lateinit var missions: MissionRepository

    @Before
    fun setUp() {
        f = Fixture(ApplicationProvider.getApplicationContext())
        settings = AppSettings(f.db)
        missions = MissionRepository(f.db, f.repo, f.scheduler, f.clock)
    }

    @After
    fun tearDown() = f.close()

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    @Test
    fun `the first-run screens are not done until they are marked done`() = run {
        assertFalse(settings.onboardingDone.first())
        settings.markOnboardingDone()
        assertTrue(settings.onboardingDone.first())
    }

    @Test
    fun `whether the notification request was shown is remembered`() = run {
        assertFalse(settings.notificationsAsked())
        settings.markNotificationsAsked()
        assertTrue(settings.notificationsAsked())
    }

    @Test
    fun `the consent store hands out every row, oldest first, system rows included`() = run {
        val store = ConsentStore(f.db, f.clock, f.repo, NoOpConsentRevocationListener)
        store.grant(ConsentKey.APP_LAUNCHER)
        f.db.consents().insert(ConsentRow(key = ConsentKey.NOTIFICATIONS, granted = false, atUtc = f.t0, source = ConsentSource.SYSTEM, boundaryVersion = 1, shownTextHash = null))

        val rows = store.observeAll().first()
        assertEquals(listOf(ConsentKey.APP_LAUNCHER, ConsentKey.NOTIFICATIONS), rows.map { it.key })
        assertEquals(ConsentSource.SYSTEM, rows.last().source)
    }

    @Test
    fun `a person has missions once one of their own is switched on, and the hidden test alarm does not count`() = run {
        assertFalse(missions.hasMissions())

        missions.testRing(TestRingSpec("Try it"))
        assertFalse("a test alarm is not a mission", missions.hasMissions())

        val draft = MissionDraft(
            title = "Standup", firstAction = "Open the notes", flexibility = com.command.domain.dials.Flexibility.FLEXIBLE,
            schedule = ScheduleDraft.Once(java.time.LocalDateTime.of(2026, 11, 2, 11, 0)),
        )
        val id = (missions.save(draft) as SaveResult.Saved).missionId
        assertTrue(missions.hasMissions())

        missions.delete(id)
        assertFalse(missions.hasMissions())
    }
}
