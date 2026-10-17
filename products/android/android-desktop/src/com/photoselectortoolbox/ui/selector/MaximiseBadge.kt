package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.ui.theme.FocusCyan
import com.photoselectortoolbox.ui.theme.StudioCanvas
import com.photoselectortoolbox.ui.theme.Zinc50

/** Edge of the maximise badge. */
val MaximiseBadgeSize: Dp = 44.dp

/** Distance from a frame's corner to the badge, so the pill can clear the badge band. */
val MaximiseBadgeInset: Dp = 6.dp

/**
 * The one control drawn on a photograph.
 *
 * Three equal frames abreast or stacked are limited by the display, not by the
 * chrome around them — so "as large as possible" for a *single* frame has to be
 * a mode rather than a layout constant. This badge is how the user gets there
 * without learning a gesture: 44 dp, in a corner, on every frame, doing the
 * obvious thing.
 *
 * It is permitted over the image where nothing else is because it is small,
 * fixed, and in a corner rather than across the middle — and because the
 * alternative is a control that costs every frame height. The corner differs by
 * frame: bottom-right on the current one, bottom-left on the neighbours, whose
 * right edge belongs to the value overlay. Two interactive controls never share
 * bounds, and a UI test asserts it.
 */
@Composable
fun MaximiseBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(MaximiseBadgeSize)
            .clip(RoundedCornerShape(8.dp))
            .background(StudioCanvas.copy(alpha = 0.72f))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (active) {
                    "Return to comparing all three frames"
                } else {
                    "Fill the screen with this frame"
                }
            }
            .testTag("maximise_badge"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (active) {
                Icons.Default.CloseFullscreen
            } else {
                Icons.Default.OpenInFull
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (active) FocusCyan else Zinc50,
        )
    }
}
