package com.phototok.ui.phonemode

import androidx.compose.foundation.layout.FlowRow
import android.content.res.Configuration
import android.net.Uri
import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoselector.core.model.ExifData
import com.photoselector.core.storage.SourceKind
import com.phototok.BuildConfig
import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.PhotoFolders
import com.phototok.domain.SwipeAction
import com.phototok.ui.components.NameTooltip
import com.phototok.ui.components.ViewerBottomBar
import com.phototok.ui.resolve
import com.phototok.ui.settings.SettingsScreen
import com.phototok.viewmodel.PhoneModeViewModel
import com.phototok.viewmodel.ReloadPrompt
import com.phototok.viewmodel.SelectionViewerLogic
import com.phototok.viewmodel.SelectionViewerTab
import com.phototok.viewmodel.SelectionViewerViewModel
import com.phototok.viewmodel.canRevert
import com.phototok.viewmodel.rawCompanionUris
import com.phototok.viewmodel.unmountedRecentUris
import kotlinx.coroutines.launch

/**
 * Root composable for the phone-mode experience.
 * Integrates top app bar, bottom action bar, and content (landing / loading / viewer).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
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
            favourites = uiState.favourites,
            // Open where the user's swipe right puts photos: ★ -> Favourites, copy/move -> Selection.
            preferredTab = SelectionViewerLogic.preferredTab(uiState.swipeRightVerb),
            // `Jackpix` for a Casual card import, else the configured Selection folder.
            selectionSubfolder = uiState.selectionSubfolderName,
        )
        viewModel.onSelectionViewerOpened()
    }

    // The bar's Share button and the long-press menu take the same path.
    val shareCurrentPhoto = {
        viewModel.buildCurrentShareIntent()?.let { context.startActivity(it) }
        Unit
    }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showDirectDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showClearAllCardConfirm by remember { mutableStateOf(false) }
    val sheetSnackbarHostState = remember { SnackbarHostState() }
    val sheetScope = rememberCoroutineScope()

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
    // A Casual card import copies into the phone's camera folder, which is known once it is granted.
    val collectionReady = !uiState.importToPhoneCamera || uiState.collectionFolderUri != null
    val isViewingSelection = selectionState.isOpen

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? -> uri?.let { viewModel.selectSourceFolder(it) } }

    // MediaStore favourite requests (U-17): launched once each; the ViewModel decides when.
    val favouriteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onLaunchedFavouriteRequestResult(result.resultCode == Activity.RESULT_OK) }

    LaunchedEffect(uiState.pendingIntentSenderRequest) {
        val request = uiState.pendingIntentSenderRequest ?: return@LaunchedEffect
        viewModel.onSystemFavouriteRequestLaunched(request)
        try {
            favouriteRequestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (e: ActivityNotFoundException) {
            viewModel.onSystemFavouriteResult(request, accepted = false)
        }
    }

    val copyFavouritesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? -> uri?.let { selectionViewModel.copyFavouritesToFolder(it) } }

    // Casual swipe right on a card or camera needs write access to the phone's camera folder once:
    // the picker opens inside DCIM/Camera. The photo stays put until the user has answered.
    val cameraFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.onCameraFolderGranted(uri) else viewModel.onCameraFolderGrantCancelled()
    }
    LaunchedEffect(uiState.cameraFolderGrantRequest?.id) {
        uiState.cameraFolderGrantRequest?.let { cameraFolderLauncher.launch(it.initialUri) }
    }

    // Error snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(message = error.resolve(context), duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    // Action feedback snackbar
    LaunchedEffect(uiState.lastActionFeedback) {
        uiState.lastActionFeedback?.let { fb ->
            snackbarHostState.showSnackbar(message = fb.message.resolve(context), duration = SnackbarDuration.Short)
            viewModel.clearFeedback()
        }
    }

    // Selection-viewer feedback snackbar
    LaunchedEffect(selectionState.feedback) {
        selectionState.feedback?.let { fb ->
            snackbarHostState.showSnackbar(message = fb.message.resolve(context), duration = SnackbarDuration.Short)
            selectionViewModel.clearFeedback()
        }
    }

    // Milestone celebration snackbar (U-19)
    LaunchedEffect(uiState.celebrationBanner) {
        uiState.celebrationBanner?.let { banner ->
            snackbarHostState.showSnackbar(
                message = banner.resolve(context),
                duration = SnackbarDuration.Short,
            )
            viewModel.dismissCelebrationBanner()
        }
    }

    // Direct Delete Confirmation Dialog
    if (showDirectDeleteConfirmDialog) {
        DirectDeleteDialog(
            onConfirm = { dontAskAgain ->
                showDirectDeleteConfirmDialog = false
                if (dontAskAgain) viewModel.updateDirectDeleteConfirm(false)
                viewModel.requestDelete()
            },
            onDismiss = { showDirectDeleteConfirmDialog = false },
        )
    }

    // U-14c: "Done with this card" sheet
    if (uiState.showCardDoneSheet) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCardDoneSheet() },
            title = { Text(stringResource(R.string.dialog_title_card_done)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.keepAllFilesOnCard() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_keep_all))
                    }
                    if (uiState.undoStack.isNotEmpty() || uiState.pendingDelete != null) {
                        TextButton(
                            onClick = { viewModel.deleteQueuedRejectsOnCard() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.action_delete_rejects))
                        }
                    }
                    TextButton(
                        onClick = {
                            viewModel.dismissCardDoneSheet()
                            showClearAllCardConfirm = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.action_delete_all_count, uiState.images.size),
                            color = colors.error,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCardDoneSheet() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showClearAllCardConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllCardConfirm = false },
            title = {
                Text(
                    pluralStringResource(R.plurals.dialog_title_delete_all, uiState.images.size, uiState.images.size),
                    color = colors.error,
                )
            },
            text = {
                Text(stringResource(R.string.dialog_body_card_loss))
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllCardConfirm = false
                    viewModel.confirmClearEntireCard()
                }) {
                    Text(stringResource(R.string.action_delete_all), color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllCardConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
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
            // The sheet is its own window and covers the screen's snackbar host, so the messages
            // Settings raises (Pro mode on/off) are shown by a host inside the sheet.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // The sheet is its own window: export its test tags to the lab flows as well.
                    .semantics { testTagsAsResourceId = BuildConfig.EXPOSE_TEST_TAGS },
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
                    onFeedback = { message ->
                        sheetScope.launch {
                            sheetSnackbarHostState.currentSnackbarData?.dismiss()
                            sheetSnackbarHostState.showSnackbar(
                                message = message.resolve(context),
                                duration = SnackbarDuration.Short,
                            )
                        }
                    },
                )
                SnackbarHost(
                    hostState = sheetSnackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                        .testTag("settings_snackbar_host"),
                )
            }
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
                folderName = selectionState.selectionFolderName,
                activeTab = selectionState.activeTab,
                showTabs = selectionState.showTabs,
                favouriteCount = selectionState.favouriteImages.size,
                selectionCount = selectionState.selectionImages.size,
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
                LandscapeLeftPanel(
                    canRevert = uiState.canRevert,
                    helpOpen = guideUp,
                    anchors = guideAnchors,
                    cameraInfoEnabled = uiState.proMode,
                    onToggleExif = { viewModel.toggleExifOverlay() },
                    onSources = { viewModel.goBackToLanding() },
                    onSelection = openSelectionViewer,
                    onShare = shareCurrentPhoto,
                    onRevert = { viewModel.revertDelete() },
                    onHelp = { viewModel.showControlsGuide() },
                )

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
                        topBadgesSuppressed = guideUp,
                        isDiscovering = uiState.isDiscovering,
                        isCloudSource = uiState.sourceKind == SourceKind.CLOUD_OTHER,
                        rawCompanionUris = uiState.rawCompanionUris,
                        favourites = uiState.favourites,
                        onCardDwelled = viewModel::onCardDwelled,
                        onShareCurrentPhoto = {
                            viewModel.buildCurrentShareIntent()?.let { context.startActivity(it) }
                        },
                        onOpenWithCurrentPhoto = {
                            viewModel.buildCurrentOpenWithIntent()?.let { context.startActivity(it) }
                        },
                        onFirstRunHint = viewModel::maybeShowFirstRunHint,
                        collectionReady = collectionReady,
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
                    LandscapeRightButtons(
                        exifOn = uiState.showExifOverlay,
                        tint = sideIconTint,
                        anchors = guideAnchors,
                        cameraInfoEnabled = uiState.proMode,
                        onReload = { viewModel.reloadSourceFolder() },
                        onInfo = { viewModel.toggleExifOverlay() },
                        onSettings = { showSettingsSheet = true },
                    )

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
                            text = stringResource(R.string.label_page_counter, uiState.currentIndex + 1, uiState.images.size),
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
                    topBadgesSuppressed = guideUp,
                    isDiscovering = uiState.isDiscovering,
                    isCloudSource = uiState.sourceKind == SourceKind.CLOUD_OTHER,
                    rawCompanionUris = uiState.rawCompanionUris,
                    favourites = uiState.favourites,
                    onCardDwelled = viewModel::onCardDwelled,
                    onShareCurrentPhoto = {
                        viewModel.buildCurrentShareIntent()?.let { context.startActivity(it) }
                    },
                    onOpenWithCurrentPhoto = {
                        viewModel.buildCurrentOpenWithIntent()?.let { context.startActivity(it) }
                    },
                    onToggleFavourite = viewModel::toggleCurrentFavourite,
                    onFirstRunHint = viewModel::maybeShowFirstRunHint,
                    collectionReady = collectionReady,
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
                    proMode = uiState.proMode,
                    onProModeChange = viewModel::setProMode,
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
                swipeRightVerb = uiState.swipeRightVerb,
                anchors = guideAnchors,
                cameraInfo = uiState.proMode,
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
            swipeRightVerb = uiState.swipeRightVerb,
            anchors = guideAnchors,
            cameraInfo = uiState.proMode,
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
                    onOpenSettings = { showSettingsSheet = true },
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
                cameraInfoEnabled = uiState.proMode,
            )

            // ── Bottom action bar ────────────────────────────────────
            if (isViewing) {
                ViewerBottomBar(
                    canRevert = uiState.canRevert,
                    onRevert = { viewModel.revertDelete() },
                    onJumpToSelection = openSelectionViewer,
                    onShare = shareCurrentPhoto,
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
 * The landscape left panel: the logo (camera info) and the icon-only navigation buttons
 * (`REQ-TOK-UX.02`). Extracted so the screenshot tests can render it on its own.
 */
