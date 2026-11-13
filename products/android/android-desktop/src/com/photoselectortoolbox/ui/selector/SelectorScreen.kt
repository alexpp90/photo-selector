package com.photoselectortoolbox.ui.selector

import com.photoselectortoolbox.ui.components.handCursor
import android.app.Activity
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Popup
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoselectortoolbox.domain.format.SelectorLabels
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.domain.session.SelectorSortOrder
import com.photoselectortoolbox.ui.components.EmptyStateCard
import com.photoselectortoolbox.ui.theme.StudioCanvas
import com.photoselectortoolbox.ui.theme.StudioCard
import com.photoselectortoolbox.ui.navigation.Screen
import com.photoselectortoolbox.viewmodel.SelectorFrame
import com.photoselectortoolbox.viewmodel.SelectorViewModel
import com.photoselectortoolbox.viewmodel.isCloudProvider
import com.photoselectortoolbox.viewmodel.providerLabel

/**
 * The culling workspace.
 *
 * This composable owns orchestration only — folder pickers, dialogs, sheets,
 * keyboard handling and which layout is on screen. The layouts themselves live
 * in [ThreeUpSelectorLayout] and [CompactSelectorLayout], because the geometry
 * of each is intricate enough that mixing it with dialog plumbing is how the
 * two drift apart.
 *
 * There is no app bar. Session controls live in [SelectorSidebar] to the left;
 * everything else that used to sit in a bar — folder name, position, burst chip
 * — now sits beside the photograph it describes. On a layout bound by height
 * (see [FrameGeometry]) a 44dp bar is 29dp off every frame, which is a price
 * this screen does not pay for chrome.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SelectorScreen(
    windowSizeClass: WindowSizeClass,
    currentRoute: String? = null,
    onNavigate: (Screen) -> Unit = {},
    viewModel: SelectorViewModel = hiltViewModel(),
    /** The maximised frame's zoom (a parameter so a test can read it; production takes the default). */
    maximisedZoom: PhotoZoom = remember { PhotoZoom() },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    var showScanConfig by remember { mutableStateOf(false) }
    var showFullscreen by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var contextMenuAt by remember { mutableStateOf<Offset?>(null) }
    // The confirmation card's measured top, so the first-run pill can stack above it.
    var snackbarTop by remember { mutableStateOf<Float?>(null) }
    var lastPointerPosition by remember { mutableStateOf(Offset.Zero) }
    // The maximised frame's zoom: kept while stepping, dropped when the frame is restored.
    LaunchedEffect(uiState.maximisedFrame == null) { if (uiState.maximisedFrame == null) maximisedZoom.reset() }

    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded
    val isMedium = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Medium
    val useExpandedLayout = isExpanded || isMedium

    // The guide opens by itself once, at first launch, and on demand thereafter.
    // Both routes are the same overlay and the same dismissal, so there is one
    // "seen" fact rather than two that can disagree.
    //
    // Never while a frame is maximised: the overlay reserves the three-up
    // footprints, so over a single filled frame its callouts would land on the
    // photograph — the one thing it exists not to do. Opening it therefore
    // leaves the maximised state first.
    val guideOpen = useExpandedLayout &&
        uiState.images.isNotEmpty() &&
        uiState.maximisedFrame == null &&
        (showGuide || uiState.showIntroTour)

    val openGuide: () -> Unit = {
        viewModel.clearMaximised()
        showGuide = true
    }

    // "Go to photo" needs a photo to go to, so it never opens on an empty folder.
    val jumpOpen = showJump && uiState.images.isNotEmpty()
    val openJump: () -> Unit = { if (uiState.images.isNotEmpty()) showJump = true }

    // The Sort menu hangs off the control block, so it opens only where that block
    // is drawn: the comparison layout, with a photo, not while a frame is maximised
    // (the flanks are not composed then). `S` is not consumed anywhere else.
    val sortMenuAvailable = useExpandedLayout && uiState.images.isNotEmpty() && uiState.maximisedFrame == null
    val sortMenuOpen = showSortMenu && sortMenuAvailable
    // Where the block goes away (a frame is maximised, the folder empties) the request goes with
    // it, so the menu does not spring open when the block comes back.
    LaunchedEffect(sortMenuAvailable) { if (!sortMenuAvailable) showSortMenu = false }
    val openSortMenu: (() -> Unit)? = if (sortMenuAvailable) ({ showSortMenu = true }) else null
    val sortChoice = SortChoice.of(uiState.sortOrder, uiState.sortWorstFirst)
    val selectSortChoice: (SortChoice) -> Unit = { choice ->
        when (choice) {
            SortChoice.NEWEST_FIRST -> viewModel.setSortOrder(SelectorSortOrder.NEWEST_FIRST)
            SortChoice.OLDEST_FIRST -> viewModel.setSortOrder(SelectorSortOrder.OLDEST_FIRST)
            SortChoice.FILE_NAME -> viewModel.setSortOrder(SelectorSortOrder.FILE_NAME)
            // Worst-first triage (U-34) is the fourth member of the group: a session
            // view over scores. Picking one of the three above leaves it.
            SortChoice.BLURRIEST_FIRST -> if (!uiState.sortWorstFirst) viewModel.toggleSortWorstFirst()
        }
    }
    val sortControl = SortControl(
        selected = sortChoice,
        blurriestEnabled = uiState.hasAnyScores,
        menuOpen = sortMenuOpen,
        onMenuOpenChange = { showSortMenu = it },
        onSelect = selectSortChoice,
    )

    // Any open sheet swallows the shortcuts, so a stray M while configuring a
    // scan cannot move the frame behind the sheet. Esc is the exception — it is
    // what closes the sheet. The Go-to-photo dialog is one of them: without it,
    // typing `1` into its field would maximise a frame. So is the Sort menu: its
    // popup is a window of its own, but a key that reaches the selector while it
    // is up must not file a photo behind it.
    val sheetOpen = showScanConfig || guideOpen || jumpOpen || sortMenuOpen ||
        uiState.showDeleteConfirmation || uiState.uncompletedDeletionsPrompt != null

    val dismissGuide: () -> Unit = {
        showGuide = false
        viewModel.markGuideSeen()
    }

    // The one place that knows which layer is on top and how to close it. Escape calls it
    // (through `handleSelectorKey`) and so does system Back (the `BackHandler` below), so the
    // two cannot drift apart: one press, one layer, topmost first.
    val closeTopLayer: () -> Unit = {
        when {
            jumpOpen -> showJump = false
            sortMenuOpen -> showSortMenu = false
            showFullscreen -> showFullscreen = false
            contextMenuAt != null -> contextMenuAt = null
            showScanConfig -> showScanConfig = false
            guideOpen -> dismissGuide()
            uiState.showDeleteConfirmation -> viewModel.dismissDeleteConfirmation()
            uiState.uncompletedDeletionsPrompt != null -> viewModel.keepUncompletedDeletions()
            uiState.maximisedFrame != null -> if (maximisedZoom.isZoomed) maximisedZoom.reset() else viewModel.clearMaximised()
        }
    }

    // System Back does what Escape does. Only the layers drawn inside this window need it: the
    // dialogs, the fullscreen viewer, the sheet and the Sort menu are windows of their own and
    // take Back themselves, so handling them here too would close two layers per press. The
    // context menu is a non-focusable popup, which is never offered the key. With none of
    // these open the handler is disabled and Back falls through to the system (leaves the app).
    BackHandler(enabled = guideOpen || contextMenuAt != null || uiState.maximisedFrame != null) {
        closeTopLayer()
    }

    val dragAndDropTarget = remember(context, viewModel) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val dragEvent = event.toAndroidDragEvent()
                (context as? Activity)?.requestDragAndDropPermissions(dragEvent)
                val clipData = dragEvent.clipData
                if (clipData != null && clipData.itemCount > 0) {
                    clipData.getItemAt(0).uri?.let { uri ->
                        viewModel.selectFolder(uri)
                        return true
                    }
                }
                return false
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { viewModel.selectFolder(it) } }

    // Move, Copy and Delete all advance to the next frame: one key per decision. A copied
    // frame becomes the left "previous" frame, still there to compare against, and carries
    // the copied mark (backlog 2026-11-11; the advance is in the ViewModel).
    //
    // The confirmation wording no longer lives here. The message and its UNDO
    // are one fact about one operation, so they are decided together in the
    // ViewModel — a message set by the composable could outlive, or disagree
    // with, the operation it claims to describe.
    val actions = SelectorActions(
        onMove = viewModel::moveToSelection,
        onCopy = viewModel::copyToSelection,
        onDelete = { viewModel.requestDelete() },
        onFullscreen = { showFullscreen = true },
        onToggleDetails = viewModel::toggleDetails,
        onToggleFilmstrip = viewModel::toggleFilmstrip,
        onToggleOverlayValues = viewModel::toggleOverlayValues,
        onShowShortcuts = openGuide,
        onGoToPhoto = openJump,
    )

    // One More menu for both layouts (REQ-AND-LAYOUT.02): the sidebar's in wide windows, the
    // compact action row's below 600 dp.
    val moreMenu = SelectorMoreMenu(
        undoSteps = uiState.undoHistory.size,
        hasImages = uiState.images.isNotEmpty(),
        canScan = !uiState.isScanRunning,
        hasAnyScores = uiState.hasAnyScores,
        sortChoice = sortChoice,
        onUndo = viewModel::undoLastOperation,
        onScan = { showScanConfig = true },
        onOpenFolder = { folderPickerLauncher.launch(null) },
        onSort = selectSortChoice,
        onGoToPhoto = openJump,
        onClearScores = viewModel::clearScores,
        onShowGuide = openGuide.takeIf { useExpandedLayout },
    )

    // Errors already reached the snackbar through the ViewModel; the sticky
    // field is cleared so the same failure does not re-announce itself.
    LaunchedEffect(uiState.error) {
        if (uiState.error != null) viewModel.clearError()
    }

    // The selector's key handler owns focus whenever the selector is on screen
    // and nothing above it is. Re-claimed on every event that can take it away:
    // the first frame of images, the window regaining focus (resize, DeX window
    // switch), and any overlay closing (AD-11). Tab/arrow traversal in other
    // screens is untouched — this node exists only on the selector.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    // The guide is drawn in this window, so it does not take focus away: the selector keeps it,
    // and Esc reaches `handleSelectorKey` even when the guide opened by itself at first launch
    // (before this, nothing had asked for focus yet and Esc went nowhere).
    val windowOverlayOpen = showScanConfig || showFullscreen || jumpOpen || sortMenuOpen || contextMenuAt != null ||
        uiState.showDeleteConfirmation || uiState.uncompletedDeletionsPrompt != null
    // Keys stay live while there is something to undo, so Ctrl+Z works on the Done card too.
    val keysLive = uiState.images.isNotEmpty() || uiState.undoHistory.isNotEmpty()
    LaunchedEffect(keysLive, windowFocused, windowOverlayOpen) {
        if (keysLive && windowFocused && !windowOverlayOpen) {
            try {
                focusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                // The node may not be attached yet; the next state change retries.
                // Logged, never silent: a swallowed failure here is how the first
                // arrow key went missing for months (AD-11).
                Log.w("SelectorScreen", "Selector could not take key focus", e)
            }
        }
    }

    if (uiState.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmation() },
            // "Delete IMG_0001 +2?" and "Also deletes JPG, XMP" where the photo takes companions
            // with it ([USER 2026-11-12] decision #33 (1)); else as before.
            title = { DeleteDialogTitle(uiState.currentImage) },
            text = {
                DeleteDialogBody(
                    image = uiState.currentImage,
                    message = SelectorLabels.deleteConfirmationMessage(
                        fileName = uiState.currentImage?.fileName ?: "",
                        isCloudProvider = uiState.sourceKind.isCloudProvider,
                        providerLabel = uiState.sourceKind.providerLabel,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteCurrentImage() },
                    modifier = Modifier.testTag("dialog_confirm_delete").handCursor(),
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(modifier = Modifier.handCursor(), onClick = { viewModel.dismissDeleteConfirmation() }) { Text("Cancel") }
            },
            containerColor = StudioCard,
        )
    }

    if (jumpOpen) {
        GoToPhotoDialog(
            images = uiState.images,
            // Learn's rating filter does not reach the cull (REQ-AND-RATING.01).
            ratingFilter = RatingFilter.All,
            dates = viewModel.photoDates(),
            pickerStartDay = viewModel.pickerStartDay(),
            preview = viewModel::previewJump,
            onGo = viewModel::jumpToPhoto,
            onJumpToDate = viewModel::jumpToDate,
            onDismiss = { showJump = false },
        )
    }

    if (uiState.showCloudScanConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCloudScanConfirmation() },
            title = { Text("Cloud folder scan") },
            text = {
                Text(
                    SelectorLabels.cloudScanConfirmationMessage(
                        providerLabel = uiState.sourceKind.providerLabel,
                        imageCount = uiState.images.size,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmCloudScan() },
                    modifier = Modifier.testTag("dialog_confirm_cloud_scan").handCursor(),
                ) {
                    Text("Scan")
                }
            },
            dismissButton = {
                TextButton(modifier = Modifier.handCursor(), onClick = { viewModel.dismissCloudScanConfirmation() }) { Text("Cancel") }
            },
            containerColor = StudioCard,
        )
    }

    uiState.uncompletedDeletionsPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { viewModel.keepUncompletedDeletions() },
            title = { Text("Uncompleted deletions") },
            text = {
                Text(SelectorLabels.uncompletedDeletionsMessage(prompt.uris.size))
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmUncompletedDeletions() },
                    modifier = Modifier.testTag("dialog_confirm_uncompleted_deletions").handCursor(),
                ) {
                    Text("Delete now", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.keepUncompletedDeletions() },
                    modifier = Modifier.testTag("dialog_keep_uncompleted_deletions").handCursor(),
                ) {
                    Text("Keep")
                }
            },
            containerColor = StudioCard,
        )
    }

    if (showScanConfig) {
        ScanConfigSheet(
            onStartScan = { config ->
                showScanConfig = false
                viewModel.startScan(config.measures)
            },
            onDismiss = { showScanConfig = false },
            isExpanded = useExpandedLayout,
        )
    }

    if (showFullscreen && uiState.images.isNotEmpty()) {
        FullscreenViewer(
            images = uiState.images,
            initialIndex = uiState.currentIndex,
            onDismiss = { showFullscreen = false },
            onDelete = { index ->
                viewModel.navigateToImage(index)
                viewModel.requestDelete()
            },
            onMoveToSelection = { index ->
                viewModel.navigateToImage(index)
                viewModel.moveToSelection()
            },
            onCopyToSelection = { index ->
                viewModel.navigateToImage(index)
                viewModel.copyToSelection()
            },
            windowSizeClass = windowSizeClass,
            onPageSelected = viewModel::navigateToImage,
            fullscreenButtonsEnabled = uiState.fullscreenButtonsEnabled,
            filingAction = uiState.filingAction,
            showGestureHint = !uiState.hasSeenFullscreenHint,
            onGestureHintSeen = viewModel::markFullscreenHintSeen,
            selectionFolderName = uiState.filingFolderName,
            customDestinationName = uiState.customDestinationName,
            infoPinned = uiState.fullscreenInfoPinned,
            onToggleInfoPinned = viewModel::toggleFullscreenInfoPinned,
            // The card is hosted in the main window, and fullscreen is a second
            // window above it: without these the viewer showed no confirmation
            // and no UNDO for a move or delete made inside it.
            snackbarMessage = uiState.snackbarMessage,
            snackbarSequence = uiState.snackbarSequence,
            snackbarLifetime = uiState.snackbarLifetime,
            snackbarSpoken = uiState.snackbarSpoken,
            onUndo = if (uiState.undoOperation != null) viewModel::undoLastOperation else null,
            onDismissSnackbar = viewModel::dismissSnackbar,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioCanvas)
            // Pads for horizontal insets (cutouts) and the top status bar.
            // The top status bar is preserved ("left alone"), but its space is
            // not used by the app so that it does not overlay content.
            // On the bottom, navigation bars (launcher, back/home) are hidden
            // by the system bars controller to maximize space for the app.
            // See REQUIREMENTS §2 Edge-to-Edge and DESIGN §7.2.
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
            )
            .dragAndDropTarget(
                shouldStartDragAndDrop = { true },
                target = dragAndDropTarget,
            )
            .trackPointerPosition { lastPointerPosition = it }
            .testTag("selector_root")
            .then(
                if (keysLive) {
                    Modifier
                        // A focus *target*, not just a requester: without
                        // `focusable()` the requester had nothing to focus, the call
                        // threw and was swallowed, and the first DPAD key at launch
                        // went to Android's initial-focus pass instead (AD-11).
                        .focusRequester(focusRequester)
                        .focusable()
                        // Preview, so the selector's keys are decided before whatever
                        // descendant holds focus sees them: a sidebar item that took
                        // initial focus must not swallow the first arrow (AD-11).
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            handleSelectorKey(
                                key = event.key,
                                repeat = event.nativeKeyEvent.repeatCount > 0,
                                sheetOpen = sheetOpen,
                                contextMenuOpen = contextMenuAt != null,
                                fullscreenOpen = showFullscreen,
                                maximised = uiState.maximisedFrame != null,
                                actions = actions,
                                onPrevious = viewModel::navigatePrevious,
                                onNext = viewModel::navigateNext,
                                onMaximise = viewModel::toggleMaximised,
                                onShowShortcuts = openGuide,
                                onGoToPhoto = openJump,
                                onSortMenu = openSortMenu,
                                onCloseOverlays = closeTopLayer,
                                onNavigate = onNavigate,
                                ctrlOrMetaPressed = event.isCtrlPressed || event.isMetaPressed,
                                onUndo = viewModel::undoLastOperation,
                                onZoomToggle = maximisedZoom::toggle,
                                onPanStep = { dx -> maximisedZoom.panStep(dx, 0) },
                                shiftPressed = event.isShiftPressed,
                            )
                        }
                } else {
                    Modifier
                }
            ),
    ) {
        // One horizontal band: sidebar, then the frames. Nothing above, nothing
        // below. The 44dp app bar that used to sit here cost 29dp of height on
        // every one of the three frames — see FrameGeometry for why height is
        // the axis that must not be spent.
        Row(modifier = Modifier.fillMaxSize()) {
            if (useExpandedLayout) {
                SelectorSidebar(
                    currentRoute = currentRoute,
                    groupingEnabled = uiState.groupingEnabled,
                    hasImages = uiState.images.isNotEmpty(),
                    isScanning = uiState.isScanRunning,
                    scanStatusText = uiState.scanStatusText,
                    isGrouping = uiState.isGroupingRunning,
                    queuedWork = uiState.queuedWork,
                    onCancelQueued = viewModel::cancelQueuedWork,
                    onOpenFolder = { folderPickerLauncher.launch(null) },
                    onScan = { showScanConfig = true },
                    onCancelScan = viewModel::cancelScan,
                    onToggleGrouping = viewModel::toggleGrouping,
                    onShowLegend = openGuide,
                    onShowMenu = { showMenu = true },
                    onNavigate = onNavigate,
                    overflowContent = {
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            SelectorMoreMenuItems(moreMenu, onDismiss = { showMenu = false })
                        }
                    },
                )
            }

            Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                if (uiState.folderUri == null || uiState.images.isEmpty()) {
                    EmptySelectorState(
                        kind = selectorEmptyKind(
                            folderUri = uiState.folderUri,
                            missingGrantPrompt = uiState.missingGrantPrompt,
                            isLoading = uiState.isLoading,
                            isEnumerating = uiState.isEnumerating,
                            sessionActions = uiState.sessionMovedCount + uiState.sessionCopiedCount + uiState.sessionDeletedCount,
                        ),
                        folderName = uiState.folderName,
                        onOpenFolder = { folderPickerLauncher.launch(null) },
                        done = DoneSummary(
                            moved = uiState.sessionMovedCount,
                            copied = uiState.sessionCopiedCount,
                            deleted = uiState.sessionDeletedCount,
                            destination = DoneSummary.destinationOf(
                                uiState.folderName, uiState.filingFolderName, uiState.customDestinationName,
                            ).takeIf { uiState.sessionKeptCount > 0 },
                            safeToFormat = uiState.safeToFormatLine,
                        ),
                        onOpenSelection = viewModel::openSelection.takeIf { uiState.doneSelection != null },
                        onDone = viewModel::lookUpSelection,
                    )
                } else {
                    // Tagged because the height budget is the thing this screen
                    // is judged on: a test can measure what the frames were
                    // actually offered, rather than inferring it from the frame
                    // that came out. Two revisions shipped small because the
                    // arithmetic was reasoned about instead of measured.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("image_region"),
                    ) {
                        if (useExpandedLayout) {
                            ThreeUpSelectorLayout(
                                current = uiState.currentImage,
                                previous = uiState.previousImage,
                                next = uiState.nextImage,
                                currentIndex = uiState.currentIndex,
                                total = uiState.images.size,
                                filingAction = uiState.filingAction,
                                folderName = uiState.folderName,
                                burstLabel = burstLabelFor(uiState.groups, uiState.currentIndex)
                                    .takeIf { uiState.groupingEnabled },
                                detailsVisible = uiState.detailsVisible,
                                filmstripVisible = uiState.filmstripVisible,
                                overlayValuesVisible = uiState.overlayValuesVisible,
                                maximisedFrame = uiState.maximisedFrame,
                                maximisedZoom = maximisedZoom,
                                actions = actions,
                                onNavigatePrevious = viewModel::navigatePrevious,
                                onNavigateNext = viewModel::navigateNext,
                                onMaximise = viewModel::toggleMaximised,
                                onLongPressFrame = { contextMenuAt = lastPointerPosition },
                                showFirstRunHint = !uiState.hasSeenNavHint,
                                onDismissFirstRunHint = viewModel::markNavHintSeen,
                                stillEnumerating = uiState.isEnumerating,
                                // Suppressed while the guide is up: the guide is
                                // already explaining this screen, and two
                                // explanations of it at once is neither.
                                pendingHint = uiState.pendingHintText.takeIf { !guideOpen },
                                onDismissHint = viewModel::dismissHint,
                                copiedMark = { image ->
                                    SelectorLabels.copiedMarkSpoken(uiState.filingFolderName)
                                        .takeIf { image.uri in uiState.sessionCopiedUris }
                                },
                                onSetRating = { rating ->
                                    if (uiState.maximisedFrame != null) {
                                        viewModel.setRatingForMaximised(rating)
                                    } else {
                                        viewModel.setRatingForCurrent(rating)
                                    }
                                },
                                sort = sortControl,
                                snackbarTopInRoot = snackbarTop,
                                // Passed as a slot and drawn as a vertical column
                                // at the outer edge of the control flank. It
                                // used to span the bottom of the window, which
                                // measured 38 dp off the height of every frame
                                // on the reference device — see FrameGeometry.
                                // The maximised state does not draw the flanks
                                // at all, so the strip disappears there without
                                // a second condition.
                                // Every photo of the folder: Learn's rating filter does not
                                // reach the cull (REQ-AND-RATING.01).
                                filmstrip = {
                                    CandidateStrip(
                                        images = uiState.images,
                                        currentIndex = uiState.currentIndex,
                                        selectedUri = uiState.currentImage?.uri,
                                        onImageSelected = viewModel::navigateToImage,
                                        groups = uiState.groups.takeIf { uiState.groupingEnabled },
                                        copiedUris = uiState.sessionCopiedUris,
                                        copiedFolderName = uiState.filingFolderName,
                                        modifier = Modifier.fillMaxHeight(),
                                    )
                                },
                            )
                        } else {
                            CompactSelectorLayout(
                                uiState = uiState,
                                onNavigateToImage = viewModel::navigateToImage,
                                onFullscreen = { showFullscreen = true },
                                onMoveToSelection = actions.onMove,
                                onCopyToSelection = actions.onCopy,
                                onDelete = actions.onDelete,
                                showGestureHint = !uiState.hasSeenFullscreenHint,
                                onDismissGestureHint = viewModel::markFullscreenHintSeen,
                                moreMenu = moreMenu,
                            )
                        }
                    }
                }
            }
        }

        // `onUndo` is non-null only where `UndoPolicy` found something genuinely
        // reversible: a deferred delete, a trashable committed delete, or a move
        // that reported where the file went. A copy gets none — undoing it could
        // only mean deleting the file the photographer just asked for.
        SelectorSnackbarHost(
            message = uiState.snackbarMessage,
            onUndo = if (uiState.undoOperation != null) viewModel::undoLastOperation else null,
            onDismiss = viewModel::dismissSnackbar,
            sidebarInset = if (useExpandedLayout) SidebarWidth else 0.dp,
            sequence = uiState.snackbarSequence,
            lifetime = uiState.snackbarLifetime,
            onCardTop = { snackbarTop = it },
            spoken = uiState.snackbarSpoken,
        )

        // Last in the Box, so it is above every piece of chrome it labels —
        // that is what makes it a coach mark rather than a diagram. It reserves
        // the real geometry itself (see [SelectorCoachOverlay]), so no callout
        // can land on a frame.
        SelectorCoachOverlay(
            visible = guideOpen,
            filingAction = uiState.filingAction,
            detailsVisible = uiState.detailsVisible,
            filmstripVisible = uiState.filmstripVisible && uiState.maximisedFrame == null,
            aspect = currentFrameAspect(uiState),
            onDismiss = dismissGuide,
        )

        contextMenuAt?.let { offset ->
            Popup(
                offset = IntOffset(offset.x.toInt(), offset.y.toInt()),
                onDismissRequest = { contextMenuAt = null },
            ) {
                SelectorContextMenu(
                    actions = actions,
                    filingAction = uiState.filingAction,
                    onDismissRequest = { contextMenuAt = null },
                )
            }
        }
    }
}

