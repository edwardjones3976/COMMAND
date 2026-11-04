package com.command.yourdata

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.data.DataOverview
import com.example.R
import kotlinx.coroutines.delay

object DataTags {
    const val COUNTS = "data_counts"
    const val SAVE = "data_save"
    const val SAVING = "data_saving"
    const val SAVED = "data_saved"
    const val SAVE_FAILED = "data_save_failed"
    const val ERASE = "data_erase"
    const val ERASE_DIALOG = "data_erase_dialog"
    const val ERASE_CONFIRM = "data_erase_confirm"
    const val ERASE_KEEP = "data_erase_keep"
    const val ERASING = "data_erasing"
    const val ERASED = "data_erased"
    const val ERASE_FAILED = "data_erase_failed"
    const val CLOSE = "data_close"
    const val RETRY = "data_retry"
}

/** How long "Everything was deleted" stays before the app closes by itself. */
internal const val AUTO_CLOSE_MS = 3_000L

@Composable
fun DataRoute(onBack: () -> Unit, viewModel: DataViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }
    DataScreen(
        state = state,
        onBack = onBack,
        onSave = { save.launch(viewModel.suggestedFileName()) },
        onAskErase = viewModel::askToErase,
        onKeep = viewModel::keepData,
        onErase = viewModel::eraseEverything,
        onEraseAgain = viewModel::eraseAgain,
        onClose = viewModel::closeApp,
    )
}

/** Stateless, so it can be shown in tests without a view model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataScreen(
    state: DataUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onAskErase: () -> Unit,
    onKeep: () -> Unit,
    onErase: () -> Unit,
    onEraseAgain: () -> Unit,
    onClose: () -> Unit,
) {
    val erase = state.erase
    // Nothing is left to go back to once the erase has begun: leaving would leave an erase nobody is watching.
    BackHandler(enabled = erase == EraseStatus.Working || erase is EraseStatus.Done) { }
    // When everything is gone the app closes by itself after a moment to read it; the button is there for anyone who does not want to wait.
    LaunchedEffect(erase) {
        if (erase is EraseStatus.Done && erase.complete) {
            delay(AUTO_CLOSE_MS)
            onClose()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = erase != EraseStatus.Working && erase !is EraseStatus.Done) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.data_back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            erase == EraseStatus.Working -> Working(Modifier.padding(padding))
            erase is EraseStatus.Done -> Erased(erase, Modifier.padding(padding), onClose = onClose, onRetry = onEraseAgain)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Overview(state.overview) }
                item { SaveSection(state.export, enabled = state.export != ExportStatus.Working, onSave = onSave) }
                item { EraseSection(erase, enabled = state.export != ExportStatus.Working, onAskErase = onAskErase) }
            }
        }
        if (erase == EraseStatus.Confirming) ConfirmErase(onErase = onErase, onKeep = onKeep)
    }
}

@Composable
private fun Overview(overview: DataOverview?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.data_overview_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.data_overview_body), style = MaterialTheme.typography.bodyMedium)
        if (overview != null) {
            Text(
                stringResource(
                    R.string.data_overview_counts,
                    pluralStringResource(R.plurals.data_count_missions, overview.missions, overview.missions),
                    pluralStringResource(R.plurals.data_count_alarms, overview.alarms, overview.alarms),
                    pluralStringResource(R.plurals.data_count_entries, overview.entries, overview.entries),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(DataTags.COUNTS),
            )
        }
    }
}

@Composable
private fun SaveSection(export: ExportStatus, enabled: Boolean, onSave: () -> Unit) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.data_save_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.data_save_body), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(DataTags.SAVE)) {
            Text(stringResource(R.string.data_save_button), style = MaterialTheme.typography.labelLarge)
        }
        when (export) {
            ExportStatus.Idle -> Unit
            ExportStatus.Working -> Row(
                Modifier.testTag(DataTags.SAVING).semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(stringResource(R.string.data_save_working), style = MaterialTheme.typography.bodyMedium)
            }
            is ExportStatus.Saved -> Text(
                stringResource(
                    R.string.data_save_done,
                    pluralStringResource(R.plurals.data_count_records, export.records, export.records),
                    pluralStringResource(R.plurals.data_count_history_lines, export.historyLines, export.historyLines),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(DataTags.SAVED).semantics { liveRegion = LiveRegionMode.Polite },
            )
            ExportStatus.Failed -> Text(
                stringResource(R.string.data_save_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(DataTags.SAVE_FAILED).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun EraseSection(erase: EraseStatus, enabled: Boolean, onAskErase: () -> Unit) {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.data_erase_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.data_erase_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = onAskErase,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(DataTags.ERASE),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.data_erase_button), style = MaterialTheme.typography.labelLarge) }
        if (erase == EraseStatus.Failed) {
            Text(
                stringResource(R.string.data_erase_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(DataTags.ERASE_FAILED).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** The question that comes before it: what goes, that it cannot be undone, and what stays. The safe answer is the one that is focused first. */
@Composable
private fun ConfirmErase(onErase: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        modifier = Modifier.testTag(DataTags.ERASE_DIALOG),
        title = { Text(stringResource(R.string.data_erase_confirm_title)) },
        // It scrolls, so that a very large font cannot push what stays, and the buttons under it, out of reach.
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.data_erase_confirm_body)) } },
        confirmButton = {
            TextButton(onClick = onKeep, modifier = Modifier.heightIn(min = 56.dp).testTag(DataTags.ERASE_KEEP)) {
                Text(stringResource(R.string.data_erase_keep))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onErase,
                modifier = Modifier.heightIn(min = 56.dp).testTag(DataTags.ERASE_CONFIRM),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.data_erase_confirm)) }
        },
    )
}

@Composable
private fun Working(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag(DataTags.ERASING).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.data_erase_working), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    }
}

/**
 * What is true now: all of it is gone and the app is closing, or some of it could not be removed and the person decides. It scrolls,
 * because at large type on a short window the buttons are the only way out (Back is held), and they must never be squeezed to nothing.
 */
@Composable
private fun Erased(done: EraseStatus.Done, modifier: Modifier, onClose: () -> Unit, onRetry: () -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag(DataTags.ERASED).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(
            if (done.complete) {
                stringResource(R.string.data_erased_done)
            } else {
                pluralStringResource(R.plurals.data_erased_partial, done.leftovers + done.problems, done.leftovers + done.problems)
            },
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(DataTags.CLOSE)) {
            Text(stringResource(R.string.data_erased_close), style = MaterialTheme.typography.labelLarge)
        }
        if (!done.complete) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(DataTags.RETRY)) {
                Text(stringResource(R.string.data_erased_retry), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