@Composable
internal fun LandscapeLeftPanel(
    canRevert: Boolean,
    helpOpen: Boolean,
    anchors: ControlsGuideAnchors?,
    onToggleExif: () -> Unit,
    onSources: () -> Unit,
    onSelection: () -> Unit,
    onShare: () -> Unit,
    onRevert: () -> Unit,
    onHelp: () -> Unit,
    /** Camera info is Pro only: in Casual the logo is plain (no toggle, no guide callout). */
    cameraInfoEnabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.8f))
            .guideAnchor(anchors, GuideAnchor.LEFT_PANEL)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Left + WindowInsetsSides.Top + WindowInsetsSides.Bottom
                )
            )
            .width(PhoneModeChromeMetrics.LEFT_PANEL_WIDTH)
            .padding(vertical = PhoneModeChromeMetrics.LEFT_PANEL_VERTICAL_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Logo toggles the EXIF overlay (Pro); in Casual it is just the logo.
        if (cameraInfoEnabled) {
            val logoName = stringResource(PhoneModeTopBarDescriptions.LOGO)
            val infoName = stringResource(PhoneModeTopBarDescriptions.INFO)
            NameTooltip(name = infoName) {
                Image(
                    painter = painterResource(id = R.drawable.ic_brand_mark),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    contentDescription = null,
                    modifier = Modifier
                        .size(PhoneModeChromeMetrics.LEFT_PANEL_LOGO_SIZE)
                        .guideAnchor(anchors, GuideAnchor.LOGO)
                        .semantics { contentDescription = logoName }
                        .testTag("landscape_logo")
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClickLabel = infoName,
                            role = Role.Button,
                        ) { onToggleExif() }
                )
            }
        } else {
            val logoName = stringResource(R.string.cd_logo_plain)
            Image(
                painter = painterResource(id = R.drawable.ic_brand_mark),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary),
                contentDescription = null,
                modifier = Modifier
                    .size(PhoneModeChromeMetrics.LEFT_PANEL_LOGO_SIZE)
                    .semantics { contentDescription = logoName }
                    .testTag("landscape_logo"),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PhoneModeChromeMetrics.SIDE_PANEL_BUTTON_SPACING)
        ) {
            SidePanelButton(
                icon = Icons.Default.FolderOpen,
                name = stringResource(R.string.cd_sources),
                isActive = false,
                enabled = true,
                onClick = onSources,
                modifier = Modifier.guideAnchor(anchors, GuideAnchor.SOURCES).testTag("landscape_sources_button"),
            )
            SidePanelButton(
                icon = Icons.Default.CollectionsBookmark,
                name = stringResource(R.string.cd_selection),
                isActive = false,
                enabled = true,
                onClick = onSelection,
                modifier = Modifier.guideAnchor(anchors, GuideAnchor.SELECTION).testTag("landscape_selection_button"),
            )
            SidePanelButton(
                icon = Icons.Default.Share,
                name = stringResource(R.string.cd_share),
                isActive = false,
                enabled = true,
                onClick = onShare,
                modifier = Modifier.guideAnchor(anchors, GuideAnchor.SHARE).testTag("landscape_share_button"),
            )
            // Revert (restore pending deleted photo from trash)
            SidePanelButton(
                icon = Icons.Default.RestoreFromTrash,
                name = stringResource(R.string.cd_revert),
                isActive = canRevert,
                enabled = canRevert,
                onClick = onRevert,
                modifier = Modifier.guideAnchor(anchors, GuideAnchor.REVERT).testTag("landscape_revert_button"),
            )
            SidePanelButton(
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                name = stringResource(PhoneModeTopBarDescriptions.help(helpOpen)),
                isActive = false,
                enabled = true,
                onClick = onHelp,
                modifier = Modifier.guideAnchor(anchors, GuideAnchor.HELP).testTag("landscape_help_button"),
            )
        }

        Spacer(modifier = Modifier.height(PhoneModeChromeMetrics.LEFT_PANEL_BOTTOM_SPACER))
    }
}

