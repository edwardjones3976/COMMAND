package com.command.handshake

import androidx.lifecycle.ViewModel
import com.command.data.ConsentStore
import com.command.data.RegisterResult
import com.command.data.RenameResult
import com.command.data.Token
import com.command.data.TokenRepository
import com.command.domain.dials.HandshakeMode
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

/** What the last thing done with a code came to. Each has its own words on the screen. */
sealed interface CodeOutcome {
    /** The code that was scanned is registered as [label]. */
    data class Registered(val label: String) : CodeOutcome

    /** The code that was scanned holds nothing but whitespace, which is not a code. */
    data object Blank : CodeOutcome

    /** The name, trimmed, is empty or longer than [TokenRepository.MAX_LABEL] characters. */
    data object BadLabel : CodeOutcome

    /** The code that was scanned is already registered, as [label]. */
    data class Duplicate(val label: String) : CodeOutcome

    /** [TokenRepository.MAX_PER_MODE] codes are registered, and no more can be. */
    data object Full : CodeOutcome

    data object Renamed : CodeOutcome

    /** The code was deleted before it could be renamed. */
    data object Gone : CodeOutcome

    /** The camera could not be used while a code was scanned. */
    data object CameraFailed : CodeOutcome

    /** A code arrived after the camera consent was turned off (a frame already on its way), so it was not registered. */
    data object NoConsent : CodeOutcome
}

sealed interface RegisterTokenUiState {
    data object Loading : RegisterTokenUiState

    /**
     * [codes] are the registered codes of the QR handshake, by label, never their hashes; [consentGranted] is the camera consent as it is
     * recorded now; [outcome] is what the last registration or rename came to, until it is put away or replaced; [madeCode] is a new code
     * that is being shown, held only here and only until it is put away: it is not registered until it is scanned.
     */
    data class Ready(
        val codes: List<Token>,
        val consentGranted: Boolean,
        val outcome: CodeOutcome?,
        val madeCode: String?,
    ) : RegisterTokenUiState
}

/**
 * The codes a step that scans a code checks against: they are listed, renamed and deleted here, and registered from a scan. The list and
 * the consent follow the database, so a change made in the consent center, or a code deleted elsewhere, shows at once.
 */
@HiltViewModel
class RegisterTokenViewModel @Inject constructor(
    private val tokens: TokenRepository,
    private val consents: ConsentStore,
) : ViewModel() {

    private val _state = MutableStateFlow<RegisterTokenUiState>(RegisterTokenUiState.Loading)
    val state: StateFlow<RegisterTokenUiState> = _state.asStateFlow()

    init {
        guardedScope.launch {
            combine(tokens.observe(HandshakeMode.QR), consents.observe(ConsentKey.CAMERA_QR)) { codes, granted -> codes to granted }
                .collect { (codes, granted) ->
                    _state.update { current ->
                        when (current) {
                            RegisterTokenUiState.Loading -> RegisterTokenUiState.Ready(codes, granted, outcome = null, madeCode = null)
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

    /** The person turned the camera consent on or off. It is recorded as theirs; turning it off keeps the codes, which are only hashes. */
    fun setConsent(granted: Boolean) {
        guardedScope.launch {
            if (granted) consents.grant(ConsentKey.CAMERA_QR, ConsentSource.USER) else consents.revoke(ConsentKey.CAMERA_QR, ConsentSource.USER)
        }
    }

    /**
     * Registers the code [payload] that was scanned, as [label]. Whatever it comes to is said. The last answer is put away at once, so that
     * the screen, which keeps the typed name until the answer comes, never takes an answer left over from before for this one.
     */
    fun register(label: String, payload: String) {
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

    /** Makes a new code to show. It is not registered: the person prints it or shows it elsewhere, and registers it by scanning it. */
    fun makeCode() = ready { it.copy(madeCode = QrCodes.newPayload()) }

    fun closeMadeCode() = ready { it.copy(madeCode = null) }

    fun dismissOutcome() = say(null)
}
