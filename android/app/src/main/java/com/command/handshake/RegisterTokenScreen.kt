package com.command.handshake

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
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
import com.command.domain.dials.HandshakeMode
import com.command.domain.handshake.TagRead
import com.command.domain.reliability.Fix
import com.command.reliability.SystemLinks
import com.command.reveille.LocalNfcReader
import com.command.reveille.LocalQrScanner
import com.command.ui.SafeQuestion
import java.util.concurrent.atomic.AtomicBoolean

/** Whether this phone's camera can be used to scan a code: there is none, Android has not been asked yet, Android refused it, or it is given. */
enum class CameraAccess { NONE, NOT_ASKED, REFUSED, GRANTED }

/**
 * Everything the screen of the registered codes (or tags) can ask for, so it can be tested without a view model. [onRegister] and [onRename]
 * put the last answer away at once, as [RegisterTokenViewModel] does: the screen keeps the typed name, or the rename field, until the next
 * answer. The tag actions are used only on the tags screen: [onTagRead] hands on one touch (as its fingerprint only), [onCancelTag] forgets a
 * first touch, [onTagReaderOff] says the reader stopped, and [onOpenNfcSettings] opens Android's NFC settings.
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
    val onTagRead: (label: String, read: TagRead) -> Unit = { _, _ -> },
    val onCancelTag: () -> Unit = {},
    val onTagReaderOff: () -> Unit = {},
    val onOpenNfcSettings: () -> Unit = {},
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
    const val TAG_PANE = "codes_tag_pane"
    const val TAG_INSTRUCTION = "codes_tag_instruction"
    const val TAG_AGAIN = "codes_tag_again"
    const val TAG_CANCEL = "codes_tag_cancel"
    const val NFC_OFF = "codes_nfc_off"
    const val NFC_SETTINGS = "codes_nfc_settings"
    const val NFC_NONE = "codes_nfc_none"
    const val SECURE_NOTE = "codes_secure_note"
    fun code(id: String) = "codes_code_$id"
    fun rename(id: String) = "codes_rename_$id"
    fun delete(id: String) = "codes_delete_$id"
    fun renameField(id: String) = "codes_rename_field_$id"
    fun renameSave(id: String) = "codes_rename_save_$id"
    fun renameCancel(id: String) = "codes_rename_cancel_$id"
}

/**
 * The registered codes, with the phone's camera as Android has it, or the registered tags (the view model's mode), with the phone's NFC.
 * Turning the camera consent on records it and then asks Android for the camera; whether Android gave it is read again whenever the screen
 * comes back, since the person may have changed it in the app's settings. The tags ask Android for nothing (NFC is granted at install); the
 * phone's NFC is read again whenever the screen comes back, since the person may have turned it on in Android's settings, and when the
 * reader says it stopped, since NFC can be turned off from Quick Settings without the screen ever leaving the front.
 */
