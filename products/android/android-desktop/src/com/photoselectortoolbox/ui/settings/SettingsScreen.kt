package com.photoselectortoolbox.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.theme.ErrorRed
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc700
import com.photoselectortoolbox.ui.theme.Zinc800
import com.photoselectortoolbox.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showClearCacheDialog by remember { mutableStateOf(false) }
    // Confirmation only. The reset itself is one repository call and is already
    // done by the time this flips.
    var guidanceReset by remember { mutableStateOf(false) }
    var editingFolderName by remember(uiState.selectionFolderName) {
        mutableStateOf(uiState.selectionFolderName)
    }
    var editingSecondaryFolderName by remember(uiState.secondarySelectionFolderName) {
        mutableStateOf(uiState.secondarySelectionFolderName)
    }

    // Folder picker for custom selection destination
    val selectionFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let {
            viewModel.updatePhoneCollectionUri(it.toString())
        }
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear Cache") },
            text = {
                Text("Are you sure you want to clear all cached analysis scores? This will not affect your images.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearCacheDialog = false
                        viewModel.clearCache()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = Zinc800,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        // --- Storage Section ---
        SettingsSection(title = "Storage") {
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
                Text(
                    text = "Created inside the source folder by default",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editingFolderName,
                    onValueChange = { editingFolderName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandAccent,
                        unfocusedBorderColor = Zinc700,
                        cursorColor = BrandAccent,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    placeholder = {
                        Text(
                            text = "Selection",
                            color = Zinc400,
                        )
                    },
                )
                Spacer(modifier = Modifier.height(4.dp))

                if (editingFolderName != uiState.selectionFolderName &&
                    editingFolderName.isNotBlank()
                ) {
                    TextButton(
                        onClick = { viewModel.updateSelectionFolder(editingFolderName) },
                        modifier = Modifier.align(Alignment.End),
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
                OutlinedTextField(
                    value = editingSecondaryFolderName,
                    onValueChange = { editingSecondaryFolderName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandAccent,
                        unfocusedBorderColor = Zinc700,
                        cursorColor = BrandAccent,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    placeholder = {
                        Text(
                            text = "Final",
                            color = Zinc400,
                        )
                    },
                )
                if (editingSecondaryFolderName != uiState.secondarySelectionFolderName &&
                    editingSecondaryFolderName.isNotBlank()
                ) {
                    TextButton(
                        onClick = { viewModel.updateSecondarySelectionFolder(editingSecondaryFolderName) },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Save", color = BrandAccent)
                    }
                }
            }

            HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

            // Custom selection destination
            SettingsClickItem(
                title = "Custom Selection Location",
                description = if (uiState.phoneCollectionUri != null) {
                    "Using custom folder"
                } else {
                    "Default: subfolder in source folder"
                },
                onClick = { selectionFolderPickerLauncher.launch(null) },
                icon = Icons.Default.FolderOpen,
                trailing = {
                    if (uiState.phoneCollectionUri != null) {
                        TextButton(onClick = { viewModel.updatePhoneCollectionUri(null) }) {
                            Text("Reset", color = BrandAccent)
                        }
                    }
                },
            )

            HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

            SettingsToggleItem(
                title = "File Sorting",
                description = "Sort RAW and JPEG files into separate subfolders",
                checked = uiState.sortingEnabled,
                onCheckedChange = { viewModel.updateSortingEnabled(it) },
                icon = Icons.Default.Sort,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Analysis Section ---
        SettingsSection(title = "Analysis") {
            SettingsToggleItem(
                title = "Group Similar Series",
                description = "Group photos shot in close succession",
                checked = uiState.groupingEnabled,
                onCheckedChange = { viewModel.updateGroupingEnabled(it) },
                icon = Icons.Default.Collections,
            )

            AnimatedVisibility(
                visible = uiState.groupingEnabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "Grouping Level",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        GroupingLevel.entries.forEach { level ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = uiState.groupingLevel == level,
                                    onClick = { viewModel.updateGroupingLevel(level) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = BrandAccent,
                                        unselectedColor = Zinc400,
                                    ),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = level.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = level.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Analysis Threads",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${uiState.analysisThreadCount}",
                        style = MaterialTheme.typography.titleMedium,
                        color = BrandAccent,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = uiState.analysisThreadCount.toFloat(),
                    onValueChange = { viewModel.updateThreadCount(it.toInt()) },
                    valueRange = 1f..4f,
                    steps = 2,
                    colors = SliderDefaults.colors(
                        thumbColor = BrandAccent,
                        activeTrackColor = BrandAccent,
                        inactiveTrackColor = Zinc700,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "1",
                        style = MaterialTheme.typography.labelSmall,
                        color = Zinc400,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "4",
                        style = MaterialTheme.typography.labelSmall,
                        color = Zinc400,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Fullscreen Section ---
        SettingsSection(title = "Filing and Fullscreen") {
            SettingsToggleItem(
                title = "Show Fullscreen Action Buttons",
                description = "Display delete, copy, and move buttons in fullscreen mode",
                checked = uiState.fullscreenButtonsEnabled,
                onCheckedChange = { viewModel.updateFullscreenButtonsEnabled(it) },
                icon = Icons.Default.Info,
            )

            HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                // Renamed from "Double-Tap Gesture Action": double-tap no longer
                // files a photograph anywhere in the app, so a setting named
                // after that gesture described something that does not happen.
                // The persisted key is unchanged.
                Text(
                    text = "Filing Action",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Which verb the emphasised button performs. Both Copy and Move " +
                        "stay available on their own buttons and shortcuts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Zinc400,
                )
                Spacer(modifier = Modifier.height(8.dp))

                FilingAction.entries.forEach { action ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = uiState.filingAction == action,
                            onClick = { viewModel.updateFilingAction(action) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = BrandAccent,
                                unselectedColor = Zinc400,
                            ),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = action.phrase,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

            SettingsToggleItem(
                title = "TV / 10-Foot Display Scale (125%)",
                description = "Scale font size by 125% for living-room TV / DeX display viewing",
                checked = uiState.tvDisplayScaleEnabled,
                onCheckedChange = { viewModel.updateTvDisplayScaleEnabled(it) },
                icon = Icons.Default.Info,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Guidance Section ---
        //
        // Follows the Clear Cache pattern without its confirmation dialog:
        // nothing is destroyed here, so a modal asking "are you sure" would be
        // ceremony. The description confirms instead, because an action with no
        // visible effect reads as a control that does nothing.
        SettingsSection(title = "Guidance") {
            SettingsClickItem(
                title = "Reset Guidance",
                description = if (guidanceReset) {
                    "The explanations and the guide will show again"
                } else {
                    "Show the first-run explanations and the layout guide again"
                },
                onClick = {
                    viewModel.resetGuidance()
                    guidanceReset = true
                },
                icon = Icons.Default.School,
                trailing = {
                    Button(
                        onClick = {
                            viewModel.resetGuidance()
                            guidanceReset = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BrandAccent.copy(alpha = 0.15f),
                            contentColor = BrandAccent,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Reset")
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Cache Section ---
        SettingsSection(title = "Cache") {
            SettingsClickItem(
                title = "Cached Scores",
                description = "${uiState.cachedScoreCount} analysis results stored",
                onClick = {},
                icon = Icons.Default.Cached,
            )

            HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

            SettingsClickItem(
                title = "Clear Cache",
                description = "Remove all cached analysis scores",
                onClick = { showClearCacheDialog = true },
                icon = Icons.Default.DeleteSweep,
                trailing = {
                    Button(
                        onClick = { showClearCacheDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ErrorRed.copy(alpha = 0.15f),
                            contentColor = ErrorRed,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Clear")
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- About Section ---
        SettingsSection(title = "About") {
            SettingsClickItem(
                title = "Frameduel",
                description = "Version ${com.photoselectortoolbox.BuildConfig.VERSION_NAME}",
                onClick = {},
                icon = Icons.Default.Info,
            )

            HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

            SettingsClickItem(
                title = "Source Code",
                description = "View on GitHub",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(com.photoselectortoolbox.domain.LegalLinks.SOURCE_CODE))
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        e.printStackTrace()
                    }
                },
                icon = Icons.Default.Code,
            )

            HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

            SettingsClickItem(
                title = "Privacy Policy",
                description = "How Frameduel handles your data",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(com.photoselectortoolbox.domain.LegalLinks.PRIVACY_POLICY))
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        e.printStackTrace()
                    }
                },
                icon = Icons.Default.PrivacyTip,
            )

            HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

            SettingsClickItem(
                title = "Legal Notice (Impressum)",
                description = "Provider identification",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(com.photoselectortoolbox.domain.LegalLinks.IMPRESSUM))
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        e.printStackTrace()
                    }
                },
                icon = Icons.Default.Gavel,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
