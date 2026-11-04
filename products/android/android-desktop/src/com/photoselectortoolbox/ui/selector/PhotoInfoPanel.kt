package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.format.ExifFormatter
import com.photoselectortoolbox.ui.theme.StudioCanvas
import com.photoselectortoolbox.ui.theme.Zinc300
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc50

internal val InfoPanelShape = RoundedCornerShape(6.dp)
internal const val InfoPanelAlpha = 0.85f

/** The panel is as wide as its content, within these bounds. */
internal val InfoPanelMinWidth = 280.dp
internal val InfoPanelMaxWidth = 420.dp

/** ... and never more than this share of the surface it sits on, so it cannot become a bar. */
internal const val InfoPanelMaxShare = 0.35f

/**
 * The flat info card laid over a photograph: name and position, whole exposure
 * tokens, lens.
 *
 * One composable for both surfaces that put the facts *on* the photograph — the
 * fullscreen viewer and the maximised frame — so they cannot drift apart. It is
 * exactly as wide as its content, clamped to [InfoPanelMinWidth]..[InfoPanelMaxWidth]
 * and to [InfoPanelMaxShare] of [availableWidth] (the width of the surface it
 * sits on: the window in fullscreen, the frame when maximised), whichever is
 * smaller. Flat and 85 % opaque, never a gradient scrim: a gradient over the top of
 * a frame darkens the sky in a way the photographer will read as part of the photograph.
 *
 * Name and position lead; the exposure line follows as whole tokens that wrap only
 * between tokens (see [ExposureFlow]); the lens closes the panel on its own line
 * and ellipsises. Zinc300 rather than 400 for the values: this sits over whatever
 * the sky is doing, and 400 on 85 % canvas is too faint on a bright one.
 *
 * The text is one merged node for a screen reader. The panel takes no pointer
 * input itself; [trailing] (the fullscreen pin) is the only interactive part.
 *
 * Test tags are `<tagPrefix>_info_overlay`, `_file_name`, `_position`, `_exif`, `_lens`.
 */
@Composable
internal fun PhotoInfoPanel(
    image: ImageItem?,
    position: Int,
    total: Int,
    availableWidth: Dp,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val maxPanelWidth = minOf(InfoPanelMaxWidth, availableWidth * InfoPanelMaxShare)
    val minPanelWidth = minOf(InfoPanelMinWidth, maxPanelWidth)
    Row(
        modifier = modifier
            .widthIn(min = minPanelWidth, max = maxPanelWidth)
            .clip(InfoPanelShape)
            .background(StudioCanvas.copy(alpha = InfoPanelAlpha))
            .testTag("${tagPrefix}_info_overlay"),
        verticalAlignment = Alignment.Top,
    ) {
        // One item for a screen reader: name, position, exposure, lens.
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = if (trailing == null) 10.dp else 0.dp)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The name is what gives way; the position keeps its width.
                Text(
                    text = image?.fileName.orEmpty(),
                    fontSize = 14.sp,
                    color = Zinc50,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag("${tagPrefix}_file_name"),
                )
                Text(
                    text = "$position / $total",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Zinc400,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag("${tagPrefix}_position"),
                )
            }

            ExifFormatter.summaryLine(image?.exifData)?.let { line ->
                ExposureFlow(
                    labels = exposureTokenLabels(line),
                    color = Zinc300,
                    containerTag = "${tagPrefix}_exif",
                )
            }
            ExifFormatter.lensLine(image?.exifData)?.let { lens ->
                Text(
                    text = lens,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Zinc300,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_lens"),
                )
            }
        }
        trailing?.invoke()
    }
}
