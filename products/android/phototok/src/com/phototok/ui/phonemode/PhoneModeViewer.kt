package com.phototok.ui.phonemode

import androidx.compose.foundation.layout.FlowRow
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.photoselector.core.model.ExifData
import com.phototok.data.model.ImageItem
import com.phototok.domain.CollectionAction
import com.phototok.domain.FavouritesSnapshot
import com.phototok.domain.FirstRunHint
import com.phototok.domain.SeenStore
import com.phototok.domain.SwipeAction
import com.phototok.R
import com.phototok.domain.SwipeLabels
import com.phototok.ui.asString
import com.phototok.domain.SwipeRightVerb
import com.phototok.ui.theme.SuccessGreen
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How many images ahead/behind to warm into Coil's caches for snappy paging. */
private const val PREFETCH_RADIUS = 3
private const val CLOUD_PREFETCH_RADIUS = 1

/** Glyph for the configured swipe-right verb. */
private fun swipeRightIcon(verb: SwipeRightVerb): ImageVector = when (verb) {
    SwipeRightVerb.COPY -> Icons.Default.ContentCopy
    SwipeRightVerb.MOVE -> Icons.AutoMirrored.Filled.DriveFileMove
    SwipeRightVerb.FAVOURITE -> Icons.Default.Star
}

/** Glyph for the configured swipe-left action. */
private fun leftSwipeIcon(action: SwipeAction): ImageVector = when (action) {
    SwipeAction.DELETE -> Icons.Default.Delete
    SwipeAction.COPY -> Icons.Default.ContentCopy
    SwipeAction.MOVE -> Icons.AutoMirrored.Filled.DriveFileMove
}

/**
 * Full-screen TikTok-style vertical-pager image viewer.
 *
 * - Swipe up/down → navigate images
 * - Swipe right → add to collection or toggle favourite (green check / star flash)
 * - Swipe left → delete (card rotates, pulsing trash indicator)
 * - Single tap → toggle HUD overlay
 * - Long press → Share / Open in another app / Show file details
 * - Gradient vignettes for readability
 *
 * When [readOnly] is true (selection-folder preview) the horizontal add/delete
 * gestures and their indicators are disabled — only vertical paging + HUD remain.
 */
