package com.phototok.ui.phonemode

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** TalkBack names for the controls that open the EXIF overlay and the controls guide. */
object PhoneModeTopBarDescriptions {
    const val LOGO = "Jackpix logo: show or hide camera info"
    const val INFO = "Toggle EXIF stats"
    const val HELP_SHOW = "Show controls guide"
    const val HELP_HIDE = "Hide controls guide"

    fun help(guideOpen: Boolean) = if (guideOpen) HELP_HIDE else HELP_SHOW
}

/**
 * The portrait top app bar: the logo on the left and the action buttons right-aligned.
 * Every control registers its measured bounds in [anchors] so the controls guide can label it
 * where it really is.
 *
 * @param showControls false on the landing screen, where only Settings is available.
 * @param guideOpen true while the controls guide or first-run tutorial is up (dims the bar and
 *   turns the Help button into a close button).
 */
@Composable
fun PhoneModeTopBar(
    showControls: Boolean,
    showDoneWithCard: Boolean,
    guideOpen: Boolean,
    exifOverlayOn: Boolean,
    onToggleExif: () -> Unit,
    onDoneWithCard: () -> Unit,
    onReload: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    anchors: ControlsGuideAnchors? = null,
) {
    val colors = MaterialTheme.colorScheme
    val metrics = PhoneModeChromeMetrics
    val tint = if (guideOpen) colors.onSurfaceVariant.copy(alpha = 0.4f) else colors.onSurfaceVariant
    val buttonSize = metrics.TOP_BAR_BUTTON_SIZE

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            // Outline of the bar below the status bar: the guide's callouts start under it.
            .guideAnchor(anchors, GuideAnchor.TOP_BAR)
            .padding(
                horizontal = metrics.TOP_BAR_HORIZONTAL_PADDING,
                vertical = metrics.TOP_BAR_VERTICAL_PADDING,
            )
            .testTag("phone_top_bar"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(id = com.phototok.R.drawable.ic_brand_mark),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(colors.primary),
            alpha = if (guideOpen) 0.4f else 1.0f,
            modifier = Modifier
                .size(metrics.TOP_BAR_LOGO_SIZE)
                .guideAnchor(anchors, GuideAnchor.LOGO)
                .semantics { contentDescription = PhoneModeTopBarDescriptions.LOGO }
                .testTag("top_bar_logo")
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Show or hide camera info",
                    role = Role.Button,
                    onClick = onToggleExif,
                ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showControls) {
                if (showDoneWithCard) {
                    IconButton(
                        onClick = onDoneWithCard,
                        modifier = Modifier
                            .size(buttonSize)
                            .guideAnchor(anchors, GuideAnchor.DONE_WITH_CARD)
                            .testTag("done_with_card_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SdCard,
                            contentDescription = "Done with this card",
                            tint = tint,
                        )
                    }
                }
                IconButton(
                    onClick = onReload,
                    modifier = Modifier
                        .size(buttonSize)
                        .guideAnchor(anchors, GuideAnchor.RELOAD)
                        .testTag("reload_folder_button"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Reload folder",
                        tint = tint,
                    )
                }
                IconButton(
                    onClick = onHelp,
                    modifier = Modifier
                        .size(buttonSize)
                        .guideAnchor(anchors, GuideAnchor.HELP)
                        .testTag("help_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = PhoneModeTopBarDescriptions.help(guideOpen),
                        tint = tint,
                    )
                }
                IconButton(
                    onClick = onToggleExif,
                    modifier = Modifier
                        .size(buttonSize)
                        .guideAnchor(anchors, GuideAnchor.INFO)
                        .testTag("info_exif_button"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = PhoneModeTopBarDescriptions.INFO,
                        tint = if (exifOverlayOn) colors.primary else tint,
                    )
                }
            }
            IconButton(
                onClick = onSettings,
                modifier = Modifier
                    .size(buttonSize)
                    .guideAnchor(anchors, GuideAnchor.SETTINGS)
                    .testTag("settings_button"),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = tint,
                )
            }
        }
    }
}
