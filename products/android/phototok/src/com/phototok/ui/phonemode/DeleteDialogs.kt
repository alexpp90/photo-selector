package com.phototok.ui.phonemode

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phototok.R
import com.phototok.data.repository.SystemTrashRequest
import com.phototok.data.repository.TrashNotice
import com.phototok.domain.DeleteSplit
import com.phototok.viewmodel.SystemTrashViewModel

/**
 * Direct Delete Confirmation (REQ-TOK-GESTURE.03), shown before a swipe-left delete while the
 * confirmation is on. It says per source what happens ([USER 2026-11-12] decision #13): "Move to
 * Trash?" when every file of the photo goes to the system bin, "Delete permanently?" where there is
 * none (SD card, USB, cloud), and for a photo whose companions have no bin the file count with
 * "1 to Trash · 1 permanent". It never claims a bin where none exists.
 *
 * A "Don't ask" checkbox, applied with the confirm button, turns this prompt off for good (the same
 * setting as Settings > Confirm delete). Revert still works after confirming, so the dialog does
 * not claim the delete is immediate.
 *
 * @param onConfirm Delete was chosen; the argument is whether "Don't ask" was ticked.
 */
@Composable
fun DirectDeleteDialog(
    onConfirm: (dontAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
    split: DeleteSplit = DeleteSplit.permanentOnly(1),
) {
    val colors = MaterialTheme.colorScheme
    var dontAsk by rememberSaveable { mutableStateOf(false) }
    val toBin = split.allToBin
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("direct_delete_dialog"),
        icon = {
            Icon(
                imageVector = if (toBin) Icons.Filled.Delete else Icons.Filled.DeleteForever,
                contentDescription = null,
                tint = if (toBin) colors.onSurfaceVariant else colors.error,
                modifier = Modifier.testTag(if (toBin) "direct_delete_icon_bin" else "direct_delete_icon_permanent"),
            )
        },
        title = {
            Text(
                text = when {
                    toBin -> stringResource(R.string.dialog_title_trash)
                    split.mixed -> pluralStringResource(R.plurals.dialog_title_delete_all, split.total, split.total)
                    else -> stringResource(R.string.dialog_title_direct_delete)
                },
                color = if (toBin) colors.onSurface else colors.error,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (split.mixed) {
                    Text(
                        text = stringResource(R.string.dialog_body_trash_split, split.bin, split.permanent),
                        modifier = Modifier.testTag("direct_delete_split"),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = dontAsk, role = Role.Checkbox, onValueChange = { dontAsk = it })
                        .testTag("direct_delete_dont_ask"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // The row is the target; the box only draws the state.
                    Checkbox(checked = dontAsk, onCheckedChange = null)
                    Text(
                        text = stringResource(R.string.label_dont_ask),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(dontAsk) },
                modifier = Modifier.testTag("direct_delete_confirm_button"),
            ) {
                Text(
                    text = stringResource(if (toBin) R.string.action_trash else R.string.action_delete),
                    color = colors.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("direct_delete_cancel_button"),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = colors.surfaceContainerHigh,
    )
}

/**
 * Launches the system bin's consent requests ([USER 2026-11-12] decision #13) from this Activity
 * and shows "N not deleted" with Retry after a decline. The requests are held by a singleton, so a
 * batch finalised while no Activity was shown is launched as soon as one is (it collects only while
 * started).
 */
@Composable
internal fun SystemTrashHost(viewModel: SystemTrashViewModel, hostState: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SystemTrashRequests(
        request = state.request,
        notice = state.notice,
        hostState = hostState,
        onLaunched = viewModel::onLaunched,
        launch = null,
        onResult = viewModel::onResult,
        onRetry = viewModel::retry,
        onNoticeShown = viewModel::clearNotice,
    )
}

/**
 * The stateless part of [SystemTrashHost]. [launch] replaces the system launcher in tests; null
 * uses `StartIntentSenderForResult`.
 */
@Composable
internal fun SystemTrashRequests(
    request: SystemTrashRequest?,
    notice: TrashNotice?,
    hostState: SnackbarHostState,
    onLaunched: (SystemTrashRequest) -> Unit,
    launch: ((SystemTrashRequest) -> Unit)?,
    onResult: (accepted: Boolean) -> Unit,
    onRetry: (TrashNotice) -> Unit,
    onNoticeShown: (TrashNotice) -> Unit,
) {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        currentOnResult(result.resultCode == Activity.RESULT_OK)
    }
    val currentLaunch by rememberUpdatedState(launch)
    val currentOnLaunched by rememberUpdatedState(onLaunched)
    LaunchedEffect(request?.id) {
        val r = request ?: return@LaunchedEffect
        currentOnLaunched(r)
        val custom = currentLaunch
        if (custom != null) {
            custom(r)
        } else {
            try {
                launcher.launch(IntentSenderRequest.Builder(r.intentSender).build())
            } catch (e: ActivityNotFoundException) {
                currentOnResult(false)
            }
        }
    }
    val currentOnRetry by rememberUpdatedState(onRetry)
    val currentOnNoticeShown by rememberUpdatedState(onNoticeShown)
    LaunchedEffect(notice?.id) {
        val n = notice ?: return@LaunchedEffect
        val result = hostState.showSnackbar(
            message = context.getString(R.string.snackbar_not_deleted, n.items.size),
            actionLabel = context.getString(R.string.snackbar_action_retry),
            duration = SnackbarDuration.Long,
        )
        currentOnNoticeShown(n)
        if (result == SnackbarResult.ActionPerformed) currentOnRetry(n)
    }
}
