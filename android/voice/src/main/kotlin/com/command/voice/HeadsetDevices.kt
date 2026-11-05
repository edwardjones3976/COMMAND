package com.command.voice

import android.media.AudioDeviceInfo
import android.os.Build

/**
 * Which audio outputs count as a headset: something worn or plugged in, as opposed to the phone's own speaker, a television or a
 * car. The line that must stay private is played only through one of these, and the audit log names the kind.
 */
object HeadsetDevices {

    /** A short token for the kind of headset an output of [type] is (`wired`, `bt`, `usb`, `hearing`, `ble`), or null when it is not one. */
    fun kindOf(type: Int): String? = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
        else -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID -> "hearing"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble"
            else -> null
        }
    }

    fun isHeadset(device: AudioDeviceInfo?): Boolean = device != null && kindOf(device.type) != null

    /**
     * The output a headset-only line is pinned to: a wired headset first, then USB, a hearing aid, a low-energy headset and last
     * Bluetooth audio. Bluetooth's call channel is a headset for the log but not somewhere to play a line.
     */
    fun playbackTarget(outputs: List<AudioDeviceInfo>): AudioDeviceInfo? {
        fun rank(device: AudioDeviceInfo): Int? = when (kindOf(device.type)) {
            "wired" -> 0
            "usb" -> 1
            "hearing" -> 2
            "ble" -> 3
            "bt" -> if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) null else 4
            else -> null
        }
        return outputs.mapNotNull { d -> rank(d)?.let { it to d } }.minByOrNull { it.first }?.second
    }
}
