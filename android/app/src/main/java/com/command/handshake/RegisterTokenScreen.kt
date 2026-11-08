package com.command.handshake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.R
import com.command.data.Token
import com.command.data.TokenRepository
import com.command.domain.reliability.Fix
import com.command.reliability.SystemLinks
import com.command.reveille.LocalQrScanner
import com.command.ui.SafeQuestion
import java.util.concurrent.atomic.AtomicBoolean

/** Whether this phone's camera can be used to scan a code: there is none, Android has not been asked yet, Android refused it, or it is given. */
enum class CameraAccess { NONE, NOT_ASKED, REFUSED, GRANTED }

/**
 * Everything the screen of the registered codes can ask for, so it can be tested without a view model. [onRegister] and [onRename] put the
 * last answer away at once, as [RegisterTokenViewModel] does: the screen keeps the typed name, or the rename field, until the next answer.
 */
class RegisterTokenActions(
    val onBack: () -> Unit,
    val onSetConsent: (Boolean) -> Unit,
    val onAskCamera: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onRegister: (label: String, payload: String) -> Unit,
    val onScanFailed: () -> Unit,
    val onRename: (id: String, label: String) -> Unit,
    val onDelete: (id: String) -> Unit,
    val onMakeCode: () -> Unit,
    val onCloseMadeCode: () -> Unit,
    val onDismissOutcome: () -> Unit,
)

object RegisterTokenTags {
    const val LIST = "codes_list"
    const val NONE = "codes_none"
    const val CONSENT = "codes_consent"
    const val NO_CAMERA = "codes_no_camera"
    const val CAMERA_ASK = "codes_camera_ask"
    const val CAMERA_REFUSED = "codes_camera_refused"
    const val APP_SETTINGS = "codes_app_settings"
    const val NEED_CONSENT = "codes_need_consent"
    const val ADD = "codes_add"
    const val LABEL_FIELD = "codes_label_field"
    const val SCAN = "codes_scan"
    const val ADD_CANCEL = "codes_add_cancel"
    const val SCAN_INSTRUCTION = "codes_scan_instruction"
    const val SCAN_CANCEL = "codes_scan_cancel"
    const val MAKE = "codes_make"
    const val MADE_IMAGE = "codes_made_image"
    const val MADE_WORDS = "codes_made_words"
    const val MADE_CLOSE = "codes_made_close"
    const val OUTCOME = "codes_outcome"
    const val OUTCOME_DISMISS = "codes_outcome_dismiss"
    const val DELETE_DIALOG = "codes_delete_dialog"
    const val DELETE_KEEP = "codes_delete_keep"
    const val DELETE_CONFIRM = "codes_delete_confirm"
    fun code(id: String) = "codes_code_$id"
    fun rename(id: String) = "codes_rename_$id"
    fun delete(id: String) = "codes_delete_$id"
    fun renameField(id: String) = "codes_rename_field_$id"
    fun renameSave(id: String) = "codes_rename_save_$id"
    fun renameCancel(id: String) = "codes_rename_cancel_$id"
}

/**
 * The registered codes, with the phone's camera as Android has it. Turning the camera consent on records it and then asks Android for the
 * camera; whether Android gave it is read again whenever the screen comes back, since the person may have changed it in the app's settings.
 */
