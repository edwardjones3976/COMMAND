package com.command.yourdata

import android.net.Uri
import android.os.Looper
import com.command.alarm.ErasureReport
import com.command.data.DataOverview
import com.command.data.ExportSummary
import com.command.testing.FakeClock
import com.command.testing.android.awaitUntil
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.ZoneId
import java.time.ZonedDateTime

/** What the Your data screen does with the two rights, with the jobs themselves faked: the order of things, and what it says about how they went. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataViewModelTest {

    private enum class Survival { NO, WITH_RESULT, WITH_EXCEPTION }

    private class FakeRights : DataRights {
        var overview: DataOverview? = DataOverview(missions = 3, alarms = 120, entries = 2114, records = 4000)
        var exportFails = false
        var eraseFails = false

        /**
         * Like the real eraser, which cannot be cancelled half-way: it runs on to its end even when the screen that asked for it is gone. It
         * reaches the caller with an exception when it changed dispatcher (the real one runs on the IO dispatcher), without one when it did not.
         */
        var eraseSurvives: Survival = Survival.NO
        var eraseReport = ErasureReport(occurrences = 5, leftovers = emptyList(), problems = emptyList())

        /** While set, an export or an erase waits for it, so that a test can look at the screen in the middle. */
        @Volatile var hold: CompletableDeferred<Unit>? = null
        @Volatile var holdOverview: CompletableDeferred<Unit>? = null
        @Volatile var exports = 0
        @Volatile var erases = 0

        override suspend fun overview(): DataOverview {
            holdOverview?.await()
            return overview ?: throw IllegalStateException("cannot be counted")
        }

        /** Like the real one: the data is read first, and only then is the destination asked for. */
        override suspend fun export(destination: () -> OutputStream): ExportSummary {
            exports++
            hold?.await()
            if (exportFails) throw IOException("the disk is full")
            destination().use { it.write("zip".toByteArray()) }
            return ExportSummary(mapOf("missions" to 2, "events" to 5), historyRows = 4)
        }

        override suspend fun erase(): ErasureReport {
            erases++
            when (eraseSurvives) {
                Survival.NO -> hold?.await()
                Survival.WITH_RESULT -> withContext(NonCancellable) { hold?.await() }
                Survival.WITH_EXCEPTION -> withContext(NonCancellable + Dispatchers.Default) { hold?.await() }
            }
            if (eraseFails) throw IllegalStateException("could not erase")
            return eraseReport
        }
    }

    private class CloseTracking : ByteArrayOutputStream() {
        @Volatile var closed = false
        @Volatile var closedOnMain = false
        override fun close() {
            closed = true
            closedOnMain = closedOnMain || onMain()
            super.close()
        }
    }

    private class FakeSink : ExportSink {
        var out = CloseTracking()
        var openFails = false
        val opened = ArrayList<Uri>()
        val discarded = ArrayList<Uri>()
        @Volatile var openedOnMain = false
        @Volatile var discardedOnMain = false

        override fun open(uri: Uri): OutputStream? {
            openedOnMain = openedOnMain || onMain()
            if (openFails) return null
            synchronized(opened) { opened += uri }
            return out
        }

        override fun discard(uri: Uri) {
            discardedOnMain = discardedOnMain || onMain()
            synchronized(discarded) { discarded += uri }
        }
    }

    private val rights = FakeRights()
    private val sink = FakeSink()
    private var closes = 0
    private val uri = Uri.parse("content://documents/export.zip")
    private lateinit var vm: DataViewModel

    @Before
    fun setUp() {
        vm = DataViewModel(rights, sink, { closes++ }, FakeClock(nowMs = ZonedDateTime.of(2026, 11, 2, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()))
    }

    /** The screen once the data has been counted. */
    private fun ready(): DataUiState {
        awaitUntil(message = "the data is counted") { vm.state.value.overview != null }
        return vm.state.value
    }

    private fun awaitState(message: String, check: (DataUiState) -> Boolean) = awaitUntil(message = message) { check(vm.state.value) }

    // --- opening --------------------------------------------------------------------------------------------------

    @Test
    fun `it opens at once with nothing started, and says what there is when it has counted`() {
        val s = vm.state.value
        assertEquals(ExportStatus.Idle, s.export)
        assertEquals(EraseStatus.Idle, s.erase)

        assertEquals(DataOverview(3, 120, 2114, 4000), ready().overview)
        assertEquals(0, rights.exports + rights.erases)
    }

    @Test
    fun `when it cannot be counted the copy and the erase are still offered`() {
        rights.overview = null
        val vm2 = DataViewModel(rights, sink, { closes++ }, FakeClock())

        vm2.askToErase()
        awaitUntil(message = "asked") { vm2.state.value.erase == EraseStatus.Confirming }
        assertNull(vm2.state.value.overview)
    }

    @Test
    fun `the copy is offered under a name that says the day it was made`() {
        assertEquals("command-export-2026-11-02.zip", vm.suggestedFileName())
    }

    // --- the copy -------------------------------------------------------------------------------------------------

    @Test
    fun `a copy is written to the chosen document and says how much was saved`() {
        ready()

        vm.exportTo(uri)

        awaitState("saved") { it.export is ExportStatus.Saved }
        assertEquals(ExportStatus.Saved(records = 7, historyLines = 4), vm.state.value.export)
        assertEquals(listOf(uri), sink.opened)
        assertEquals("zip", sink.out.toString())
        assertTrue("the document was closed", sink.out.closed)
        assertTrue("nothing was removed", sink.discarded.isEmpty())
    }

    @Test
    fun `a document chosen before the data has been counted is written all the same`() {
        rights.holdOverview = CompletableDeferred() // the picker's answer comes first, as it does after the activity was recreated
        val early = DataViewModel(rights, sink, { closes++ }, FakeClock())

        early.exportTo(uri)

        awaitUntil(message = "saved") { early.state.value.export is ExportStatus.Saved }
        assertEquals(listOf(uri), sink.opened)
        assertNull("and it has not counted yet", early.state.value.overview)
        rights.holdOverview!!.complete(Unit)
        awaitUntil(message = "counted") { early.state.value.overview != null }
    }

    @Test
    fun `the document is touched off the main thread, and only once the data has been read`() {
        ready()
        rights.hold = CompletableDeferred()

        vm.exportTo(uri)
        awaitState("working") { it.export == ExportStatus.Working }
        Thread.sleep(100)
        assertTrue("the document is not opened while the data is still being read", sink.opened.isEmpty())
        rights.hold!!.complete(Unit)

        awaitState("saved") { it.export is ExportStatus.Saved }
        assertFalse("opened on the main thread", sink.openedOnMain)
        assertFalse("closed on the main thread", sink.out.closedOnMain)
    }

    @Test
    fun `a copy that failed says so, removes the document it started, off the main thread, and changes nothing else`() {
        ready()
        rights.exportFails = true

        vm.exportTo(uri)

        awaitState("failed") { it.export == ExportStatus.Failed }
        assertEquals(listOf(uri), sink.discarded)
        assertFalse("removed on the main thread", sink.discardedOnMain)
        assertEquals(EraseStatus.Idle, vm.state.value.erase)
        assertEquals(0, rights.erases)
    }

    @Test
    fun `a document that cannot be written to is a failed copy, not a crash`() {
        ready()
        sink.openFails = true

        vm.exportTo(uri)

        awaitState("failed") { it.export == ExportStatus.Failed }
        assertEquals(listOf(uri), sink.discarded)
    }

    @Test
    fun `a second copy asked for while one is being written is removed again and does not disturb the first`() {
        ready()
        rights.hold = CompletableDeferred()
        val other = Uri.parse("content://documents/other.zip")

        vm.exportTo(uri)
        awaitState("working") { it.export == ExportStatus.Working }
        vm.exportTo(other)
        awaitUntil(message = "the second document is removed") { synchronized(sink.discarded) { sink.discarded == listOf(other) } }
        rights.hold!!.complete(Unit)

        awaitState("saved") { it.export is ExportStatus.Saved }
        assertEquals(1, rights.exports)
        assertEquals(listOf(uri), sink.opened)
    }

    @Test
    fun `a document that arrives while the question about erasing is open, or after an erase, is removed again`() {
        ready()
        vm.askToErase()
        awaitState("asked") { it.erase == EraseStatus.Confirming }

        vm.exportTo(uri)

        awaitUntil(message = "removed") { synchronized(sink.discarded) { sink.discarded == listOf(uri) } }
        assertEquals(ExportStatus.Idle, vm.state.value.export)
        assertEquals(0, rights.exports)
    }

    // --- the erase ------------------------------------------------------------------------------------------------

    @Test
    fun `nothing is erased without the question being answered yes`() {
        ready()

        vm.eraseEverything()

        assertEquals(0, rights.erases)
        assertEquals(EraseStatus.Idle, vm.state.value.erase)
    }

    @Test
    fun `asking and then keeping the data erases nothing`() {
        ready()

        vm.askToErase()
        awaitState("asked") { it.erase == EraseStatus.Confirming }
        vm.keepData()

        awaitState("idle again") { it.erase == EraseStatus.Idle }
        vm.eraseEverything()
        assertEquals("the answer no is not undone by a late tap", 0, rights.erases)
    }

    @Test
    fun `erasing after the question deletes, says everything is gone, and the app closes only when asked`() {
        ready()
        rights.hold = CompletableDeferred()

        vm.askToErase()
        awaitState("asked") { it.erase == EraseStatus.Confirming }
        vm.eraseEverything()
        awaitState("working") { it.erase == EraseStatus.Working }
        assertEquals("not closed while it works", 0, closes)
        rights.hold!!.complete(Unit)

        awaitState("done") { it.erase is EraseStatus.Done }
        assertTrue((vm.state.value.erase as EraseStatus.Done).complete)
        assertEquals(1, rights.erases)
        assertEquals("the screen decides when to close; the view model does not", 0, closes)

        vm.closeApp()
        assertEquals(1, closes)
    }

    @Test
    fun `an erase that left something says how much, and is not called complete`() {
        ready()
        rights.eraseReport = ErasureReport(occurrences = 5, leftovers = listOf("files/a", "files/b"), problems = listOf("queued work could not be cancelled"))

        vm.askToErase()
        awaitState("asked") { it.erase == EraseStatus.Confirming }
        vm.eraseEverything()

        awaitState("done") { it.erase is EraseStatus.Done }
        val done = vm.state.value.erase as EraseStatus.Done
        assertFalse(done.complete)
        assertEquals(2, done.leftovers)
        assertEquals(1, done.problems)
        assertEquals(0, closes)
    }

    @Test
    fun `trying again after an erase that left something runs it again without asking, and the offer does not come back`() {
        ready()
        rights.eraseReport = ErasureReport(occurrences = 5, leftovers = listOf("files/a"), problems = emptyList())
        vm.askToErase()
        awaitState("asked") { it.erase == EraseStatus.Confirming }
        vm.eraseEverything()
        awaitState("left something") { (it.erase as? EraseStatus.Done)?.complete == false }

        vm.askToErase() // the database is gone: there is nothing to ask about, and nothing to keep
        vm.keepData()
        vm.exportTo(uri)
        assertTrue("still the end of an erase", vm.state.value.erase is EraseStatus.Done)
        assertEquals("a copy cannot be written of what is gone", 0, rights.exports)

        rights.eraseReport = ErasureReport(occurrences = 0, leftovers = emptyList(), problems = emptyList())
        vm.eraseAgain()

        awaitState("complete") { (it.erase as? EraseStatus.Done)?.complete == true }
        assertEquals(2, rights.erases)
    }

    @Test
    fun `trying again is only for an erase that left something`() {
        ready()

        vm.eraseAgain()

        assertEquals(0, rights.erases)
        assertEquals(EraseStatus.Idle, vm.state.value.erase)
    }

    @Test
    fun `an erase that failed outright can be asked for again`() {
        ready()
        rights.eraseFails = true

        vm.askToErase()
        awaitState("asked") { it.erase == EraseStatus.Confirming }
        vm.eraseEverything()
        awaitState("failed") { it.erase == EraseStatus.Failed }

        rights.eraseFails = false
        vm.askToErase()
        awaitState("asked again") { it.erase == EraseStatus.Confirming }
        vm.eraseEverything()
        awaitState("done") { it.erase is EraseStatus.Done }
        assertEquals(2, rights.erases)
    }

    // --- one thing at a time -------------------------------------------------------------------------------------

    @Test
    fun `no erase is asked for while a copy is being written`() {
        ready()
        rights.hold = CompletableDeferred()

        vm.exportTo(uri)
        awaitState("working") { it.export == ExportStatus.Working }
        vm.askToErase()

        assertEquals(EraseStatus.Idle, vm.state.value.erase)
        rights.hold!!.complete(Unit)
        awaitState("saved") { it.export is ExportStatus.Saved }
    }

    @Test
    fun `a second erase asked for while one runs is ignored`() {
        ready()
        rights.hold = CompletableDeferred()

        vm.askToErase()
        awaitState("asked") { it.erase == EraseStatus.Confirming }
        vm.eraseEverything()
        awaitState("working") { it.erase == EraseStatus.Working }
        vm.askToErase()
        vm.eraseEverything()
        vm.eraseAgain()
        rights.hold!!.complete(Unit)

        awaitState("done") { it.erase is EraseStatus.Done }
        assertEquals(1, rights.erases)
    }

    @Test
    fun `a screen that is destroyed while the erase runs on ends the app when the erase is over, because nobody is left to do it`() {
        destroyedWhileErasing(Survival.WITH_EXCEPTION)
    }

    @Test
    fun `and so when the erase reaches the destroyed screen with its result and not with an exception`() {
        destroyedWhileErasing(Survival.WITH_RESULT)
    }

    private fun destroyedWhileErasing(survival: Survival) {
        ready()
        rights.hold = CompletableDeferred()
        rights.eraseSurvives = survival

        vm.askToErase()
        awaitState("asked") { it.erase == EraseStatus.Confirming }
        vm.eraseEverything()
        awaitUntil(message = "the erase is running") { rights.erases == 1 }
        vm.viewModelScope.cancel() // the activity was destroyed (the phone's "don't keep activities", say) and took its view model with it
        assertEquals("the erase has not finished, so the app is not ended under it", 0, closes)

        rights.hold!!.complete(Unit)

        awaitUntil(message = "the app is ended") { closes == 1 }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("once", 1, closes)
    }

    /** What happens to a view model when its screen is gone for good (the activity finished, or was destroyed without being turned). */
    private fun screenGoneForGood() {
        val method = DataViewModel::class.java.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(vm)
    }

    @Test
    fun `a screen that is gone for good after the erase finished ends the app, because nobody else will`() {
        ready()
        vm.askToErase()
        awaitState("asked") { it.erase == EraseStatus.Confirming }
        vm.eraseEverything()
        awaitState("done") { it.erase is EraseStatus.Done }
        assertEquals("still open: the screen decides when to close", 0, closes)

        screenGoneForGood()

        assertEquals(1, closes)
    }

    @Test
    fun `and so when the erase left something, because its memory still holds a closed database all the same`() {
        ready()
        rights.eraseReport = ErasureReport(occurrences = 5, leftovers = listOf("files/a"), problems = emptyList())
        vm.askToErase()
        awaitState("asked") { it.erase == EraseStatus.Confirming }
        vm.eraseEverything()
        awaitState("done") { it.erase is EraseStatus.Done }

        screenGoneForGood()

        assertEquals(1, closes)
    }

    @Test
    fun `a screen that goes when nothing was erased ends nothing, whether it was asked, failed or never asked`() {
        ready()
        screenGoneForGood()
        assertEquals("never asked", 0, closes)

        vm.askToErase()
        awaitState("asked") { it.erase == EraseStatus.Confirming }
        screenGoneForGood()
        assertEquals("asked", 0, closes)

        rights.eraseFails = true
        vm.eraseEverything()
        awaitState("failed") { it.erase == EraseStatus.Failed }
        screenGoneForGood()
        assertEquals("failed outright: the database is still there", 0, closes)
    }

    @Test
    fun `an erase that could not put the alarm volume back says so, and when it is all that is left it says so alone`() {
        ready()
        rights.eraseReport = ErasureReport(occurrences = 5, leftovers = emptyList(), problems = listOf("the alarm volume could not be put back"), volumeLeft = true)
        vm.askToErase()
        awaitState("asked") { it.erase == EraseStatus.Confirming }
        vm.eraseEverything()

        awaitState("done") { it.erase is EraseStatus.Done }
        val done = vm.state.value.erase as EraseStatus.Done
        assertTrue(done.volumeLeft)
        assertTrue("nothing but the volume is left", done.onlyVolumeLeft)
        assertFalse(done.complete)
    }

    @Test
    fun `the volume along with something else is not all that is left`() {
        ready()
        rights.eraseReport = ErasureReport(occurrences = 5, leftovers = listOf("files/a"), problems = listOf("the alarm volume could not be put back"), volumeLeft = true)
        vm.askToErase()
        awaitState("asked") { it.erase == EraseStatus.Confirming }
        vm.eraseEverything()

        awaitState("done") { it.erase is EraseStatus.Done }
        val done = vm.state.value.erase as EraseStatus.Done
        assertTrue(done.volumeLeft)
        assertFalse(done.onlyVolumeLeft)
    }

    @Test
    fun `a screen that is cancelled before the erase has begun does not end the app`() {
        ready()
        rights.hold = CompletableDeferred()

        vm.askToErase()
        awaitState("asked") { it.erase == EraseStatus.Confirming }
        vm.viewModelScope.cancel() // gone while still being asked: nothing was erased

        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, rights.erases)
        assertEquals(0, closes)
    }
}

private fun onMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()
