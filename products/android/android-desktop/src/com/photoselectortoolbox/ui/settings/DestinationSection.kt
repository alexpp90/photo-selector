package com.photoselectortoolbox.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.domain.curation.DestinationMode
import com.photoselectortoolbox.domain.format.DestinationLabels
import com.photoselectortoolbox.ui.components.handCursor
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.ScoreBad
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc700
import com.photoselectortoolbox.viewmodel.DestinationUiState
import com.photoselectortoolbox.viewmodel.SettingsUiState

/**
 * Settings → **Where photos go** (`[USER 2026-11-04]`: "I do not understand where the pictures
 * go when they get moved"): Working folder or Custom folder as radio rows, the path the next
 * Copy or Move will use under the chosen one, the Selection folder names, and the RAW and JPEG
 * switch.
 *
 * Stateless apart from the two name fields' editing text: everything else is [uiState], and
 * every action is a callback, so the screen hosts it with the real ViewModel and a test hosts it
 * with plain state. The path line is [DestinationLabels.pathLine] of the preview the ViewModel
 * built from the same plan Copy and Move use.
 *
 * - Working folder: selecting it clears the custom folder ([onUseWorkingFolder]).
 * - Custom folder: with none chosen, tapping the row opens the picker ([onChooseCustom]); a
 *   cancelled picker changes nothing, so the choice stays Working folder. Choose / Change opens it
 *   too.
 * - A custom folder that will not take files shows a warning glyph, "Folder unavailable" and why.
 */
@Composable
internal fun DestinationSection(
    uiState: SettingsUiState,
    onUseWorkingFolder: () -> Unit,
    onChooseCustom: () -> Unit,
    onSaveSelectionName: (String) -> Unit,
    onSaveSecondPassName: (String) -> Unit,
    onSortingChange: (Boolean) -> Unit,
) {
    val destination = uiState.destination
    var editingFolderName by remember(uiState.selectionFolderName) {
        mutableStateOf(uiState.selectionFolderName)
    }
    var editingSecondaryFolderName by remember(uiState.secondarySelectionFolderName) {
        mutableStateOf(uiState.secondarySelectionFolderName)
    }

    SettingsSection(title = DestinationLabels.SECTION_TITLE) {
        DestinationRadioRow(
            label = DestinationLabels.WORKING_FOLDER,
            selected = destination.mode == DestinationMode.WORKING_FOLDER,
            onSelect = onUseWorkingFolder,
            tag = "destination_working",
        )
        if (destination.mode == DestinationMode.WORKING_FOLDER) {
            DestinationDetail(destination)
        }

        DestinationRadioRow(
            label = DestinationLabels.CUSTOM_FOLDER,
            selected = destination.mode == DestinationMode.CUSTOM,
            // Already Custom: nothing to select, Change is the way to another folder.
            onSelect = { if (destination.customFolder == null) onChooseCustom() },
            tag = "destination_custom",
            trailing = {
                TextButton(
                    onClick = onChooseCustom,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .testTag("destination_choose"),
                ) {
                    Text(
                        text = if (destination.customFolder == null) DestinationLabels.CHOOSE else DestinationLabels.CHANGE,
                        color = BrandAccent,
                    )
                }
            },
        )
        if (destination.mode == DestinationMode.CUSTOM) {
            DestinationDetail(destination)
        }

        HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = "Selection Subfolder Name",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            NameField(
                value = editingFolderName,
                onValueChange = { editingFolderName = it },
                placeholder = "Selection",
                tag = "selection_name_field",
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (editingFolderName != uiState.selectionFolderName && editingFolderName.isNotBlank()) {
                TextButton(
                    onClick = { onSaveSelectionName(editingFolderName) },
                    modifier = Modifier.align(Alignment.End).handCursor(),
                ) {
                    Text("Save", color = BrandAccent)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Second-Pass Selection Subfolder Name",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Used automatically when re-culling an existing Selection folder",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            NameField(
                value = editingSecondaryFolderName,
                onValueChange = { editingSecondaryFolderName = it },
                placeholder = "Final",
                tag = "second_pass_name_field",
            )
            if (editingSecondaryFolderName != uiState.secondarySelectionFolderName &&
                editingSecondaryFolderName.isNotBlank()
            ) {
                TextButton(
                    onClick = { onSaveSecondPassName(editingSecondaryFolderName) },
                    modifier = Modifier.align(Alignment.End).handCursor(),
                ) {
                    Text("Save", color = BrandAccent)
                }
            }
        }

        HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

        // Renamed from "File Sorting", its description dropped: the path line above ends
        // `› RAW · JPEG` exactly while this is on, which says it better than a sentence.
        SettingsToggleItem(
            title = DestinationLabels.RAW_AND_JPEG_FOLDERS,
            checked = uiState.sortingEnabled,
            onCheckedChange = onSortingChange,
            icon = Icons.Default.Sort,
        )
    }
}

/**
 * A whole 48 dp-tall row that is the radio button: the circle is its picture, the row is its
 * target and its merged semantics (`Role.RadioButton`, selected). [trailing] sits beside the
 * row, not inside it, so a button there is its own target.
 */
@Composable
private fun DestinationRadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    tag: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 8.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                // The row is the control; the cursor stays a hand over the radio inside it too.
                modifier = Modifier.handCursor(),
                colors = RadioButtonDefaults.colors(selectedColor = BrandAccent, unselectedColor = Zinc400),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        trailing?.invoke()
    }
}

/**
 * The folder the selected choice resolves to. A reachable folder shows its path; a custom
 * folder that will not take files shows the warning glyph, "Folder unavailable" and the reason
 * (no permission, missing, read-only) in the error colour, with its path under it.
 */
@Composable
private fun DestinationDetail(destination: DestinationUiState) {
    val preview = destination.preview
    val unavailable = destination.customFolderUnreachable
    val reason = destination.customFolder?.reachability?.let(DestinationLabels::unavailableReason)
    val path = DestinationLabels.pathLine(preview)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 64.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (unavailable) Icons.Default.Warning else Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(18.dp).testTag(if (unavailable) "destination_warning" else "destination_folder_glyph"),
            tint = if (unavailable) ScoreBad else Zinc400,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (unavailable) {
                Text(
                    text = DestinationLabels.FOLDER_UNAVAILABLE + (reason?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = ScoreBad,
                    modifier = Modifier.testTag("destination_problem"),
                )
            }
            if (path != null) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .semantics {
                            DestinationLabels.pathDescription(preview)?.let { contentDescription = it }
                        }
                        .testTag("destination_path"),
                )
            }
            if (preview.isSecondPass && !unavailable) {
                Text(
                    text = DestinationLabels.SECOND_PASS_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("destination_second_pass"),
                )
            }
        }
    }
}

@Composable
private fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BrandAccent,
            unfocusedBorderColor = Zinc700,
            cursorColor = BrandAccent,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
        placeholder = { Text(text = placeholder, color = Zinc400) },
    )
}
