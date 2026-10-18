package com.phototok.ui.phonemode

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoselector.core.model.ExifData
import com.photoselector.core.storage.SourceKind
import com.phototok.data.model.ImageItem
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.SwipeAction
import com.phototok.ui.components.ViewerBottomBar
import com.phototok.ui.settings.SettingsScreen
import com.phototok.viewmodel.PhoneModeViewModel
import com.phototok.viewmodel.ReloadPrompt
import com.phototok.viewmodel.SelectionViewerTab
import com.phototok.viewmodel.SelectionViewerViewModel
import com.phototok.viewmodel.canRevert
import com.phototok.viewmodel.rawCompanionUris
import com.phototok.viewmodel.unmountedRecentUris

/**
 * Root composable for the phone-mode experience.
 * Integrates top app bar, bottom action bar, and content (landing / loading / viewer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneModeScreen(
    viewModel: PhoneModeViewModel = hiltViewModel(),
    selectionViewModel: SelectionViewerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectionState by selectionViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current

    val openSelectionViewer = {
        viewModel.finalizePendingDelete()
        selectionViewModel.openWithFavourites(
            targetFolderUri = uiState.collectionFolderUri ?: uiState.sourceFolderUri,
            sourceImages = uiState.images,
            favouritedUris = uiState.favouritedUris,
        )
    }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showDirectDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showClearAllCardConfirm by remember { mutableStateOf(false) }

    val performDeleteSwipe = {
        if (uiState.leftSwipeAction == SwipeAction.DELETE) {
            val currentImage = uiState.images.getOrNull(uiState.currentIndex)
            if (currentImage != null) {
                if (uiState.directDeleteConfirmEnabled) {
                    showDirectDeleteConfirmDialog = true
                } else {
                    viewModel.requestDelete()
                }
            }
        } else {
            viewModel.performLeftSwipeCopyOrMove()
        }
    }

    val isViewing = uiState.images.isNotEmpty()
    val isViewingSelection = selectionState.isOpen

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? -> uri?.let { viewModel.selectSourceFolder(it) } }

    val copyFavouritesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? -> uri?.let { selectionViewModel.copyFavouritesToFolder(it) } }

    // Error snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(message = error, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    // Action feedback snackbar
    LaunchedEffect(uiState.lastActionFeedback) {
        uiState.lastActionFeedback?.let { fb ->
            snackbarHostState.showSnackbar(message = fb.message, duration = SnackbarDuration.Short)
            viewModel.clearFeedback()
        }
    }

    // Selection-viewer feedback snackbar
    LaunchedEffect(selectionState.feedback) {
        selectionState.feedback?.let { fb ->
            snackbarHostState.showSnackbar(message = fb.message, duration = SnackbarDuration.Short)
            selectionViewModel.clearFeedback()
        }
    }

    // Milestone celebration snackbar (U-19)
    LaunchedEffect(uiState.celebrationBanner) {
        uiState.celebrationBanner?.let { banner ->
            snackbarHostState.showSnackbar(
                message = banner,
                duration = SnackbarDuration.Short,
            )
            viewModel.dismissCelebrationBanner()
        }
    }

    // Direct Delete Confirmation Dialog
    if (showDirectDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDirectDeleteConfirmDialog = false },
            modifier = Modifier.testTag("direct_delete_dialog"),
            title = { Text("Delete Permanently", color = colors.error) },
            text = {
                Text("This picture will be directly deleted (permanently) because trash is not supported for this location.\n\nHint: You can disable this confirmation in Settings.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDirectDeleteConfirmDialog = false
                        viewModel.requestDelete()
                    },
                    modifier = Modifier.testTag("direct_delete_confirm_button"),
                ) {
                    Text("Delete", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDirectDeleteConfirmDialog = false },
                    modifier = Modifier.testTag("direct_delete_cancel_button"),
                ) {
                    Text("Cancel")
                }
            },
            containerColor = colors.surfaceContainerHigh,
        )
    }

    // U-14c: "Done with this card" sheet
    if (uiState.showCardDoneSheet) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCardDoneSheet() },
            title = { Text("Done with this card") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose how to wrap up this SD / USB card session:")
                    Button(
                        onClick = { viewModel.keepAllFilesOnCard() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Leave card as-is (Recommended)")
                    }
                    if (uiState.undoStack.isNotEmpty() || uiState.pendingDelete != null) {
                        TextButton(
                            onClick = { viewModel.deleteQueuedRejectsOnCard() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Delete rejected photos from card")
                        }
                    }
                    TextButton(
                        onClick = {
                            viewModel.dismissCardDoneSheet()
                            showClearAllCardConfirm = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Clear all photos from card (${uiState.images.size})", color = colors.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCardDoneSheet() }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showClearAllCardConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllCardConfirm = false },
            title = { Text("Clear All Photos from Card?", color = colors.error) },
            text = {
                Text("This permanently deletes ${uiState.images.size} photos from ${uiState.sourceFolderName}. Uncopied photos cannot be recovered.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllCardConfirm = false
                    viewModel.confirmClearEntireCard()
                }) {
                    Text("Delete All", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllCardConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Settings bottom sheet
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 16.dp, bottom = 8.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .background(colors.outlineVariant, RoundedCornerShape(50)),
                )
            },
        ) {
            SettingsScreen(
                onChangeSourceFolder = {
                    showSettingsSheet = false
                    folderPickerLauncher.launch(null)
                },
                onReloadFolder = {
                    showSettingsSheet = false
                    viewModel.reloadSourceFolder()
                },
            )
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Measured positions of the real controls, shared by the bars and the controls guide.
    val guideAnchors = remember { ControlsGuideAnchors() }
    val guideUp = uiState.showGestureTutorial || uiState.showControlsGuide

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Read-only Selection / Favourites viewer ─────────────────────────
        if (isViewingSelection) {
            SelectionFolderViewer(
                images = selectionState.images,
                currentIndex = selectionState.currentIndex,
                folderName = selectionState.folderName,
                activeTab = selectionState.activeTab,
                showExifOverlay = uiState.showExifOverlay,
                onSelectTab = selectionViewModel::selectTab,
                onShareBatch = {
                    selectionViewModel.buildBatchShareIntent(uiState.images)?.let {
                        context.startActivity(it)
                    }
                },
                onCopyFavouritesToFolder = {
                    copyFavouritesPickerLauncher.launch(null)
                },
                onNavigate = selectionViewModel::navigateTo,
                onClose = selectionViewModel::close,
            )
        } else if (isLandscape && isViewing) {
            // ── Landscape viewer with side panels ───────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black)
            ) {
                // Left Side Panel: Navigation
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.8f))
                        .guideAnchor(guideAnchors, GuideAnchor.LEFT_PANEL)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Left + WindowInsetsSides.Top + WindowInsetsSides.Bottom
                            )
                        )
                        .width(72.dp)
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Logo toggles the EXIF overlay
                    Image(
                        painter = painterResource(id = com.phototok.R.mipmap.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier
                            .size(PhoneModeChromeMetrics.LEFT_PANEL_LOGO_SIZE)
                            .guideAnchor(guideAnchors, GuideAnchor.LOGO)
                            .semantics { contentDescription = PhoneModeTopBarDescriptions.LOGO }
                            .testTag("landscape_logo")
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClickLabel = "Show or hide camera info",
                                role = Role.Button,
                            ) { viewModel.toggleExifOverlay() }
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SidePanelButton(
                            icon = Icons.Default.FolderOpen,
                            description = "Sources",
                            isActive = false,
                            enabled = true,
                            onClick = { viewModel.goBackToLanding() },
                            modifier = Modifier.guideAnchor(guideAnchors, GuideAnchor.SOURCES),
                        )
                        SidePanelButton(
                            icon = Icons.Default.CollectionsBookmark,
                            description = "Selection",
                            isActive = false,
                            enabled = true,
                            onClick = openSelectionViewer,
                            modifier = Modifier.guideAnchor(guideAnchors, GuideAnchor.SELECTION),
                        )
                        // Revert (restore pending deleted photo from trash)
                        SidePanelButton(
                            icon = Icons.Default.RestoreFromTrash,
                            description = "Revert",
                            isActive = uiState.canRevert,
                            enabled = uiState.canRevert,
                            onClick = { viewModel.revertDelete() },
                            modifier = Modifier.guideAnchor(guideAnchors, GuideAnchor.REVERT),
                        )
                        SidePanelButton(
                            icon = Icons.AutoMirrored.Filled.HelpOutline,
                            description = "Help",
                            iconDescription = PhoneModeTopBarDescriptions.help(guideUp),
                            isActive = false,
                            enabled = true,
                            onClick = { viewModel.showControlsGuide() },
                            modifier = Modifier.guideAnchor(guideAnchors, GuideAnchor.HELP),
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Center Panel: Image Viewer
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                ) {
                    PhoneModeViewer(
                        images = uiState.images,
                        currentIndex = uiState.currentIndex,
                        portraitSectionStart = uiState.portraitSectionStart,
                        onNavigate = viewModel::navigateToImage,
                        onAddToCollection = viewModel::addToCollection,
                        onRequestDelete = performDeleteSwipe,
                        leftSwipeAction = uiState.leftSwipeAction,
                        leftSwipeFolderName = uiState.leftSwipeFolderName,
                        collectionAction = uiState.collectionAction,
                        swipeRightVerb = uiState.swipeRightVerb,
                        collectionFolderName = uiState.collectionFolderName,
                        showExifOverlay = false,
                        showPageCounter = false,
                        isDiscovering = uiState.isDiscovering,
                        isCloudSource = uiState.sourceKind == SourceKind.CLOUD_OTHER,
                        rawCompanionUris = uiState.rawCompanionUris,
                        favouriteUris = uiState.favouritedUris,
                        onCardDwelled = viewModel::onCardDwelled,
                        onShareCurrentPhoto = {
                            viewModel.buildCurrentShareIntent()?.let { context.startActivity(it) }
                        },
                        onOpenWithCurrentPhoto = {
                            viewModel.buildCurrentOpenWithIntent()?.let { context.startActivity(it) }
                        },
                        onFirstRunHint = viewModel::maybeShowFirstRunHint,
                    )
                }

                // Right Side Panel: Settings, EXIF and Page Counter
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.8f))
                        .guideAnchor(guideAnchors, GuideAnchor.RIGHT_PANEL)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Right + WindowInsetsSides.Top + WindowInsetsSides.Bottom
                            )
                        )
                        .width(125.dp)
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    val isHelpOrTutorialActive = uiState.showGestureTutorial || uiState.showControlsGuide
                    val sideIconTint = if (isHelpOrTutorialActive) {
                        colors.onSurfaceVariant.copy(alpha = 0.4f)
                    } else {
                        colors.onSurfaceVariant
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { viewModel.reloadSourceFolder() },
                            modifier = Modifier
                                .size(PhoneModeChromeMetrics.TOP_BAR_BUTTON_SIZE)
                                .guideAnchor(guideAnchors, GuideAnchor.RELOAD)
                                .testTag("landscape_reload_folder_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Reload folder",
                                tint = sideIconTint,
                            )
                        }
                        IconButton(
                            onClick = { viewModel.toggleExifOverlay() },
                            modifier = Modifier
                                .size(PhoneModeChromeMetrics.TOP_BAR_BUTTON_SIZE)
                                .guideAnchor(guideAnchors, GuideAnchor.INFO)
                                .testTag("landscape_info_exif_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Toggle EXIF stats",
                                tint = if (uiState.showExifOverlay) colors.primary else sideIconTint,
                            )
                        }
                        IconButton(
                            onClick = { showSettingsSheet = true },
                            modifier = Modifier
                                .size(PhoneModeChromeMetrics.TOP_BAR_BUTTON_SIZE)
                                .guideAnchor(guideAnchors, GuideAnchor.SETTINGS),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = sideIconTint,
                            )
                        }
                    }

                    val currentImage = uiState.images.getOrNull(uiState.currentIndex)
                    if (uiState.showExifOverlay && currentImage?.exifData != null) {
                        ExifSidebarPanel(
                            exif = currentImage.exifData!!,
                            fileName = currentImage.fileName,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = colors.surfaceContainerHigh.copy(alpha = 0.8f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            colors.outline.copy(alpha = 0.2f)
                        )
                    ) {
                        Text(
                            text = "${uiState.currentIndex + 1} / ${uiState.images.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        } else {
            // ── Portrait: viewer / loading / landing ────────────────────────
            if (isViewing) {
                PhoneModeViewer(
                    images = uiState.images,
                    currentIndex = uiState.currentIndex,
                    portraitSectionStart = uiState.portraitSectionStart,
                    onNavigate = viewModel::navigateToImage,
                    onAddToCollection = viewModel::addToCollection,
                    onRequestDelete = performDeleteSwipe,
                    leftSwipeAction = uiState.leftSwipeAction,
                    leftSwipeFolderName = uiState.leftSwipeFolderName,
                    collectionAction = uiState.collectionAction,
                    swipeRightVerb = uiState.swipeRightVerb,
                    collectionFolderName = uiState.collectionFolderName,
                    showExifOverlay = uiState.showExifOverlay,
                    bottomHudSuppressed = uiState.firstRunHint != null || guideUp,
                    isDiscovering = uiState.isDiscovering,
                    isCloudSource = uiState.sourceKind == SourceKind.CLOUD_OTHER,
                    rawCompanionUris = uiState.rawCompanionUris,
                    favouriteUris = uiState.favouritedUris,
                    onCardDwelled = viewModel::onCardDwelled,
                    onShareCurrentPhoto = {
                        viewModel.buildCurrentShareIntent()?.let { context.startActivity(it) }
                    },
                    onOpenWithCurrentPhoto = {
                        viewModel.buildCurrentOpenWithIntent()?.let { context.startActivity(it) }
                    },
                    onFirstRunHint = viewModel::maybeShowFirstRunHint,
                )
            } else if (uiState.isLoading) {
                PhoneModeLoading(folderName = uiState.sourceFolderName)
            } else {
                PhoneModeLanding(
                    sourceFolderUri = uiState.sourceFolderUri,
                    sourceFolderName = uiState.sourceFolderName,
                    collectionFolderName = uiState.collectionFolderName,
                    isLoading = uiState.isLoading,
                    emptyFolderMessage = uiState.emptyFolderMessage,
                    missingGrantPrompt = uiState.missingGrantPrompt,
                    onSelectCamera = {
                        viewModel.selectCameraFolder { cameraUri ->
                            folderPickerLauncher.launch(cameraUri)
                        }
                    },
                    onSelectSdCard = { launchPicker ->
                        viewModel.selectSdCardFolder(launchPicker)
                    },
                    onSelectSource = { viewModel.selectSourceFolder(it) },
                    onSelectCollection = { viewModel.selectCollectionFolder(it) },
                    onStart = {
                        uiState.sourceFolderUri?.let {
                            viewModel.selectSourceFolder(Uri.parse(it))
                        }
                    },
                    recentPaths = uiState.recentPaths,
                    recentPathsEnabled = uiState.recentPathsEnabled,
                    recentPathsCount = uiState.recentPathsCount,
                    unmountedRecentUris = uiState.unmountedRecentUris,
                    folderSeenProgress = uiState.folderSeenProgress,
                    onSelectRecentPath = { viewModel.selectRecentPath(it) },
                )
            }
        }

        // ── Gesture tutorial overlay (Always on top of content & bars) ────────
        if (isViewing && !isViewingSelection) {
            GestureTutorialOverlay(
                visible = uiState.showGestureTutorial,
                onDismiss = viewModel::dismissGestureTutorial,
                leftSwipeAction = uiState.leftSwipeAction,
                collectionAction = uiState.collectionAction,
                leftSwipeFolderName = uiState.leftSwipeFolderName,
                collectionFolderName = uiState.collectionFolderName,
                anchors = guideAnchors,
            )
        }

        // ── Controls guide, opened on demand from the info button ─────────────
        GestureTutorialOverlay(
            visible = uiState.showControlsGuide,
            onDismiss = viewModel::hideControlsGuide,
            leftSwipeAction = uiState.leftSwipeAction,
            collectionAction = uiState.collectionAction,
            leftSwipeFolderName = uiState.leftSwipeFolderName,
            collectionFolderName = uiState.collectionFolderName,
            title = "Controls",
            subtitle = "Everything you can do while reviewing photos",
            dismissLabel = "CLOSE",
            anchors = guideAnchors,
        )

        // ── RAW + JPEG pair suggestions dialog ──────────────────────────────
        if (!uiState.showGestureTutorial && !uiState.showControlsGuide) {
            RawJpegSuggestionCard(
                visible = uiState.showRawJpegSuggestion,
                onShowOnePerShot = { viewModel.applyRawJpegFilter(FileTypeFilter.ONE_PER_SHOT) },
                onFilterRaw = { viewModel.applyRawJpegFilter(FileTypeFilter.RAW) },
                onFilterJpg = { viewModel.applyRawJpegFilter(FileTypeFilter.JPG) },
                onEnableMoveRelatedFiles = { viewModel.enableMoveRelatedFiles() },
                onDismiss = { viewModel.dismissRawJpegSuggestion() },
            )

            if (!uiState.showRawJpegSuggestion) {
                FirstRunHintCard(
                    hint = uiState.firstRunHint,
                    onDismiss = viewModel::dismissFirstRunHint,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .padding(bottom = if (isLandscape && isViewing) 24.dp else 88.dp),
                )
            }
        }

        // ── Folder resume / reload prompt dialog ─────────────────────────────
        val reloadPrompt = uiState.reloadPrompt
        if (reloadPrompt != null) {
            FolderResumeDialog(
                prompt = reloadPrompt,
                onStartWithNewest = { viewModel.confirmReloadJumpToLatest() },
                onContinueWhereLeftOff = { viewModel.dismissReloadPrompt() },
            )
        }

        // ── Overlay App Bars (Only when not in Landscape Viewer / selection) ──
        if (!isViewingSelection && !(isLandscape && isViewing)) {
            // ── Top app bar ──────────────────────────────────────────
            PhoneModeTopBar(
                showControls = isViewing,
                showDoneWithCard = isViewing && uiState.sourceKind == SourceKind.REMOVABLE,
                guideOpen = guideUp,
                exifOverlayOn = uiState.showExifOverlay,
                onToggleExif = { viewModel.toggleExifOverlay() },
                onDoneWithCard = { viewModel.openCardDoneSheet() },
                onReload = { viewModel.reloadSourceFolder() },
                onHelp = {
                    when {
                        uiState.showGestureTutorial -> viewModel.dismissGestureTutorial()
                        uiState.showControlsGuide -> viewModel.hideControlsGuide()
                        else -> viewModel.showControlsGuide()
                    }
                },
                onSettings = { showSettingsSheet = true },
                modifier = Modifier.align(Alignment.TopCenter),
                anchors = guideAnchors,
            )

            // ── Bottom action bar ────────────────────────────────────
            if (isViewing) {
                ViewerBottomBar(
                    canRevert = uiState.canRevert,
                    onRevert = { viewModel.revertDelete() },
                    onJumpToSelection = openSelectionViewer,
                    onGoToLanding = { viewModel.goBackToLanding() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    anchors = guideAnchors,
                )
            }
        }

        // ── Snackbar ─────────────────────────────────────────────
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isLandscape && isViewing) 16.dp else 80.dp),
        )
    }
}

/**
 * Modal dialog shown when reloading PhotoTok or reopening a previously opened folder,
 * informing the user whether newer photos were added since they left and letting them
 * choose between continuing where they left off or starting with the newest image.
 */
