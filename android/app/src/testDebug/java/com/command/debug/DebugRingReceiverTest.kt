package com.command.debug

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.command.data.ConsentStore
import com.command.data.db.CommandDb
import com.command.domain.model.ConsentKey
import com.command.domain.occurrence.OccurrenceState
import com.command.testing.android.awaitUntil
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * The shell's ring starter, debug builds only. The volume restore case of scripts/reliability.sh needs the volume consent, which a person gives on
 * the consent screen: `--ez volume true` gives it from the shell, as a person would (source USER), and nothing else does.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class DebugRingReceiverTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var consents: ConsentStore
    @Inject lateinit var db: CommandDb

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        hilt.inject()
    }

    private fun ring(title: String, volume: Boolean? = null) {
        val intent = Intent(DebugRingReceiver.ACTION).putExtra("seconds", 600).putExtra("title", title)
        if (volume != null) intent.putExtra("volume", volume)
        DebugRingReceiver().onReceive(context, intent)
    }

    private fun armed(title: String): Boolean = runBlocking {
        val mission = db.missions().all().firstOrNull { it.title == title } ?: return@runBlocking false
        db.occurrences().forMission(mission.id).any { it.state == OccurrenceState.ARMED }
    }

    @Test
    fun `volume true gives the volume consent and still arms the ring`() {
        ring("relvolume", volume = true)
        awaitUntil(message = "the occurrence is armed") { armed("relvolume") }
        awaitUntil(message = "the volume consent is given") { runBlocking { consents.isGranted(ConsentKey.VOLUME_CONTROL) } }
    }

    @Test
    fun `without it the volume consent is left as it was`() {
        ring("relplain")
        awaitUntil(message = "the occurrence is armed") { armed("relplain") }
        assertFalse(runBlocking { consents.isGranted(ConsentKey.VOLUME_CONTROL) })
    }
}
