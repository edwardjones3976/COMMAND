package com.command.alarm

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/** How the tone's own gain rises when the person asked for a gradual start. It is even in decibels, which is how a rise is heard. */
internal object GradualStart {
    const val RAMP_MS = 10_000L
    private const val FROM_DB = -24.0

    /** The gain [elapsedMs] after the first sound: audible from the start, and full from [RAMP_MS] on. */
    fun gainAt(elapsedMs: Long): Float {
        if (elapsedMs >= RAMP_MS) return 1f
        val db = FROM_DB * (1.0 - elapsedMs.coerceAtLeast(0).toDouble() / RAMP_MS)
        return 10.0.pow(db / 20.0).toFloat()
    }
}

/**
 * What the ring needs to know before its first sound, which comes before the database has opened. So it is kept here and not in the settings table. The
 * erase removes it with every other preference file. If it cannot be read (before the phone's first unlock, say), the answer is the default: off, at full.
 */
@Singleton
class RingPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val store: SharedPreferences? = runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }.getOrNull()
    private val gradual = MutableStateFlow(runCatching { store?.getBoolean(KEY_GRADUAL, false) }.getOrNull() ?: false)

    val gradualStart: Boolean get() = gradual.value

    fun observeGradualStart(): Flow<Boolean> = gradual

    fun setGradualStart(on: Boolean) {
        store?.edit { putBoolean(KEY_GRADUAL, on) }
        gradual.value = on
    }

    private companion object {
        const val FILE = "command_ring"
        const val KEY_GRADUAL = "gradual_start"
    }
}
