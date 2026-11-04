package com.command.reveille

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.command.domain.handshake.TryId
import com.example.R

/** How many deliberate taps the assistive confirm takes, in place of a press. */
internal const val ASSISTIVE_TAPS = 3

/**
 * The typing field's keyboard: autocorrect and capitals are off (the check ignores capitals either way, but a keyboard that "corrects" a
 * word is a wrong try the person did not make), and Done submits.
 */
internal val TYPING_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Text,
    imeAction = ImeAction.Done,
)

/**
 * The typing challenge: the phrase is on the screen and the person types it. The field has focus at once so that the keyboard is up without
 * a tap (not under a screen reader, which would be taken past the phrase to the field before it had been read), and Done on the keyboard
 * and the button both submit. What was typed belongs to the try: a new try, after a wrong one, begins with an empty field.
 */
@Composable
internal fun TypingChallenge(challenge: Challenge.Typing, onChanged: (String) -> Unit, onSubmitted: (String) -> Unit) {
    var text by rememberSaveable(challenge.tryId) { mutableStateOf("") }
    // What was typed comes back with a restored screen (it is saved with it) without anyone typing it: the try had begun, so the view model is
    // told again and its clock runs. It does nothing if it knows already (a screen that was only turned).
    LaunchedEffect(challenge.tryId) { if (text.isNotEmpty()) onChanged(text) }
    val focus = remember { FocusRequester() }
    val screenReader = LocalContext.current.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    LaunchedEffect(challenge.tryId) { if (!screenReader) runCatching { focus.requestFocus() } }

    Text(
        stringResource(R.string.handshake_typing_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Text(challenge.phrase, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag(ReveilleTags.TYPING_PHRASE))
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChanged(it)
        },
        label = { Text(stringResource(R.string.handshake_typing_field)) },
        singleLine = true,
        keyboardOptions = TYPING_KEYBOARD,
        keyboardActions = KeyboardActions(onDone = { onSubmitted(text) }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(ReveilleTags.TYPING_FIELD),
    )
    if (challenge.mistaken) {
        Mistaken(pluralStringResource(if (challenge.slow) R.plurals.handshake_typing_slow else R.plurals.handshake_typing_wrong, challenge.triesLeft, challenge.triesLeft))
    }
    Button(onClick = { onSubmitted(text) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ReveilleTags.TYPING_DONE)) {
        Text(stringResource(R.string.handshake_typing_done), style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * The confirmation sequence: four large numbered targets in an order that is not the order, to be tapped as 1, 2, 3, 4. A number that is
 * done cannot be tapped again (a double tap is not a wrong try); a wrong tap starts the sequence again, in another arrangement.
 */
@Composable
internal fun SequenceChallenge(challenge: Challenge.Sequence, onTapped: (Int) -> Unit) {
    Text(
        stringResource(R.string.handshake_sequence_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Text(
        stringResource(R.string.handshake_sequence_progress, challenge.tapped),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(ReveilleTags.SEQUENCE_PROGRESS),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        for (cells in challenge.layout.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                for (number in cells) {
                    val description = stringResource(R.string.handshake_sequence_target, number)
                    FilledTonalButton(
                        onClick = { onTapped(number) },
                        enabled = number > challenge.tapped,
                        modifier = Modifier.weight(1f).heightIn(min = 88.dp).testTag(ReveilleTags.sequenceTarget(number)).semantics { contentDescription = description },
                    ) {
                        Text(number.toString(), style = MaterialTheme.typography.headlineLarge)
                    }
                }
            }
        }
    }
    if (challenge.mistaken) {
        Mistaken(pluralStringResource(if (challenge.slow) R.plurals.handshake_sequence_slow else R.plurals.handshake_sequence_wrong, challenge.triesLeft, challenge.triesLeft))
    }
}

/** The two-step's question: what the person will do first, in their own mission's words, and one explicit tap that stands behind it. */
@Composable
internal fun TwoStepChallenge(challenge: Challenge.TwoStep, onIndicated: () -> Unit) {
    Text(
        stringResource(R.string.handshake_two_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Text(challenge.firstAction, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
    Button(onClick = onIndicated, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag(ReveilleTags.TWO_STEP_INDICATE)) {
        Text(stringResource(R.string.handshake_two_indicate, challenge.firstAction), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

/** Every way is used up: said plainly, with nothing to press but the silence that is always there and the way out that Back opens. */
@Composable
internal fun OutOfTriesNote() {
    Text(
        stringResource(R.string.handshake_out_of_tries),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag(ReveilleTags.OUT_OF_TRIES),
    )
}

/**
 * The hold's assistive confirm, for a person who cannot hold a finger down: [ASSISTIVE_TAPS] deliberate taps in place of the press, the
 * label saying how many are done (a screen reader announces it as it changes). [primary] is the whole answer to the hold (the tries
 * on the press are used up), otherwise it sits under the hold target (a screen reader is on). The first tap begins the try ([onBegun]): from
 * then on it must be finished in the step's time, like a phrase or a sequence.
 */
@Composable
internal fun AssistiveConfirm(tryId: TryId, primary: Boolean, onBegun: () -> Unit = {}, onConfirmed: () -> Unit) {
    var taps by rememberSaveable(tryId) { mutableIntStateOf(0) }
    val label = if (taps == 0) {
        stringResource(R.string.reveille_hold_accessible_button)
    } else {
        stringResource(R.string.handshake_assistive_tap, taps + 1, ASSISTIVE_TAPS)
    }
    val modifier = Modifier.fillMaxWidth().heightIn(min = if (primary) 96.dp else 56.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag(ReveilleTags.ACCESSIBLE)
    val tapped = {
        if (taps == 0) onBegun()
        if (taps + 1 >= ASSISTIVE_TAPS) onConfirmed() else taps += 1
    }
    if (primary) {
        Button(onClick = tapped, modifier = modifier) { Text(label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center) }
    } else {
        TextButton(onClick = tapped, modifier = modifier) { Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun Mistaken(words: String) {
    Text(
        words,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag(ReveilleTags.MISTAKEN),
    )
}
