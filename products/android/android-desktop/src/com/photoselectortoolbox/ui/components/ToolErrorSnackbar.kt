package com.photoselectortoolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.domain.curation.SnackbarLifetime
import com.photoselectortoolbox.ui.selector.SelectorSnackbarHost

/**
 * A tool screen's error (Dupes, Stats) as the cull shows one: a snackbar card that leaves after the
 * error lifetime or when [onDismiss] clears it (review 2026-11-09, FD-UI-5: both screens set an
 * error and drew none). [sequence] is bumped per emission, so a second identical message is a new
 * card with its own timer and announcement. [suppressed] keeps an error the screen already states
 * — a revoked folder's empty state — from being said twice.
 */
@Composable
fun ToolErrorSnackbar(
    message: String?,
    sequence: Long,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    suppressed: Boolean = false,
) {
    SelectorSnackbarHost(
        message = message.takeUnless { suppressed },
        onUndo = null,
        onDismiss = onDismiss,
        sidebarInset = 0.dp,
        sequence = sequence,
        lifetime = SnackbarLifetime.ERROR,
        modifier = modifier,
    )
}
