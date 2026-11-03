package com.command.alarm

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Where the phone's sound is going to come out: whether a headset is connected, and a short token for the audit log. */
interface AudioRoutes {
    fun headsetConnected(): Boolean

    /** What is connected, as short tokens (`speaker`, `wired`, `bt`, `usb`, `ble`, `hearing`) joined with `+`; safe in the audit log. */
    fun describe(): String
}

class AndroidAudioRoutes @Inject constructor(@ApplicationContext private val context: Context) : AudioRoutes {

    private fun outputs(): List<AudioDeviceInfo> =
        runCatching { context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() }.getOrNull().orEmpty()

    private fun kindOf(device: AudioDeviceInfo): String? = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
        else -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && device.type == AudioDeviceInfo.TYPE_HEARING_AID -> "hearing"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble"
            else -> null
        }
    }

    override fun headsetConnected(): Boolean = outputs().any { kindOf(it) != null }

    override fun describe(): String {
        val kinds = outputs().mapNotNull { kindOf(it) }.distinct().sorted()
        return (listOf("speaker") + kinds).joinToString("+")
    }
}
