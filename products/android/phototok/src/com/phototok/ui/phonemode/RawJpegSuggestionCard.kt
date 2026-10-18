package com.phototok.ui.phonemode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Modal dialog presented when a folder contains matching RAW and JPEG pairs of the same photos.
 *
 * No explanatory paragraph: the title names the situation and each button names
 * its outcome (DESIGN.md, "Copy & messaging"). Only the recommended option carries
 * a one-line subline.
 * 0. 1 card per shot (recommended; JPEG card with RAW+JPG badge, actions hit both files).
 * 1. Show both, linked (move/copy/delete applies to both files at once).
 * 2. Show only RAW.
 * 3. Show only JPEG.
 * 4. Leave as is (neutral; keeps all files visible and separate).
 * Back and tap-outside dismiss like "Leave as is", so there is no second close button.
 *
 * Rendered in a dialog window so it is centered and can never be covered or occluded
 * by bottom action bars, navigation buttons, or side panels in either portrait or landscape.
 */
@Composable
fun RawJpegSuggestionDialog(
    visible: Boolean,
    onFilterRaw: () -> Unit,
    onFilterJpg: () -> Unit,
    onEnableMoveRelatedFiles: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onShowOnePerShot: () -> Unit = {},
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val colors = MaterialTheme.colorScheme

        Surface(
            modifier = modifier
                .widthIn(min = 280.dp, max = 460.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .testTag("raw_jpeg_suggestion_dialog")
                .testTag("raw_jpeg_suggestion_card"),
            shape = RoundedCornerShape(20.dp),
            color = colors.surfaceContainerHigh,
            border = BorderStroke(
                1.dp,
                colors.primary.copy(alpha = 0.35f),
            ),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.primary.copy(alpha = 0.12f))
                            .border(1.dp, colors.primary.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Style,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Text(
                        text = "RAW + JPEG pairs",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Option 0 (recommended, primary): one card per shot
                Surface(
                    onClick = onShowOnePerShot,
                    shape = RoundedCornerShape(12.dp),
                    color = colors.primaryContainer.copy(alpha = 0.7f),
                    border = BorderStroke(1.5.dp, colors.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("one_per_shot_button"),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "1 card per shot",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Swipes act on both files",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onPrimaryContainer.copy(alpha = 0.9f),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Option 1: keep both visible, linked
                OutlinedButton(
                    onClick = onEnableMoveRelatedFiles,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("apply_both_button"),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Show both, linked", style = MaterialTheme.typography.labelLarge)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Show only",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Option 2 & 3: Show only RAW or JPEG
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onFilterRaw,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("filter_raw_button"),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("RAW", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onFilterJpg,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("filter_jpg_button"),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("JPEG", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Option 4: neutral choice
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dont_change_settings_button")
                        .testTag("keep_settings_button"),
                ) {
                    Text(
                        text = "Leave as is",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/**
 * Backward-compatible alias for [RawJpegSuggestionDialog].
 */
@Composable
fun RawJpegSuggestionCard(
    visible: Boolean,
    onFilterRaw: () -> Unit,
    onFilterJpg: () -> Unit,
    onEnableMoveRelatedFiles: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onShowOnePerShot: () -> Unit = {},
) = RawJpegSuggestionDialog(
    visible = visible,
    onFilterRaw = onFilterRaw,
    onFilterJpg = onFilterJpg,
    onEnableMoveRelatedFiles = onEnableMoveRelatedFiles,
    onDismiss = onDismiss,
    modifier = modifier,
    onShowOnePerShot = onShowOnePerShot,
)
