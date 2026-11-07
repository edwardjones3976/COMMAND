package com.command.ui

import android.text.format.DateFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.command.R
import java.time.LocalTime

/** Asks for a time of day, in the 12 or 24 hour form the phone is set to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeQuestion(initial: LocalTime, okTag: String, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    val picker = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(picker.hour, picker.minute)) }, modifier = Modifier.testTag(okTag)) { Text(stringResource(R.string.editor_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
        text = { TimePicker(state = picker) },
    )
}
