package com.phototok.ui.phonemode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phototok.domain.CollectionAction
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeLabels

/**
 * Space the up/down swipe callouts take above and below the photo frame
 * (44dp circle + label, twice, plus the 6dp gaps).
 */
private val VERTICAL_CALLOUTS_HEIGHT = 140.dp
private val COMPACT_VERTICAL_CALLOUTS_HEIGHT = 108.dp

/** Below this stage height the up/down callouts shrink to a single line each. */
private val TIGHT_STAGE_HEIGHT = 190.dp
private val TIGHT_VERTICAL_CALLOUTS_HEIGHT = 56.dp

/**
 * Coach-mark overlay explaining every Photo-Tok control **in place**.
 *
 * Serves two entry points:
 *  - the first-launch tutorial (shown automatically), and
 *  - the help button in the viewer, which opens the same guide on demand.
 *
 * Deliberately *not* a list. The controls it describes are all on screen
 * already, so the overlay dims the viewer and labels them where they actually
 * are — logo and buttons in the top bar, Sources / Selection / Revert in the
 * bottom bar, or the side panels in landscape; their positions come from the
 * bars' measured [anchors] — while the middle of the screen animates the three swipe
 * directions over the photo they act on. Nothing scrolls: everything the user
 * needs is visible at a glance, which a ten-row card list never was.
 *
 * All wording comes from [SwipeLabels] and the user's *configured* actions, so
 * the guide can never claim a left swipe deletes when it is set to copy.
 */
@Composable
fun GestureTutorialOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    leftSwipeAction: SwipeAction = SwipeAction.DEFAULT,
    collectionAction: CollectionAction = CollectionAction.DEFAULT,
    leftSwipeFolderName: String = "",
    collectionFolderName: String = "",
    title: String = "How to Photo-Tok",
    subtitle: String? = null,
    dismissLabel: String = "GOT IT",
    /** Measured positions of the real controls; `null` falls back to nominal geometry. */
    anchors: ControlsGuideAnchors? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)),
        exit = fadeOut(tween(250)),
    ) {
        val colors = MaterialTheme.colorScheme

        GuideBox(anchors = anchors) { geometry, compact ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (geometry.landscape) {
                            Modifier.statusBarsPadding().navigationBarsPadding()
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = if (compact) 12.dp else 16.dp),
            ) {
                // The callouts are placed by [GuideLayout] around this column, so
                // it only ever gets the space between them.
                Text(
                    text = title,
                    style = if (compact) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // ── Gesture stage: the three swipes over the photo area ───
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    GestureStage(
                        leftSwipeAction = leftSwipeAction,
                        collectionAction = collectionAction,
                        leftSwipeFolderName = leftSwipeFolderName,
                        collectionFolderName = collectionFolderName,
                        compact = compact,
                    )
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 32.dp else 36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onDismiss() }
                        .testTag("gesture_tutorial_got_it"),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.primaryContainer,
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text(
                            text = dismissLabel,
                            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp),
                            color = colors.onPrimaryContainer,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(if (compact) 2.dp else 4.dp))
            }
        }
    }
}

// ── Gesture stage ────────────────────────────────────────────────────────

/**
 * The centre of the overlay: a photo-shaped frame with the three swipe
 * directions animating outwards from it, each labelled with what it does under
 * the user's current settings, plus the two tap gestures underneath.
 */
