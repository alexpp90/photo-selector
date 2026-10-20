package com.photoselectortoolbox.ui.selector

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoselectortoolbox.domain.curation.DeferredDeletion
import com.photoselectortoolbox.domain.format.SelectionActionLabels
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.BrandAccentText
import com.photoselectortoolbox.ui.theme.ScoreBad
import com.photoselectortoolbox.ui.theme.StudioBorder
import com.photoselectortoolbox.ui.theme.StudioCard
import com.photoselectortoolbox.ui.theme.StudioPanel
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc50
import com.photoselectortoolbox.ui.theme.Zinc500
import kotlinx.coroutines.delay

/**
 * The callbacks the selector needs, gathered so the layout, the control block
 * and the context menu cannot drift apart in what they offer.
 *
 * `onToggleLayout` is gone with the layout it toggled: there is one comparison
 * layout now (see `docs/products/android-desktop/DESIGN.md` §8), so there is
 * nothing to switch between and no free-floating toggle to collide with another
 * layout's controls.
 */
data class SelectorActions(
    val onMove: () -> Unit,
    val onCopy: () -> Unit,
    val onDelete: () -> Unit,
    val onFullscreen: () -> Unit,
    val onToggleDetails: () -> Unit,
    val onToggleFilmstrip: () -> Unit,
    val onToggleOverlayValues: () -> Unit,
    val onShowShortcuts: () -> Unit,
)

// ── Context menu ────────────────────────────────────────────────────────────

/**
 * Long-press / right-click menu for a frame.
 *
 * Repeats what the rails already offer on purpose: on a tablet held in two
 * hands the rails are a thumb-stretch away, and a menu at the pointer is not.
 */
@Composable
fun SelectorContextMenu(
    actions: SelectorActions,
    filingAction: FilingAction,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(230.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(StudioPanel)
            .border(1.dp, StudioBorder, RoundedCornerShape(10.dp))
            .padding(5.dp)
            .testTag("selector_context_menu"),
    ) {
        // Configured verb first, same order as the control block, from the
        // same source. Two lists of the same two actions in two orders is how
        // a menu and a button end up disagreeing about which one is primary.
        SelectionActionLabels.both(filingAction).forEach { label ->
            ContextMenuRow(
                icon = if (label.action == FilingAction.MOVE) {
                    Icons.AutoMirrored.Filled.DriveFileMove
                } else {
                    Icons.Default.ContentCopy
                },
                label = label.phrase,
                keyHint = label.shortcut,
                onClick = {
                    onDismissRequest()
                    if (label.action == FilingAction.MOVE) actions.onMove() else actions.onCopy()
                },
            )
        }
        ContextMenuRow(
            icon = Icons.Default.Delete,
            label = "Delete",
            keyHint = "Del",
            labelColor = ScoreBad,
            onClick = { onDismissRequest(); actions.onDelete() },
        )
        ContextMenuRow(
            icon = Icons.Outlined.Info,
            label = "Photo details",
            keyHint = null,
            onClick = { onDismissRequest(); actions.onToggleDetails() },
        )
        ContextMenuRow(
            icon = Icons.Default.Fullscreen,
            label = "Open fullscreen",
            keyHint = "F",
            onClick = { onDismissRequest(); actions.onFullscreen() },
        )
    }
}

@Composable
private fun ContextMenuRow(
    icon: ImageVector,
    label: String,
    keyHint: String?,
    onClick: () -> Unit,
    labelColor: Color = Zinc50,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (labelColor == ScoreBad) ScoreBad else Zinc400,
        )
        Text(
            text = label,
            fontSize = 13.sp,
            color = labelColor,
            modifier = Modifier.weight(1f),
        )
        if (keyHint != null) {
            Text(
                text = keyHint,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Zinc500,
            )
        }
    }
}

// ── First-run hint ──────────────────────────────────────────────────────────

/**
 * The one permitted image-adjacent overlay: a single pill telling the user the
 * neighbour frames are tappable.
 *
 * Everything else in this design stays out of the letterbox because chrome
 * over a photograph changes how the photograph reads. This one earns its place
 * because the layout has no arrows and no other cue that the tiles navigate —
 * and it is shown exactly once, ever.
 */