@Composable
fun RegisterTokenRoute(onBack: () -> Unit, viewModel: RegisterTokenViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tags = viewModel.mode == HandshakeMode.NFC
    val hasCamera = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var granted by remember { mutableStateOf(cameraGranted(context)) }
    var refused by rememberSaveable { mutableStateOf(false) }
    var reader by remember { mutableStateOf(if (tags) viewModel.readerAccess() else ReaderAccess.NONE) }
    var secure by remember { mutableStateOf(tags && viewModel.secureNfcOn()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { given ->
        granted = given
        refused = !given
    }
    LifecycleResumeEffect(context) {
        granted = cameraGranted(context)
        if (tags) {
            reader = viewModel.readerAccess()
            secure = viewModel.secureNfcOn()
        }
        onPauseOrDispose {}
    }
    RegisterTokenScreen(
        state,
        cameraAccess(hasCamera, granted, refused),
        RegisterTokenActions(
            onBack = onBack,
            onSetConsent = { on ->
                viewModel.setConsent(on)
                if (!tags && shouldAskAfterConsent(on, hasCamera, granted)) ask.launch(Manifest.permission.CAMERA)
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
            onTagRead = viewModel::tagRead,
            onCancelTag = viewModel::cancelTag,
            onTagReaderOff = {
                viewModel.tagReaderOff()
                reader = viewModel.readerAccess()
                secure = viewModel.secureNfcOn()
            },
            onOpenNfcSettings = { runCatching { SystemLinks.open(context, Intent(Settings.ACTION_NFC_SETTINGS)) } },
        ),
        reader = reader,
        secureNfc = secure,
        mode = viewModel.mode,
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
 *
 * For tags (the state's mode is NFC), [camera] is not used: [reader] and [secureNfc] say what the phone's NFC can do. While a tag is being
 * registered the tag pane takes the screen and listens through [LocalNfcReader]; each touch is its own session, handed on once, and a first
 * touch that asks for a second starts the next session. Saved state holds only the name, the session counters and switches: never a read.
 * [mode] is the screen's own (the route's), for its words while the state is still loading; once it is ready, the state's mode is used.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterTokenScreen(
    state: RegisterTokenUiState,
    camera: CameraAccess,
    actions: RegisterTokenActions,
    reader: ReaderAccess = ReaderAccess.NONE,
    secureNfc: Boolean = false,
    mode: HandshakeMode? = null,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var codeName by rememberSaveable { mutableStateOf("") }
    var scanningAs by rememberSaveable { mutableStateOf<String?>(null) }
    // Which scan is on screen: a code or a failure from a scan that has ended (one already queued when Cancel or Back was tapped) is dropped.
    var scanSession by rememberSaveable { mutableIntStateOf(0) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    // A code was read and handed on; the typed name stays until the answer comes, and only a registration clears it.
    var awaitingScan by rememberSaveable { mutableStateOf(false) }
    // Tags: a touch was handed on and its answer has not come. A save can still be on its way when Cancel or Back ends the pane, and its
    // registration then clears the typed name all the same.
    var awaitingTag by rememberSaveable { mutableStateOf(false) }
    // A mark of the made code this screen last brought into view, so that coming back to the screen does not scroll to it again. Only its
    // hash is kept: saved state is written into a Bundle, and the code itself is held only by the view model until Done.
    var shownMade by rememberSaveable { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    val ready = state as? RegisterTokenUiState.Ready
    val tags = (ready?.mode ?: mode) == HandshakeMode.NFC
    val words = registerWords(tags)
    val deviceUsable = if (tags) reader == ReaderAccess.READY else camera == CameraAccess.GRANTED
    val scanUsable = deviceUsable && ready?.consentGranted == true
    // The scan is shown only while it can be: a scan whose consent or camera (or NFC) is gone ends, and does not come back by itself.
    val scanShown = scanningAs != null && ready != null && scanUsable
    val latestActions by rememberUpdatedState(actions)
    val endScan = {
        if (scanningAs != null && tags) latestActions.onCancelTag()
        scanningAs = null
    }
    LaunchedEffect(scanUsable) { if (!scanUsable) endScan() }
    // The newest answer, for a result that arrives between the pane going and the effect above ending the scan.
    val usableNow by rememberUpdatedState(scanUsable)
    val outcome = ready?.outcome
    // Tags: each answer to a touch is a new outcome (the last one is put away when the pane opens). A first touch asks for a second, in a
    // session of its own; any other answer ends the pane, and only a registration clears the typed name.
    LaunchedEffect(outcome) {
        if (tags && outcome != null && (scanningAs != null || awaitingTag)) {
            awaitingTag = false
            if (outcome == CodeOutcome.TouchAgain) {
                if (scanningAs != null) scanSession++
            } else {
                scanningAs = null
                if (outcome is CodeOutcome.Registered) {
                    adding = false
                    codeName = ""
                }
            }
        }
    }
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
    BackHandler(enabled = scanShown) { endScan() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(words.title)) },
                navigationIcon = {
                    IconButton(onClick = { if (scanShown) endScan() else actions.onBack() }) {
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
                if (scanShown && scanning != null && tags) {
                    val session = scanSession
                    val stillTouching = { scanningAs != null && scanSession == session && usableNow }
                    TagPane(
                        label = scanning,
                        again = state.outcome == CodeOutcome.TouchAgain,
                        session = session,
                        modifier = Modifier.fillMaxSize().padding(padding),
                        onTag = { read ->
                            if (stillTouching()) {
                                awaitingTag = true
                                actions.onTagRead(scanning, read)
                            }
                        },
                        onUnavailable = {
                            if (stillTouching()) {
                                scanningAs = null
                                actions.onTagReaderOff()
                            }
                        },
                        onCancel = endScan,
                    )
                } else if (scanShown && scanning != null) {
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
                            reader = reader,
                            secureNfc = secureNfc,
                            words = words,
                            actions = actions,
                            adding = adding,
                            label = codeName,
                            onStartAdding = { adding = true },
                            onLabel = { codeName = it },
                            onScan = {
                                scanSession++
                                // A tag's answers are told apart by changing: the last one is put away before the first touch.
                                if (tags) actions.onDismissOutcome()
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
                        state.outcome?.let { Outcome(it, words, actions.onDismissOutcome) }
                    }
                }
                val doomed = state.codes.firstOrNull { it.id == deleting }
                if (doomed != null) {
                    DeleteQuestion(doomed, words, onKeep = { deleting = null }, onDelete = { deleting = null; actions.onDelete(doomed.id) })
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
    reader: ReaderAccess,
    secureNfc: Boolean,
    words: RegisterWords,
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
        item { Text(stringResource(words.intro), style = MaterialTheme.typography.bodyMedium) }
        if (words.tags) {
            item { Text(stringResource(R.string.tags_passive_advice), style = MaterialTheme.typography.bodyMedium) }
            item { Heading(R.string.tags_reader_title) }
            if (reader == ReaderAccess.NONE) {
                item {
                    Text(stringResource(R.string.tags_nfc_none), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(RegisterTokenTags.NFC_NONE))
                }
            } else {
                item { ConsentRow(state.consentGranted, words, actions.onSetConsent) }
                if (reader == ReaderAccess.OFF) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.tags_nfc_off),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag(RegisterTokenTags.NFC_OFF),
                            )
                            OutlinedButton(onClick = actions.onOpenNfcSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RegisterTokenTags.NFC_SETTINGS)) {
                                Text(stringResource(R.string.tags_open_settings))
                            }
                        }
                    }
                }
            }
            if (secureNfc) {
                item {
                    Text(stringResource(R.string.tags_secure_note), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(RegisterTokenTags.SECURE_NOTE))
                }
            }
        } else {
            item { Heading(R.string.codes_camera_title) }
            if (camera == CameraAccess.NONE) {
                item {
                    Text(stringResource(R.string.codes_camera_none), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(RegisterTokenTags.NO_CAMERA))
                }
            } else {
                item { ConsentRow(state.consentGranted, words, actions.onSetConsent) }
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
        }
        item { Heading(words.listTitle) }
        if (state.codes.isEmpty()) {
            item { Text(stringResource(words.none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(RegisterTokenTags.NONE)) }
        }
        items(state.codes, key = { it.id }) { code ->
            CodeCard(
                code = code,
                words = words,
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
        if (words.tags) {
            if (reader != ReaderAccess.NONE) {
                item { AddCode(state.consentGranted, reader == ReaderAccess.READY, words, adding, label, onStartAdding, onLabel, onScan, onCancelAdding) }
            }
        } else if (camera != CameraAccess.NONE) {
            item { AddCode(state.consentGranted, camera == CameraAccess.GRANTED, words, adding, label, onStartAdding, onLabel, onScan, onCancelAdding) }
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

/** The camera (or tag) consent, in the words of the consent center: what it allows, and what it does not. */
@Composable
private fun ConsentRow(granted: Boolean, words: RegisterWords, onSet: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = granted, onValueChange = onSet, role = Role.Switch)
            .testTag(RegisterTokenTags.CONSENT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(words.consentTitle), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(words.consentAllows), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(words.consentNot), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = granted, onCheckedChange = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodeCard(
    code: Token,
    words: RegisterWords,
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
                    label = { Text(stringResource(words.renameField)) },
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
 * Adding a code (or a tag): a name first, then the scan (or the touches). It needs the consent and the camera (or NFC on); without the
 * consent the button is off and says why, and without the camera (or NFC) the lines above say what to do.
 */
@Composable
private fun AddCode(
    consent: Boolean,
    deviceUsable: Boolean,
    words: RegisterWords,
    adding: Boolean,
    label: String,
    onStart: () -> Unit,
    onLabel: (String) -> Unit,
    onScan: () -> Unit,
    onCancel: () -> Unit,
) {
    val usable = consent && deviceUsable
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (adding && usable) {
            val name = label.trim()
            val tooLong = name.codePointCount(0, name.length) > TokenRepository.MAX_LABEL
            OutlinedTextField(
                value = label,
                onValueChange = onLabel,
                label = { Text(stringResource(words.labelField)) },
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
            ) { Text(stringResource(words.start)) }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RegisterTokenTags.ADD_CANCEL)) {
                Text(stringResource(R.string.codes_add_cancel))
            }
        } else {
            Button(onClick = onStart, enabled = usable, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RegisterTokenTags.ADD)) {
                Text(stringResource(words.add))
            }
            if (!consent) {
                Text(
                    stringResource(words.needConsent),
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

/**
 * Registering a tag: its name, what to do, and a way out below. The reader listens only while this is shown (and the screen is resumed);
 * one touch is handed on per [session], and the reader stopping ends it. Nothing of the tag is drawn or kept here.
 */
@Composable
private fun TagPane(
    label: String,
    again: Boolean,
    session: Int,
    modifier: Modifier,
    onTag: (TagRead) -> Unit,
    onUnavailable: () -> Unit,
    onCancel: () -> Unit,
) {
    val done = remember(session) { AtomicBoolean(false) }
    Column(modifier.padding(16.dp).testTag(RegisterTokenTags.TAG_PANE), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(
            stringResource(if (again) R.string.tags_touch_again else R.string.tags_touch),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(if (again) RegisterTokenTags.TAG_AGAIN else RegisterTokenTags.TAG_INSTRUCTION),
        )
        LocalNfcReader.current.Listen(
            onTag = { read -> if (done.compareAndSet(false, true)) onTag(read) },
            onUnavailable = onUnavailable,
        )
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RegisterTokenTags.TAG_CANCEL)) {
            Text(stringResource(R.string.codes_add_cancel))
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
private fun Outcome(outcome: CodeOutcome, table: RegisterWords, onDismiss: () -> Unit) {
    val words = when (outcome) {
        is CodeOutcome.Registered -> stringResource(table.registered, outcome.label)
        CodeOutcome.Blank -> stringResource(R.string.codes_blank)
        CodeOutcome.BadLabel -> stringResource(R.string.codes_label_limit, TokenRepository.MAX_LABEL)
        is CodeOutcome.Duplicate -> stringResource(table.duplicate, outcome.label)
        CodeOutcome.Full -> stringResource(table.full, TokenRepository.MAX_PER_MODE)
        CodeOutcome.Renamed -> stringResource(R.string.codes_renamed)
        CodeOutcome.Gone -> stringResource(table.gone)
        CodeOutcome.CameraFailed -> stringResource(R.string.codes_camera_failed)
        CodeOutcome.NoConsent -> stringResource(table.noConsent)
        CodeOutcome.TouchAgain -> stringResource(R.string.tags_touch_again)
        CodeOutcome.TagChanged -> stringResource(R.string.tags_changed)
        CodeOutcome.RandomTag -> stringResource(R.string.tags_random)
        CodeOutcome.NoTagId -> stringResource(R.string.tags_no_id)
        CodeOutcome.ReaderOff -> stringResource(R.string.tags_reader_off)
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
private fun DeleteQuestion(code: Token, words: RegisterWords, onKeep: () -> Unit, onDelete: () -> Unit) {
    SafeQuestion(
        tag = RegisterTokenTags.DELETE_DIALOG,
        title = stringResource(words.deleteTitle),
        message = stringResource(words.deleteBody, code.label),
        keepLabel = words.deleteKeep,
        keepTag = RegisterTokenTags.DELETE_KEEP,
        onKeep = onKeep,
        throwAwayLabel = words.deleteConfirm,
        throwAwayTag = RegisterTokenTags.DELETE_CONFIRM,
        onThrowAway = onDelete,
    )
}

/** The words (string resources) that differ between the codes and the tags; everything else on the screen is shared. */
internal class RegisterWords(
    val tags: Boolean,
    val title: Int,
    val intro: Int,
    val listTitle: Int,
    val none: Int,
    val renameField: Int,
    val add: Int,
    val labelField: Int,
    val start: Int,
    val needConsent: Int,
    val registered: Int,
    val duplicate: Int,
    val full: Int,
    val gone: Int,
    val noConsent: Int,
    val deleteTitle: Int,
    val deleteBody: Int,
    val deleteKeep: Int,
    val deleteConfirm: Int,
    val consentTitle: Int,
    val consentAllows: Int,
    val consentNot: Int,
)

private val CODE_WORDS = RegisterWords(
    tags = false,
    title = R.string.codes_title,
    intro = R.string.codes_intro,
    listTitle = R.string.codes_list_title,
    none = R.string.codes_none,
    renameField = R.string.codes_rename_field,
    add = R.string.codes_add,
    labelField = R.string.codes_label_field,
    start = R.string.codes_scan,
    needConsent = R.string.codes_need_consent,
    registered = R.string.codes_registered,
    duplicate = R.string.codes_duplicate,
    full = R.string.codes_full,
    gone = R.string.codes_gone,
    noConsent = R.string.codes_no_consent,
    deleteTitle = R.string.codes_delete_title,
    deleteBody = R.string.codes_delete_body,
    deleteKeep = R.string.codes_delete_keep,
    deleteConfirm = R.string.codes_delete_confirm,
    consentTitle = R.string.consent_camera_qr_title,
    consentAllows = R.string.consent_camera_qr_allows,
    consentNot = R.string.consent_camera_qr_not,
)

private val TAG_WORDS = RegisterWords(
    tags = true,
    title = R.string.tags_title,
    intro = R.string.tags_intro,
    listTitle = R.string.tags_list_title,
    none = R.string.tags_none,
    renameField = R.string.tags_rename_field,
    add = R.string.tags_add,
    labelField = R.string.tags_label_field,
    start = R.string.tags_start,
    needConsent = R.string.tags_need_consent,
    registered = R.string.tags_registered,
    duplicate = R.string.tags_duplicate,
    full = R.string.tags_full,
    gone = R.string.tags_gone,
    noConsent = R.string.tags_no_consent,
    deleteTitle = R.string.tags_delete_title,
    deleteBody = R.string.tags_delete_body,
    deleteKeep = R.string.tags_delete_keep,
    deleteConfirm = R.string.tags_delete_confirm,
    consentTitle = R.string.consent_nfc_title,
    consentAllows = R.string.consent_nfc_allows,
    consentNot = R.string.consent_nfc_not,
)

internal fun registerWords(tags: Boolean): RegisterWords = if (tags) TAG_WORDS else CODE_WORDS

private const val MADE_CODE_PIXELS = 512