@Composable
fun FolderResumeDialog(
    prompt: ReloadPrompt,
    onStartWithNewest: () -> Unit,
    onContinueWhereLeftOff: () -> Unit,
) {
    val photoNumber = (prompt.resumeIndex + 1).coerceAtLeast(1)
    val positionContext = if (prompt.totalCount > 0) {
        " (at photo #$photoNumber of ${prompt.totalCount})"
    } else {
        ""
    }
    val newerInfo = when {
        prompt.newCount == 1 -> "1 newer photo was added since you last left$positionContext."
        prompt.newCount > 1 -> "${prompt.newCount} newer photos were added since you last left$positionContext."
        else -> "No newer photos were added since you last left$positionContext."
    }
    val dialogTitle = if (prompt.newCount > 0) "New Photos Found" else "Resume Folder?"

    AlertDialog(
        onDismissRequest = onContinueWhereLeftOff,
        title = {
            Text(
                text = dialogTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = "$newerInfo Would you like to continue where you left off or start with the newest image?",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = onStartWithNewest,
                modifier = Modifier.testTag("reload_jump_confirm_button"),
            ) {
                Text("Start with Newest")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onContinueWhereLeftOff,
                modifier = Modifier.testTag("reload_jump_stay_button"),
            ) {
                Text("Continue Where I Left Off")
            }
        },
        modifier = Modifier
            .widthIn(max = 460.dp)
            .testTag("reload_jump_dialog"),
    )
}

