package com.phototok.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.BrowseGallery
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoselector.core.storage.SourceKind
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb
import com.phototok.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onChangeSourceFolder: (() -> Unit)? = null,
    onReloadFolder: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    var editingFolderName by remember(uiState.selectionFolderName) {
        mutableStateOf(uiState.selectionFolderName)
    }

    val selectionFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let {
            viewModel.updateCollectionUri(it.toString())
        }
    }

    val leftSwipeFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let {
            viewModel.updateLeftSwipeUri(it.toString())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        // ── Storage ──────────────────────────────────────────────────
        SettingsSection(title = "Storage", icon = Icons.Default.Storage) {
            SettingsClickItem(
                title = "Source Folder",
                description = uiState.sourceFolderName.ifEmpty { "No source folder selected" },
                onClick = { onChangeSourceFolder?.invoke() },
                icon = Icons.Default.FolderOpen,
            )

            if (onReloadFolder != null && uiState.sourceFolderName.isNotEmpty()) {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))
                SettingsClickItem(
                    title = "Reload Folder",
                    description = "Rescan photos in source folder",
                    onClick = onReloadFolder,
                    icon = Icons.Default.Sync,
                )
            }

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = "Selection Subfolder",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Created inside the source folder by default",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editingFolderName,
                    onValueChange = { editingFolderName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outlineVariant,
                        cursorColor = colors.primary,
                        focusedTextColor = colors.onSurface,
                        unfocusedTextColor = colors.onSurface,
                        focusedContainerColor = colors.surfaceDim,
                        unfocusedContainerColor = colors.surfaceDim,
                    ),
                    placeholder = {
                        Text(text = "Selection", color = colors.onSurfaceVariant)
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
                        Text("Save", color = colors.primary)
                    }
                }
            }

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            SettingsToggleItem(
                title = "Organize Selection by RAW / JPEG",
                description = "Create RAW/ and JPEG/ subfolders inside Selection",
                checked = uiState.sortingEnabled,
                onCheckedChange = { viewModel.updateSortingEnabled(it) },
            )

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            SettingsClickItem(
                title = "Custom Collection Location",
                description = if (uiState.collectionUri != null) {
                    "Using custom folder"
                } else {
                    "Default: subfolder in source folder"
                },
                onClick = { selectionFolderPickerLauncher.launch(null) },
                trailing = {
                    if (uiState.collectionUri != null) {
                        TextButton(onClick = { viewModel.updateCollectionUri(null) }) {
                            Text("Reset", color = colors.primary)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Change",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.primary,
                            )
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = colors.primary,
                            )
                        }
                    }
                },
            )

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            var seenFolderReset by remember(uiState.sourceFolderUri) { mutableStateOf(false) }
            if (uiState.sourceFolderUri != null) {
                SettingsClickItem(
                    title = "Reset Seen History for Current Folder",
                    description = if (seenFolderReset) {
                        "Seen history cleared for ${uiState.sourceFolderName.ifEmpty { "current folder" }}"
                    } else {
                        "Mark all photos in ${uiState.sourceFolderName.ifEmpty { "current folder" }} as unseen"
                    },
                    onClick = {
                        viewModel.resetSeenForCurrentFolder {
                            seenFolderReset = true
                        }
                    },
                    icon = Icons.Default.RestartAlt,
                )
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))
            }

            var allSeenReset by remember { mutableStateOf(false) }
            SettingsClickItem(
                title = "Reset All Seen History",
                description = if (allSeenReset) {
                    "All seen history cleared"
                } else {
                    "Reset reviewed photo progress across all folders and cards"
                },
                onClick = {
                    viewModel.resetAllSeenHistory {
                        allSeenReset = true
                    }
                },
                icon = Icons.Default.RestartAlt,
            )

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            var cacheCleared by remember { mutableStateOf(false) }
            SettingsClickItem(
                title = "Delete Cache",
                description = if (cacheCleared) "Cache deleted" else "Clear image cache & folder scan history",
                onClick = {
                    viewModel.clearCache {
                        cacheCleared = true
                    }
                },
                icon = Icons.Default.Delete,
                trailing = {
                    TextButton(onClick = {
                        viewModel.clearCache {
                            cacheCleared = true
                        }
                    }) {
                        Text(if (cacheCleared) "Deleted" else "Delete", color = colors.primary)
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Browsing (per-SourceKind presets + global toggles) ───────
        SettingsSection(title = "Browsing", icon = Icons.Default.BrowseGallery) {
            SettingsRadioGroup(
                title = "Source Preset",
                options = listOf(
                    SourceKind.PHONE_LIBRARY to "Phone",
                    SourceKind.REMOVABLE to "SD / Card",
                    SourceKind.CAMERA_MTP to "Camera",
                ),
                selected = uiState.selectedSourceKind,
                onSelect = { viewModel.selectSourceKindTab(it) },
            )

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            SettingsRadioGroup(
                title = "Feed Order",
                options = listOf(
                    FeedOrderMode.NEWEST_FIRST to "Newest first",
                    FeedOrderMode.SHOOTING to "Shooting order (filename)",
                    FeedOrderMode.SHUFFLE to "Shuffle (unseen first)",
                    FeedOrderMode.ORIENTATION_THEN_NEWEST to "Group by orientation",
                ),
                selected = uiState.feedOrder,
                onSelect = { viewModel.updateFeedOrder(it) },
            )

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.updateDirectDeleteConfirm(!uiState.directDeleteConfirmEnabled) }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Direct Delete Confirmation",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurface,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Ask before deleting permanently",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Switch(
                        checked = uiState.directDeleteConfirmEnabled,
                        onCheckedChange = { viewModel.updateDirectDeleteConfirm(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onPrimary,
                            checkedTrackColor = colors.primary,
                            uncheckedThumbColor = colors.onSurfaceVariant,
                            uncheckedTrackColor = colors.secondaryContainer,
                            uncheckedBorderColor = colors.secondaryContainer,
                        ),
                    )
                }

                if (!uiState.directDeleteConfirmEnabled) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = colors.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Deletes permanently without asking",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.error,
                        )
                    }
                }
            }

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            SettingsToggleItem(
                title = "Show EXIF Stats",
                description = "Display aperture and ISO data",
                checked = uiState.showExifOverlay,
                onCheckedChange = { viewModel.updateShowExifOverlay(it) },
            )

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            SettingsToggleItem(
                title = "Milestone Celebrations",
                description = "Celebrate space freed and photo review milestones",
                checked = uiState.celebrationsEnabled,
                onCheckedChange = { viewModel.updateCelebrationsEnabled(it) },
            )

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            SettingsToggleItem(
                title = "Move Related Files",
                description = "Also move/delete same-name files (e.g. .JPG + .RAW)",
                checked = uiState.moveRelatedFiles,
                onCheckedChange = { viewModel.updateMoveRelatedFiles(it) },
            )

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            SettingsRadioGroup(
                title = "Format Filter",
                options = listOf(
                    FileTypeFilter.ONE_PER_SHOT to "1 per shot (Recommended)",
                    FileTypeFilter.ALL to "Both",
                    FileTypeFilter.RAW to "RAW only",
                    FileTypeFilter.JPG to "JPG only",
                ),
                selected = uiState.fileTypeFilter,
                onSelect = { viewModel.updateFileTypeFilter(it) },
            )

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            SettingsRadioGroup(
                title = "Swipe Right Action",
                options = listOf(
                    SwipeRightVerb.FAVOURITE to "★ Favourite (in-place)",
                    SwipeRightVerb.COPY to "Copy to Selection",
                    SwipeRightVerb.MOVE to "Move to Selection",
                ),
                selected = uiState.swipeRightVerb,
                onSelect = { viewModel.updateSwipeRightVerb(it) },
            )

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            // Left swipe action (triggered by swipe-left)
            SettingsRadioGroup(
                title = "Left Swipe Action",
                options = listOf(
                    SwipeAction.DELETE to "Delete / Trash",
                    SwipeAction.COPY to "Copy to Custom Folder",
                    SwipeAction.MOVE to "Move to Custom Folder",
                ),
                selected = uiState.leftSwipeAction,
                onSelect = { viewModel.updateLeftSwipeAction(it) },
            )

            if (uiState.leftSwipeAction != SwipeAction.DELETE) {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

                SettingsClickItem(
                    title = "Left Swipe Custom Location",
                    description = if (uiState.leftSwipeUri != null) "Using custom folder" else "Default: subfolder in source",
                    onClick = { leftSwipeFolderPickerLauncher.launch(null) },
                    trailing = {
                        if (uiState.leftSwipeUri != null) {
                            TextButton(onClick = { viewModel.updateLeftSwipeUri(null) }) {
                                Text("Reset", color = colors.primary)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Change",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.primary,
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = colors.primary,
                                )
                            }
                        }
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Recent folders ───────────────────────────────────────────
        SettingsSection(title = "Recent Folders", icon = Icons.Default.History) {
            SettingsToggleItem(
                title = "Show Recent Folders",
                description = "List last-used folders on the start screen",
                checked = uiState.recentPathsEnabled,
                onCheckedChange = { viewModel.updateRecentPathsEnabled(it) },
            )

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            SettingsRadioGroup(
                title = "How Many to Show",
                options = listOf("1" to "1", "3" to "3", "5" to "5", "10" to "10"),
                selected = uiState.recentPathsCount.toString(),
                onSelect = { viewModel.updateRecentPathsCount(it.toIntOrNull() ?: 3) },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Help ─────────────────────────────────────────────────────
        SettingsSection(title = "Help", icon = Icons.AutoMirrored.Filled.HelpOutline) {
            var tutorialsReset by remember { mutableStateOf(false) }

            SettingsClickItem(
                title = "Reset Tutorials",
                description = if (tutorialsReset) {
                    "Tips will show again"
                } else {
                    "Show all tips again"
                },
                onClick = { viewModel.resetTutorials { tutorialsReset = true } },
                icon = Icons.Default.RestartAlt,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── About ────────────────────────────────────────────────────
        SettingsSection(title = "About", icon = Icons.Default.Info) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "App version",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = com.phototok.BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                )
            }

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            SettingsClickItem(
                title = "Source Code",
                description = "View on GitHub",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/alexpp90/photo-selector"))
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        e.printStackTrace()
                    }
                },
                icon = Icons.Default.Code,
            )

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            // Google Play requires the privacy policy to be accessible from
            // within the app; the Impressum link addresses § 5 DDG (Germany).
            SettingsClickItem(
                title = "Privacy Policy",
                description = "How Jackpix handles your data",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(com.phototok.domain.LegalLinks.PRIVACY_POLICY))
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        e.printStackTrace()
                    }
                },
                icon = Icons.Default.PrivacyTip,
            )

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.2f))

            SettingsClickItem(
                title = "Legal Notice (Impressum)",
                description = "Provider identification",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(com.phototok.domain.LegalLinks.IMPRESSUM))
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        e.printStackTrace()
                    }
                },
                icon = Icons.Default.Gavel,
            )
        }

        // Footer branding
        Spacer(modifier = Modifier.height(32.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Jackpix",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface.copy(alpha = 0.3f),
            )
            Text(
                text = "Professional Curation Engine",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface.copy(alpha = 0.2f),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
