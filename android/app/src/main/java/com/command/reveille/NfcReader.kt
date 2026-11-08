package com.command.reveille

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.command.domain.handshake.TagRead
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The tag reader of the NFC handshake, behind a seam (the same shape as [QrScanner]): the screens listen through whatever [LocalNfcReader]
 * holds, which is the phone's own reader ([PlatformNfcReader]) unless a test provides another.
 */
interface NfcReader {
    /**
     * Listens for tags while composed and the activity is resumed. Draws nothing. Each touch is reported to [onTag] on the main thread, as its
     * fingerprint only ([TagRead]); a tag is never once-only (the caller decides what a repeat means). [onUnavailable] is called, once per
     * resume, when the reader cannot be used: there is no adapter, NFC is off or is turned off, or reader mode cannot start. It is called
     * again when a new [onUnavailable] arrives while the reader still cannot be used, so that a report made with a try that was just
     * replaced (and dropped for that) is not the last one.
     */
    @Composable
    fun Listen(onTag: (TagRead) -> Unit, onUnavailable: () -> Unit)
}

/** The reader the screens use. */
val LocalNfcReader = staticCompositionLocalOf<NfcReader> { PlatformNfcReader }

/**
 * The phone's reader through Android's reader mode, on only while it is composed and the activity is resumed (it follows the activity's own
 * resume and pause): ReveilleActivity needs no `onResume`/`onPause` of its own for it. While it is on, card emulation (tap-to-pay) and tag
 * dispatch to other apps are paused, which is how reader mode works. It touches no audio: the platform's own tag sound is left as the phone
 * sets it ([FLAG_READER_NO_PLATFORM_SOUNDS][NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS] is not set).
 *
 * The raw id never leaves the reader callback: it is classified and hashed there, on the NFC binder thread ([TagRead.of]), and only the
 * redacted [TagRead] is posted to the main thread. The [android.nfc.Tag] is not kept, and nothing of it is logged.
 */
object PlatformNfcReader : NfcReader {

    /** Every kind of tag, without the NDEF check (only the id is read). No platform-sound flag. */
    private const val FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

    @Composable
    override fun Listen(onTag: (TagRead) -> Unit, onUnavailable: () -> Unit) {
        // The callbacks change with every try (they carry its TryId) while the reader stays on: the latest is always the one called.
        val tagged by rememberUpdatedState(onTag)
        val failed by rememberUpdatedState(onUnavailable)
        val activity = LocalActivity.current ?: LocalContext.current.findActivity()
        if (activity == null) {
            LaunchedEffect(Unit) { failed() }
            return
        }
        // Set once this resume's report has been made, and cleared when the resume ends: a new onUnavailable (a new try) hears it again.
        val dead = remember { AtomicBoolean(false) }
        LaunchedEffect(onUnavailable) { if (dead.get()) failed() }
        LifecycleResumeEffect(activity) {
            val gone = AtomicBoolean(false)
            val reported = AtomicBoolean(false)
            val main = ContextCompat.getMainExecutor(activity)
            fun unavailable() {
                if (!gone.get() && reported.compareAndSet(false, true)) {
                    main.execute {
                        if (!gone.get()) {
                            dead.set(true)
                            failed()
                        }
                    }
                }
            }

            val adapter = runCatching { NfcAdapter.getDefaultAdapter(activity) }.getOrNull()
            var registered = false
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val state = intent?.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_ON) ?: return
                    if (state == NfcAdapter.STATE_TURNING_OFF || state == NfcAdapter.STATE_OFF) unavailable()
                }
            }
            if (adapter == null || !runCatching { adapter.isEnabled }.getOrDefault(false)) {
                unavailable()
            } else {
                val callback = NfcAdapter.ReaderCallback { tag ->
                    // The NFC binder thread: the id is copied, classified and hashed here, and the bytes go no further.
                    val read = TagRead.of(tag?.id)
                    main.execute { if (!gone.get()) tagged(read) }
                }
                val started = runCatching { adapter.enableReaderMode(activity, callback, FLAGS, null) }.isSuccess
                if (!started) {
                    unavailable()
                } else {
                    // Exported: the state change is sent by the NFC service (its own uid, neither the system nor this app), which a receiver
                    // that is not exported never hears. The action is a protected broadcast, so only the platform can send it.
                    registered = runCatching {
                        ContextCompat.registerReceiver(activity, receiver, IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
                    }.isSuccess
                }
            }

            onPauseOrDispose {
                gone.set(true)
                dead.set(false)
                runCatching { adapter?.disableReaderMode(activity) }
                if (registered) {
                    runCatching { activity.unregisterReceiver(receiver) }
                    registered = false
                }
            }
        }
    }

    private fun Context.findActivity(): Activity? {
        var context: Context? = this
        while (context != null) {
            if (context is Activity) return context
            context = (context as? ContextWrapper)?.baseContext
        }
        return null
    }
}
