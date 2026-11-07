package com.command.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A question whose answers are not equal: the safe one comes first and is the heavier, and leaving the dialog by any other way is the safe one too.
 * A Material `AlertDialog` cannot promise this: it lays out its two buttons in a row, or stacks them with the confirm button on top when they wrap,
 * so the order depends on the width of the screen and the size of the type. The message scrolls, so that a very large font cannot push the answers out of reach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SafeQuestion(
    tag: String,
    title: String,
    message: String,
    @StringRes keepLabel: Int,
    keepTag: String,
    onKeep: () -> Unit,
    @StringRes throwAwayLabel: Int,
    throwAwayTag: String,
    onThrowAway: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onKeep, modifier = Modifier.testTag(tag)) {
        Surface(
            modifier = Modifier.sizeIn(minWidth = 280.dp, maxWidth = 560.dp),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = AlertDialogDefaults.titleContentColor,
                    modifier = Modifier.semantics { heading() },
                )
                Column(Modifier.weight(1f, fill = false).padding(top = 16.dp, bottom = 24.dp).verticalScroll(rememberScrollState())) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = AlertDialogDefaults.textContentColor)
                }
                Button(onClick = onKeep, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(keepTag)) {
                    Text(stringResource(keepLabel))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onThrowAway,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(throwAwayTag),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(throwAwayLabel)) }
            }
        }
    }
}
