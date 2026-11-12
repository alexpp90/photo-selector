package com.phototok.ui.phonemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.phototok.R

/**
 * The "Done with this card" sheet (`U-14c`). Its first row says how many photos of this card are
 * safe on another volume ([copiedCount], from `CardTransferJournal`), so the user decides about the
 * card knowing it; zero is shown too, with a warning glyph in the error colour.
 */
@Composable
internal fun CardDoneDialog(
    copiedCount: Int,
    showDeleteRejects: Boolean,
    clearReady: Boolean,
    clearFileCount: Int,
    onKeepAll: () -> Unit,
    onDeleteRejects: () -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_title_card_done)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardCopiedRow(copiedCount)
                Button(onClick = onKeepAll, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_keep_all))
                }
                if (showDeleteRejects) {
                    TextButton(onClick = onDeleteRejects, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_delete_rejects))
                    }
                }
                TextButton(onClick = onDeleteAll, enabled = clearReady, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.action_delete_all_count, clearFileCount),
                        color = if (clearReady) colors.error else colors.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Glyph, number and one word; the glyph turns into a warning when nothing was copied off yet. */
@Composable
private fun CardCopiedRow(copiedCount: Int) {
    val colors = MaterialTheme.colorScheme
    val spoken = stringResource(R.string.cd_card_copied, copiedCount)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .testTag("card_copied_count")
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Icon(
            imageVector = if (copiedCount == 0) Icons.Filled.Warning else Icons.Filled.PhoneAndroid,
            contentDescription = null,
            tint = if (copiedCount == 0) colors.error else colors.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(R.string.label_card_copied, copiedCount),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
    }
}
