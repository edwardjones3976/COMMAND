package com.command.handshake

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.command.data.ConsentStore
import com.command.data.RegisterResult
import com.command.data.RenameResult
import com.command.data.Token
import com.command.data.TokenRepository
import com.command.domain.dials.HandshakeMode
import com.command.domain.handshake.TagRead
import com.command.domain.model.ConsentKey
import com.command.domain.model.ConsentSource
import com.command.ui.guardedScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the last thing done with a code or a tag came to. Each has its own words on the screen. None of them carries a tag's id or fingerprint. */
sealed interface CodeOutcome {
    /** The code that was scanned, or the tag that was touched twice, is registered as [label]. */
    data class Registered(val label: String) : CodeOutcome

    /** The code that was scanned holds nothing but whitespace, which is not a code. */
    data object Blank : CodeOutcome

    /** The name, trimmed, is empty or longer than [TokenRepository.MAX_LABEL] characters. */
    data object BadLabel : CodeOutcome

    /** The code that was scanned, or the tag, is already registered, as [label]. */
    data class Duplicate(val label: String) : CodeOutcome

    /** [TokenRepository.MAX_PER_MODE] codes (or tags) are registered, and no more can be. */
    data object Full : CodeOutcome

    data object Renamed : CodeOutcome

    /** The code was deleted before it could be renamed. */
    data object Gone : CodeOutcome

    /** The camera could not be used while a code was scanned. Codes only. */
    data object CameraFailed : CodeOutcome

    /** A code or a tag arrived after its consent was turned off (a frame or a touch already on its way), so it was not registered. */
    data object NoConsent : CodeOutcome

    /** Tags: the first touch was read; the same tag must be touched once more before it is saved. */
    data object TouchAgain : CodeOutcome

    /** Tags: the second touch gave a different id from the first. Nothing is saved, and the next touch is a first touch again. */
    data object TagChanged : CodeOutcome

    /** Tags: the tag gives a new id on every touch (a phone, many bank cards), so it can never be matched. */
    data object RandomTag : CodeOutcome

    /** Tags: the tag gave no id at all. */
    data object NoTagId : CodeOutcome

    /** Tags: the phone stopped reading tags (NFC turned off, or reader mode could not start). */
    data object ReaderOff : CodeOutcome
}

/** The phone's tag reader as the tags screen needs it. This screen is used unlocked, so Secure NFC with the keyguard locked counts as ready. */
enum class ReaderAccess { NONE, OFF, READY }

sealed interface RegisterTokenUiState {
    data object Loading : RegisterTokenUiState

    /**
     * [codes] are the registered codes of the QR handshake, or the registered tags of the NFC handshake ([mode]), by label, never their
     * hashes; [consentGranted] is the camera consent, or the tag consent, as it is recorded now; [outcome] is what the last registration or
     * rename came to, until it is put away or replaced; [madeCode] is a new code that is being shown, held only here and only until it is put
     * away: it is not registered until it is scanned. There is never a made code for tags.
     */
    data class Ready(
        val codes: List<Token>,
        val consentGranted: Boolean,
        val outcome: CodeOutcome?,
        val madeCode: String?,
        val mode: HandshakeMode = HandshakeMode.QR,
    ) : RegisterTokenUiState
}

/**
 * The codes a step that scans a code checks against, or the tags a step that touches a tag checks against ([mode], from the route's "mode"
 * argument: anything but NFC is the codes). They are listed, renamed and deleted here, and registered from a scan or from two matching
 * touches. The list and the consent follow the database, so a change made in the consent center, or a code deleted elsewhere, shows at once.
 *
 * A tag is seen here only as its fingerprint, made on the reader thread ([TagRead]). The first touch's fingerprint is held in memory only,
 * until the second touch, a cancel, a save or the end of this view model: never in the saved state, the UI state, an outcome or a log.
 */