@Composable
fun RegisterTokenRoute(onBack: () -> Unit, viewModel: RegisterTokenViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasCamera = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var granted by remember { mutableStateOf(cameraGranted(context)) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { given ->
        granted = given
        refused = !given
    }
    LifecycleResumeEffect(context) {
        granted = cameraGranted(context)
        onPauseOrDispose {}
    }
    RegisterTokenScreen(
        state,
        cameraAccess(hasCamera, granted, refused),
        RegisterTokenActions(
            onBack = onBack,
            onSetConsent = { on ->
                viewModel.setConsent(on)
                if (shouldAskAfterConsent(on, hasCamera, granted)) ask.launch(Manifest.permission.CAMERA)
            },
            onAskCamera = { ask.launch(Manifest.permission.CAMERA) },
            onOpenAppSettings = { SystemLinks.intentFor(context, Fix.OPEN_APP_SETTINGS)?.let { SystemLinks.open(context, it) } },
            onRegister = viewModel::register,
            onScanFailed = viewModel::scanFailed,
            onRename = viewModel::rename,
            onDelete = viewModel::delete,
            onMakeCode = viewModel::makeCode,
            onCloseMadeCode = viewModel::closeMadeCode,
            onDismissOutcome = viewModel::dismissOutcome,
        ),
    )
}

/** The camera as Android has it: none on a phone without one; given, even after an earlier refusal; refused once Android said no; else not asked yet. */
internal fun cameraAccess(hasCamera: Boolean, granted: Boolean, refused: Boolean): CameraAccess = when {
    !hasCamera -> CameraAccess.NONE
    granted -> CameraAccess.GRANTED
    refused -> CameraAccess.REFUSED
    else -> CameraAccess.NOT_ASKED
}

/** Turning the camera consent on asks Android for the camera, when there is one and Android has not given it yet. Turning it off never asks. */
internal fun shouldAskAfterConsent(on: Boolean, hasCamera: Boolean, granted: Boolean): Boolean = on && hasCamera && !granted

private fun cameraGranted(context: Context) =ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * Stateless, so it can be shown in tests without a view model. A delete is asked first, with the answer that keeps the code first. While a
 * code is being scanned the viewfinder takes the screen, and the first code it reads is handed on once, whatever it holds: what it comes to
 * has its own words. The camera is the one [LocalQrScanner] holds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterTokenScreen(state: RegisterTokenUiState, camera: CameraAccess, actions: RegisterTokenActions) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var codeName by rememberSaveable { mutableStateOf("") }
    var scanningAs by rememberSaveable { mutableStateOf<String?>(null) }
    // Which scan is on screen: a code or a failure from a scan that has ended (one already queued when Cancel or Back was tapped) is dropped.
    var scanSession by rememberSaveable { mutableIntStateOf(0) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    // A code was read and handed on; the typed name stays until the answer comes, and only a registration clears it.
    var awaitingScan by rememberSaveable { mutableStateOf(false) }
    // A mark of the made code this screen last brought into view, so that coming back to the screen does not scroll to it again. Only its
    // hash is kept: saved state is written into a Bundle, and the code itself is held only by the view model until Done.
    var shownMade by rememberSaveable { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    val ready = state as? RegisterTokenUiState.Ready
    val scanUsable = camera == CameraAccess.GRANTED && ready?.consentGranted == true
    // The scan is shown only while it can be: a scan whose consent or camera is gone ends, and does not come back by itself.
    val scanShown = scanningAs != null && ready != null && scanUsable
    LaunchedEffect(scanUsable) { if (!scanUsable) scanningAs = null }
    // The newest answer, for a result that arrives between the pane going and the effect above ending the scan.
    val usableNow by rememberUpdatedState(scanUsable)
    val outcome = ready?.outcome
    LaunchedEffect(outcome, awaitingScan) {
        if (awaitingScan && outcome != null) {
            awaitingScan = false
            if (outcome is CodeOutcome.Registered) {
                adding = false
                codeName = ""
            }
        }
    }
    val made = ready?.madeCode
    LaunchedEffect(made) {
        if (made != null && made.hashCode() != shownMade) {
            shownMade = made.hashCode()
            withFrameNanos {} // the made code is laid out as the last item of the list before it is scrolled to
            listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }
    // Back while scanning ends the scan, and stays on this screen.
    BackHandler(enabled = scanShown) { scanningAs = null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.codes_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (scanShown) scanningAs = null else actions.onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.codes_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            RegisterTokenUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is RegisterTokenUiState.Ready -> {
                val scanning = scanningAs
                if (scanShown && scanning != null) {
                    val session = scanSession
                    // Read at the moment a result arrives, not when the pane was drawn: Cancel and Back end the scan before the pane goes.
                    // (A code that arrives even before the screen has seen a withdrawn consent is refused by the view model.)
                    val stillScanning = { scanningAs != null && scanSession == session && usableNow }
                    ScanPane(
                        label = scanning,
                        modifier = Modifier.fillMaxSize().padding(padding),
                        onDecoded = { payload ->
                            if (stillScanning()) {
                                scanningAs = null
                                awaitingScan = true
                                actions.onRegister(scanning, payload)
                            }
                        },
                        onFailed = {
                            if (stillScanning()) {
                                scanningAs = null
                                actions.onScanFailed()
                            }
                        },
                        onCancel = { scanningAs = null },
                    )
                } else {
                    // Edge to edge: the list and the answer under it end above the keyboard, without counting the navigation bar twice.
                    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                        CodesList(
                            state = state,
                            camera = camera,
                            actions = actions,
                            adding = adding,
                            label = codeName,
                            onStartAdding = { adding = true },
                            onLabel = { codeName = it },
                            onScan = {
                                scanSession++
                                scanningAs = codeName.trim()
                            },
                            onCancelAdding = {
                                adding = false
                                codeName = ""
                            },
                            onAskDelete = { deleting = it },
                            listState = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        state.outcome?.let { Outcome(it, actions.onDismissOutcome) }
                    }
                }
                val doomed = state.codes.firstOrNull { it.id == deleting }
                if (doomed != null) {
                    DeleteQuestion(doomed, onKeep = { deleting = null }, onDelete = { deleting = null; actions.onDelete(doomed.id) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodesList(
    state: RegisterTokenUiState.Ready,
    camera: CameraAccess,
    actions: RegisterTokenActions,
    adding: Boolean,
    label: String,
    onStartAdding: () -> Unit,
    onLabel: (String) -> Unit,
    onScan: () -> Unit,
    onCancelAdding: () -> Unit,
    onAskDelete: (String) -> Unit,
    listState: LazyListState,
    modifier: Modifier,
) {
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    // The code whose new name was handed on: its field stays open, with what was typed, until the answer comes, and closes only if the name changed.
    var renameAwaited by rememberSaveable { mutableStateOf<String?>(null) }
    val outcome = state.outcome
    LaunchedEffect(outcome, renameAwaited) {
        val awaited = renameAwaited
        if (awaited != null && outcome != null) {
            renameAwaited = null
            if ((outcome == CodeOutcome.Renamed || outcome == CodeOutcome.Gone) && renaming == awaited) renaming = null
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.testTag(RegisterTokenTags.LIST),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.codes_intro), style = MaterialTheme.typography.bodyMedium) }
        item { Heading(R.string.codes_camera_title) }
        if (camera == CameraAccess.NONE) {
            item {
                Text(stringResource(R.string.codes_camera_none), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(RegisterTokenTags.NO_CAMERA))
            }
        } else {
            item { ConsentRow(state.consentGranted, actions.onSetConsent) }
            if (state.consentGranted && camera == CameraAccess.NOT_ASKED) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.codes_camera_not_asked), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = actions.onAskCamera, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RegisterTokenTags.CAMERA_ASK)) {
                            Text(stringResource(R.string.codes_camera_ask))
                        }
                    }
                }
            }
            if (state.consentGranted && camera == CameraAccess.REFUSED) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.codes_camera_refused),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(RegisterTokenTags.CAMERA_REFUSED),
                        )
                        OutlinedButton(onClick = actions.onOpenAppSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RegisterTokenTags.APP_SETTINGS)) {
                            Text(stringResource(R.string.codes_app_settings))
                        }
                    }
                }
            }
        }
        item { Heading(R.string.codes_list_title) }
        if (state.codes.isEmpty()) {
            item { Text(stringResource(R.string.codes_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(RegisterTokenTags.NONE)) }
        }
        items(state.codes, key = { it.id }) { code ->
            CodeCard(
                code = code,
                renaming = renaming == code.id,
                onStartRename = { renaming = code.id },
                onRename = { name ->
                    renameAwaited = code.id
                    actions.onRename(code.id, name)
                },
                onCancelRename = { renaming = null },
                onAskDelete = { onAskDelete(code.id) },
            )
        }
        if (camera != CameraAccess.NONE) {
            item { AddCode(state.consentGranted, camera, adding, label, onStartAdding, onLabel, onScan, onCancelAdding) }
            item {
                OutlinedButton(onClick = actions.onMakeCode, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RegisterTokenTags.MAKE)) {
                    Text(stringResource(R.string.codes_make))
                }
            }
            // The made code is the last item: the screen scrolls to the last item when one is made.
            state.madeCode?.let { made -> item { MadeCode(made, actions.onCloseMadeCode) } }
        }
    }
}