/** The landscape right panel's icon-only buttons: Reload, camera info and Settings. */
@Composable
internal fun LandscapeRightButtons(
    exifOn: Boolean,
    tint: androidx.compose.ui.graphics.Color,
    anchors: ControlsGuideAnchors?,
    onReload: () -> Unit,
    onInfo: () -> Unit,
    onSettings: () -> Unit,
    /** Camera info is Pro only: in Casual there is no Info button. */
    cameraInfoEnabled: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TopBarIconButton(
            icon = Icons.Default.Sync,
            name = stringResource(R.string.cd_reload),
            tint = tint,
            onClick = onReload,
            modifier = Modifier
                .size(PhoneModeChromeMetrics.TOP_BAR_BUTTON_SIZE)
                .guideAnchor(anchors, GuideAnchor.RELOAD)
                .testTag("landscape_reload_folder_button"),
        )
        if (cameraInfoEnabled) {
            TopBarIconButton(
                icon = Icons.Default.Info,
                name = stringResource(PhoneModeTopBarDescriptions.INFO),
                tint = if (exifOn) MaterialTheme.colorScheme.primary else tint,
                onClick = onInfo,
                modifier = Modifier
                    .size(PhoneModeChromeMetrics.TOP_BAR_BUTTON_SIZE)
                    .guideAnchor(anchors, GuideAnchor.INFO)
                    .testTag("landscape_info_exif_button"),
            )
        }
        TopBarIconButton(
            icon = Icons.Default.Settings,
            name = stringResource(R.string.cd_settings),
            tint = tint,
            onClick = onSettings,
            modifier = Modifier
                .size(PhoneModeChromeMetrics.TOP_BAR_BUTTON_SIZE)
                .guideAnchor(anchors, GuideAnchor.SETTINGS)
                .testTag("landscape_settings_button"),
        )
    }
}

