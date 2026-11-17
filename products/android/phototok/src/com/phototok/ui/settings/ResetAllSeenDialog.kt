package com.phototok.ui.settings

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phototok.R

/** Test tags of the "Reset all seen?" confirmation. */
internal const val RESET_ALL_DIALOG_TAG = "reset_all_dialog"
internal const val RESET_ALL_CONFIRM_TAG = "reset_all_confirm"
internal const val RESET_ALL_CANCEL_TAG = "reset_all_cancel"

/**
 * "Reset all seen?" (REQ-TOK-FEATURE.16): forgetting what was seen in every folder at once cannot
 * be undone, so it asks first; Cancel has the initial focus. No body line: the title says it.
 * "Reset seen photos" for the open folder stays one tap.
 */
@Composable
internal fun ResetAllSeenDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Keyboard and D-pad users land on the safe choice; touch mode has no focus to move.
        runCatching { cancelFocus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(RESET_ALL_DIALOG_TAG),
        title = { Text(stringResource(R.string.dialog_title_reset_all_seen)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(RESET_ALL_CONFIRM_TAG),
            ) {
                Text(stringResource(R.string.action_reset))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .focusRequester(cancelFocus)
                    .testTag(RESET_ALL_CANCEL_TAG),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
