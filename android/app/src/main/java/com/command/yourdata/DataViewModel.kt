package com.command.yourdata

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.data.DataOverview
import com.command.domain.time.Clock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

/** Where a copy of the data stands. */
sealed interface ExportStatus {
    data object Idle : ExportStatus
    data object Working : ExportStatus
    data class Saved(val records: Int, val historyLines: Int) : ExportStatus
    data object Failed : ExportStatus
}

/** Where the erase stands: asked about, running, or finished with what was left of it. */
sealed interface EraseStatus {
    data object Idle : EraseStatus
    data object Confirming : EraseStatus
    data object Working : EraseStatus

    /**
     * [leftovers] are files that could not be deleted, [problems] steps that did not work. Both are zero when everything is gone.
     * [volumeLeft] is one of the problems: the alarm volume could not be put back, which closing the app does not change.
     */
    data class Done(val leftovers: Int, val problems: Int, val volumeLeft: Boolean = false) : EraseStatus {
        val complete: Boolean get() = leftovers == 0 && problems == 0

        /** Nothing is left but the volume, so that the words about "the rest" that Android clears would be untrue. */
        val onlyVolumeLeft: Boolean get() = leftovers == 0 && problems == 1 && volumeLeft
    }

    /** The erase itself failed: it can be asked for again. */
    data object Failed : EraseStatus
}

/**
 * [overview] is null until the data has been counted, and when it could not be: the copy and the erase are offered all the same, and the
 * screen is usable from the first moment (a document chosen in the picker can arrive before the counting is done).
 */
data class DataUiState(val overview: DataOverview?, val export: ExportStatus, val erase: EraseStatus)

/**
 * The person's two rights over what COMMAND keeps: to have a copy, and to have it gone. Neither leaves the phone by itself: a copy is
 * written where the person chose, and the erase deletes. Only one thing runs at a time, and an erase is only run after the question
 * about it has been answered; once one has run there is no going back to the offer (the database is gone), only on to the end.
 */
@HiltViewModel
class DataViewModel @Inject constructor(
    private val rights: DataRights,
    private val sink: ExportSink,
    private val reset: ProcessReset,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(DataUiState(overview = null, export = ExportStatus.Idle, erase = EraseStatus.Idle))
    val state: StateFlow<DataUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val overview = try {
                rights.overview()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.update { it.copy(overview = overview) }
        }
    }

    private fun busy(s: DataUiState): Boolean = s.export == ExportStatus.Working || s.erase == EraseStatus.Working

    /** The name offered for the copy, which says when it was made. */
    fun suggestedFileName(): String = "command-export-" + Instant.ofEpochMilli(clock.nowMs()).atZone(clock.zone()).toLocalDate() + ".zip"

    /**
     * Writes the copy to the document the person chose. The document is only opened once the data has been read, all of it off the main
     * thread (a provider can be a network drive), and one that could not be written completely is removed again.
     */
    fun exportTo(uri: Uri) {
        val s = _state.value
        if (busy(s) || s.erase == EraseStatus.Confirming || s.erase is EraseStatus.Done) {
            // Not now: another job is running, or the question about erasing is open, or the erase has run. The picker has already made the
            // document, so it is removed again rather than left empty.
            viewModelScope.launch(Dispatchers.IO) { runCatching { sink.discard(uri) } }
            return
        }
        _state.update { it.copy(export = ExportStatus.Working) }
        viewModelScope.launch {
            val summary = try {
                withContext(Dispatchers.IO) { rights.export { sink.open(uri) ?: throw IOException("the document cannot be written") } }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { sink.discard(uri) } }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { runCatching { sink.discard(uri) } }
                null
            }
            _state.update { it.copy(export = if (summary != null) ExportStatus.Saved(summary.rows, summary.historyRows) else ExportStatus.Failed) }
        }
    }

    fun askToErase() {
        _state.update { if (busy(it) || it.erase is EraseStatus.Done) it else it.copy(erase = EraseStatus.Confirming) }
    }

    /** The answer "no": nothing was erased. */
    fun keepData() {
        _state.update { if (it.erase == EraseStatus.Confirming) it.copy(erase = EraseStatus.Idle) else it }
    }

    /** Only after the question: it deletes everything and cannot be undone. */
    fun eraseEverything() {
        val s = _state.value
        if (s.erase != EraseStatus.Confirming || s.export == ExportStatus.Working) return
        runErase()
    }

    /** After an erase that left something: the question has been answered, and the database is gone, so the same erase is run again. */
    fun eraseAgain() {
        val s = _state.value
        if (s.erase !is EraseStatus.Done || s.export == ExportStatus.Working) return
        runErase()
    }

    private fun runErase() {
        _state.update { it.copy(erase = EraseStatus.Working) }
        viewModelScope.launch {
            val status = try {
                val report = rights.erase()
                EraseStatus.Done(report.leftovers.size, report.problems.size, report.volumeLeft)
            } catch (e: CancellationException) {
                // The screen is gone (the activity was destroyed with the phone's "don't keep activities" setting, say) and the erase runs on to
                // its end without it: by now the database is deleted, and nobody is left to look at a report or to end the app. Android's own
                // clearing of the app's data finishes whatever the erase could not, so it is asked for here.
                reset.clearAndExit()
                throw e
            } catch (e: Exception) {
                EraseStatus.Failed
            }
            if (!isActive) {
                // The same, when the result reached here without the exception (the erase that finished on the same dispatcher does not throw).
                reset.clearAndExit()
                return@launch
            }
            _state.update { it.copy(erase = status) }
        }
    }

    /** Ends the app. Once everything is erased there is nothing left for it to do, and its memory still holds a closed database. */
    fun closeApp() = reset.clearAndExit()

    /**
     * The screen is gone for good (not turned: this is not called then). If an erase had finished, nobody is left to end the app, and its
     * memory still holds a closed database and a closed latch: it is ended here. (While the erase is running the cancelled scope does it.)
     */
    override fun onCleared() {
        if (_state.value.erase is EraseStatus.Done) reset.clearAndExit()
    }
}