/**
 * Confirmation before a left-swipe delete in a location without a trash.
 *
 * The title carries the consequence and the body the one reason. A "Don't ask" checkbox, applied
 * with Delete, turns this prompt off for good (the same setting as Settings > Confirm delete), so
 * there is no sentence pointing at Settings (REQ-TOK-GESTURE.03). Revert still works after
 * confirming, so the dialog does not claim the delete is immediate.
 *
 * @param onConfirm Delete was chosen; the argument is whether "Don't ask" was ticked.
 */
@Composable
fun DirectDeleteDialog(
    onConfirm: (dontAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var dontAsk by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("direct_delete_dialog"),
        title = { Text(stringResource(R.string.dialog_title_direct_delete), color = colors.error) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dialog_body_no_trash))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = dontAsk, role = Role.Checkbox, onValueChange = { dontAsk = it })
                        .testTag("direct_delete_dont_ask"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // The row is the target; the box only draws the state.
                    Checkbox(checked = dontAsk, onCheckedChange = null)
                    Text(
                        text = stringResource(R.string.label_dont_ask),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(dontAsk) },
                modifier = Modifier.testTag("direct_delete_confirm_button"),
            ) {
                Text(stringResource(R.string.action_delete), color = colors.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("direct_delete_cancel_button"),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = colors.surfaceContainerHigh,
    )
}