@Composable
private fun GestureStage(
    leftSwipeAction: SwipeAction,
    collectionAction: CollectionAction,
    leftSwipeFolderName: String,
    collectionFolderName: String,
    compact: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val destructive = SwipeLabels.leftIsDestructive(leftSwipeAction)
    val leftTint = if (destructive) colors.error else colors.primary

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Derived from what is left after the up/down callouts rather than as a
        // fraction of the height: a fraction overflows on short screens, where the
        // two callouts are a large share of the available space.
        val tight = maxHeight < TIGHT_STAGE_HEIGHT
        val calloutsHeight = when {
            tight -> TIGHT_VERTICAL_CALLOUTS_HEIGHT
            compact -> COMPACT_VERTICAL_CALLOUTS_HEIGHT
            else -> VERTICAL_CALLOUTS_HEIGHT
        }
        val frameHeight = (maxHeight - calloutsHeight).coerceIn(
            minimumValue = 24.dp,
            maximumValue = if (compact) 132.dp else 260.dp,
        )
        val sideCalloutWidth = if (compact || maxWidth < 340.dp) 76.dp else 92.dp

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // ── Up ────────────────────────────────────────────────────
            SwipeCallout(
                icon = Icons.Default.KeyboardArrowUp,
                label = "Next photo",
                tint = colors.secondary,
                axis = DriftAxis.UP,
                delayMs = 0,
                compact = compact,
                inline = tight,
            )

            Spacer(modifier = Modifier.height(if (tight) 2.dp else if (compact) 4.dp else 6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // ── Left ──────────────────────────────────────────────
                SwipeCallout(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    actionIcon = leftSwipeActionIcon(leftSwipeAction),
                    label = SwipeLabels.leftLabel(leftSwipeAction),
                    description = SwipeLabels.leftDescription(leftSwipeAction, leftSwipeFolderName),
                    tint = leftTint,
                    axis = DriftAxis.LEFT,
                    delayMs = 600,
                    compact = compact,
                    small = tight,
                    modifier = Modifier.width(sideCalloutWidth),
                )

                // ── The photo the gestures act on ─────────────────────
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(frameHeight)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceContainerHigh.copy(alpha = 0.35f))
                        .border(
                            1.dp,
                            colors.outlineVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(14.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 14.dp),
                    ) {
                        TapHint(
                            icon = Icons.Default.TouchApp,
                            label = "Single tap",
                            description = "Hide the overlays",
                            compact = compact,
                        )
                        TapHint(
                            icon = Icons.Default.ZoomIn,
                            label = "Double tap or pinch",
                            description = "Zoom in, drag to pan",
                            compact = compact,
                        )
                    }
                }

                // ── Right ─────────────────────────────────────────────
                SwipeCallout(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    actionIcon = collectionActionIcon(collectionAction),
                    label = SwipeLabels.rightLabel(collectionAction),
                    description = SwipeLabels.rightDescription(
                        collectionAction,
                        collectionFolderName,
                    ),
                    tint = colors.primary,
                    axis = DriftAxis.RIGHT,
                    delayMs = 300,
                    compact = compact,
                    small = tight,
                    modifier = Modifier.width(sideCalloutWidth),
                )
            }

            Spacer(modifier = Modifier.height(if (tight) 2.dp else if (compact) 4.dp else 6.dp))

            // ── Down ──────────────────────────────────────────────────
            SwipeCallout(
                icon = Icons.Default.KeyboardArrowDown,
                label = "Previous photo",
                tint = colors.secondary,
                axis = DriftAxis.DOWN,
                delayMs = 900,
                compact = compact,
                inline = tight,
            )
        }
    }
}

/** A tap gesture explained inside the photo frame. */
@Composable
private fun TapHint(
    icon: ImageVector,
    label: String,
    description: String,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(if (compact) 16.dp else 22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A swipe direction: an arrow drifting the way the finger goes, the glyph of
 * the action it triggers, and the caption naming the effect.
 */
@Composable
private fun SwipeCallout(
    icon: ImageVector,
    label: String,
    tint: Color,
    axis: DriftAxis,
    delayMs: Int,
    actionIcon: ImageVector? = null,
    description: String? = null,
    compact: Boolean = false,
    /** Icon and label side by side, for stages too short for the stacked form. */
    inline: Boolean = false,
    /** Smaller glyph circle, for the shortest screens. */
    small: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    if (inline) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.12f))
                    .border(1.dp, tint.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DriftingIcon(icon = icon, tint = tint, axis = axis, delayMs = delayMs)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(if (small) 26.dp else if (compact) 32.dp else 44.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.12f))
                .border(1.dp, tint.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DriftingIcon(icon = icon, tint = tint, axis = axis, delayMs = delayMs)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (actionIcon != null) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Animation ────────────────────────────────────────────────────────────

private enum class DriftAxis { UP, DOWN, LEFT, RIGHT }

/** An arrow that drifts in [axis] on a loop, so the gesture reads as motion. */
@Composable
private fun DriftingIcon(icon: ImageVector, tint: Color, axis: DriftAxis, delayMs: Int) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(axis) {
        kotlinx.coroutines.delay(delayMs.toLong())
        progress.animateTo(
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        )
    }

    // Ease out at the end of each cycle so the arrow fades as it travels.
    val travel = progress.value * 9f
    val alpha = 1f - progress.value * 0.55f

    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint.copy(alpha = alpha),
        modifier = Modifier
            .size(24.dp)
            .offset {
                when (axis) {
                    DriftAxis.UP -> IntOffset(0, (-travel).dp.roundToPx())
                    DriftAxis.DOWN -> IntOffset(0, travel.dp.roundToPx())
                    DriftAxis.LEFT -> IntOffset((-travel).dp.roundToPx(), 0)
                    DriftAxis.RIGHT -> IntOffset(travel.dp.roundToPx(), 0)
                }
            },
    )
}

// ── Icon mapping (mirrors the viewer's indicators) ───────────────────────

private fun collectionActionIcon(action: CollectionAction): ImageVector = when (action) {
    CollectionAction.COPY -> Icons.Default.ContentCopy
    CollectionAction.MOVE -> Icons.AutoMirrored.Filled.DriveFileMove
}

private fun leftSwipeActionIcon(action: SwipeAction): ImageVector = when (action) {
    SwipeAction.DELETE -> Icons.Default.Delete
    SwipeAction.COPY -> Icons.Default.ContentCopy
    SwipeAction.MOVE -> Icons.AutoMirrored.Filled.DriveFileMove
}
