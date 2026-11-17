package com.phototok.ui.phonemode

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phototok.R
import com.phototok.viewmodel.CardClearProgress

/** Test tags of the Delete all progress sheet. */
internal object CardClearProgressTags {
    const val SHEET = "cardClear.sheet"
    const val PROGRESS = "cardClear.progress"
    const val COUNT = "cardClear.count"
    const val CANCEL = "cardClear.cancel"
}

/**
 * When TalkBack hears how far Delete all is: after 10 more files or 2 s, whichever comes first, but
 * never twice within a second, so a fast card does not flood the screen reader (UX note J1).
 */
internal object ClearProgressAnnouncement {
    const val EVERY_FILES = 10
    const val EVERY_MS = 2_000L
    const val MIN_GAP_MS = 1_000L

    fun shouldAnnounce(done: Int, lastDone: Int, nowMs: Long, lastMs: Long): Boolean {
        if (done == lastDone) return false
        val elapsed = nowMs - lastMs
        if (elapsed < MIN_GAP_MS) return false
        return done - lastDone >= EVERY_FILES || elapsed >= EVERY_MS
    }
}

/**
 * Delete all on a card, while it runs ([USER 2026-11-16] D2, REQ-TOK-FEATURE.15): a sheet that
 * neither a swipe nor a tap beside it closes, with a determinate bar, "done / total" and one
 * Cancel. Cancel (and Back) stops the run between two files; until the file in hand returns the
 * button reads "Cancelling…" and is disabled. The feed under the sheet takes no input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardClearProgressSheet(
    progress: CardClearProgress,
    onCancel: () -> Unit,
    clock: () -> Long = SystemClock::uptimeMillis,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    var spokenDone by remember { mutableIntStateOf(progress.done) }
    var spokenAt by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(progress.done) {
        val now = clock()
        if (ClearProgressAnnouncement.shouldAnnounce(progress.done, spokenDone, now, spokenAt)) {
            spokenDone = progress.done
            spokenAt = now
        }
    }
    ModalBottomSheet(
        // Never called by a swipe or a tap on the scrim (both refused); Back is the BackHandler below.
        onDismissRequest = { if (!progress.cancelling) onCancel() },
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = false,
            shouldDismissOnClickOutside = false,
        ),
    ) {
        BackHandler { if (!progress.cancelling) onCancel() }
        CardClearProgressContent(progress = progress, spokenDone = spokenDone, onCancel = onCancel)
    }
}

@Composable
private fun CardClearProgressContent(progress: CardClearProgress, spokenDone: Int, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CardClearProgressTags.SHEET)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.title_deleting),
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        LinearProgressIndicator(
            progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CardClearProgressTags.PROGRESS),
            color = colors.error,
        )
        val spoken = stringResource(R.string.cd_clear_progress, spokenDone, progress.total)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.label_clear_progress, progress.done, progress.total),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .testTag(CardClearProgressTags.COUNT)
                    .clearAndSetSemantics {
                        contentDescription = spoken
                        liveRegion = LiveRegionMode.Polite
                    },
            )
            TextButton(
                onClick = onCancel,
                enabled = !progress.cancelling,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CardClearProgressTags.CANCEL),
            ) {
                Text(
                    stringResource(if (progress.cancelling) R.string.action_cancelling else R.string.action_cancel),
                )
            }
        }
    }
}