@HiltViewModel
class RegisterTokenViewModel @Inject constructor(
    private val tokens: TokenRepository,
    private val consents: ConsentStore,
    private val nfc: NfcStatus,
    savedState: SavedStateHandle,
) : ViewModel() {

    /** QR or NFC. A missing, unknown or odd value is QR: this never throws. */
    val mode: HandshakeMode = (savedState.get<Any?>(ARG_MODE) as? String)
        ?.let { runCatching { HandshakeMode.valueOf(it) }.getOrNull() }
        ?.takeIf { it == HandshakeMode.NFC }
        ?: HandshakeMode.QR

    private val consentKey = if (mode == HandshakeMode.NFC) ConsentKey.NFC else ConsentKey.CAMERA_QR

    /** The first touch of a tag being registered: a fingerprint, never an id. Read and written on the main thread only. */
    private var pendingFirst: String? = null

    private val _state = MutableStateFlow<RegisterTokenUiState>(RegisterTokenUiState.Loading)
    val state: StateFlow<RegisterTokenUiState> = _state.asStateFlow()

    init {
        guardedScope.launch {
            combine(tokens.observe(mode), consents.observe(consentKey)) { codes, granted -> codes to granted }
                .collect { (codes, granted) ->
                    _state.update { current ->
                        when (current) {
                            RegisterTokenUiState.Loading -> RegisterTokenUiState.Ready(codes, granted, outcome = null, madeCode = null, mode = mode)
                            is RegisterTokenUiState.Ready -> current.copy(codes = codes, consentGranted = granted)
                        }
                    }
                }
        }
    }

    private inline fun ready(change: (RegisterTokenUiState.Ready) -> RegisterTokenUiState.Ready) {
        _state.update { if (it is RegisterTokenUiState.Ready) change(it) else it }
    }

    private fun say(outcome: CodeOutcome?) = ready { it.copy(outcome = outcome) }

    /** The person turned the camera (or tag) consent on or off. It is recorded as theirs; turning it off keeps the codes, which are only hashes. */
    fun setConsent(granted: Boolean) {
        guardedScope.launch {
            if (granted) consents.grant(consentKey, ConsentSource.USER) else consents.revoke(consentKey, ConsentSource.USER)
        }
    }

    /**
     * Registers the code [payload] that was scanned, as [label]. Whatever it comes to is said. The last answer is put away at once, so that
     * the screen, which keeps the typed name until the answer comes, never takes an answer left over from before for this one. Codes only.
     */
    fun register(label: String, payload: String) {
        if (mode != HandshakeMode.QR) return
        say(null)
        guardedScope.launch {
            // The consent is read where the code is stored: a frame can be on its way when the consent is turned off, here or elsewhere.
            if (!consents.isGranted(ConsentKey.CAMERA_QR)) {
                say(CodeOutcome.NoConsent)
                return@launch
            }
            say(
                when (val result = tokens.register(HandshakeMode.QR, label, payload)) {
                    is RegisterResult.Saved -> CodeOutcome.Registered(label.trim())
                    RegisterResult.BlankPayload -> CodeOutcome.Blank
                    RegisterResult.BadLabel -> CodeOutcome.BadLabel
                    is RegisterResult.Duplicate -> CodeOutcome.Duplicate(result.existingLabel)
                    RegisterResult.Full -> CodeOutcome.Full
                },
            )
        }
    }

    /** The camera failed while a code was being scanned. Nothing is registered. */
    fun scanFailed() = say(CodeOutcome.CameraFailed)

    /**
     * A tag was touched while one is being registered as [label] (tags only; ignored on the codes screen). The first usable touch is held
     * and asks for a second; a second that matches is saved, after the tag consent is read once more (a touch can be on its way when it is
     * turned off); one that differs, a tag with no id, or a tag whose id changes, forgets the first touch.
     */
    fun tagRead(label: String, read: TagRead) {
        if (mode != HandshakeMode.NFC) return
        when (read) {
            TagRead.NoId -> forget(CodeOutcome.NoTagId)
            TagRead.Changing -> forget(CodeOutcome.RandomTag)
            is TagRead.Seen -> {
                val first = pendingFirst
                when {
                    first == null -> {
                        pendingFirst = read.hash
                        say(CodeOutcome.TouchAgain)
                    }
                    first != read.hash -> forget(CodeOutcome.TagChanged)
                    else -> {
                        pendingFirst = null
                        saveTag(label, read.hash)
                    }
                }
            }
        }
    }

    private fun forget(outcome: CodeOutcome) {
        pendingFirst = null
        say(outcome)
    }

    private fun saveTag(label: String, hash: String) {
        say(null)
        guardedScope.launch {
            if (!consents.isGranted(ConsentKey.NFC)) {
                say(CodeOutcome.NoConsent)
                return@launch
            }
            say(
                when (val result = tokens.registerTag(label, hash)) {
                    is RegisterResult.Saved -> CodeOutcome.Registered(label.trim())
                    RegisterResult.BlankPayload -> CodeOutcome.NoTagId
                    RegisterResult.BadLabel -> CodeOutcome.BadLabel
                    is RegisterResult.Duplicate -> CodeOutcome.Duplicate(result.existingLabel)
                    RegisterResult.Full -> CodeOutcome.Full
                },
            )
        }
    }

    /** The person stopped registering a tag: the first touch is forgotten, and the words that asked for the second are put away. */
    fun cancelTag() {
        pendingFirst = null
        ready { if (it.outcome == CodeOutcome.TouchAgain) it.copy(outcome = null) else it }
    }

    /** The phone stopped reading tags while one was being registered. The first touch is forgotten. Tags only. */
    fun tagReaderOff() {
        if (mode != HandshakeMode.NFC) return
        forget(CodeOutcome.ReaderOff)
    }

    /** The phone's tag reader as this screen needs it. The screen reads it again whenever it comes back. */
    fun readerAccess(): ReaderAccess = when (nfc.availability()) {
        NfcAvailability.NONE -> ReaderAccess.NONE
        NfcAvailability.OFF -> ReaderAccess.OFF
        NfcAvailability.SECURE_LOCKED, NfcAvailability.READY -> ReaderAccess.READY
    }

    /** Secure NFC is on: an alarm over the lock screen will ask for a typed phrase instead of a tag. */
    fun secureNfcOn(): Boolean = nfc.secureNfcOn()

    /** Renames the code [id]. As with a registration, the last answer is put away at once: the screen keeps the field open until this answer comes. */
    fun rename(id: String, label: String) {
        say(null)
        guardedScope.launch {
            say(
                when (tokens.rename(id, label)) {
                    RenameResult.Renamed -> CodeOutcome.Renamed
                    RenameResult.BadLabel -> CodeOutcome.BadLabel
                    RenameResult.Gone -> CodeOutcome.Gone
                },
            )
        }
    }

    /** Deletes the code [id]. The screen asks first. */
    fun delete(id: String) {
        guardedScope.launch { tokens.delete(id) }
    }

    /** Makes a new code to show. It is not registered: the person prints it or shows it elsewhere, and registers it by scanning it. Codes only. */
    fun makeCode() {
        if (mode != HandshakeMode.QR) return
        ready { it.copy(madeCode = QrCodes.newPayload()) }
    }

    fun closeMadeCode() = ready { it.copy(madeCode = null) }

    fun dismissOutcome() = say(null)

    override fun onCleared() {
        pendingFirst = null
        super.onCleared()
    }

    companion object {
        /** The route's argument that picks the codes (QR, the default) or the tags (NFC). */
        const val ARG_MODE = "mode"
    }
}
