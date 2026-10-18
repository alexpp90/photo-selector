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
import androidx.compose.ui.platform.LocalConfiguration
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
 * Height of the real top app bar (36dp logo + 8dp vertical padding), kept clear
 * so the coach marks label the icons rather than covering them. Must stay in
 * sync with the top bar in [PhoneModeScreen].
 */
private val TOP_BAR_HEIGHT = 12.dp

/** Height of the real [com.phototok.ui.components.ViewerBottomBar] (48dp + padding). */
private val BOTTOM_BAR_HEIGHT = 12.dp

/**
 * Space the up/down swipe callouts take above and below the photo frame
 * (44dp circle + label, twice, plus the 6dp gaps).
 */
private val VERTICAL_CALLOUTS_HEIGHT = 140.dp
private val COMPACT_VERTICAL_CALLOUTS_HEIGHT = 108.dp

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
 * bottom bar — while the middle of the screen animates the three swipe
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
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)),
        exit = fadeOut(tween(250)),
    ) {
        val colors = MaterialTheme.colorScheme
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                // A scrim rather than an opaque sheet: the controls being explained
                // stay faintly visible underneath, which is the whole point.
                .background(Color(0xFF09090B).copy(alpha = 0.70f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { /* absorb taps */ }
                .testTag("controls_guide"),
        ) {
            val compact = isLandscape || maxHeight < 600.dp || maxWidth < 360.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = if (compact) 12.dp else 16.dp),
            ) {
                // The real top app bar keeps drawing above this scrim; leave its
                // height clear so the callouts sit *under* the icons they label
                // instead of on top of them.
                Spacer(modifier = Modifier.height(if (compact) 6.dp else TOP_BAR_HEIGHT))

                TopBarCoachMarks(compact = compact)

                Spacer(modifier = Modifier.height(if (compact) 2.dp else 4.dp))

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

                // ── Bottom-bar coach marks, above the real bottom bar ────
                BottomBarCoachMarks(compact = compact)

                Spacer(modifier = Modifier.height(if (compact) 6.dp else BOTTOM_BAR_HEIGHT))
            }
        }
    }
}

// ── Coach marks anchored to the real chrome ──────────────────────────────

/**
 * Labels for the top app bar: the logo on the left, reload, help, info and settings
 * on the right — in the same order and alignment as [PhoneModeScreen] renders them.
 */
@Composable
private fun TopBarCoachMarks(compact: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CoachMark(
            icon = null,
            label = "Tap the logo",
            description = "Show or hide the camera info on each photo",
            pointsUp = true,
            compact = compact,
            modifier = Modifier.weight(1.35f),
        )
        CoachMark(
            icon = null,
            label = "Reload",
            description = "Rescan folder",
            pointsUp = true,
            compact = compact,
            modifier = Modifier.weight(0.9f),
        )
        CoachMark(
            icon = null,
            label = "Help",
            description = "This guide",
            pointsUp = true,
            compact = compact,
            modifier = Modifier.weight(0.8f),
        )
        CoachMark(
            icon = null,
            label = "Info",
            description = "EXIF overlay",
            pointsUp = true,
            compact = compact,
            modifier = Modifier.weight(0.8f),
        )
        CoachMark(
            icon = null,
            label = "Settings",
            description = "Actions & filters",
            pointsUp = true,
            compact = compact,
            modifier = Modifier.weight(1.05f),
        )
    }
}

/** Labels for the three bottom-bar buttons, in their real order. */
@Composable
private fun BottomBarCoachMarks(compact: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        CoachMark(
            icon = Icons.Default.FolderOpen,
            label = "Sources",
            description = "Pick another folder",
            pointsUp = false,
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        CoachMark(
            icon = Icons.Default.CollectionsBookmark,
            label = "Selection",
            description = "Everything you filed away",
            pointsUp = false,
            compact = compact,
            modifier = Modifier.weight(1.1f),
        )
        CoachMark(
            icon = Icons.Default.RestoreFromTrash,
            label = "Revert",
            description = "Restore last deleted photo",
            pointsUp = false,
            compact = compact,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One callout: an optional glyph, a title, a one-line description and a
 * connector line pointing towards the control it describes.
 */
@Composable
private fun CoachMark(
    icon: ImageVector?,
    label: String,
    description: String,
    pointsUp: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (pointsUp) Connector(colors.primary, compact = compact)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(if (compact) 12.dp else 14.dp),
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
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (!pointsUp) Connector(colors.primary, compact = compact)
    }
}

/** The short vertical leader line that ties a callout to its control. */
@Composable
private fun Connector(color: Color, compact: Boolean = false) {
    Box(
        modifier = Modifier
            .padding(vertical = if (compact) 2.dp else 3.dp)
            .width(1.dp)
            .height(if (compact) 8.dp else 12.dp)
            .background(color.copy(alpha = 0.5f)),
    )
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
        val calloutsHeight = if (compact) COMPACT_VERTICAL_CALLOUTS_HEIGHT else VERTICAL_CALLOUTS_HEIGHT
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
            )

            Spacer(modifier = Modifier.height(if (compact) 4.dp else 6.dp))

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
                    modifier = Modifier.width(sideCalloutWidth),
                )
            }

            Spacer(modifier = Modifier.height(if (compact) 4.dp else 6.dp))

            // ── Down ──────────────────────────────────────────────────
            SwipeCallout(
                icon = Icons.Default.KeyboardArrowDown,
                label = "Previous photo",
                tint = colors.secondary,
                axis = DriftAxis.DOWN,
                delayMs = 900,
                compact = compact,
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
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 32.dp else 44.dp)
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