/** Side-panel labelled button used in the landscape viewer. */
@Composable
private fun SidePanelButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconDescription: String = description,
) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        isActive -> colors.onPrimaryContainer
        enabled -> colors.secondary
        else -> colors.onSurface.copy(alpha = 0.25f)
    }
    Box(
        modifier = modifier
            .size(
                width = PhoneModeChromeMetrics.SIDE_PANEL_BUTTON_WIDTH,
                height = PhoneModeChromeMetrics.SIDE_PANEL_BUTTON_HEIGHT,
            )
            .then(
                if (isActive) Modifier.clip(RoundedCornerShape(14.dp)).background(colors.primaryContainer)
                else Modifier
            )
            .then(
                if (enabled) Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClick,
                ) else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = iconDescription,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = description,
                color = tint,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/** Read-only viewer for the Favourites and PhotoTok_Selection tabs (U-13, U-17). */
@Composable
private fun SelectionFolderViewer(
    images: List<ImageItem>,
    currentIndex: Int,
    folderName: String,
    activeTab: SelectionViewerTab,
    showExifOverlay: Boolean,
    onSelectTab: (SelectionViewerTab) -> Unit,
    onShareBatch: () -> Unit,
    onCopyFavouritesToFolder: () -> Unit,
    onNavigate: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Box(modifier = Modifier.fillMaxSize()) {
        if (images.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (activeTab == SelectionViewerTab.FAVOURITES) {
                        "No favourites yet — swipe right in ★ mode to mark favourites"
                    } else {
                        "No photos in the selection folder yet"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        } else {
            PhoneModeViewer(
                images = images,
                currentIndex = currentIndex,
                portraitSectionStart = -1,
                onNavigate = onNavigate,
                onAddToCollection = { },
                onRequestDelete = { },
                showExifOverlay = showExifOverlay,
                readOnly = true,
            )
        }

        // Top bar: back + Favourites | Selection tabs + batch share & copy
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = colors.onSurface,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                FilterChip(
                    selected = activeTab == SelectionViewerTab.FAVOURITES,
                    onClick = { onSelectTab(SelectionViewerTab.FAVOURITES) },
                    label = { Text("★ Favourites") },
                )
                Spacer(modifier = Modifier.width(6.dp))
                FilterChip(
                    selected = activeTab == SelectionViewerTab.SELECTION,
                    onClick = { onSelectTab(SelectionViewerTab.SELECTION) },
                    label = { Text(folderName.ifEmpty { "Selection" }) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (activeTab == SelectionViewerTab.FAVOURITES && images.isNotEmpty()) {
                    IconButton(onClick = onCopyFavouritesToFolder) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy favourites to folder",
                            tint = colors.onSurface,
                        )
                    }
                }
                if (images.isNotEmpty()) {
                    IconButton(onClick = onShareBatch) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share all",
                            tint = colors.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExifSidebarPanel(
    exif: ExifData,
    fileName: String,
    modifier: Modifier = Modifier,
) {
    val items = buildList {
        exif.iso?.let { add("ISO $it") }
        exif.shutterSpeed?.let { speed ->
            val formatted = if (speed < 1.0 && speed > 0.0) "1/${(1.0 / speed).toInt()}s" else "${speed}s"
            add(formatted)
        }
        exif.aperture?.let { add("f/%.1f".format(java.util.Locale.US, it)) }
        exif.focalLength?.let { add("${it.toInt()}mm") }
    }
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerLowest.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            colors.outlineVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = fileName.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.5.sp),
                color = colors.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            items.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = colors.primary,
                    )
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            if (exif.lens != "Unknown") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lens,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = colors.tertiary,
                    )
                    Text(
                        text = exif.lens,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
