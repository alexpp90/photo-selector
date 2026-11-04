package com.phototok.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.phototok.R

/**
 * Where a swipe files its photos: "Default" — named with what it means, the [defaultSubfolder]
 * inside the source folder — or a folder of the user's choice. Tapping a folder row in Settings
 * opens this instead of going straight to the system picker, so "Default" is never unexplained.
 * Centred, at most 460 dp wide, scrolls at large font scales, Cancel is the neutral action.
 */
@Composable
fun FolderTargetDialog(
    title: String,
    defaultSubfolder: String,
    isCustom: Boolean,
    onUseDefault: () -> Unit,
    onChooseFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(max = 460.dp)
            .testTag("folder_target_dialog"),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                FolderTargetOption(
                    label = stringResource(R.string.setting_summary_default),
                    detail = stringResource(R.string.setting_summary_in_source_folder, defaultSubfolder),
                    selected = !isCustom,
                    onClick = onUseDefault,
                    tag = "folder_target_default",
                )
                FolderTargetOption(
                    label = stringResource(R.string.action_choose_folder),
                    detail = if (isCustom) stringResource(R.string.setting_summary_custom) else null,
                    selected = isCustom,
                    onClick = onChooseFolder,
                    tag = "folder_target_choose",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("folder_target_cancel")) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = colors.surfaceContainerHigh,
    )
}

@Composable
private fun FolderTargetOption(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(
            selected = selected,
            // The row is the target; the RadioButton only draws the state.
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.primary,
                unselectedColor = colors.onSurfaceVariant,
            ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            if (detail != null) {
                Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}
