package com.photoselectortoolbox.ui.settings

import com.photoselectortoolbox.ui.components.handCursor
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
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.photoselectortoolbox.R
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.theme.DestructiveFill
import com.photoselectortoolbox.ui.theme.DestructiveText
import com.photoselectortoolbox.ui.theme.ErrorRed
import com.photoselectortoolbox.ui.theme.OnDestructiveFill
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc700
import com.photoselectortoolbox.ui.theme.Zinc800
import com.photoselectortoolbox.ui.text.asString
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

    // The folders are described again whenever Settings comes back to the foreground:
    // a permission revoked or a card removed while the app was away shows up here, not
    // at the next failed Copy.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshDestination()
        onPauseOrDispose {}
    }

    // Folder picker for the custom destination. A cancelled picker returns null and
    // changes nothing, so the choice stays where it was.
    val selectionFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let { viewModel.chooseCustomFolder(it.toString()) }
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text(stringResource(R.string.dialog_title_clear_cache)) },
            text = {
                Text(stringResource(R.string.dialog_body_clear_cache))
            },
            confirmButton = {
                Button(
                    modifier = Modifier.handCursor(),
                    onClick = {
                        showClearCacheDialog = false
                        viewModel.clearCache()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DestructiveFill,
                        contentColor = OnDestructiveFill,
                    ),
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(modifier = Modifier.handCursor(), onClick = { showClearCacheDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
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
        // --- Where photos go ---
        DestinationSection(
            uiState = uiState,
            onUseWorkingFolder = viewModel::useWorkingFolder,
            onChooseCustom = { selectionFolderPickerLauncher.launch(null) },
            onSaveSelectionName = viewModel::updateSelectionFolder,
            onSaveSecondPassName = viewModel::updateSecondarySelectionFolder,
            onSortingChange = viewModel::updateSortingEnabled,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // --- Analysis Section ---
        SettingsSection(title = stringResource(R.string.title_section_analysis)) {
            SettingsToggleItem(
                title = stringResource(R.string.setting_title_bursts),
                description = stringResource(R.string.setting_summary_bursts),
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
                            text = stringResource(R.string.label_grouping_level),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        GroupingLevel.entries.forEach { level ->
                            SettingsRadioRow(
                                label = stringResource(level.label),
                                description = stringResource(level.description),
                                selected = uiState.groupingLevel == level,
                                onSelect = { viewModel.updateGroupingLevel(level) },
                                tag = "settings_grouping_${level.name.lowercase()}",
                            )
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
                        text = stringResource(R.string.label_analysis_threads),
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
        SettingsSection(title = stringResource(R.string.title_section_filing)) {
            SettingsToggleItem(
                title = stringResource(R.string.setting_title_fullscreen_buttons),
                description = stringResource(R.string.setting_summary_fullscreen_buttons),
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
                    text = stringResource(R.string.label_filing_action),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.setting_summary_filing_action),
                    style = MaterialTheme.typography.bodySmall,
                    color = Zinc400,
                )
                Spacer(modifier = Modifier.height(8.dp))

                FilingAction.entries.forEach { action ->
                    SettingsRadioRow(
                        label = action.verbText.asString(),
                        selected = uiState.filingAction == action,
                        onSelect = { viewModel.updateFilingAction(action) },
                        tag = "settings_filing_${action.name.lowercase()}",
                    )
                }
            }

            HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

            SettingsToggleItem(
                title = stringResource(R.string.setting_title_tv_scale),
                description = stringResource(R.string.setting_summary_tv_scale),
                checked = uiState.tvDisplayScaleEnabled,
                onCheckedChange = { viewModel.updateTvDisplayScaleEnabled(it) },
                icon = Icons.Default.Info,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Ratings ---
        //
        // `[USER 2026-11-12]` decision #16: embedding a rating in a JPEG is the one write that
        // changes a photo, so it is opt-in (off on a new install). The summary is the data-loss
        // line UI_TEXT allows; RAW and TIFF ratings go to a sidecar either way.
        SettingsSection(title = stringResource(R.string.title_section_ratings)) {
            SettingsToggleItem(
                title = stringResource(R.string.setting_title_write_ratings_into_photos),
                description = stringResource(R.string.setting_summary_write_ratings_into_photos),
                checked = uiState.writeRatingsIntoPhotos,
                onCheckedChange = viewModel::updateWriteRatingsIntoPhotos,
                icon = Icons.Default.Star,
                tag = "settings_write_ratings_into_photos",
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Guidance Section ---
        //
        // Follows the Clear Cache pattern without its confirmation dialog:
        // nothing is destroyed here, so a modal asking "are you sure" would be
        // ceremony. The description confirms instead, because an action with no
        // visible effect reads as a control that does nothing.
        SettingsSection(title = stringResource(R.string.title_section_guidance)) {
            SettingsClickItem(
                title = stringResource(R.string.setting_title_reset_guidance),
                description = if (guidanceReset) {
                    stringResource(R.string.setting_summary_guidance_was_reset)
                } else {
                    stringResource(R.string.setting_summary_reset_guidance)
                },
                onClick = {
                    viewModel.resetGuidance()
                    guidanceReset = true
                },
                icon = Icons.Default.School,
                trailing = {
                    Button(
                        modifier = Modifier.handCursor(),
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
                        Text(stringResource(R.string.action_reset))
                    }
                },
            )

            HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

            ResetLearnRow(
                counts = uiState.learnHistory,
                onOpen = viewModel::loadLearnHistory,
                onReset = viewModel::resetLearnHistory,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Cache Section ---
        SettingsSection(title = stringResource(R.string.title_section_cache)) {
            SettingsClickItem(
                title = stringResource(R.string.setting_title_cached_scores),
                description = pluralStringResource(R.plurals.setting_summary_cached_scores, uiState.cachedScoreCount, uiState.cachedScoreCount),
                icon = Icons.Default.Cached,
            )

            HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

            SettingsClickItem(
                title = stringResource(R.string.setting_title_clear_cache),
                description = stringResource(R.string.setting_summary_clear_cache),
                onClick = { showClearCacheDialog = true },
                icon = Icons.Default.DeleteSweep,
                trailing = {
                    Button(
                        modifier = Modifier.handCursor(),
                        onClick = { showClearCacheDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ErrorRed.copy(alpha = 0.15f),
                            contentColor = DestructiveText,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.action_clear))
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- About Section ---
        SettingsSection(title = stringResource(R.string.title_section_about)) {
            SettingsClickItem(
                title = stringResource(R.string.app_name),
                description = stringResource(R.string.setting_summary_version, com.photoselectortoolbox.BuildConfig.VERSION_NAME),
                onClick = {},
                icon = Icons.Default.Info,
            )

            HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

            SettingsClickItem(
                title = stringResource(R.string.setting_title_source_code),
                description = stringResource(R.string.setting_summary_source_code),
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
                title = stringResource(R.string.setting_title_privacy_policy),
                description = stringResource(R.string.setting_summary_privacy_policy),
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
                title = stringResource(R.string.setting_title_legal_notice),
                description = stringResource(R.string.setting_summary_legal_notice),

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

            HorizontalDivider(color = Zinc700.copy(alpha = 0.5f))

            LicencesRow()
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
