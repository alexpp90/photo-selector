package com.phototok.ui.phonemode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phototok.domain.FirstRunHint
import com.phototok.R
import com.phototok.domain.FirstRunHintText
import com.phototok.domain.UiText
import com.phototok.ui.asString
import com.phototok.viewmodel.FirstRunHintUi
import kotlinx.coroutines.delay

/** How long an explanation stays up before it fades on its own. */
private const val AUTO_DISMISS_MS = 7000L

/** Longer when the card offers an action, so there is time to reach it. */
private const val AUTO_DISMISS_WITH_ACTION_MS = 12000L

/**
 * The one-time explanation shown after a first-time action.
 *
 * Deliberately a card rather than a snackbar: it carries an action (Settings,
 * Show all) and an explicit "Got it" rather than a three-second flash the user
 * can miss mid-swipe. The copy is one line, with an optional short second line
 * (see [com.phototok.domain.FirstRunHintText]).
 *
 * @param onOpenSettings shown as a SETTINGS action on hints whose behaviour is
 *   configurable ([FirstRunHintText.offersSettings]), instead of telling the
 *   user in words where to change it.
 */
@Composable
fun FirstRunHintCard(
    hint: FirstRunHintUi?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
) {
    // The hint's own action wins; otherwise configurable hints point at Settings.
    val action: Pair<UiText, () -> Unit>? = hint?.let { h ->
        when {
            h.actionLabel != null && h.onAction != null -> h.actionLabel to h.onAction
            onOpenSettings != null && FirstRunHintText.offersSettings(h.hint) ->
                UiText.of(R.string.action_settings) to onOpenSettings
            else -> null
        }
    }

    // Restart the timer whenever a *different* hint arrives.
    LaunchedEffect(hint?.id) {
        if (hint != null) {
            delay(if (action != null) AUTO_DISMISS_WITH_ACTION_MS else AUTO_DISMISS_MS)
            onDismiss()
        }
    }

    var lastAction by remember { mutableStateOf(action) }
    if (hint != null) lastAction = action

    // Hold on to the last hint so the card still has content to draw while it
    // animates out (by then [hint] is already null).
    var lastHint by remember { mutableStateOf(hint) }
    if (hint != null) lastHint = hint

    AnimatedVisibility(
        visible = hint != null,
        enter = slideInVertically(tween(250)) { it / 2 } + fadeIn(tween(250)),
        exit = slideOutVertically(tween(200)) { it / 2 } + fadeOut(tween(200)),
        modifier = modifier,
    ) {
        val colors = MaterialTheme.colorScheme
        val current = lastHint ?: return@AnimatedVisibility

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("first_run_hint"),
            shape = RoundedCornerShape(16.dp),
            color = colors.surfaceContainerHigh.copy(alpha = 0.97f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                colors.primary.copy(alpha = 0.35f),
            ),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.12f))
                        .border(1.dp, colors.primary.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = iconFor(current.hint),
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = current.title.asString(),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val message = current.message
                    if (message != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = message.asString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val currentAction = lastAction
                        if (currentAction != null) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(role = Role.Button) {
                                        currentAction.second.invoke()
                                        onDismiss()
                                    }
                                    .padding(horizontal = 12.dp)
                                    .testTag("first_run_hint_action"),
                            ) {
                                Text(
                                    text = currentAction.first.asString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        val dismissName = stringResource(R.string.cd_dismiss)
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("first_run_hint_dismiss"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = dismissName,
                                tint = if (currentAction != null) colors.onSurfaceVariant else colors.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun iconFor(hint: FirstRunHint): ImageVector = when (hint) {
    FirstRunHint.SWIPE_RIGHT -> Icons.AutoMirrored.Filled.ArrowForward
    FirstRunHint.SWIPE_LEFT_FOLDER -> Icons.AutoMirrored.Filled.ArrowBack
    FirstRunHint.SWIPE_LEFT_DELETE -> Icons.Default.Delete
    FirstRunHint.SWIPE_VERTICAL -> Icons.Default.SwapVert
    FirstRunHint.TAP_HUD -> Icons.Default.TouchApp
    FirstRunHint.DOUBLE_TAP_ZOOM -> Icons.Default.ZoomIn
    FirstRunHint.REVERT -> Icons.AutoMirrored.Filled.Undo
    FirstRunHint.RAW_JPEG_PAIRS -> Icons.Default.Style
    FirstRunHint.FILTER_MISMATCH -> Icons.Default.FilterList
}
