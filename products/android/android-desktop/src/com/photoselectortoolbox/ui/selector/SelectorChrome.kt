package com.photoselectortoolbox.ui.selector

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.scoring.ScoreMetric
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.StudioBorder
import com.photoselectortoolbox.ui.theme.StudioBorderActive
import com.photoselectortoolbox.ui.theme.StudioCard
import com.photoselectortoolbox.ui.theme.Zinc600

// ── Image tile ──────────────────────────────────────────────────────────────

/**
 * A photograph, and nothing else.
 *
 * The tile does not decide its own size. [FrameGeometry] computes one DpSize
 * and every frame is given that exact value, because a tile that resolves its
 * own constraints will resolve them differently for a wide frame than a narrow
 * one — and three frames at subtly different sizes cannot be compared, which is
 * the whole task. See the 2026-07-27 entry in `ai/memory/palette.md`.
 *
 * The active frame is marked only by a 2dp lavender accent border. Neighbours are never
 * dimmed or shrunk: a dimmed frame cannot be judged for exposure, and a smaller
 * one cannot be judged for sharpness.
 */
@Composable
fun ImageTile(
    image: ImageItem?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    emptyLabel: String = "No image",
    contentScale: ContentScale = ContentScale.Fit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val borderColor by animateColorAsState(
        targetValue = when {
            isCurrent -> BrandAccent
            hovered -> StudioBorderActive
            else -> StudioBorder
        },
        animationSpec = tween(150),
        label = "tileBorder",
    )
    val borderWidth = if (isCurrent) 2.dp else 1.dp
    val shape = RoundedCornerShape(4.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(StudioCard)
            .border(borderWidth, borderColor, shape)
            .hoverable(interactionSource)
            .then(
                if (image != null) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .tileClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            AsyncImage(
                model = image.uri,
                contentDescription = image.fileName,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            Text(
                text = emptyLabel,
                fontSize = 12.sp,
                color = Zinc600,
            )
        }
    }
}

/**
 * Reports where the pointer last went down, without consuming the event.
 *
 * Used to open the context menu under the finger or cursor. Observing on the
 * Initial pass matters: the tile's own click handling runs on Main, so reading
 * the position first leaves the tap itself untouched.
 */
fun Modifier.trackPointerPosition(onPosition: (Offset) -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.firstOrNull()?.let { change ->
                    if (change.pressed) onPosition(change.position)
                }
            }
        }
    }

/** Best-of-three metrics for each of [frames], indexed the same way. */
fun bestMetricSets(frames: List<ScanResult?>): List<Set<ScoreMetric>> =
    frames.indices.map { index ->
        ScoreMetric.entries
            .filter { metric ->
                metric.bestIndexOf(frames.map { r -> r?.let { metric.valueOf(it) } }) == index
            }
            .toSet()
    }

/**
 * Tap to act, long-press for the context menu.
 *
 * Falls back to a plain click when no long-press handler is supplied, so a
 * tile without a menu does not pay for gesture detection it will not use.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.tileClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier = if (onLongClick == null) {
    this.clickable(onClick = onClick)
} else {
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
}
