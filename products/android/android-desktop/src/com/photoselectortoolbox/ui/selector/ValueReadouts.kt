package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.format.ExifFormatter
import com.photoselectortoolbox.domain.scoring.ScoreMetric
import com.photoselectortoolbox.domain.scoring.ScoreMetricIcon
import com.photoselectortoolbox.ui.theme.FocusCyan
import com.photoselectortoolbox.ui.theme.FocusCyanGlow
import com.photoselectortoolbox.ui.theme.ScoreBad
import com.photoselectortoolbox.ui.theme.ScoreGood
import com.photoselectortoolbox.ui.theme.ScoreWarn
import com.photoselectortoolbox.ui.theme.StudioBorder
import com.photoselectortoolbox.ui.theme.StudioCanvas
import com.photoselectortoolbox.ui.theme.WarningAmber
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc50
import com.photoselectortoolbox.ui.theme.Zinc600

/**
 * The glyph for a metric.
 *
 * Exhaustive on purpose. [ScoreMetric] is kept free of Compose types so its
 * wording and normalisation stay unit-testable on the JVM, which means the
 * choice of vector has to live here — and an exhaustive `when` is what makes
 * "every metric has exactly one glyph" a compile-time fact rather than a
 * convention. Adding a metric without choosing a glyph does not build.
 *
 * This matters more than it looks: on the Previous and Next frames the glyph is
 * the *only* thing identifying which metric a number belongs to.
 */
fun ScoreMetricIcon.vector(): ImageVector = when (this) {
    ScoreMetricIcon.FOCUS -> Icons.Default.CenterFocusStrong
    ScoreMetricIcon.GRAIN -> Icons.Default.Grain
    ScoreMetricIcon.HIGHLIGHT -> Icons.Default.WbSunny
    ScoreMetricIcon.SHADOW -> Icons.Default.NightlightRound
    ScoreMetricIcon.AESTHETIC -> Icons.Outlined.AutoAwesome
}

/** Tint for a goodness value, matching the thresholds used everywhere else. */
private fun goodnessColor(goodness: Double): Color = when {
    goodness >= 0.62 -> ScoreGood
    goodness >= 0.33 -> ScoreWarn
    else -> ScoreBad
}

/**
 * The current frame's readouts: filename, exposure, then a named row per metric.
 *
 * Full verbosity here because this block does double duty — it describes the
 * frame being decided on, *and* it is the legend for the two compact overlays
 * below it. A user learns "reticle means sharpness" here and reads it there.
 *
 * It sits in the letterbox column to the left of the centred frame, which costs
 * the photographs nothing (see [FrameGeometry]).
 */
@Composable
fun CurrentFrameReadout(
    image: ImageItem?,
    bestMetrics: Set<ScoreMetric>,
    modifier: Modifier = Modifier,
    folderName: String? = null,
    burstLabel: String? = null,
) {
    Column(
        modifier = modifier.padding(horizontal = 10.dp).testTag("current_readout"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The folder name and the burst chip used to live in a 44dp app bar.
        // They are context for the frame being judged, so they sit with it —
        // and the bar they came from cost 29dp of height on all three frames.
        if (folderName != null || burstLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                folderName?.let {
                    Text(
                        text = it,
                        fontSize = 11.sp,
                        color = Zinc400,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                burstLabel?.let {
                    Text(
                        text = it,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = FocusCyan,
                    )
                }
            }
        }

        if (image == null) {
            Text(text = "No image", fontSize = 13.sp, color = Zinc600)
            return@Column
        }

        Text(
            text = image.fileName,
            fontSize = 14.sp,
            color = Zinc50,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        ExifFormatter.summaryLine(image.exifData)?.let { line ->
            Text(
                text = line,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                color = Zinc400,
                maxLines = 2,
            )
        }

        if (image.exifData?.isFallback == true) {
            LimitedMetadataMarker()
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(StudioBorder))

        val scores = image.scanResult
        if (scores == null) {
            NotScannedRow()
            return@Column
        }

        ScoreMetric.entries.forEach { metric ->
            val value = metric.valueOf(scores) ?: return@forEach
            NamedMetricRow(
                metric = metric,
                value = value,
                isBest = metric in bestMetrics,
            )
        }
    }
}

/** One `icon · name · value` line with its goodness bar beneath. */
@Composable
private fun NamedMetricRow(
    metric: ScoreMetric,
    value: Double,
    isBest: Boolean,
) {
    val goodness = metric.goodness(value)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = metric.accessibilityLabel(value, isBest)
            },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = metric.icon.vector(),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Zinc400,
            )
            Text(
                text = metric.displayName,
                fontSize = 12.5.sp,
                color = Zinc400,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = metric.format(value),
                fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace,
                color = Zinc50,
                textAlign = TextAlign.End,
            )
            // The best-of-visible marker keeps its existing rules: absent below
            // two values, ties to the earliest frame, so it cannot flicker.
            if (isBest) {
                Text(
                    text = "◈",
                    fontSize = 11.sp,
                    color = FocusCyanGlow,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
        GoodnessBar(
            goodness = goodness,
            modifier = Modifier.padding(start = 24.dp),
        )
    }
}

/**
 * The 2 dp direction bar.
 *
 * An 8-segment LED meter with a small non-zero floor so a worst-case value still
 * reads as "a bar at the bottom of its range" rather than as missing data.
 */
@Composable
private fun GoodnessBar(
    goodness: Double,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 2.dp,
) {
    val totalSegments = 8
    val activeCount = ((0.06f + goodness.toFloat() * 0.94f) * totalSegments).toInt().coerceIn(1, totalSegments)
    val activeColor = goodnessColor(goodness)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until totalSegments) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .background(
                        if (i < activeCount) activeColor else StudioBorder,
                        RoundedCornerShape(0.5.dp),
                    ),
            )
        }
    }
}

