package com.phototok.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwipeRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.phototok.R

/**
 * The Pro mode switch, shared by the landing screen and the top of Settings: a `Tune` icon, the
 * name and a Switch are one toggle target (spoken "Pro mode, switch, on"), the help button next to
 * it is a separate 48 dp target. Off it is a plain card; on it carries a thin primary border and a
 * faint primary fill, so the mode is visible without a badge or a colour that means "warning".
 */
@Composable
fun ProModeRow(
    proMode: Boolean,
    onProModeChange: (Boolean) -> Unit,
    onHelp: () -> Unit,
    toggleTag: String,
    helpTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val helpName = stringResource(R.string.cd_pro_mode_help)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (proMode) colors.primary.copy(alpha = 0.08f) else colors.surfaceContainerLow)
            .border(
                width = 1.dp,
                color = if (proMode) colors.primary.copy(alpha = 0.4f) else colors.outlineVariant.copy(alpha = 0.5f),
                shape = shape,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .toggleable(value = proMode, role = Role.Switch, onValueChange = onProModeChange)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                .testTag(toggleTag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                // Decorative: the label names the row.
                contentDescription = null,
                tint = if (proMode) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(R.string.label_pro_mode),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = proMode,
                // The row is the toggle target; the Switch only draws the state.
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onPrimary,
                    checkedTrackColor = colors.primary,
                    uncheckedThumbColor = colors.onSurfaceVariant,
                    uncheckedTrackColor = colors.secondaryContainer,
                    uncheckedBorderColor = colors.secondaryContainer,
                ),
            )
        }
        NameTooltip(name = helpName) {
            IconButton(onClick = onHelp, modifier = Modifier.size(48.dp).testTag(helpTag)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = helpName,
                    tint = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What Pro mode adds: a title, three icon items and one button. Centred, never wider than 460 dp,
 * scrolls at large font scales (an exception to "no body text" recorded in DESIGN.md section 8).
 */
@Composable
fun ProModeHelpDialog(onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(max = 460.dp)
            .testTag("pro_mode_help_dialog"),
        title = { Text(stringResource(R.string.dialog_title_pro_mode)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProModeHelpItem(Icons.Default.CameraAlt, stringResource(R.string.label_pro_camera_info))
                ProModeHelpItem(Icons.Default.SwipeRight, stringResource(R.string.label_pro_swipes_folders))
                ProModeHelpItem(Icons.Default.PhotoLibrary, stringResource(R.string.label_raw_jpeg_pair))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("pro_mode_help_got_it")) {
                Text(stringResource(R.string.action_got_it))
            }
        },
        containerColor = colors.surfaceContainerHigh,
    )
}

@Composable
private fun ProModeHelpItem(icon: ImageVector, label: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
        )
    }
}
