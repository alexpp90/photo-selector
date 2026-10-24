package com.phototok.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoselector.core.storage.SourceKind
import com.phototok.R
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.LegalLinks
import com.phototok.domain.SwipeAction
import com.phototok.domain.PhotoFolders
import com.phototok.domain.SwipeRightVerb
import com.phototok.domain.UiText
import com.phototok.ui.components.ProModeHelpDialog
import com.phototok.ui.components.ProModeRow
import com.phototok.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onChangeSourceFolder: (() -> Unit)? = null,
    onReloadFolder: (() -> Unit)? = null,
    /** A message to show after a change (Pro mode on/off); the host shows it above the sheet. */
    onFeedback: (UiText) -> Unit = {},
) {
    var showProModeHelp by rememberSaveable { mutableStateOf(false) }
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

    val divider = colors.outlineVariant.copy(alpha = 0.2f)
    val pro = uiState.proMode

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        // ── Pro mode: its own card at the very top, shown in both modes ────────
        ProModeRow(
            proMode = pro,
            onProModeChange = { viewModel.setProMode(it) { message -> message?.let(onFeedback) } },
            onHelp = { showProModeHelp = true },
            toggleTag = "settings_pro_mode_toggle",
            helpTag = "settings_pro_mode_help",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (showProModeHelp) ProModeHelpDialog(onDismiss = { showProModeHelp = false })

        Spacer(modifier = Modifier.height(8.dp))

        // ── Storage ──────────────────────────────────────────────────
        // Casual hides the power-user rows (they keep their stored values for Pro mode).
        SettingsSection(title = stringResource(R.string.title_storage), icon = Icons.Default.Storage) {
            DividedRows(divider) {
                row("source_folder") {
                    SettingsClickItem(
                        title = stringResource(R.string.setting_title_source_folder),
                        summary = uiState.sourceFolderName.ifEmpty { stringResource(R.string.setting_summary_none) },
                        onClick = { onChangeSourceFolder?.invoke() },
                        icon = Icons.Default.FolderOpen,
                    )
                }

                if (onReloadFolder != null && uiState.sourceFolderName.isNotEmpty()) {
                    row("reload") {
                        SettingsClickItem(
                            title = stringResource(R.string.setting_title_reload),
                            onClick = onReloadFolder,
                            icon = Icons.Default.Sync,
                        )
                    }
                }

                if (pro) {
                    row("selection_folder") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.setting_title_selection_folder),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurface,
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
                                    Text(text = PhotoFolders.SELECTION, color = colors.onSurfaceVariant)
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
                                    Text(stringResource(R.string.action_save), color = colors.primary)
                                }
                            }
                        }
                    }

                    row("raw_jpeg_folders") {
                        SettingsToggleItem(
                            title = stringResource(R.string.setting_title_raw_jpeg_folders),
                            checked = uiState.sortingEnabled,
                            onCheckedChange = { viewModel.updateSortingEnabled(it) },
                        )
                    }

                    row("selection_location") {
                        SettingsClickItem(
                            title = stringResource(R.string.setting_title_selection_location),
                            summary = stringResource(
                                if (uiState.collectionUri != null) R.string.setting_summary_custom else R.string.setting_summary_default,
                            ),
                            onClick = { selectionFolderPickerLauncher.launch(null) },
                            trailing = {
                                if (uiState.collectionUri != null) {
                                    TextButton(onClick = { viewModel.updateCollectionUri(null) }) {
                                        Text(stringResource(R.string.action_reset), color = colors.primary)
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = colors.primary,
                                    )
                                }
                            },
                        )
                    }
                }

                if (uiState.sourceFolderUri != null) {
                    row("reset_folder") {
                        var seenFolderReset by remember(uiState.sourceFolderUri) { mutableStateOf(false) }
                        SettingsClickItem(
                            title = stringResource(R.string.setting_title_reset_folder),
                            summary = if (seenFolderReset) {
                                stringResource(R.string.setting_summary_done)
                            } else {
                                uiState.sourceFolderName.ifEmpty { null }
                            },
                            onClick = {
                                viewModel.resetSeenForCurrentFolder {
                                    seenFolderReset = true
                                }
                            },
                            icon = Icons.Default.RestartAlt,
                        )
                    }
                }

                row("reset_all") {
                    var allSeenReset by remember { mutableStateOf(false) }
                    SettingsClickItem(
                        title = stringResource(R.string.setting_title_reset_all),
                        summary = if (allSeenReset) stringResource(R.string.setting_summary_done) else null,
                        onClick = {
                            viewModel.resetAllSeenHistory {
                                allSeenReset = true
                            }
                        },
                        icon = Icons.Default.RestartAlt,
                    )
                }

                row("clear_cache") {
                    var cacheCleared by remember { mutableStateOf(false) }
                    SettingsClickItem(
                        title = stringResource(R.string.setting_title_clear_cache),
                        summary = if (cacheCleared) stringResource(R.string.setting_summary_done) else null,
                        onClick = {
                            viewModel.clearCache {
                                cacheCleared = true
                            }
                        },
                        icon = Icons.Default.Delete,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Browsing (per-SourceKind presets + global toggles) ───────
        SettingsSection(title = stringResource(R.string.title_browsing), icon = Icons.Default.BrowseGallery) {
            DividedRows(divider) {
                if (pro) {
                    row("source_type") {
                        SettingsRadioGroup(
                            title = stringResource(R.string.setting_title_source_type),
                            options = listOf(
                                // Cloud folders follow the phone's settings, so the first tab is both.
                                SourceKind.PHONE_LIBRARY to stringResource(R.string.chip_phone_cloud),
                                SourceKind.REMOVABLE to stringResource(R.string.chip_sd_card),
                                SourceKind.CAMERA_MTP to stringResource(R.string.chip_camera),
                            ),
                            selected = uiState.selectedSourceKind,
                            onSelect = { viewModel.selectSourceKindTab(it) },
                        )
                    }

                    row("feed_order") {
                        SettingsRadioGroup(
                            title = stringResource(R.string.setting_title_feed_order),
                            options = listOf(
                                FeedOrderMode.NEWEST_FIRST to stringResource(R.string.chip_newest),
                                FeedOrderMode.SHOOTING to stringResource(R.string.chip_by_filename),
                                FeedOrderMode.SHUFFLE to stringResource(R.string.chip_shuffle),
                                FeedOrderMode.ORIENTATION_THEN_NEWEST to stringResource(R.string.chip_by_orientation),
                            ),
                            selected = uiState.feedOrder,
                            onSelect = { viewModel.updateFeedOrder(it) },
                        )
                    }
                }

                // Confirm delete is a Casual setting too: the delete dialog's "Don't ask" writes it.
                row("confirm_delete") {
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
                                    text = stringResource(R.string.setting_title_confirm_delete),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.onSurface,
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
                                    contentDescription = stringResource(R.string.cd_warning),
                                    tint = colors.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.setting_summary_deletes_unasked),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.error,
                                )
                            }
                        }
                    }
                }

                if (pro) {
                    row("camera_info") {
                        SettingsToggleItem(
                            title = stringResource(R.string.setting_title_camera_info),
                            checked = uiState.showExifOverlay,
                            onCheckedChange = { viewModel.updateShowExifOverlay(it) },
                        )
                    }
                }

                row("milestones") {
                    SettingsToggleItem(
                        title = stringResource(R.string.setting_title_milestones),
                        checked = uiState.celebrationsEnabled,
                        onCheckedChange = { viewModel.updateCelebrationsEnabled(it) },
                    )
                }

                if (pro) {
                    row("link_raw_jpeg") {
                        SettingsToggleItem(
                            title = stringResource(R.string.setting_title_link_raw_jpeg),
                            checked = uiState.moveRelatedFiles,
                            onCheckedChange = { viewModel.updateMoveRelatedFiles(it) },
                        )
                    }

                    row("format_filter") {
                        SettingsRadioGroup(
                            title = stringResource(R.string.setting_title_format_filter),
                            options = listOf(
                                FileTypeFilter.ONE_PER_SHOT to stringResource(R.string.chip_pairs_merged),
                                FileTypeFilter.ALL to stringResource(R.string.chip_both),
                                FileTypeFilter.RAW to stringResource(R.string.chip_raw_only),
                                FileTypeFilter.JPG to stringResource(R.string.chip_jpeg_only),
                            ),
                            selected = uiState.fileTypeFilter,
                            onSelect = { viewModel.updateFileTypeFilter(it) },
                        )
                    }

                    row("swipe_right") {
                        SettingsRadioGroup(
                            title = stringResource(R.string.setting_title_swipe_right),
                            options = listOf(
                                SwipeRightVerb.FAVOURITE to stringResource(R.string.chip_favourite),
                                SwipeRightVerb.COPY to stringResource(R.string.chip_copy),
                                SwipeRightVerb.MOVE to stringResource(R.string.chip_move),
                            ),
                            selected = uiState.swipeRightVerb,
                            onSelect = { viewModel.updateSwipeRightVerb(it) },
                        )
                    }

                    // Left swipe action (triggered by swipe-left)
                    row("swipe_left") {
                        SettingsRadioGroup(
                            title = stringResource(R.string.setting_title_swipe_left),
                            options = listOf(
                                SwipeAction.DELETE to stringResource(R.string.chip_delete),
                                SwipeAction.COPY to stringResource(R.string.chip_copy),
                                SwipeAction.MOVE to stringResource(R.string.chip_move),
                            ),
                            selected = uiState.leftSwipeAction,
                            onSelect = { viewModel.updateLeftSwipeAction(it) },
                        )
                    }

                    if (uiState.leftSwipeAction != SwipeAction.DELETE) {
                        row("left_folder") {
                            SettingsClickItem(
                                title = stringResource(R.string.setting_title_left_folder),
                                summary = stringResource(
                                    if (uiState.leftSwipeUri != null) R.string.setting_summary_custom else R.string.setting_summary_default,
                                ),
                                onClick = { leftSwipeFolderPickerLauncher.launch(null) },
                                trailing = {
                                    if (uiState.leftSwipeUri != null) {
                                        TextButton(onClick = { viewModel.updateLeftSwipeUri(null) }) {
                                            Text(stringResource(R.string.action_reset), color = colors.primary)
                                        }
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = colors.primary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // ── Recent folders (Pro mode; Casual shows the list with its defaults) ──
        if (pro) {
            Spacer(modifier = Modifier.height(8.dp))

            Box(modifier = Modifier.testTag("settings_section_recent")) {
                SettingsSection(title = stringResource(R.string.title_recent), icon = Icons.Default.History) {
                    SettingsToggleItem(
                        title = stringResource(R.string.setting_title_show_recent),
                        checked = uiState.recentPathsEnabled,
                        onCheckedChange = { viewModel.updateRecentPathsEnabled(it) },
                    )

                    HorizontalDivider(color = divider)

                    SettingsRadioGroup(
                        title = stringResource(R.string.setting_title_recent_count),
                        options = listOf("1" to "1", "3" to "3", "5" to "5", "10" to "10"),
                        selected = uiState.recentPathsCount.toString(),
                        onSelect = { viewModel.updateRecentPathsCount(it.toIntOrNull() ?: 3) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Help ─────────────────────────────────────────────────────
        SettingsSection(title = stringResource(R.string.title_help), icon = Icons.AutoMirrored.Filled.HelpOutline) {
            var tutorialsReset by remember { mutableStateOf(false) }

            SettingsClickItem(
                title = stringResource(R.string.setting_title_reset_tips),
                summary = if (tutorialsReset) stringResource(R.string.setting_summary_done) else null,
                onClick = { viewModel.resetTutorials { tutorialsReset = true } },
                icon = Icons.Default.RestartAlt,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── About ────────────────────────────────────────────────────
        SettingsSection(title = stringResource(R.string.title_about), icon = Icons.Default.Info) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.setting_title_version),
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
                title = stringResource(R.string.setting_title_source_code),
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LegalLinks.SOURCE_CODE))
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
                title = stringResource(R.string.setting_title_privacy),
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
                title = stringResource(R.string.setting_title_legal),
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

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** One settings row with a stable identity (its state survives rows appearing around it) and a test tag. */
private class SettingsRowsBuilder {
    val rows = mutableListOf<Pair<String, @Composable () -> Unit>>()
    fun row(id: String, content: @Composable () -> Unit) {
        rows += id to content
    }
}

/** Rows of one card with a divider between neighbours, so hiding a row never leaves a double divider. */
@Composable
private fun DividedRows(dividerColor: Color, build: SettingsRowsBuilder.() -> Unit) {
    val rows = SettingsRowsBuilder().apply(build).rows
    rows.forEachIndexed { index, (id, content) ->
        key(id) {
            if (index > 0) HorizontalDivider(color = dividerColor)
            Box(modifier = Modifier.testTag("settings_row_$id")) { content() }
        }
    }
}