/** Which card the selector shows in place of the frames. */
internal enum class SelectorEmptyKind { NO_FOLDER, MISSING_GRANT, OPENING, EMPTY_FOLDER, DONE }

/**
 * An open folder with nothing to show is not "no folder": while it is still being read the screen
 * waits, and once read it says the folder holds no photos (FD-UX-5) — unless this session emptied
 * it by filing and deleting, which is the end of the cull ([sessionActions] > 0, backlog 2026-11-11).
 */
internal fun selectorEmptyKind(
    folderUri: String?,
    missingGrantPrompt: Boolean,
    isLoading: Boolean,
    isEnumerating: Boolean,
    sessionActions: Int = 0,
): SelectorEmptyKind = when {
    missingGrantPrompt -> SelectorEmptyKind.MISSING_GRANT
    folderUri == null -> SelectorEmptyKind.NO_FOLDER
    isLoading || isEnumerating -> SelectorEmptyKind.OPENING
    sessionActions > 0 -> SelectorEmptyKind.DONE
    else -> SelectorEmptyKind.EMPTY_FOLDER
}

@Composable
internal fun EmptySelectorState(
    kind: SelectorEmptyKind,
    folderName: String,
    onOpenFolder: () -> Unit,
    done: DoneSummary? = null,
    onOpenSelection: (() -> Unit)? = null,
    /** Run when the Done card appears and whenever its counts change: looks up the Selection. */
    onDone: () -> Unit = {},
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (kind) {
            SelectorEmptyKind.MISSING_GRANT -> EmptyStateCard(
                icon = Icons.Default.FolderOpen,
                title = "Folder Access Needed",
                description = "Permission for this folder was revoked or the folder was moved. Re-select the folder to continue.",
                actionLabel = "Re-select Folder",
                onAction = onOpenFolder,
                modifier = Modifier.testTag("missing_grant_prompt_card"),
            )
            SelectorEmptyKind.OPENING -> CircularProgressIndicator(
                modifier = Modifier.testTag("opening_folder_progress"),
                color = MaterialTheme.colorScheme.primary,
            )
            SelectorEmptyKind.EMPTY_FOLDER -> EmptyStateCard(
                icon = Icons.Default.FolderOpen,
                title = "No photos found",
                description = if (folderName.isBlank()) {
                    "This folder holds no supported photos."
                } else {
                    "$folderName holds no supported photos."
                },
                actionLabel = "Open Folder",
                onAction = onOpenFolder,
                modifier = Modifier.testTag("empty_folder_card"),
            )
            SelectorEmptyKind.DONE -> {
                val summary = done ?: DoneSummary(0, 0, 0, null)
                val latestOnDone by rememberUpdatedState(onDone)
                LaunchedEffect(summary.moved, summary.copied, summary.deleted) { latestOnDone() }
                DoneCard(summary = summary, onOpenFolder = onOpenFolder, onOpenSelection = onOpenSelection)
            }
            SelectorEmptyKind.NO_FOLDER -> EmptyStateCard(
                icon = Icons.Default.PhotoCamera,
                title = "Select a Folder",
                description = "Choose a shoot folder to start comparing and culling frames.",
                actionLabel = "Open Folder",
                onAction = onOpenFolder,
            )
        }
    }
}

