package com.photoselectortoolbox.ui.statistics

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoselectortoolbox.ui.components.EmptyStateCard
import com.photoselectortoolbox.ui.components.ProgressIndicatorBar
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.Zinc800
import com.photoselectortoolbox.viewmodel.StatisticsViewModel

@Composable
fun StatisticsScreen(
    windowSizeClass: WindowSizeClass,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let { viewModel.selectFolder(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.folderUri == null -> {
                EmptyStateCard(
                    icon = Icons.Default.FolderOpen,
                    title = if (uiState.missingGrantPrompt) "Folder Access Needed" else "No Folder Selected",
                    description = if (uiState.missingGrantPrompt) {
                        "Permission for this folder was revoked or the folder was moved. Re-select the folder to continue."
                    } else {
                        "Select a folder containing photos to view EXIF statistics."
                    },
                    actionLabel = if (uiState.missingGrantPrompt) "Re-select Folder" else "Select Folder",
                    onAction = { folderPickerLauncher.launch(null) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ProgressIndicatorBar(
                        progress = uiState.scanProgress,
                        statusText = uiState.progressText,
                        isIndeterminate = uiState.scanProgress == 0f,
                        modifier = Modifier.fillMaxWidth(0.6f)
                    )
                }
            }

            else -> {
                val isExpanded =
                    windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

                if (isExpanded) {
                    ExpandedStatisticsLayout(uiState = uiState)
                } else {
                    CompactStatisticsLayout(uiState = uiState)
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(
    imageCount: Int,
    uniqueLenses: Int,
    focalLengthRange: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryCard(
            icon = Icons.Default.BarChart,
            label = "Images",
            value = "$imageCount",
            modifier = Modifier.weight(1f),
        )
        SummaryCard(
            icon = Icons.Default.CameraAlt,
            label = "Lenses",
            value = "$uniqueLenses",
            modifier = Modifier.weight(1f),
        )
        SummaryCard(
            icon = Icons.Default.Straighten,
            label = "Focal Range",
            value = focalLengthRange,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc800),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = BrandAccent,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompactStatisticsLayout(
    uiState: com.photoselectortoolbox.viewmodel.StatisticsUiState,
) {
    val focalLengthRange = computeFocalLengthRange(uiState.focalLengthDistribution)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SummaryRow(
                imageCount = uiState.imageCount,
                uniqueLenses = uiState.lensDistribution.size,
                focalLengthRange = focalLengthRange,
            )
        }

        item {
            ChartCard(
                title = "Shutter Speed Distribution",
                data = uiState.shutterSpeedDistribution,
            )
        }

        item {
            ChartCard(
                title = "Aperture Distribution",
                data = uiState.apertureDistribution,
            )
        }

        item {
            ChartCard(
                title = "ISO Distribution",
                data = uiState.isoDistribution,
            )
        }

        item {
            ChartCard(
                title = "Focal Length Distribution",
                data = uiState.focalLengthDistribution,
            )
        }

        item {
            ChartCard(
                title = "Lens Usage",
                data = uiState.lensDistribution,
            )
        }

        if (uiState.shutterSpeedSharpness.isNotEmpty()) {
            item {
                SharpnessBreakdownCard(
                    title = "Sharpness by Shutter Speed",
                    stats = uiState.shutterSpeedSharpness,
                )
            }
        }
        if (uiState.apertureSharpness.isNotEmpty()) {
            item {
                SharpnessBreakdownCard(
                    title = "Sharpness by Aperture",
                    stats = uiState.apertureSharpness,
                )
            }
        }
        if (uiState.focalLengthSharpness.isNotEmpty()) {
            item {
                SharpnessBreakdownCard(
                    title = "Sharpness by Focal Length",
                    stats = uiState.focalLengthSharpness,
                )
            }
        }
    }
}

@Composable
private fun ExpandedStatisticsLayout(
    uiState: com.photoselectortoolbox.viewmodel.StatisticsUiState,
) {
    val focalLengthRange = computeFocalLengthRange(uiState.focalLengthDistribution)

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            SummaryRow(
                imageCount = uiState.imageCount,
                uniqueLenses = uiState.lensDistribution.size,
                focalLengthRange = focalLengthRange,
            )
        }

        item {
            ChartCard(
                title = "Shutter Speed Distribution",
                data = uiState.shutterSpeedDistribution,
            )
        }

        item {
            ChartCard(
                title = "Aperture Distribution",
                data = uiState.apertureDistribution,
            )
        }

        item {
            ChartCard(
                title = "ISO Distribution",
                data = uiState.isoDistribution,
            )
        }

        item {
            ChartCard(
                title = "Focal Length Distribution",
                data = uiState.focalLengthDistribution,
            )
        }

        item(span = { GridItemSpan(2) }) {
            ChartCard(
                title = "Lens Usage",
                data = uiState.lensDistribution,
            )
        }

        if (uiState.shutterSpeedSharpness.isNotEmpty()) {
            item {
                SharpnessBreakdownCard(
                    title = "Sharpness by Shutter Speed",
                    stats = uiState.shutterSpeedSharpness,
                )
            }
        }
        if (uiState.apertureSharpness.isNotEmpty()) {
            item {
                SharpnessBreakdownCard(
                    title = "Sharpness by Aperture",
                    stats = uiState.apertureSharpness,
                )
            }
        }
        if (uiState.focalLengthSharpness.isNotEmpty()) {
            item(span = { GridItemSpan(2) }) {
                SharpnessBreakdownCard(
                    title = "Sharpness by Focal Length",
                    stats = uiState.focalLengthSharpness,
                )
            }
        }
    }
}

@Composable
private fun SharpnessBreakdownCard(
    title: String,
    stats: Map<String, com.photoselectortoolbox.viewmodel.ExifSharpnessStats>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc800),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            stats.values.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${entry.bucketLabel} (${entry.sampleCount})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = String.format(
                            java.util.Locale.US,
                            "mean %.1f · median %.1f",
                            entry.meanSharpness,
                            entry.medianSharpness,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun computeFocalLengthRange(distribution: Map<String, Int>): String {
    if (distribution.isEmpty()) return "--"
    val lengths = distribution.keys.mapNotNull { key ->
        key.removeSuffix("mm").toIntOrNull()
    }
    return if (lengths.isEmpty()) {
        "--"
    } else {
        "${lengths.min()}mm - ${lengths.max()}mm"
    }
}
