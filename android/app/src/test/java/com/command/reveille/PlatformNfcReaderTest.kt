package com.command.reveille

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.command.domain.handshake.TagRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNfcAdapter
import org.robolectric.util.ReflectionHelpers

/**
 * The phone's tag reader ([PlatformNfcReader]) in an activity, against Robolectric's NFC adapter: reader mode is on only while the reader is
 * composed and the activity is resumed, a tag reaches the main thread as its fingerprint only, and a reader that cannot be used says so once
 * (and again to a new callback while it still cannot be used).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlatformNfcReaderTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val tags = ArrayList<TagRead>()
    private var unavailable = 0
    private var listening by mutableStateOf(true)

    /** Lets a state written by the test reach the composition, and the main thread run what was posted to it. */
    private fun idle() {
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun pause() = compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
    private fun resume() = compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

    private fun phone(hardware: Boolean = true, feature: Boolean = true, on: Boolean = true): NfcAdapter? {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, feature)
        ShadowNfcAdapter.setNfcHardwareExists(hardware)
        val adapter = NfcAdapter.getDefaultAdapter(app) ?: return null
        shadow(adapter).setEnabled(on)
        return adapter
    }

    private fun shadow(adapter: NfcAdapter): ShadowNfcAdapter = Shadow.extract(adapter)

    /** The activity listens while [listening], reporting to [onTag] and [onUnavailable] (the test's own, unless it says otherwise). */
    private fun open(onTag: () -> (TagRead) -> Unit = { { tags += it } }, onUnavailable: () -> () -> Unit = { { unavailable++ } }) {
        compose.setContent {
            if (listening) PlatformNfcReader.Listen(onTag = onTag(), onUnavailable = onUnavailable())
        }
        idle()
    }

    private fun callbackOf(adapter: NfcAdapter) = ReflectionHelpers.getField<NfcAdapter.ReaderCallback>(shadow(adapter), "readerCallback")

    /** The receivers registered for the adapter's state changes, from any context of the app. */
    private fun stateReceivers() = shadowOf(app).registeredReceivers.filter { it.intentFilter.hasAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) }

    private fun adapterState(state: Int) =
        app.sendBroadcast(Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED).putExtra(NfcAdapter.EXTRA_ADAPTER_STATE, state))

    private val mine = MockTags.withId(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
    private val mineRead = TagRead.of(byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66))

    @Test
    fun readerModeOnAfterResume() {
        val adapter = phone()!!
        open()
        assertTrue(shadow(adapter).isInReaderMode)
        assertEquals(0, unavailable)
    }

    @Test
    fun offAfterPause() {
        val adapter = phone()!!
        open()
        pause()
        assertFalse(shadow(adapter).isInReaderMode)
        resume()
        idle()
        assertTrue("on again when the activity is back", shadow(adapter).isInReaderMode)
    }

    @Test
    fun offAfterDispose() {
        val adapter = phone()!!
        open()
        listening = false
        idle()
        assertFalse(shadow(adapter).isInReaderMode)
    }

    @Test
    fun tagPostedOnMainLooperAsSeen() {
        val adapter = phone()!!
        open()

        shadow(adapter).dispatchTagDiscovered(mine)
        assertTrue("nothing reaches the screen until the main thread runs", tags.isEmpty())
        idle()

        assertEquals(listOf(mineRead), tags)
        assertTrue(tags.single() is TagRead.Seen)
    }

    @Test
    fun noIdAndRandomIdArriveClassified() {
        val adapter = phone()!!
        open()

        shadow(adapter).dispatchTagDiscovered(MockTags.withId(ByteArray(0)))
        shadow(adapter).dispatchTagDiscovered(MockTags.withId(0x08, 0x01, 0x02, 0x03))
        idle()

        assertEquals(listOf(TagRead.NoId, TagRead.Changing), tags)
    }

    @Test
    fun secondTagAlsoDelivered() {
        val adapter = phone()!!
        open()

        shadow(adapter).dispatchTagDiscovered(mine)
        idle()
        shadow(adapter).dispatchTagDiscovered(mine)
        idle()

        assertEquals("tags are never once-only", listOf(mineRead, mineRead), tags)
    }

    @Test
    fun latestCallbackUsed() {
        val adapter = phone()!!
        val first = ArrayList<TagRead>()
        val second = ArrayList<TagRead>()
        var current by mutableStateOf<(TagRead) -> Unit>({ first += it })
        open(onTag = { current })
        val started = callbackOf(adapter)

        current = { second += it }
        idle()
        shadow(adapter).dispatchTagDiscovered(mine)
        idle()

        assertTrue(first.isEmpty())
        assertEquals(listOf(mineRead), second)
        assertTrue(shadow(adapter).isInReaderMode)
        assertSame("the reader was not started again for a new callback", started, callbackOf(adapter))
    }

    @Test
    fun tagAfterDisposeDropped() {
        val adapter = phone()!!
        open()
        val callback = callbackOf(adapter)

        listening = false
        idle()
        callback.onTagDiscovered(mine) // the binder thread's late delivery
        idle()

        assertTrue(tags.isEmpty())
    }

    @Test
    fun tagAfterPauseDropped() {
        val adapter = phone()!!
        open()
        val callback = callbackOf(adapter)

        pause()
        callback.onTagDiscovered(mine) // the binder thread's late delivery
        idle()

        assertTrue("a read that arrives after the pause is not delivered", tags.isEmpty())
    }

    @Test
    fun tagPostedBeforePauseRunAfterItDropped() {
        val adapter = phone()!!
        open()
        val activity = compose.activity

        callbackOf(adapter).onTagDiscovered(mine) // read on the binder thread while resumed, its post not yet run
        InstrumentationRegistry.getInstrumentation().callActivityOnPause(activity) // the pause comes first on the main thread
        idle()
        assertTrue("a read posted before the pause and run after it is not delivered", tags.isEmpty())

        InstrumentationRegistry.getInstrumentation().callActivityOnResume(activity)
        idle()
    }

    @Test
    fun nullAdapterReportsUnavailableOnce() {
        phone(hardware = false)
        open()
        idle()
        assertEquals(1, unavailable)
    }

    @Test
    fun disabledAdapterReportsUnavailableOnce() {
        val adapter = phone(on = false)!!
        open()
        idle()
        assertEquals(1, unavailable)
        assertFalse(shadow(adapter).isInReaderMode)
    }

    @Test
    fun readerModeThatCannotStartReportsUnavailable() {
        // Robolectric's adapter refuses reader mode (UnsupportedOperationException) on a phone without the NFC feature.
        phone(feature = false)
        open()
        idle()
        assertEquals(1, unavailable)
    }

    @Test
    fun anAdapterThatIsOnButRefusesReaderModeReportsUnavailable() {
        // The adapter is there and on (the app's NfcManager keeps the one it made while the feature was there), so only the start of reader
        // mode can fail: Robolectric's adapter throws then, as a phone's throws SecurityException or IllegalStateException. Never a crash.
        val adapter = phone()!!
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, false)
        open()
        idle()
        assertEquals("the reader that cannot start says so once", 1, unavailable)
        assertFalse(shadow(adapter).isInReaderMode)
        assertTrue("no receiver for a reader that never started", stateReceivers().isEmpty())
    }

    @Test
    fun adapterOffBroadcastReportsUnavailable() {
        phone()
        open()

        adapterState(NfcAdapter.STATE_ON)
        idle()
        assertEquals("NFC turning on is nothing to report", 0, unavailable)

        adapterState(NfcAdapter.STATE_TURNING_OFF)
        adapterState(NfcAdapter.STATE_OFF)
        idle()
        assertEquals("said once for the resume", 1, unavailable)
    }

    @Test
    fun aNewCallbackHearsAReaderThatIsStillOff() {
        phone()
        val first = ArrayList<String>()
        val second = ArrayList<String>()
        var current by mutableStateOf<() -> Unit>({ first += "off" })
        open(onUnavailable = { current })

        adapterState(NfcAdapter.STATE_OFF)
        idle()
        assertEquals(listOf("off"), first)

        // The try on screen changed while the report was made with the one before (which the view model drops): the new one hears it too.
        current = { second += "off" }
        idle()
        assertEquals(listOf("off"), first)
        assertEquals(listOf("off"), second)
    }

    @Test
    fun aNewCallbackWhileTheReaderWorksHearsNothing() {
        phone()
        var current by mutableStateOf<() -> Unit>({ unavailable++ })
        open(onUnavailable = { current })

        current = { unavailable += 10 }
        idle()
        assertEquals(0, unavailable)
    }

    @Test
    fun aResumeAfterTheReportStartsClean() {
        val adapter = phone()!!
        var current by mutableStateOf<() -> Unit>({ unavailable++ })
        open(onUnavailable = { current })
        adapterState(NfcAdapter.STATE_OFF)
        idle()
        assertEquals(1, unavailable)

        pause()
        shadow(adapter).setEnabled(true)
        resume()
        idle()
        current = { unavailable += 10 }
        idle()
        assertEquals("NFC is on again: the report of the resume before is not made again", 1, unavailable)
    }

    @Test
    fun noBroadcastHeardAfterPause() {
        phone()
        open()
        pause()
        assertTrue("the receiver is let go at the pause", stateReceivers().isEmpty())

        adapterState(NfcAdapter.STATE_OFF)
        idle()

        assertEquals(0, unavailable)
    }

    @Test
    fun adapterStateReceiverHearsTheNfcService() {
        phone()
        open()

        // ACTION_ADAPTER_STATE_CHANGED comes from the NFC service's own uid, which a receiver that is not exported never hears (API 33+).
        val receiver = stateReceivers().single()
        assertTrue("exported", receiver.flags and Context.RECEIVER_EXPORTED != 0)
        assertEquals("not marked not-exported", 0, receiver.flags and Context.RECEIVER_NOT_EXPORTED)
        assertNull("no permission the NFC service does not hold", receiver.broadcastPermission)
    }

    @Test
    @Config(sdk = [28])
    fun adapterStateReceiverHearsTheNfcServiceBeforeApi33() {
        phone()
        open()

        // Below API 33, a receiver that is not exported is guarded by this app's own signature permission, which the NFC service lacks.
        assertNull(stateReceivers().single().broadcastPermission)
    }

    @Test
    fun pauseThenDisposeNoDoubleUnregister() {
        val adapter = phone()!!
        open()
        assertEquals("one receiver while resumed", 1, stateReceivers().size)
        pause()
        assertEquals("none while paused", 0, stateReceivers().size)
        resume()
        idle()
        assertEquals("one again, not two", 1, stateReceivers().size)
        pause()
        listening = false
        idle()
        assertEquals(0, stateReceivers().size)
        resume()
        idle()
        assertEquals("not listening: nothing registered at the resume", 0, stateReceivers().size)
        compose.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
        assertFalse(shadow(adapter).isInReaderMode)
        assertEquals(0, stateReceivers().size)
    }

    @Test
    fun disposeWhileResumedLetsTheReceiverGo() {
        phone()
        open()
        assertEquals(1, stateReceivers().size)
        listening = false
        idle()
        assertEquals(0, stateReceivers().size)
    }

    @Test
    @Config(shadows = [FlagsNfcAdapter::class])
    fun readerModeAsksForEveryTagWithoutNdefAndKeepsThePlatformSound() {
        phone()
        FlagsNfcAdapter.flags = null
        open()

        val flags = checkNotNull(FlagsNfcAdapter.flags) { "reader mode was not asked for" }
        val every = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        assertEquals("every kind of tag, without the NDEF check", every, flags and every)
        assertEquals("the platform's own tag sound is never turned off", 0, flags and NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS)
    }
}

/** Robolectric's NFC adapter, which also keeps the flags reader mode was asked for (its own shadow throws them away). */
@Implements(NfcAdapter::class)
class FlagsNfcAdapter : ShadowNfcAdapter() {
    @Implementation
    override fun enableReaderMode(activity: Activity?, callback: NfcAdapter.ReaderCallback?, flags: Int, extras: Bundle?) {
        Companion.flags = flags
        super.enableReaderMode(activity, callback, flags, extras)
    }

    companion object {
        @Volatile var flags: Int? = null
    }
}