@Composable
private fun Heading(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

/** The camera consent, in the words of the consent center: what it allows, and what it does not. */
@Composable
private fun ConsentRow(granted: Boolean, onSet: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = granted, onValueChange = onSet, role = Role.Switch)
            .testTag(RegisterTokenTags.CONSENT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.consent_camera_qr_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.consent_camera_qr_allows), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.consent_camera_qr_not), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = granted, onCheckedChange = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodeCard(
    code: Token,
    renaming: Boolean,
    onStartRename: () -> Unit,
    onRename: (String) -> Unit,
    onCancelRename: () -> Unit,
    onAskDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(RegisterTokenTags.code(code.id))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (renaming) {
                var name by rememberSaveable(code.id) { mutableStateOf(code.label) }
                // The same limit as a new code's name, counted in characters as the repository counts them.
                val trimmed = name.trim()
                val tooLong = trimmed.codePointCount(0, trimmed.length) > TokenRepository.MAX_LABEL
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.codes_rename_field)) },
                    singleLine = true,
                    isError = tooLong,
                    supportingText = if (tooLong) {
                        { Text(stringResource(R.string.codes_label_limit, TokenRepository.MAX_LABEL)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().testTag(RegisterTokenTags.renameField(code.id)),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onRename(trimmed) },
                        enabled = trimmed.isNotEmpty() && !tooLong,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(RegisterTokenTags.renameSave(code.id)),
                    ) {
                        Text(stringResource(R.string.codes_rename_save))
                    }
                    TextButton(onClick = onCancelRename, modifier = Modifier.heightIn(min = 48.dp).testTag(RegisterTokenTags.renameCancel(code.id))) {
                        Text(stringResource(R.string.codes_rename_cancel))
                    }
                }
            } else {
                Text(code.label, style = MaterialTheme.typography.titleMedium)
                // Each button is named for its code, to a screen reader and to a voice; Delete is apart from Rename and asks first.
                val renameNamed = stringResource(R.string.codes_rename_named, code.label)
                val deleteNamed = stringResource(R.string.codes_delete_named, code.label)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onStartRename,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = renameNamed }.testTag(RegisterTokenTags.rename(code.id)),
                    ) { Text(stringResource(R.string.codes_rename)) }
                    TextButton(
                        onClick = onAskDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = deleteNamed }.testTag(RegisterTokenTags.delete(code.id)),
                    ) { Text(stringResource(R.string.codes_delete)) }
                }
            }
        }
    }
}

