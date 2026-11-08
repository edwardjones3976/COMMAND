package com.command.reveille

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.command.domain.handshake.TagRead

/**
 * A tag reader that reads what the test says it reads. It counts how many [Listen]s are composed now ([active]) and ever ([composed]), and
 * [touch] and [fail] reach the latest callbacks of every active one, as the real reader does.
 */
internal class FakeNfcReader : NfcReader {
    private class Listener(var onTag: (TagRead) -> Unit, var onUnavailable: () -> Unit)

    private val listeners = LinkedHashSet<Listener>()

    val active: Int get() = listeners.size
    var composed = 0
        private set

    fun touch(read: TagRead) = listeners.toList().forEach { it.onTag(read) }

    fun fail() = listeners.toList().forEach { it.onUnavailable() }

    @Composable
    override fun Listen(onTag: (TagRead) -> Unit, onUnavailable: () -> Unit) {
        val tagged by rememberUpdatedState(onTag)
        val failed by rememberUpdatedState(onUnavailable)
        DisposableEffect(Unit) {
            composed++
            val listener = Listener({ tagged(it) }, { failed() })
            listeners += listener
            onDispose { listeners -= listener }
        }
    }
}
