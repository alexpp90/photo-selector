package com.photoselectortoolbox.ui.duplicates

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.photoselectortoolbox.data.model.DuplicateGroup
import com.photoselectortoolbox.ui.components.EmptyStateCard
import com.photoselectortoolbox.ui.components.ProgressIndicatorBar
import com.photoselectortoolbox.ui.theme.ErrorRed
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.BrandOnAccent
import com.photoselectortoolbox.ui.theme.Zinc700
import com.photoselectortoolbox.ui.theme.Zinc800
import com.photoselectortoolbox.viewmodel.DuplicatesViewModel

@Composable
fun DuplicatesScreen(
    windowSizeClass: WindowSizeClass,
    viewModel: DuplicatesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let { viewModel.selectFolder(it) }
    }

    var showDeleteConfirmation by remember { mutableStateOf(false) }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete Selected Files") },
            text = {
                Text(
                    "Are you sure you want to delete ${uiState.selectedForDeletion.size} " +
                        "file(s)? This action cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteSelected()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
            containerColor = Zinc800,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                        "Select a folder to scan for duplicate images."
                    },
                    actionLabel = if (uiState.missingGrantPrompt) "Re-select Folder" else "Select Folder",
                    onAction = { folderPickerLauncher.launch(null) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            uiState.duplicateGroups.isEmpty() && !uiState.isScanning -> {
                InstructionCard(
                    folderName = uiState.folderName,
                    statusText = uiState.statusText,
                    onStartScan = { viewModel.startScan() },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            else -> {
                val isExpanded =
                    windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

                Column(modifier = Modifier.fillMaxSize()) {
                    if (uiState.isScanning) {
                        ProgressIndicatorBar(
                            progress = uiState.scanProgress,
                            statusText = uiState.statusText,
                            isIndeterminate = uiState.scanProgress <= 0f,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else if (uiState.statusText.isNotEmpty()) {
                        Text(
                            text = uiState.statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(
                            items = uiState.duplicateGroups,
                            key = { it.hash },
                        ) { group ->
                            if (isExpanded) {
                                ExpandedDuplicateGroupCard(
                                    group = group,
                                    selectedUris = uiState.selectedForDeletion,
                                    onToggleSelection = { viewModel.toggleSelection(it) },
                                    onSelectAllButFirst = { viewModel.selectAllButFirst(group) },
                                )
                            } else {
                                CompactDuplicateGroupCard(
                                    group = group,
                                    selectedUris = uiState.selectedForDeletion,
                                    onToggleSelection = { viewModel.toggleSelection(it) },
                                    onSelectAllButFirst = { viewModel.selectAllButFirst(group) },
                                )
                            }
                        }
                    }

                    if (uiState.selectedForDeletion.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Zinc800,
                            tonalElevation = 8.dp,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${uiState.selectedForDeletion.size} selected",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = { showDeleteConfirmation = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ErrorRed,
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Delete Selected (${uiState.selectedForDeletion.size})")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstructionCard(
    folderName: String,
    statusText: String,
    onStartScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Ready to Scan",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Scan \"$folderName\" for duplicate images based on file hash comparison.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (statusText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onStartScan,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandAccent,
                    contentColor = BrandOnAccent,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Start Scan")
            }
        }
    }
}

@Composable
private fun ExpandedDuplicateGroupCard(
    group: DuplicateGroup,
    selectedUris: Set<String>,
    onToggleSelection: (String) -> Unit,
    onSelectAllButFirst: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc800),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Group (${group.files.size} files)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FilledTonalButton(
                    onClick = onSelectAllButFirst,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.SelectAll,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Select All But First",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(group.files) { index, fileUri ->
                    val isSelected = fileUri in selectedUris
                    DuplicateImageCard(
                        fileUri = fileUri,
                        isSelected = isSelected,
                        isFirst = index == 0,
                        onToggle = { onToggleSelection(fileUri) },
                        modifier = Modifier
                            .width(180.dp)
                            .aspectRatio(0.85f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactDuplicateGroupCard(
    group: DuplicateGroup,
    selectedUris: Set<String>,
    onToggleSelection: (String) -> Unit,
    onSelectAllButFirst: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc800),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Group (${group.files.size} files)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FilledTonalButton(
                    onClick = onSelectAllButFirst,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.SelectAll,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Select All But First",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // First image displayed larger
            if (group.files.isNotEmpty()) {
                val firstUri = group.files.first()
                val isFirstSelected = firstUri in selectedUris

                DuplicateImageCard(
                    fileUri = firstUri,
                    isSelected = isFirstSelected,
                    isFirst = true,
                    onToggle = { onToggleSelection(firstUri) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.5f),
                )
            }

            if (group.files.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = group.files.drop(1),
                        key = { it },
                    ) { fileUri ->
                        val isSelected = fileUri in selectedUris

                        DuplicateImageCard(
                            fileUri = fileUri,
                            isSelected = isSelected,
                            isFirst = false,
                            onToggle = { onToggleSelection(fileUri) },
                            modifier = Modifier
                                .size(100.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateImageCard(
    fileUri: String,
    isSelected: Boolean,
    isFirst: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = when {
        isSelected -> ErrorRed
        isFirst -> BrandAccent.copy(alpha = 0.4f)
        else -> Color.Transparent
    }

    Card(
        modifier = modifier.clickable(onClick = onToggle),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc700),
        border = if (borderColor != Color.Transparent) {
            BorderStroke(2.dp, borderColor)
        } else {
            null
        },
    ) {
        Box {
            AsyncImage(
                model = fileUri,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ErrorRed.copy(alpha = 0.2f)),
                )
            }

            // File name at bottom
            val fileName = remember(fileUri) {
                Uri.parse(fileUri).lastPathSegment?.substringAfterLast('/') ?: "Unknown"
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Checkbox overlay at top-right
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = ErrorRed,
                    uncheckedColor = Color.White.copy(alpha = 0.7f),
                    checkmarkColor = Color.White,
                ),
            )

            // "Original" badge for the first item
            if (isFirst) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = BrandAccent.copy(alpha = 0.9f),
                ) {
                    Text(
                        text = "Original",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandOnAccent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            // Checkmark icon for selected state
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected for deletion",
                    tint = ErrorRed,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(32.dp),
                )
            }
        }
    }
}