@Composable
fun FirstRunNavigationHint(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(StudioCard)
                .border(1.dp, StudioBorder, RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp)
                .testTag("first_run_hint"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Zinc400,
            )
            Text(text = "Tap either side to browse", fontSize = 12.sp, color = Zinc50)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Zinc400,
            )
            Text(
                text = "Got it",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = BrandAccentText,
                // A 48 dp target without a 48 dp pill: the minimum is applied
                // outside the clickable so the whole square is the control, while
                // the text stays 12 sp on a 36 dp pill (AD-8).
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onDismiss)
                    .padding(start = 4.dp)
                    .testTag("first_run_hint_dismiss"),
            )
        }
    }
}

// ── Undo snackbar ───────────────────────────────────────────────────────────

/**
 * Confirmation of an action, with the time remaining to take it back drawn as
 * a draining line.
 *
 * The window is 30 seconds rather than the Material default of a few seconds
 * because culling is fast and inattentive: the user is three frames further on
 * before they register that the last one was a mistake.
 *
 * The duration is [DeferredDeletion.UNDO_WINDOW_MILLIS] — the same constant the
 * ViewModel's commit timer uses. Two copies of that number is a countdown that
 * lies about how long is left (REQUIREMENTS §2).
 */
@Composable
fun SelectorSnackbar(
    message: String?,
    onUndo: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message == null) return

    // Driven from wall-clock time rather than an animation, because the line has
    // to agree with a timer that is *also* wall-clock: the ViewModel commits the
    // deletion 30 real seconds after it was requested. A frame-clock animation
    // would drift from that under any clock manipulation and, in an instrumented
    // test, would be fast-forwarded to zero the moment the harness waited for
    // idle — dismissing the snackbar before the UNDO could be pressed.
    val remaining = remember(message) { mutableFloatStateOf(1f) }
    LaunchedEffect(message) {
        val startedAt = System.currentTimeMillis()
        while (true) {
            val left = DeferredDeletion.UNDO_WINDOW_MILLIS - (System.currentTimeMillis() - startedAt)
            remaining.floatValue =
                (left.toFloat() / DeferredDeletion.UNDO_WINDOW_MILLIS).coerceIn(0f, 1f)
            if (left <= 0L) break
            delay(COUNTDOWN_TICK_MILLIS)
        }
        onDismiss()
    }

    Column(
        modifier = modifier
            .widthIn(min = SnackbarMinWidth, max = SnackbarMaxWidth)
            .clip(RoundedCornerShape(10.dp))
            .background(StudioCard)
            .border(1.dp, StudioBorder, RoundedCornerShape(10.dp))
            .testTag("selector_snackbar"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                fontSize = 13.sp,
                color = Zinc50,
                // `weight` takes the width the *parent* offers, and the parent was
                // the whole window — so the card spanned it edge to edge (AD-7).
                // `fill = false` plus the card's `widthIn(max)` lets a short
                // message keep a card and a long one wrap inside it.
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onUndo != null) {
                Text(
                    text = "UNDO",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = BrandAccentText,
                    modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onUndo(); onDismiss() }
                        .testTag("snackbar_undo"),
                )
            }
        }
        // The line drains left to right over the undo window, so "how long have
        // I got" is answerable at a glance rather than by counting. The fraction
        // is read inside `graphicsLayer`, which defers it to the draw phase —
        // 300 ticks over the window cost 300 draws of a 2 dp bar and zero
        // recompositions of the snackbar around it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .graphicsLayer {
                    scaleX = remaining.floatValue
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .background(BrandAccent)
                .testTag("snackbar_countdown"),
        )
    }
}

/** Narrowest and widest the snackbar card is drawn (AD-7). */
private val SnackbarMinWidth = 340.dp
private val SnackbarMaxWidth = 480.dp

/**
 * Places [SelectorSnackbar] 96 dp above the bottom edge, centred over the image
 * region rather than the window.
 *
 * [sidebarInset] is the sidebar's width (0 where there is none): centring over
 * the window put a 340 dp card's left edge at 570 dp of a 1480 dp window, which
 * is fine, but at 600 × 480 it ran under the 88 dp sidebar (AD-7). The wrapper
 * ignores pointer input, so it never blocks the frames beneath it.
 */
@Composable
fun SelectorSnackbarHost(
    message: String?,
    onUndo: (() -> Unit)?,
    onDismiss: () -> Unit,
    sidebarInset: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(start = sidebarInset, end = FrameGeometry.OuterPadding, bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SelectorSnackbar(message = message, onUndo = onUndo, onDismiss = onDismiss)
    }
}

/** How often the countdown line is redrawn. Fine enough to read as continuous. */
private const val COUNTDOWN_TICK_MILLIS = 100L
