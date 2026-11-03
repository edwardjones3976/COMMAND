package com.command.alarm

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.command.voice.HeadsetDevices
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

    override fun headsetConnected(): Boolean = outputs().any { HeadsetDevices.isHeadset(it) }

    override fun describe(): String {
        val kinds = outputs().mapNotNull { HeadsetDevices.kindOf(it.type) }.distinct().sorted()
        return (listOf("speaker") + kinds).joinToString("+")
    }
}