/**
 * A neighbour's data, on the neighbour.
 *
 * Same information and the same vertical order as [CurrentFrameReadout] —
 * identity, exposure, rule, then metrics in `ScoreMetric` declaration order —
 * compressed to icon and value. The order comes from the enum rather than from
 * this composable precisely so the three frames cannot disagree about it; an
 * overlay that ordered its metrics differently would be unreadable at a glance,
 * which is the only way it is ever read.
 *
 * Metric names are absent because the block beside the current frame is the
 * legend. The full name, value and direction are still in every row's
 * `contentDescription`.
 */
@Composable
fun NeighbourValueOverlay(
    image: ImageItem?,
    position: Int?,
    total: Int,
    bestMetrics: Set<ScoreMetric>,
    isOverlaid: Boolean,
    modifier: Modifier = Modifier,
) {
    if (image == null) return

    Column(
        modifier = modifier
            .width(FrameGeometry.OverlayWidth)
            .then(
                if (isOverlaid) {
                    // A flat panel, not a gradient scrim. A gradient across the
                    // right of a photograph reads as part of the photograph,
                    // which is exactly the mistake the fullscreen top bar
                    // already documents.
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(StudioCanvas.copy(alpha = 0.85f))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag("neighbour_overlay"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = image.fileName,
            fontSize = 11.sp,
            color = Zinc50,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Position leads the exposure block: it is the one number that says
        // *where* this frame is, and it costs no extra line.
        val exposureLines = ExifFormatter.overlayLines(image.exifData)
        exposureLines.forEachIndexed { index, line ->
            val tokens = line.split(EXPOSURE_SEPARATOR)
            TokenLine(
                tokens = if (index == 0 && position != null) {
                    listOf(position.toString()) + tokens
                } else {
                    tokens
                },
            )
        }
        if (exposureLines.isEmpty() && position != null) {
            Text(
                text = "$position / $total",
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = Zinc400,
            )
        }

        if (image.exifData?.isFallback == true) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = "Limited metadata",
                modifier = Modifier.size(10.dp),
                tint = WarningAmber,
            )
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(StudioBorder))

        val scores = image.scanResult
        if (scores == null) {
            NotScannedRow(compact = true)
            return@Column
        }

        ScoreMetric.entries.forEach { metric ->
            val value = metric.valueOf(scores) ?: return@forEach
            CompactMetricRow(
                metric = metric,
                value = value,
                isBest = metric in bestMetrics,
            )
        }
    }
}

private const val EXPOSURE_SEPARATOR = " · "

/**
 * One line of exposure tokens (`1/250s`, `f/2.8`, `50mm`, `ISO 400`) that is
 * never wrapped and never cut mid-token (AD-16).
 *
 * A token is the unit of meaning: `f/2` and `1/2` are both valid readings of a
 * clipped `f/2.8` and `1/250s`, which is worse than a missing value. When the
 * line does not fit its column — a large font scale, a long position — the
 * *last* token is dropped and the line re-laid out, until it fits. Position
 * leads and exposure follows in the order shutter, aperture, focal length, ISO,
 * so what goes first is what a cull cares about least.
 */
@Composable
private fun TokenLine(tokens: List<String>) {
    var shown by remember(tokens) { mutableIntStateOf(tokens.size) }
    Text(
        text = tokens.take(shown).joinToString(EXPOSURE_SEPARATOR),
        fontSize = 9.5.sp,
        fontFamily = FontFamily.Monospace,
        color = Zinc400,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { layout ->
            if (layout.didOverflowWidth && shown > 1) shown -= 1
        },
    )
}

/** One `icon · value` line with its goodness bar, for a neighbour overlay. */
@Composable
private fun CompactMetricRow(
    metric: ScoreMetric,
    value: Double,
    isBest: Boolean,
) {
    val goodness = metric.goodness(value)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = metric.accessibilityLabel(value, isBest) },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = metric.icon.vector(),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Zinc400,
            )
            Text(
                text = metric.format(value),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = Zinc50,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (isBest) {
                Text(
                    text = "◈",
                    fontSize = 9.sp,
                    color = FocusCyanGlow,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
        GoodnessBar(goodness = goodness, modifier = Modifier.padding(start = 20.dp))
    }
}

/** One dashed row rather than five empty ones. */
@Composable
private fun NotScannedRow(compact: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { contentDescription = "This frame has not been scanned" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Not scanned",
            fontSize = if (compact) 10.sp else 12.sp,
            color = Zinc600,
        )
    }
}

/** Says the EXIF came from the fallback reader, so gaps are ours not the file's. */
@Composable
private fun LimitedMetadataMarker() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = WarningAmber,
        )
        Text(text = "limited metadata", fontSize = 11.sp, color = WarningAmber)
    }
}