/**
 * Adding a code: a name first, then the scan. The scan needs the consent and the camera; without the consent the button is off and says
 * why, and without the camera the lines above say what to do.
 */
@Composable
private fun AddCode(
    consent: Boolean,
    camera: CameraAccess,
    adding: Boolean,
    label: String,
    onStart: () -> Unit,
    onLabel: (String) -> Unit,
    onScan: () -> Unit,
    onCancel: () -> Unit,
) {
    val usable = consent && camera == CameraAccess.GRANTED
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (adding && usable) {
            val name = label.trim()
            val tooLong = name.codePointCount(0, name.length) > TokenRepository.MAX_LABEL
            OutlinedTextField(
                value = label,
                onValueChange = onLabel,
                label = { Text(stringResource(R.string.codes_label_field)) },
                singleLine = true,
                isError = tooLong,
                supportingText = if (tooLong) {
                    { Text(stringResource(R.string.codes_label_limit, TokenRepository.MAX_LABEL)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth().testTag(RegisterTokenTags.LABEL_FIELD),
            )
            Button(
                onClick = onScan,
                enabled = name.isNotEmpty() && !tooLong,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RegisterTokenTags.SCAN),
            ) { Text(stringResource(R.string.codes_scan)) }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RegisterTokenTags.ADD_CANCEL)) {
                Text(stringResource(R.string.codes_add_cancel))
            }
        } else {
            Button(onClick = onStart, enabled = usable, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RegisterTokenTags.ADD)) {
                Text(stringResource(R.string.codes_add))
            }
            if (!consent) {
                Text(
                    stringResource(R.string.codes_need_consent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(RegisterTokenTags.NEED_CONSENT),
                )
            }
        }
    }
}

