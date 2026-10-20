package com.photoselectortoolbox.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.WbShade
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.scoring.ScoreMetric
import com.photoselectortoolbox.ui.selector.vector
import com.photoselectortoolbox.ui.theme.BrandAccentGlow
import com.photoselectortoolbox.ui.theme.ScoreBad
import com.photoselectortoolbox.ui.theme.ScoreGood
import com.photoselectortoolbox.ui.theme.ScoreWarn
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc500
import com.photoselectortoolbox.ui.theme.Zinc700
import com.photoselectortoolbox.ui.theme.Zinc800

/**
 * The colour a goodness value gets on the direction bar and on filmstrip dots.
 *
 * Thresholds are deliberately demanding at the top: a frame only earns green
 * when it is clearly good, so a wall of green does not stop meaning anything.
 */
fun goodnessColor(goodness: Double): Color = when {
    goodness >= 0.62 -> ScoreGood
    goodness >= 0.33 -> ScoreWarn
    else -> ScoreBad
}

/** Minimum visible fraction of the bar, so a zero-goodness chip still reads as a bar. */
private const val BAR_FLOOR = 0.06f

/** Height reserved for a chip row, so rows do not jump as scores arrive. */
val ScoreChipRowMinHeight = 38.dp

/**
 * A single scan metric: icon, label, value, and a 2dp bar underneath.
 *
 * The bar is the point of the component. Sharpness runs 5–100, noise runs
 * 0.2–7 and clipping is a percentage — no user can compare those numbers by
 * eye across three frames. Every metric's bar is normalised so that longer and
 * greener is always better, which turns "which of these three is best" into a
 * glance instead of arithmetic.
 *
 * [isBestOfVisible] marks the frame holding the best value for this metric
 * among the frames currently on screen, with a small indigo dot.
 *
 * Set [compact] where horizontal space is genuinely tight (fullscreen overlay,
 * thumbnails) to drop the text label; the accessibility description still
 * carries the full name, the direction and the best-of marker.
 */
@Composable
fun ScoreChip(
    metric: ScoreMetric,
    value: Double?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    isBestOfVisible: Boolean = false,
) {
    if (value == null) return

    val goodness = metric.goodness(value)
    // Animating the width rather than snapping it keeps the eye on the frame
    // during navigation; 180ms matches the design's bar timing.
    val barFraction by animateFloatAsState(
        targetValue = BAR_FLOOR + goodness.toFloat() * (1f - BAR_FLOOR),
        animationSpec = tween(durationMillis = 180),
        label = "scoreBar",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Zinc800)
            .border(1.dp, Zinc700, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics {
                contentDescription = metric.accessibilityLabel(value, isBestOfVisible)
            },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = metric.icon.vector(),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Zinc400,
            )
            if (!compact) {
                Text(
                    text = metric.shortLabel,
                    fontSize = 11.sp,
                    color = Zinc400,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = metric.format(value),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = Zinc400,
                maxLines = 1,
            )
            if (isBestOfVisible) {
                Text(
                    text = "◈",
                    fontSize = 10.sp,
                    color = BrandAccentGlow,
                )
            }
        }

        // Direction bar: track plus a tinted fill whose length is the goodness.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Zinc700),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(barFraction)
                    .height(2.dp)
                    .background(goodnessColor(goodness)),
            )
        }
    }
}

/**
 * Placeholder shown in place of the chip row for a frame that has not been
 * scanned. A dashed outline reads as "nothing here yet" rather than as a
 * metric whose value happens to be zero.
 */
@Composable
fun NotScannedChip(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .dashedRoundedBorder(color = Zinc700, cornerRadius = 8.dp.value)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { contentDescription = "Not scanned" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Radar,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = Zinc500,
        )
        Text(
            text = "Not scanned",
            fontSize = 11.sp,
            color = Zinc500,
            maxLines = 1,
        )
    }
}

/** A 1dp dashed rounded outline, drawn directly because Compose has no dashed border. */
private fun Modifier.dashedRoundedBorder(color: Color, cornerRadius: Float): Modifier =
    drawBehind {
        val stroke = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(3.dp.toPx(), 3.dp.toPx()),
                0f,
            ),
        )
        val radiusPx = cornerRadius * density
        val radius = CornerRadius(radiusPx, radiusPx)
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    topLeftCornerRadius = radius,
                    topRightCornerRadius = radius,
                    bottomRightCornerRadius = radius,
                    bottomLeftCornerRadius = radius,
                )
            )
        }
        drawPath(path = path, color = color, style = stroke)
    }

/**
 * Every metric present in [scores], wrapped so it never overflows its parent.
 *
 * [bestMetrics] names the metrics for which *this* frame is the best of the
 * frames on screen; pass the result of [bestMetricsFor].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoreChipRow(
    scores: ScanResult?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    bestMetrics: Set<ScoreMetric> = emptySet(),
    showNotScanned: Boolean = false,
) {
    val present = ScoreMetric.present(scores)
    if (present.isEmpty()) {
        if (showNotScanned) NotScannedChip(modifier = modifier)
        return
    }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        present.forEach { (metric, value) ->
            ScoreChip(
                metric = metric,
                value = value,
                compact = compact,
                isBestOfVisible = metric in bestMetrics,
            )
        }
    }
}

/**
 * For each metric, work out which of [frames] holds the best value, and return
 * the set of metrics won by the frame at [frameIndex].
 *
 * Computed across the visible frames rather than the whole folder on purpose:
 * the question the user is answering is "which of *these* do I keep", so the
 * marker has to mean "best of what you can see", not "best of 842".
 */
fun bestMetricsFor(frames: List<ScanResult?>, frameIndex: Int): Set<ScoreMetric> =
    ScoreMetric.entries
        .filter { metric ->
            metric.bestIndexOf(frames.map { result -> result?.let { metric.valueOf(it) } }) ==
                frameIndex
        }
        .toSet()

/** Reserves the chip row's height where a frame has no chips, so rows do not jump. */
@Composable
fun ScoreChipRowPlaceholder(modifier: Modifier = Modifier) {
    Spacer(modifier = modifier.height(ScoreChipRowMinHeight).width(1.dp))
}