@Composable
fun PhoneModeViewer(
    images: List<ImageItem>,
    currentIndex: Int,
    portraitSectionStart: Int,
    onNavigate: (Int) -> Unit,
    onAddToCollection: () -> Unit,
    onRequestDelete: () -> Unit,
    leftSwipeAction: SwipeAction = SwipeAction.DELETE,
    leftSwipeFolderName: String = "",
    collectionAction: CollectionAction = CollectionAction.DEFAULT,
    swipeRightVerb: SwipeRightVerb = SwipeRightVerb.fromCollectionAction(collectionAction),
    collectionFolderName: String = "",
    showExifOverlay: Boolean = true,
    showPageCounter: Boolean = true,
    /**
     * Fades out the EXIF card and page counter, e.g. while a first-run hint or the controls
     * guide covers the bottom of the screen.
     */
    bottomHudSuppressed: Boolean = false,
    /**
     * Hides the favourite / RAW+JPG badges under the top bar, where the controls guide puts
     * its callouts, so they do not show through the guide's scrim behind the callout text.
     */
    topBadgesSuppressed: Boolean = false,
    /** True while the folder is still being enumerated (page count still grows). */
    isDiscovering: Boolean = false,
    readOnly: Boolean = false,
    isCloudSource: Boolean = false,
    rawCompanionUris: Set<String> = emptySet(),
    favourites: FavouritesSnapshot = FavouritesSnapshot(),
    onCardDwelled: (ImageItem) -> Unit = {},
    onShareCurrentPhoto: () -> Unit = {},
    onOpenWithCurrentPhoto: () -> Unit = {},
    /** Long-press "Favourite" / "Remove favourite"; null hides the item (read-only viewers). */
    onToggleFavourite: (() -> Unit)? = null,
    onFirstRunHint: (FirstRunHint) -> Unit = {},
    /**
     * False while a swipe right cannot copy yet (a Casual card import still waiting for the phone's
     * camera folder grant): the card snaps back and no "copied" flash is played for nothing.
     */
    collectionReady: Boolean = true,
) {
    if (images.isEmpty()) return
    val collectionReadyNow by rememberUpdatedState(collectionReady)

    val pagerState = rememberPagerState(
        initialPage = currentIndex,
        pageCount = { images.size },
    )

    var isZoomed by remember { mutableStateOf(false) }
    var showLongPressMenu by remember { mutableStateOf(false) }
    var showFileDetailsDialog by remember { mutableStateOf(false) }

    // ── Prefetch upcoming/previous images into Coil caches ───────────────
    val context = LocalContext.current
    val prefetchRadius = if (isCloudSource) CLOUD_PREFETCH_RADIUS else PREFETCH_RADIUS
    LaunchedEffect(pagerState.currentPage, images, prefetchRadius) {
        val center = pagerState.currentPage
        for (offset in 1..prefetchRadius) {
            listOf(center + offset, center - offset)
                .filter { it in images.indices }
                .forEach { idx ->
                    val request = ImageRequest.Builder(context)
                        .data(android.net.Uri.parse(images[idx].uri))
                        .build()
                    context.imageLoader.enqueue(request)
                }
        }
    }

    // Sync pager → ViewModel. Only a settle that ends a scroll is reported. The pager is keyed
    // by URI, so when the list changes (delete, Revert, discovery) it re-indexes itself to
    // follow the visible photo and `settledPage` changes *without* any scroll; the ViewModel has
    // already chosen the index in that case and must not be overwritten (TOK-2).
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    // `settledPage` is updated as the scroll stops; read it a frame later.
                    withFrameNanos { }
                    if (!pagerState.isScrollInProgress) currentOnNavigate(pagerState.settledPage)
                }
            }
    }

    // U-15: 500ms dwell timer on settledPage marks photo as seen
    val currentOnCardDwelled by rememberUpdatedState(onCardDwelled)
    LaunchedEffect(pagerState.settledPage, images, readOnly) {
        if (!readOnly) {
            val page = pagerState.settledPage
            val item = images.getOrNull(page)
            if (item != null) {
                delay(SeenStore.SEEN_DWELL_MS)
                if (pagerState.settledPage == page) {
                    currentOnCardDwelled(item)
                }
            }
        }
    }

    // Sync ViewModel → pager. A copy advances the index from the ViewModel, so an
    // adjacent step is animated and reads as the swipe the user just made; larger
    // jumps (position restore, index shifts after a delete) and list-only changes (Revert
    // re-inserting the photo under the pager) snap. It waits one frame so the URI-keyed pager
    // has applied its key-follow before currentPage is compared with the ViewModel's index.
    var lastSyncedIndex by remember { mutableIntStateOf(currentIndex) }
    LaunchedEffect(currentIndex, images) {
        val indexChanged = currentIndex != lastSyncedIndex
        lastSyncedIndex = currentIndex
        withFrameNanos { }
        val page = pagerState.currentPage
        if (page != currentIndex && currentIndex in images.indices) {
            if (indexChanged && abs(currentIndex - page) == 1) {
                pagerState.animateScrollToPage(currentIndex)
            } else {
                pagerState.scrollToPage(currentIndex)
            }
        }
    }

    // Collection flash feedback
    var showCollectionFlash by remember { mutableStateOf(false) }
    LaunchedEffect(showCollectionFlash) {
        if (showCollectionFlash) {
            delay(900)
            showCollectionFlash = false
        }
    }

    var showLeftSwipeFlash by remember { mutableStateOf(false) }
    LaunchedEffect(showLeftSwipeFlash) {
        if (showLeftSwipeFlash) {
            delay(900)
            showLeftSwipeFlash = false
        }
    }

    val maxPageSeen = remember(images) { mutableStateOf(currentIndex) }
    val startPage = remember(images) { currentIndex }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage > maxPageSeen.value) {
            maxPageSeen.value = pagerState.currentPage
        }
        // No hint on the first page change: the user just watched it happen, and the
        // controls guide already shows up/down (explain once, REQ-TOK-UX.04).
        isZoomed = false
    }
    val showFloatingPeeks = maxPageSeen.value < 3

    // HUD visibility toggle (single tap)
    var hudVisible by remember { mutableStateOf(true) }
    val hudAlpha by animateFloatAsState(
        targetValue = if (hudVisible) 1f else 0f,
        animationSpec = tween(300),
        label = "hud", // ui-text: ignore animation debug label
    )

    val bottomHudFade by animateFloatAsState(
        targetValue = if (bottomHudSuppressed) 0f else 1f,
        animationSpec = tween(200),
        label = "bottom-hud", // ui-text: ignore animation debug label
    )
    val bottomHudAlpha = hudAlpha * bottomHudFade

    val colors = MaterialTheme.colorScheme
    val currentImage = images.getOrNull(pagerState.currentPage)
    val shareLabel = stringResource(R.string.action_share)
    val openWithLabel = stringResource(R.string.action_open_with)
    val detailsLabel = stringResource(R.string.action_details)
    val currentIsFavourite = currentImage?.let { favourites.isFavourite(it.uri) } == true
    val favouriteLabel = stringResource(if (currentIsFavourite) R.string.action_unfavourite else R.string.action_favourite)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(shareLabel) {
                        onShareCurrentPhoto()
                        true
                    },
                    CustomAccessibilityAction(openWithLabel) {
                        onOpenWithCurrentPhoto()
                        true
                    },
                    CustomAccessibilityAction(detailsLabel) {
                        showFileDetailsDialog = true
                        true
                    },
                ) + listOfNotNull(
                    onToggleFavourite?.let { toggle ->
                        CustomAccessibilityAction(favouriteLabel) {
                            toggle()
                            true
                        }
                    },
                )
            },
    ) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("viewer.photo"),
            userScrollEnabled = !isZoomed,
            key = { images[it].uri },
            beyondViewportPageCount = if (isCloudSource) 1 else 2,
        ) { page ->
            val pageItem = images[page]
            ImagePage(
                image = pageItem,
                pageIndex = page,
                totalCount = images.size,
                isOrientationDivider = portraitSectionStart in 1 until images.size && page == portraitSectionStart,
                showExifOverlay = showExifOverlay,
                showPageCounter = showPageCounter,
                isDiscovering = isDiscovering,
                hudAlpha = hudAlpha,
                bottomHudAlpha = bottomHudAlpha,
                readOnly = readOnly,
                showFloatingPeeks = showFloatingPeeks,
                hasRawCompanion = pageItem.uri in rawCompanionUris,
                topBadgesSuppressed = topBadgesSuppressed,
                isFavourite = favourites.isFavourite(pageItem.uri),
                isFavouriteSynced = favourites.isSynced(pageItem.uri),
                onFirstRunHint = onFirstRunHint,
                onSingleTap = {
                    hudVisible = !hudVisible
                    if (!readOnly) onFirstRunHint(FirstRunHint.TAP_HUD)
                },
                onLongPress = {
                    showLongPressMenu = true
                },
                onSwipeLeftDelete = {
                    onRequestDelete()
                    if (leftSwipeAction != SwipeAction.DELETE) {
                        showLeftSwipeFlash = true
                    }
                },
                onSwipeRightCollect = {
                    onAddToCollection()
                    if (collectionReadyNow) showCollectionFlash = true
                },
                leftSwipeAction = leftSwipeAction,
                leftSwipeFolderName = leftSwipeFolderName,
                swipeRightVerb = swipeRightVerb,
                collectionFolderName = collectionFolderName,
                isActive = page == pagerState.currentPage,
                onZoomChanged = { zoomed ->
                    if (page == pagerState.currentPage) {
                        isZoomed = zoomed
                    }
                }
            )
        }

        // ── Long-press action menu (U-13) ────────────────────────────
        Box(modifier = Modifier.align(Alignment.Center)) {
            DropdownMenu(
                expanded = showLongPressMenu,
                onDismissRequest = { showLongPressMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(shareLabel) },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = {
                        showLongPressMenu = false
                        onShareCurrentPhoto()
                    },
                )
                DropdownMenuItem(
                    text = { Text(openWithLabel) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                    onClick = {
                        showLongPressMenu = false
                        onOpenWithCurrentPhoto()
                    },
                )
                if (onToggleFavourite != null) {
                    DropdownMenuItem(
                        text = { Text(favouriteLabel) },
                        leadingIcon = {
                            Icon(if (currentIsFavourite) Icons.Default.StarBorder else Icons.Default.Star, contentDescription = null)
                        },
                        onClick = {
                            showLongPressMenu = false
                            onToggleFavourite()
                        },
                        modifier = Modifier.testTag("menu_toggle_favourite"),
                    )
                }
                DropdownMenuItem(
                    text = { Text(detailsLabel) },
                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                    onClick = {
                        showLongPressMenu = false
                        showFileDetailsDialog = true
                    },
                )
            }
        }

        if (showFileDetailsDialog && currentImage != null) {
            AlertDialog(
                onDismissRequest = { showFileDetailsDialog = false },
                title = { Text(currentImage.fileName) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.label_resolution, currentImage.imageWidth, currentImage.imageHeight))
                        Text(stringResource(R.string.label_file_size, currentImage.fileSize / 1024))
                        currentImage.mimeType?.let { Text(it) }
                        if (currentImage.uri in rawCompanionUris) {
                            Text(stringResource(R.string.label_raw_jpeg_pair))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showFileDetailsDialog = false }) {
                        Text(stringResource(R.string.action_close))
                    }
                },
            )
        }

        // ── Collection / Favourite flash (centered glyph with glow) ────
        AnimatedVisibility(
            visible = showCollectionFlash,
            modifier = Modifier.align(Alignment.Center),
            enter = scaleIn(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = scaleOut(tween(300)) + fadeOut(tween(300)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(SuccessGreen.copy(alpha = 0.15f))
                        .border(1.dp, SuccessGreen.copy(alpha = 0.3f), CircleShape),
                )
                Icon(
                    imageVector = swipeRightIcon(swipeRightVerb),
                    contentDescription = SwipeLabels.rightDescription(
                        swipeRightVerb,
                        collectionFolderName,
                    ).asString(),
                    tint = SuccessGreen.copy(alpha = 0.9f),
                    modifier = Modifier
                        .size(72.dp)
                        .shadow(20.dp, CircleShape, ambientColor = SuccessGreen),
                )
            }
        }

        // ── Left Swipe flash (centered copy/move glyph with glow) ────
        AnimatedVisibility(
            visible = showLeftSwipeFlash,
            modifier = Modifier.align(Alignment.Center),
            enter = scaleIn(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = scaleOut(tween(300)) + fadeOut(tween(300)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                val primaryColor = MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(primaryColor.copy(alpha = 0.15f))
                        .border(1.dp, primaryColor.copy(alpha = 0.3f), CircleShape),
                )
                Icon(
                    imageVector = leftSwipeIcon(leftSwipeAction),
                    contentDescription = SwipeLabels.leftDescription(
                        leftSwipeAction,
                        leftSwipeFolderName,
                    ).asString(),
                    tint = primaryColor.copy(alpha = 0.9f),
                    modifier = Modifier
                        .size(72.dp)
                        .shadow(20.dp, CircleShape, ambientColor = primaryColor),
                )
            }
        }
    }
}