/** The viewfinder, with what to do above it and a way out below it. The first code read is handed on once; a failing camera ends the scan once. */
@Composable
private fun ScanPane(label: String, modifier: Modifier, onDecoded: (String) -> Unit, onFailed: () -> Unit, onCancel: () -> Unit) {
    val done = remember { AtomicBoolean(false) }
    val viewfinder = stringResource(R.string.codes_viewfinder)
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.codes_scan_instruction, label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(RegisterTokenTags.SCAN_INSTRUCTION),
        )
        LocalQrScanner.current.Viewfinder(
            modifier = Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = viewfinder },
            onDecoded = { payload -> if (done.compareAndSet(false, true)) onDecoded(payload) },
            onError = { if (done.compareAndSet(false, true)) onFailed() },
        )
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RegisterTokenTags.SCAN_CANCEL)) {
            Text(stringResource(R.string.codes_scan_cancel))
        }
    }
}

/** A new code, large, dark on white whatever the theme, with what to do with it. It is not registered until it is scanned. */
@Composable
private fun MadeCode(payload: String, onClose: () -> Unit) {
    val image = remember(payload) { QrCodes.image(payload, MADE_CODE_PIXELS) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.codes_made_image),
                filterQuality = FilterQuality.None,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f).background(Color.White).testTag(RegisterTokenTags.MADE_IMAGE),
            )
            Text(
                stringResource(R.string.codes_made_words),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(RegisterTokenTags.MADE_WORDS),
            )
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RegisterTokenTags.MADE_CLOSE)) {
                Text(stringResource(R.string.codes_made_close))
            }
        }
    }
}

/** What the last registration or rename came to. It stands under the list, in view wherever the list is, and a screen reader says it. */
@Composable
private fun Outcome(outcome: CodeOutcome, onDismiss: () -> Unit) {
    val words = when (outcome) {
        is CodeOutcome.Registered -> stringResource(R.string.codes_registered, outcome.label)
        CodeOutcome.Blank -> stringResource(R.string.codes_blank)
        CodeOutcome.BadLabel -> stringResource(R.string.codes_label_limit, TokenRepository.MAX_LABEL)
        is CodeOutcome.Duplicate -> stringResource(R.string.codes_duplicate, outcome.label)
        CodeOutcome.Full -> stringResource(R.string.codes_full, TokenRepository.MAX_PER_MODE)
        CodeOutcome.Renamed -> stringResource(R.string.codes_renamed)
        CodeOutcome.Gone -> stringResource(R.string.codes_gone)
        CodeOutcome.CameraFailed -> stringResource(R.string.codes_camera_failed)
        CodeOutcome.NoConsent -> stringResource(R.string.codes_no_consent)
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                words,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }.testTag(RegisterTokenTags.OUTCOME),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag(RegisterTokenTags.OUTCOME_DISMISS)) {
                Text(stringResource(R.string.codes_outcome_dismiss))
            }
        }
    }
}

/** The question before a code is deleted; the answer that keeps it is the first, as in the editor's questions. */
@Composable
private fun DeleteQuestion(code: Token, onKeep: () -> Unit, onDelete: () -> Unit) {
    SafeQuestion(
        tag = RegisterTokenTags.DELETE_DIALOG,
        title = stringResource(R.string.codes_delete_title),
        message = stringResource(R.string.codes_delete_body, code.label),
        keepLabel = R.string.codes_delete_keep,
        keepTag = RegisterTokenTags.DELETE_KEEP,
        onKeep = onKeep,
        throwAwayLabel = R.string.codes_delete_confirm,
        throwAwayTag = RegisterTokenTags.DELETE_CONFIRM,
        onThrowAway = onDelete,
    )
}

private const val MADE_CODE_PIXELS = 512
