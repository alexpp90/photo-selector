package com.photoselectortoolbox.ui.selector

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoselectortoolbox.ui.theme.BrandAccentText
import com.photoselectortoolbox.ui.theme.StudioBorder
import com.photoselectortoolbox.ui.theme.StudioPanel
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc50
import com.photoselectortoolbox.viewmodel.SelectorHintUi

/** How long an explanation stays up before it fades on its own. */
private const val AUTO_DISMISS_MILLIS = 9_000L

/**
 * The one-time explanation of what an action just did.
 *
 * Placed by the caller in the flank beside the current frame, never over one and
 * never above or below the image region — height is the binding constraint on
 * this screen (§7.2 of `DESIGN.md`) and an explanation that shrinks the
 * photographs to describe them has made the screen worse. `SelectorGuidance`
 * decides whether there is a flank wide enough; where there is not, the card is
 * not drawn and the hint is not spent.
 *
 * A card rather than a snackbar for two reasons: the bottom centre is already
 * occupied by the confirmation of the action this is explaining, and the text
 * names a setting, which needs more than a three-second flash mid-cull.
 *
 * All wording arrives resolved in [SelectorHintUi] — built in the ViewModel from
 * the user's current settings — so no verb, folder name or key can be
 * hard-coded next to a `Text` here.
 */
@Composable
fun SelectorHintCard(
    hint: SelectorHintUi?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Restart the timer whenever a *different* explanation arrives.
    LaunchedEffect(hint?.hint) {
        if (hint != null) {
            kotlinx.coroutines.delay(AUTO_DISMISS_MILLIS)
            onDismiss()
        }
    }

    // Hold the last one so the card still has words to draw while it fades out,
    // by which time `hint` is already null.
    var last by remember { mutableStateOf(hint) }
    if (hint != null) last = hint

    AnimatedVisibility(
        visible = hint != null,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(150)),
        modifier = modifier,
    ) {
        val current = last ?: return@AnimatedVisibility

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(StudioPanel)
                .border(1.dp, StudioBorder, RoundedCornerShape(10.dp))
                .padding(12.dp)
                .semantics { contentDescription = "${current.title}. ${current.message}" }
                .testTag("selector_hint_card"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = current.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Zinc50,
            )
            Text(text = current.message, fontSize = 11.sp, color = Zinc400)
            Text(
                text = "Got it",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = BrandAccentText,
                modifier = Modifier
                    .align(Alignment.End)
                    // 48 dp target (AD-8): the minimum wraps the clickable, so the
                    // 48 dp square is the control and the text keeps its size.
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(6.dp))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("selector_hint_dismiss"),
            )
        }
    }
}