/**
 * A single page in the vertical pager.
 * Handles horizontal-drag gestures: swipe-left = delete, swipe-right = add to collection / favourite.
 */
@Composable
private fun ImagePage(
    image: ImageItem,
    pageIndex: Int,
    totalCount: Int,
    isOrientationDivider: Boolean,
    showExifOverlay: Boolean,
    showPageCounter: Boolean,
    isDiscovering: Boolean,
    hudAlpha: Float,
    bottomHudAlpha: Float,
    readOnly: Boolean,
    showFloatingPeeks: Boolean,
    hasRawCompanion: Boolean,
    topBadgesSuppressed: Boolean,
    isFavourite: Boolean,
    isFavouriteSynced: Boolean = false,
    leftSwipeAction: SwipeAction,
    leftSwipeFolderName: String,
    swipeRightVerb: SwipeRightVerb,
    collectionFolderName: String,
    isActive: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onSingleTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeLeftDelete: () -> Unit,
    onSwipeRightCollect: () -> Unit,
    onFirstRunHint: (FirstRunHint) -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val coroutineScope = rememberCoroutineScope()

    var horizontalDragOffset by remember { mutableFloatStateOf(0f) }
    val actionThreshold = 200f
    val swipeProgress = (abs(horizontalDragOffset) / actionThreshold).coerceIn(0f, 1f)
    val isSwipingLeft = horizontalDragOffset < -40f   // delete
    val isSwipingRight = horizontalDragOffset > 40f    // collect

    val isZoomed = scale > 1.05f

    LaunchedEffect(isZoomed) {
        onZoomChanged(isZoomed)
    }

    LaunchedEffect(isActive) {
        if (!isActive) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    suspend fun animateReset() {
        val startScale = scale
        val startOffset = offset
        androidx.compose.animation.core.animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
        ) { progress, _ ->
            scale = startScale + (1f - startScale) * progress
            offset = startOffset + (Offset.Zero - startOffset) * progress
        }
    }

    suspend fun animateZoomTo(targetScale: Float, targetOffset: Offset) {
        val startScale = scale
        val startOffset = offset
        androidx.compose.animation.core.animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
        ) { progress, _ ->
            scale = startScale + (targetScale - startScale) * progress
            offset = startOffset + (targetOffset - startOffset) * progress
        }
    }

    val currentOnSingleTap by rememberUpdatedState(onSingleTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnFirstRunHint by rememberUpdatedState(onFirstRunHint)

    val colors = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentOnSingleTap() },
                    onLongPress = { currentOnLongPress() },
                    onDoubleTap = { centroid ->
                        val width = size.width
                        val height = size.height
                        if (width > 0 && height > 0) {
                            currentOnFirstRunHint(FirstRunHint.DOUBLE_TAP_ZOOM)
                            if (scale > 1.05f) {
                                coroutineScope.launch { animateReset() }
                            } else {
                                val centroidFromCenter = centroid - Offset(width / 2f, height / 2f)
                                val targetScale = 2.5f
                                val targetOffset = centroidFromCenter * (1f - targetScale)
                                val maxOffsetX = ((targetScale - 1f) * width) / 2f
                                val maxOffsetY = ((targetScale - 1f) * height) / 2f
                                val boundedTargetOffset = Offset(
                                    x = targetOffset.x.coerceIn(-maxOffsetX, maxOffsetX),
                                    y = targetOffset.y.coerceIn(-maxOffsetY, maxOffsetY)
                                )
                                coroutineScope.launch { animateZoomTo(targetScale, boundedTargetOffset) }
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val width = size.width
                    val height = size.height

                    if (width > 0 && height > 0) {
                        do {
                            val event = awaitPointerEvent()
                            val pointerCount = event.changes.size
                            if (pointerCount > 1) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid()

                                if (zoom != 1f || pan != Offset.Zero) {
                                    val oldScale = scale
                                    val newScale = (scale * zoom).coerceIn(0.7f, 6f)
                                    val centroidFromCenter = centroid - Offset(width / 2f, height / 2f)

                                    val targetOffset = centroidFromCenter - (centroidFromCenter - offset) * (newScale / oldScale) + pan
                                    val maxOffsetX = (maxOf(0f, newScale - 1f) * width) / 2f
                                    val maxOffsetY = (maxOf(0f, newScale - 1f) * height) / 2f

                                    scale = newScale
                                    offset = Offset(
                                        x = targetOffset.x.coerceIn(-maxOffsetX, maxOffsetX),
                                        y = targetOffset.y.coerceIn(-maxOffsetY, maxOffsetY)
                                    )

                                    event.changes.forEach { it.consume() }
                                }
                            } else if (pointerCount == 1 && scale > 1.05f) {
                                val canceled = event.changes.any { it.isConsumed }
                                if (!canceled) {
                                    val change = event.changes.first()
                                    val dragAmount = change.position - change.previousPosition
                                    if (dragAmount != Offset.Zero) {
                                        val maxOffsetX = ((scale - 1f) * width) / 2f
                                        val maxOffsetY = ((scale - 1f) * height) / 2f
                                        offset = Offset(
                                            x = (offset.x + dragAmount.x).coerceIn(-maxOffsetX, maxOffsetX),
                                            y = (offset.y + dragAmount.y).coerceIn(-maxOffsetY, maxOffsetY)
                                        )
                                        change.consume()
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (scale < 1.05f) {
                            coroutineScope.launch { animateReset() }
                        } else if (scale > 4f) {
                            coroutineScope.launch {
                                val maxOffsetX = ((4f - 1f) * width) / 2f
                                val maxOffsetY = ((4f - 1f) * height) / 2f
                                val boundedOffset = Offset(
                                    x = offset.x.coerceIn(-maxOffsetX, maxOffsetX),
                                    y = offset.y.coerceIn(-maxOffsetY, maxOffsetY)
                                )
                                animateZoomTo(4f, boundedOffset)
                            }
                        }
                    }
                }
            },
    ) {
        // ── Image with swipe gestures (rotation + translation) ───────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .graphicsLayer {
                    translationX = horizontalDragOffset
                    rotationZ = 2f * swipeProgress * (if (horizontalDragOffset < 0f) -1f else 1f)
                    alpha = 1f - swipeProgress * 0.3f
                }
                .then(
                    if (readOnly || isZoomed) Modifier else Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (horizontalDragOffset < -actionThreshold) {
                                    onSwipeLeftDelete()
                                } else if (horizontalDragOffset > actionThreshold) {
                                    onSwipeRightCollect()
                                }
                                horizontalDragOffset = 0f
                            },
                            onDragCancel = { horizontalDragOffset = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                horizontalDragOffset += dragAmount
                            },
                        )
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            val parsedUri = remember(image.uri) { android.net.Uri.parse(image.uri) }
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            SubcomposeAsyncImage(
                model = parsedUri,
                contentDescription = image.fileName,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .then(
                        if (!isLandscape) {
                            Modifier.padding(top = 64.dp, bottom = 80.dp)
                        } else {
                            Modifier
                        }
                    ),
                contentScale = ContentScale.Fit,
                loading = {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = colors.primary
                        )
                    }
                }
            )
        }

        // ── Gradient vignettes for readability ───────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.25f)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.33f)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                    ),
                ),
        )

        // ── Collection / Favourite indicator (right-swipe) ───────────
        if (!readOnly && isSwipingRight) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(SuccessGreen.copy(alpha = 0.25f)),
                    )
                    Box(
                        modifier = Modifier
                            .size((64 + 16 * swipeProgress).dp)
                            .shadow(
                                elevation = (20 * swipeProgress).dp,
                                shape = CircleShape,
                                ambientColor = SuccessGreen.copy(alpha = 0.4f),
                            )
                            .clip(CircleShape)
                            .background(SuccessGreen.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = swipeRightIcon(swipeRightVerb),
                            contentDescription = SwipeLabels.rightDescription(
                                swipeRightVerb,
                                collectionFolderName,
                            ).asString(),
                            tint = Color.White,
                            modifier = Modifier.size((28 + 8 * swipeProgress).dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = SwipeLabels.rightLabel(swipeRightVerb).asString().uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp),
                    color = SuccessGreen,
                )
            }
        }

        // ── Left Swipe Action indicator (pulsing indicator with glow) ───────────────
        if (!readOnly && isSwipingLeft) {
            val pulseTransition = rememberInfiniteTransition(label = "left-swipe-pulse") // ui-text: ignore animation debug label
            val pulseScale by pulseTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "left-swipe-scale", // ui-text: ignore animation debug label
            )

            val isDelete = SwipeLabels.leftIsDestructive(leftSwipeAction)
            val icon = leftSwipeIcon(leftSwipeAction)
            val color = if (isDelete) colors.error else colors.primary
            val containerColor = if (isDelete) colors.errorContainer else colors.primaryContainer
            val onContainerColor = if (isDelete) colors.onErrorContainer else colors.onPrimaryContainer
            val labelText = SwipeLabels.leftLabel(leftSwipeAction).asString().uppercase()
            val contentDesc = SwipeLabels.leftDescription(leftSwipeAction, leftSwipeFolderName).asString()

            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size((96 * pulseScale).dp)
                            .clip(CircleShape)
                            .background(containerColor.copy(alpha = 0.3f)),
                    )
                    Box(
                        modifier = Modifier
                            .size((64 + 16 * swipeProgress).dp)
                            .shadow(
                                elevation = (20 * swipeProgress).dp,
                                shape = CircleShape,
                                ambientColor = color.copy(alpha = 0.4f),
                            )
                            .clip(CircleShape)
                            .background(containerColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = contentDesc,
                            tint = onContainerColor,
                            modifier = Modifier.size((28 + 8 * swipeProgress).dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = labelText,
                    style = MaterialTheme.typography.labelLarge.copy(
                        letterSpacing = 3.sp,
                    ),
                    color = color,
                )
            }
        }

        // ── Navigation peeks (subtle idle hints on left/right edges with direction arrows) ──
        if (!readOnly && showFloatingPeeks && !isZoomed && !isSwipingLeft && !isSwipingRight && hudAlpha > 0.5f) {
            val peekTransition = rememberInfiniteTransition(label = "peeks") // ui-text: ignore animation debug label
            val trashPeekOffset by peekTransition.animateFloat(
                initialValue = 12f,
                targetValue = -4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "trash-peek-offset", // ui-text: ignore animation debug label
            )
            val keepPeekOffset by peekTransition.animateFloat(
                initialValue = -12f,
                targetValue = 4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "keep-peek-offset", // ui-text: ignore animation debug label
            )

            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = keepPeekOffset.dp)
                    .graphicsLayer { alpha = hudAlpha * 0.4f },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceContainerHigh.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = swipeRightIcon(swipeRightVerb),
                        contentDescription = stringResource(
                            R.string.cd_peek_right,
                            SwipeLabels.rightDescription(swipeRightVerb, collectionFolderName).asString(),
                        ),
                        tint = SuccessGreen,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = SuccessGreen,
                    modifier = Modifier.size(16.dp),
                )
            }

            val isDelete = SwipeLabels.leftIsDestructive(leftSwipeAction)
            val icon = leftSwipeIcon(leftSwipeAction)
            val color = if (isDelete) colors.error else colors.primary
            val contentDesc = stringResource(
                R.string.cd_peek_left,
                SwipeLabels.leftDescription(leftSwipeAction, leftSwipeFolderName).asString(),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = trashPeekOffset.dp)
                    .graphicsLayer { alpha = hudAlpha * 0.4f },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceContainerHigh.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = contentDesc,
                        tint = color,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // ── Orientation section divider ──────────────────────────────
        if (isOrientationDivider) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp)
                    .graphicsLayer { alpha = hudAlpha },
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.primaryContainer.copy(alpha = 0.85f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = null,
                            tint = colors.onPrimaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.label_portrait_section),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onPrimaryContainer,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // ── Persistent Favourite badge + RAW+JPG badge (top-start below app bar) ──
        if ((isFavourite || hasRawCompanion) && hudAlpha > 0f && !topBadgesSuppressed) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .testTag("viewer.badges")
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 56.dp)
                    .graphicsLayer { alpha = hudAlpha },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isFavourite) {
                    FavouriteBadge(synced = isFavouriteSynced)
                }
                if (hasRawCompanion) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = colors.secondaryContainer.copy(alpha = 0.85f),
                    ) {
                        Text(
                            text = stringResource(R.string.chip_raw_jpg),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSecondaryContainer,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // ── Bottom HUD: EXIF card (start) and page counter (end) in ONE row, so a wide
        // card (large font scale) shrinks instead of running under the counter ─────────
        val exifForRow = if (showExifOverlay) image.exifData else null
        if (bottomHudAlpha > 0f && (exifForRow != null || showPageCounter)) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 100.dp)
                    .graphicsLayer { alpha = bottomHudAlpha },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                if (exifForRow != null) {
                    ExifOverlay(
                        exif = exifForRow,
                        fileName = image.fileName,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(end = 8.dp),
                    )
                } else {
                    Spacer(modifier = Modifier.width(0.dp))
                }
                if (showPageCounter) {
                    Surface(
                        modifier = Modifier.testTag("viewer.counter"),
                        shape = RoundedCornerShape(50),
                        color = colors.surfaceContainerHigh.copy(alpha = 0.8f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            colors.outline.copy(alpha = 0.2f),
                        ),
                    ) {
                        Text(
                            text = stringResource(
                                if (isDiscovering) R.string.label_page_counter_more else R.string.label_page_counter,
                                pageIndex + 1,
                                totalCount,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Glassmorphic EXIF overlay ────────────────────────────────────────────

@Composable
private fun ExifOverlay(
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
    if (items.isEmpty() && exif.lens == "Unknown") return

    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.testTag("viewer.info"),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerLowest.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            colors.outlineVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = fileName.uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.sp),
                color = colors.onSurface.copy(alpha = 0.7f),
                // One line; a long name loses its middle, never its extension (font scale 2.0).
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )

            if (items.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = colors.primary,
                    )
                    // Each value is one unbreakable token; at font scale 2.0 they flow onto a
                    // second line instead of breaking inside "f/2.8" (REQ-TOK-UX.05).
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items.forEachIndexed { i, item ->
                            Text(
                                text = if (i == 0) item else "· $item",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
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
                        modifier = Modifier.size(14.dp),
                        tint = colors.tertiary,
                    )
                    Text(
                        text = exif.lens,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The favourite badge under the top bar (`U-17`). A filled star means the system favourite flag
 * (MediaStore `IS_FAVORITE`) is set too; an outlined star means the favourite is only in Jackpix
 * for now (not synced yet, a source MediaStore does not index, or Android 10 and older).
 */
@Composable
internal fun FavouriteBadge(synced: Boolean) {
    val colors = MaterialTheme.colorScheme
    val stateDescription = stringResource(
        if (synced) R.string.cd_favourite_in_system else R.string.cd_favourite_only_here,
    )
    Surface(
        shape = RoundedCornerShape(50),
        color = colors.primaryContainer.copy(alpha = 0.85f),
        modifier = Modifier
            .testTag(if (synced) "viewer.favourite_badge.synced" else "viewer.favourite_badge.local")
            .semantics(mergeDescendants = true) { this.stateDescription = stateDescription },
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (synced) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                // Scales with the label, so the cue stays readable at large font sizes.
                modifier = Modifier.size(with(LocalDensity.current) { MaterialTheme.typography.labelSmall.fontSize.toDp() } + 3.dp),
            )
            Text(
                text = stringResource(R.string.chip_favourite),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