/**
 * Shown only when a reopened folder holds photos newer than the last visit
 * (REQ-TOK-FEATURE.09); an explicit Reload goes straight to the latest photo instead. The
 * count is the whole message; the buttons name the two outcomes. With nothing new there is
 * nothing to decide, so no dialog.
 */
@Composable
fun FolderResumeDialog(
    prompt: ReloadPrompt,
    onStartWithNewest: () -> Unit,
    onContinueWhereLeftOff: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onContinueWhereLeftOff,
        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
        title = {
            Text(
                text = pluralStringResource(R.plurals.dialog_title_new_photos, prompt.newCount, prompt.newCount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        confirmButton = {
            Button(
                onClick = onStartWithNewest,
                modifier = Modifier.testTag("reload_jump_confirm_button"),
            ) {
                Text(stringResource(R.string.action_show_new))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onContinueWhereLeftOff,
                modifier = Modifier.testTag("reload_jump_stay_button"),
            ) {
                Text(stringResource(R.string.action_stay))
            }
        },
        modifier = Modifier
            .widthIn(max = 460.dp)
            .testTag("reload_jump_dialog"),
    )
}

/**
 * Icon-only side-panel button used in the landscape viewer (`REQ-TOK-UX.02`): named by [name] for
 * TalkBack and the long-press tooltip, 64 × 48 dp, no visible text.
 */
@Composable
private fun SidePanelButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        isActive -> colors.onPrimaryContainer
        enabled -> colors.secondary
        else -> colors.onSurface.copy(alpha = 0.25f)
    }
    NameTooltip(name = name) {
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
                        role = Role.Button,
                        onClick = onClick,
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = name,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Leading icons of the two Selection-viewer lists: a star for ★ favourites, a folder for the copies. */
private val FAVOURITES_TAB_ICON = Icons.Default.Star
private val SELECTION_TAB_ICON = Icons.Default.Folder

/**
 * Read-only viewer for the Favourites and Selection-folder lists (U-13, U-17). Two switchable tabs
 * only when both lists have photos, otherwise a single title (`REQ-TOK-FEATURE.17`).
 */
@Composable
private fun SelectionFolderViewer(
    images: List<ImageItem>,
    currentIndex: Int,
    folderName: String,
    activeTab: SelectionViewerTab,
    showTabs: Boolean,
    favouriteCount: Int,
    selectionCount: Int,
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
                    text = stringResource(
                        if (activeTab == SelectionViewerTab.FAVOURITES) {
                            R.string.empty_no_favourites
                        } else {
                            R.string.empty_selection
                        },
                    ),
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
            // Back + the two tabs take what the action icons leave; at font scale 2.0 the tabs
            // wrap onto a second line instead of pushing Share off screen or clipping a tab
            // (usability panel 2026-10-19, REQ-TOK-UX.05).
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopBarIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    name = stringResource(R.string.cd_back),
                    tint = colors.onSurface,
                    onClick = onClose,
                )
                Spacer(modifier = Modifier.width(4.dp))
                SelectionViewerTabs(
                    activeTab = activeTab,
                    showTabs = showTabs,
                    favouriteCount = favouriteCount,
                    selectionCount = selectionCount,
                    folderName = folderName,
                    onSelectTab = onSelectTab,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (activeTab == SelectionViewerTab.FAVOURITES && images.isNotEmpty()) {
                    TopBarIconButton(
                        icon = Icons.Default.ContentCopy,
                        name = stringResource(R.string.cd_copy_favourites),
                        tint = colors.onSurface,
                        onClick = onCopyFavouritesToFolder,
                    )
                }
                if (images.isNotEmpty()) {
                    TopBarIconButton(
                        icon = Icons.Default.Share,
                        name = stringResource(R.string.cd_share_all),
                        tint = colors.onSurface,
                        onClick = onShareBatch,
                    )
                }
            }
        }
    }
}

