package com.command.alarm

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.command.voice.HeadsetDevices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

/** Whether a headset is connected decides where the spoken line may come out, so each kind of output is told apart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAudioRoutesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val routes = AndroidAudioRoutes(context)

    private fun connected(vararg types: Int) {
        val devices = types.map { AudioDeviceInfoBuilder.newBuilder().setType(it).build() }
        shadowOf(context.getSystemService(AudioManager::class.java)).setOutputDevices(devices)
    }

    @Test
    fun `with nothing but the phone's own speaker there is no headset`() {
        connected(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertFalse(routes.headsetConnected())
        assertEquals("speaker", routes.describe())
    }

    @Test
    fun `with no outputs listed at all there is no headset either`() {
        connected()
        assertFalse(routes.headsetConnected())
        assertEquals("speaker", routes.describe())
    }

    @Test
    fun `wired headphones and wired headsets are a headset, and the log says wired`() {
        connected(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        assertTrue(routes.headsetConnected())
        assertEquals("speaker+wired", routes.describe())

        connected(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        assertTrue(routes.headsetConnected())
        assertEquals("speaker+wired", routes.describe())
    }

    @Test
    fun `Bluetooth, USB, hearing aid and low-energy headsets each count, under their own names`() {
        for ((type, name) in listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "bt",
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO to "bt",
            AudioDeviceInfo.TYPE_USB_HEADSET to "usb",
            AudioDeviceInfo.TYPE_HEARING_AID to "hearing",
            AudioDeviceInfo.TYPE_BLE_HEADSET to "ble",
        )) {
            connected(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, type)
            assertTrue("type $type is a headset", routes.headsetConnected())
            assertEquals("speaker+$name", routes.describe())
        }
    }

    @Test
    fun `several outputs are listed once each and in a fixed order`() {
        connected(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertEquals("speaker+bt+wired", routes.describe())
    }

    @Test
    fun `an output that is not worn, such as a TV over HDMI, is not a headset`() {
        connected(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_HDMI)
        assertFalse(routes.headsetConnected())
        assertEquals("speaker", routes.describe())
    }

    private fun device(type: Int) = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    @Test
    fun `a headset-only line is pinned to a wired headset before anything else, and Bluetooth calls are never a place to play it`() {
        val wired = device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        val usb = device(AudioDeviceInfo.TYPE_USB_HEADSET)
        val a2dp = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val sco = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

        assertEquals(wired, HeadsetDevices.playbackTarget(listOf(speaker, a2dp, usb, wired)))
        assertEquals(usb, HeadsetDevices.playbackTarget(listOf(speaker, a2dp, usb)))
        assertEquals(a2dp, HeadsetDevices.playbackTarget(listOf(speaker, sco, a2dp)))
        assertNull("the call channel is a headset for the log but not a target", HeadsetDevices.playbackTarget(listOf(speaker, sco)))
        assertNull("the phone's own speaker is never a target", HeadsetDevices.playbackTarget(listOf(speaker)))
        assertNull(HeadsetDevices.playbackTarget(emptyList()))
    }
}