/**
 * Keyboard and DeX shortcuts.
 *
 * Extracted as a plain function so the routing — including "shortcuts are
 * suppressed while a sheet is open, except Escape" — can be reasoned about and
 * tested without composing the screen.
 */
/**
 * Keys that file or delete a photo. A held key auto-repeats (about 30 a second on a DeX or
 * keyboard dock); repeated, these would delete or file frame after frame — past 20 deletes the
 * oldest are committed to disk, and a repeated Copy cannot be undone at all. So only the first
 * press of each acts, and its repeats are swallowed (FD-D-2). Arrows keep repeating on purpose.
 */
internal val ONE_SHOT_KEYS: Set<Key> = setOf(Key.Delete, Key.Backspace, Key.M, Key.C)

/** True for an auto-repeat of a [ONE_SHOT_KEYS] key: consume it and do nothing. */
internal fun isHeldOneShotKey(key: Key, repeat: Boolean): Boolean = repeat && key in ONE_SHOT_KEYS

internal fun handleSelectorKey(
    key: Key,
    sheetOpen: Boolean,
    contextMenuOpen: Boolean,
    fullscreenOpen: Boolean,
    maximised: Boolean,
    actions: SelectorActions,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMaximise: (SelectorFrame) -> Unit,
    onShowShortcuts: () -> Unit,
    onCloseOverlays: () -> Unit,
    onGoToPhoto: (() -> Unit)? = null,
    onSortMenu: (() -> Unit)? = null,
    onNavigate: ((Screen) -> Unit)? = null,
    ctrlOrMetaPressed: Boolean = false,
    onUndo: (() -> Unit)? = null,
    /** The key event is an auto-repeat of a held key ([isHeldOneShotKey]). */
    repeat: Boolean = false,
    /** `Space` on a maximised frame: fit ↔ 100 %. */
    onZoomToggle: (() -> Unit)? = null,
    /** `Shift`+`←`/`→` on a maximised frame: pan; false when it is not zoomed (the key steps). */
    onPanStep: ((Int) -> Boolean)? = null,
    shiftPressed: Boolean = false,
): Boolean {
    if (key == Key.Escape) {
        if (sheetOpen || contextMenuOpen || fullscreenOpen || maximised) {
            onCloseOverlays()
            return true
        }
        return false
    }

    if (sheetOpen) return false

    if (ctrlOrMetaPressed) {
        if (key == Key.Z) {
            onUndo?.invoke()
            return true
        }
        return false
    }

    if (isHeldOneShotKey(key, repeat)) return true

    if (shiftPressed && maximised && (key == Key.DirectionLeft || key == Key.DirectionRight) &&
        onPanStep?.invoke(if (key == Key.DirectionLeft) -1 else 1) == true
    ) {
        return true
    }

    return when (key) {
        Key.Spacebar -> {
            if (maximised && onZoomToggle != null) {
                onZoomToggle()
                true
            } else {
                false
            }
        }
        // The desk-app key table (docs/shared/KEYBOARD.md): L opens Learn on Frameduel only;
        // I is the info (readout) block and T the filmstrip in every desk app, so I no longer
        // opens Learn; J / K step like the arrows, as on Lensjet and Blitzlens.
        Key.L -> {
            onNavigate?.invoke(Screen.Inspect)
            true
        }
        Key.I -> { actions.onToggleDetails(); true }
        Key.T -> { actions.onToggleFilmstrip(); true }
        Key.DirectionLeft, Key.K -> { onPrevious(); true }
        Key.DirectionRight, Key.J -> { onNext(); true }
        Key.M -> { actions.onMove(); true }
        Key.C -> { actions.onCopy(); true }
        Key.Delete, Key.Backspace -> { actions.onDelete(); true }
        Key.F -> { actions.onFullscreen(); true }
        // 1/2/3 maximise the frame in that position, left to right as they
        // appear on screen.
        Key.One -> { onMaximise(SelectorFrame.PREVIOUS); true }
        Key.Two -> { onMaximise(SelectorFrame.CURRENT); true }
        Key.Three -> { onMaximise(SelectorFrame.NEXT); true }
        Key.Slash -> { onShowShortcuts(); true }
        Key.G -> {
            if (onGoToPhoto == null) {
                false
            } else {
                onGoToPhoto()
                true
            }
        }
        // Opens the Sort menu; it never cycles the order, so a stray press cannot
        // reorder a folder the photographer is part-way through.
        Key.S -> {
            if (onSortMenu == null) {
                false
            } else {
                onSortMenu()
                true
            }
        }
        else -> false
    }
}


/**
 * The aspect ratio the frame solver is currently using, landscape-normalised.
 *
 * Extracted so the coach-mark overlay reserves the same footprints the layout
 * drew — the solver is shared, so the *input* has to be shared too, or the
 * overlay would reserve 4:3 boxes over 3:2 frames and its callouts would drift
 * onto the photographs.
 */
internal fun currentFrameAspect(uiState: com.photoselectortoolbox.viewmodel.SelectorUiState): Float {
    val active = uiState.currentImage ?: uiState.previousImage ?: uiState.nextImage
    val raw = active?.aspectRatio ?: return FrameGeometry.DefaultLandscapeAspect
    return if (raw < 1f) 1f / raw else raw
}

/**
 * The `burst 3/7` label for the frame at [currentIndex], or null when it is
 * not part of a series.
 */
internal fun burstLabelFor(groups: List<List<Int>>, currentIndex: Int): String? {
    val series = groups.firstOrNull { currentIndex in it } ?: return null
    return SelectorLabels.burstChip(
        indexInSeries = series.indexOf(currentIndex),
        seriesLength = series.size,
    )
}
