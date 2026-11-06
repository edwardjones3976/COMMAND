package com.command.ui

import android.os.Looper
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/** What a screen's coroutine throws never reaches the process, which may be ringing an alarm. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GuardedScopeTest {

    private class Screen : ViewModel()

    @Before
    fun setUp() = ShadowLog.reset()

    private fun failures() = ShadowLog.getLogsForTag("ScreenWork")

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `what work throws is logged, and goes no further`() {
        val vm = Screen()
        vm.guardedScope.launch { throw IllegalStateException("from a screen") }
        idle()
        assertEquals(1, failures().size)
        assertTrue(failures().single().throwable is IllegalStateException)
    }

    @Test
    fun `a failure leaves the other work of the view model running`() {
        val vm = Screen()
        var ran = false
        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
        vm.guardedScope.launch {
            waiting.await()
            ran = true
        }
        vm.guardedScope.launch { throw IllegalStateException("from a screen") }
        idle()
        waiting.complete(Unit)
        idle()
        assertTrue(ran)
        vm.guardedScope.launch { throw IllegalArgumentException("and later work still reports") }
        idle()
        assertEquals(2, failures().size)
    }

    @Test
    fun `a state flow whose source fails is logged, and keeps what it last had`() {
        val vm = Screen()
        val state = flow<Int> {
            emit(1)
            throw IllegalStateException("the database went away")
        }.stateIn(vm.guardedScope, SharingStarted.Eagerly, 0)
        idle()
        assertEquals(1, state.value)
        assertEquals(1, failures().size)
    }

    @Test
    fun `no view model uses a scope that would let it through`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "AGENTS.md").isFile && File(it, "android").isDirectory }
        val hits = File(root, "android/app/src/main/java/com/command").walkTopDown()
            .filter { it.extension == "kt" && it.name != "GuardedScope.kt" }
            .filter { it.readText().contains("viewModelScope") }
            .map { it.name }.toList()
        assertEquals("use guardedScope", emptyList<String>(), hits)
        val guarded = File(root, "android/app/src/main/java/com/command").walkTopDown()
            .filter { it.extension == "kt" && it.readText().contains(": ViewModel()") && it.readText().contains("guardedScope") }.count()
        assertTrue("the check still finds the view models ($guarded)", guarded >= 10)
    }
}