/**
 * The Selection viewer's list switch: two tabs ("Favourites 3", "Selection 12", each with its own
 * icon) when both lists have photos, otherwise a single non-clickable title in their place
 * (`REQ-TOK-FEATURE.17`). Numbers carry the information, there is no description text.
 */
@Composable
internal fun SelectionViewerTabs(
    activeTab: SelectionViewerTab,
    showTabs: Boolean,
    favouriteCount: Int,
    selectionCount: Int,
    folderName: String,
    onSelectTab: (SelectionViewerTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val favouritesLabel = stringResource(
        R.string.chip_name_count, stringResource(R.string.chip_favourites), favouriteCount,
    )
    val selectionLabel = stringResource(
        R.string.chip_name_count, folderName.ifEmpty { PhotoFolders.SELECTION }, selectionCount,
    )
    if (showTabs) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = activeTab == SelectionViewerTab.FAVOURITES,
                onClick = { onSelectTab(SelectionViewerTab.FAVOURITES) },
                leadingIcon = { Icon(FAVOURITES_TAB_ICON, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text(favouritesLabel) },
                modifier = Modifier.testTag("selection_tab_favourites"),
            )
            FilterChip(
                selected = activeTab == SelectionViewerTab.SELECTION,
                onClick = { onSelectTab(SelectionViewerTab.SELECTION) },
                leadingIcon = { Icon(SELECTION_TAB_ICON, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text(selectionLabel) },
                modifier = Modifier.testTag("selection_tab_selection"),
            )
        }
    } else {
        // Only one list has photos: a title, not a switch to an empty tab.
        Row(
            modifier = modifier.testTag("selection_title"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (activeTab == SelectionViewerTab.FAVOURITES) FAVOURITES_TAB_ICON else SELECTION_TAB_ICON,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (activeTab == SelectionViewerTab.FAVOURITES) favouritesLabel else selectionLabel,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
