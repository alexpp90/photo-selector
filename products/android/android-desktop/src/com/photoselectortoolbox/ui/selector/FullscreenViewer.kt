package com.photoselectortoolbox.ui.selector

import android.app.Activity
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.format.SelectionActionLabels
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.interaction.SelectorGestures
import com.photoselectortoolbox.domain.format.ExifFormatter
import com.photoselectortoolbox.ui.components.ScoreChipRow
import com.photoselectortoolbox.ui.theme.FocusCyan
import com.photoselectortoolbox.ui.theme.FocusCyanText
import com.photoselectortoolbox.ui.theme.ScoreBad
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc50
import com.photoselectortoolbox.ui.theme.Zinc700
import com.photoselectortoolbox.ui.theme.Zinc800
import com.photoselectortoolbox.ui.theme.Zinc900
import com.photoselectortoolbox.ui.theme.Zinc950
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FullscreenViewer(
    images: List<ImageItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onDelete: (Int) -> Unit,
    onMoveToSelection: (Int) -> Unit,
    onCopyToSelection: (Int) -> Unit,
    windowSizeClass: WindowSizeClass,
    onPageSelected: (Int) -> Unit = {},
    fullscreenButtonsEnabled: Boolean = true,
    filingAction: FilingAction = FilingAction.DEFAULT,
    showGestureHint: Boolean = false,
    onGestureHintSeen: () -> Unit = {},
) {
    val isExpanded = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    // Immersive mode: hide system bars
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let {
            WindowCompat.getInsetsController(it, view)
        }

        controller?.let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
            controller?.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        FullscreenContent(
            images = images,
            initialIndex = initialIndex,
            isExpanded = isExpanded,
            onDismiss = onDismiss,
            onDelete = onDelete,
            onMoveToSelection = onMoveToSelection,
            onCopyToSelection = onCopyToSelection,
            onPageSelected = onPageSelected,
            fullscreenButtonsEnabled = fullscreenButtonsEnabled,
            filingAction = filingAction,
            showGestureHint = showGestureHint,
            onGestureHintSeen = onGestureHintSeen,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FullscreenContent(
    images: List<ImageItem>,
    initialIndex: Int,
    isExpanded: Boolean,
    onDismiss: () -> Unit,
    onDelete: (Int) -> Unit,
    onMoveToSelection: (Int) -> Unit,
    onCopyToSelection: (Int) -> Unit,
    onPageSelected: (Int) -> Unit,
    fullscreenButtonsEnabled: Boolean,
    filingAction: FilingAction,
    showGestureHint: Boolean = false,
    onGestureHintSeen: () -> Unit = {},
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { images.size },
    )

    val coroutineScope = rememberCoroutineScope()
    var pagerScrollEnabled by remember { mutableStateOf(true) }
    var showOverlay by remember { mutableStateOf(true) }
    var currentPageIndex by remember { mutableStateOf(initialIndex) }
    var zoomedIn by remember { mutableStateOf(false) }

    // Chrome gets out of the way on its own. In fullscreen the user is
    // inspecting detail — a bar that stays put is a bar sitting on the part of
    // the frame they are trying to see. Any interaction brings it straight
    // back, so nothing is ever unreachable.
    var lastInteractionAt by remember { mutableStateOf(0L) }
    LaunchedEffect(showOverlay, lastInteractionAt) {
        if (showOverlay) {
            delay(CHROME_AUTO_HIDE_MILLIS)
            showOverlay = false
        }
    }

    // Track settled page and sync back to caller/viewmodel
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            currentPageIndex = page
            onPageSelected(page)
        }
    }

    // Sync from viewmodel changes (like deletion or key events shifting the index)
    LaunchedEffect(initialIndex) {
        if (pagerState.currentPage != initialIndex && initialIndex in images.indices) {
            pagerState.scrollToPage(initialIndex)
        }
    }

    val currentImage = images.getOrNull(currentPageIndex)

    // Collection flash feedback
    var showCollectionFlash by remember { mutableStateOf(false) }
    LaunchedEffect(showCollectionFlash) {
        if (showCollectionFlash) {
            delay(700)
            showCollectionFlash = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Near-black rather than pure black: a true #000 canvas makes deep
            // shadows in the photograph look lifted by comparison, which is
            // exactly the judgement the viewer exists to support.
            .background(Zinc950)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Escape -> { onDismiss(); true }
                        Key.Delete, Key.Backspace -> { onDelete(currentPageIndex); true }
                        Key.M -> { onMoveToSelection(currentPageIndex); true }
                        Key.C -> { onCopyToSelection(currentPageIndex); true }
                        // Left/right, matching the horizontal swipe and the
                        // rest of the app. The viewer used to page vertically
                        // while its hint card advertised horizontal swipes, so
                        // both the arrows the user pressed and the words they
                        // read were wrong about the same axis.
                        Key.DirectionLeft -> {
                            if (currentPageIndex > 0) {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(currentPageIndex - 1)
                                }
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            if (currentPageIndex < images.size - 1) {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(currentPageIndex + 1)
                                }
                            }
                            true
                        }
                        else -> false
                    }
                } else false
            },
    ) {
        // Horizontal paging: swipe ← → is "previous / next" everywhere in this
        // product, and this viewer is where that promise was previously broken.
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = pagerScrollEnabled,
            modifier = Modifier.fillMaxSize(),
            key = { images[it].uri },
            beyondViewportPageCount = 1,
        ) { page ->
            FullscreenImagePage(
                image = images[page],
                onTap = {
                    showOverlay = !showOverlay
                    lastInteractionAt = System.currentTimeMillis()
                },
                onZoomChanged = { isZoomed ->
                    pagerScrollEnabled = !isZoomed
                    zoomedIn = isZoomed
                },
                onDismiss = onDismiss,
                isGestureEnabled = pagerScrollEnabled, // only enable swipes when not zoomed
            )
        }

        // Overlays (animated visibility)
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                FullscreenTopBar(
                    image = currentImage,
                    position = currentPageIndex + 1,
                    total = images.size,
                    onClose = onDismiss,
                    modifier = Modifier.align(Alignment.TopStart),
                )

                // Compact chips: fullscreen is for judging the photograph, so
                // the scores get the smallest footprint that still carries the
                // bar. The labels are recoverable from the legend.
                currentImage?.scanResult?.let { scores ->
                    ScoreChipRow(
                        scores = scores,
                        compact = true,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .navigationBarsPadding()
                            .padding(12.dp),
                    )
                }

                if (fullscreenButtonsEnabled) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Configured verb first, then the other. Filing is a
                        // button here and nowhere else: no gesture in this
                        // viewer touches a file.
                        SelectionActionLabels.both(filingAction).forEach { label ->
                            FullscreenActionButton(
                                icon = if (label.action == FilingAction.MOVE) {
                                    Icons.AutoMirrored.Filled.DriveFileMove
                                } else {
                                    Icons.Default.ContentCopy
                                },
                                description = label.accessibilityLabel,
                                onClick = {
                                    if (label.action == FilingAction.MOVE) {
                                        onMoveToSelection(currentPageIndex)
                                    } else {
                                        onCopyToSelection(currentPageIndex)
                                    }
                                    showCollectionFlash = true
                                },
                            )
                        }
                        FullscreenActionButton(
                            icon = Icons.Default.Delete,
                            description = "Delete, shortcut Delete",
                            tint = ScoreBad,
                            onClick = { onDelete(currentPageIndex) },
                        )
                    }
                }
            }
        }

        // Navigator: only meaningful while zoomed, when the visible part of the
        // frame is no longer the whole frame.
        if (zoomedIn) {
            ZoomNavigator(
                image = currentImage,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
        }

        if (showGestureHint) {
            FullscreenGestureHint(
                onDismiss = onGestureHintSeen,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 64.dp, end = 16.dp),
            )
        }

        // Collection flash (centered checkmark)
        AnimatedVisibility(
            visible = showCollectionFlash,
            modifier = Modifier.align(Alignment.Center),
            enter = scaleIn(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = scaleOut(tween(200)) + fadeOut(tween(200)),
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(com.photoselectortoolbox.ui.theme.SuccessGreen.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Added to selection",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
    }
}

/** How long the chrome waits before fading out. */
private const val CHROME_AUTO_HIDE_MILLIS = 3_000L

/**
 * The fullscreen top bar: a bar, deliberately, not a gradient scrim.
 *
 * A gradient over the top of the frame darkens the sky in a way the
 * photographer will read as part of the photograph. A hard-edged bar with a
 * visible bottom border does not lie about where the image starts.
 */
@Composable
private fun FullscreenTopBar(
    image: ImageItem?,
    position: Int,
    total: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Zinc900.copy(alpha = 0.9f))
                .statusBarsPadding()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(48.dp).testTag("fullscreen_close"),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close fullscreen",
                    tint = Zinc50,
                )
            }

            Text(
                text = image?.fileName.orEmpty(),
                fontSize = 14.sp,
                color = Zinc50,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )

            Text(
                text = "$position / $total",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Zinc400,
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(22.dp)
                    .background(Zinc700),
            )

            val exifLine = ExifFormatter.summaryLine(image?.exifData)
            val lens = image?.exifData?.lens?.takeIf { it != ExifFormatter.UNKNOWN }
            if (exifLine != null || lens != null) {
                Text(
                    text = listOfNotNull(exifLine, lens).joinToString("  ·  "),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Zinc400,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Zinc800),
        )
    }
}

@Composable
private fun FullscreenActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = Zinc50,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Zinc900.copy(alpha = 0.9f),
            contentColor = tint,
        ),
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = tint)
    }
}

/**
 * A thumbnail of the whole frame with the visible region outlined, shown while
 * zoomed so the user knows which part of the photograph they are looking at.
 */
@Composable
private fun ZoomNavigator(
    image: ImageItem?,
    modifier: Modifier = Modifier,
) {
    if (image == null) return

    Box(
        modifier = modifier
            .width(104.dp)
            .height(78.dp)
            .background(Zinc900.copy(alpha = 0.9f))
            .border(1.dp, Zinc700)
            .testTag("zoom_navigator"),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = image.uri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(48.dp)
                .height(36.dp)
                .border(1.dp, FocusCyan),
        )
    }
}

/**
 * The one-time gesture card.
 *
 * Fullscreen has no visible controls for pinch, double-tap or swipe, so the
 * gestures have to be stated once. After "Got it" the flag is persisted and
 * the card never returns — a permanent legend over a photograph would defeat
 * the purpose of fullscreen.
 */
@Composable
private fun FullscreenGestureHint(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(270.dp)
            .background(Zinc800, RoundedCornerShape(10.dp))
            .border(1.dp, Zinc700, RoundedCornerShape(10.dp))
            .padding(14.dp)
            .testTag("fullscreen_gesture_hint"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Generated from the bindings, never written out here. The previous
        // hand-written version of this list claimed "swipe ← → navigate" while
        // a leftward swipe deleted the photograph.
        SelectorGestures.fullscreenHintRows().forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = row.input,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Zinc400,
                )
                Text(text = row.effect, fontSize = 12.sp, color = Zinc50)
            }
        }

        Text(
            text = "Got it",
            fontSize = 12.sp,
            color = FocusCyanText,
            modifier = Modifier
                .align(Alignment.End)
                // A 48 dp hit target around the 12 sp word (AD-17).
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(onClick = onDismiss)
                .wrapContentSize(Alignment.Center),
        )
    }
}

/**
 * One page of the viewer: the photograph, and the two gestures that act on the
 * page rather than on the pager.
 *
 * What this used to do is worth stating, because the shape of the bug is easy
 * to reintroduce. A horizontal drag past −200 px **deleted the photograph** and
 * past +200 px dismissed the viewer, while the pager beneath paged vertically —
 * so the horizontal axis meant "destroy or leave" here and "next frame"
 * everywhere else in the app, and the hint card described it as navigation.
 * There was also an `onDoubleTap` that filed the frame into the Selection,
 * permanently shadowed by the zoom handler in [ZoomableImage] beneath it, so it
 * never fired and could not have been noticed.
 *
 * Now: the pager owns the horizontal axis, a downward drag dismisses, and
 * nothing here touches a file. Delete is a labelled button, the `Del` key, or
 * the context menu — all three of which confirm first.
 */
@Composable
private fun FullscreenImagePage(
    image: ImageItem,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    isGestureEnabled: Boolean,
) {
    var verticalDragOffset by remember { mutableFloatStateOf(0f) }
    var isZoomed by remember { mutableStateOf(false) }
    val dismissThreshold = 220f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, verticalDragOffset.roundToInt().coerceAtLeast(0)) }
            .graphicsLayer {
                // Fades as it falls away, so a half-committed drag reads as
                // "this will close" rather than as the image drifting.
                if (isGestureEnabled && !isZoomed) {
                    alpha = (1f - (abs(verticalDragOffset) / 700f)).coerceIn(0.35f, 1f)
                }
            }
            .pointerInput(isGestureEnabled, isZoomed) {
                // Only while un-zoomed: once the user is zoomed in, a vertical
                // drag is a pan across the frame they are inspecting, and
                // stealing it to close the viewer would be maddening.
                if (isGestureEnabled && !isZoomed) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (verticalDragOffset > dismissThreshold) onDismiss()
                            verticalDragOffset = 0f
                        },
                        onDragCancel = { verticalDragOffset = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            // Downward only. An upward drag does nothing rather
                            // than doing something undiscoverable.
                            verticalDragOffset = (verticalDragOffset + dragAmount)
                                .coerceAtLeast(0f)
                        },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val parsedUri = remember(image.uri) { Uri.parse(image.uri) }
        ZoomableImage(
            imageUri = parsedUri.toString(),
            contentDescription = image.fileName,
            pixelWidth = image.imageWidth,
            pixelHeight = image.imageHeight,
            onTap = { onTap() },
            onZoomChanged = { zoomed ->
                isZoomed = zoomed
                onZoomChanged(zoomed)
            },
        )
    }
}

/**
 * The photograph, pinch-zoomable and pannable.
 *
 * Double-tap toggles **fit ↔ 100 %**, and 100 % here means what it says: one
 * image pixel per device pixel, computed from the frame's own dimensions rather
 * than a fixed magnification. The previous implementation jumped to a hard-coded
 * 2.5× and the hint card called it "100%", which is the same class of untruth as
 * the swipe labels — a number the user can check, that does not hold. When the
 * dimensions are unknown (a frame discovered without them) it falls back to a
 * plain 2.5×, because a defensible approximation beats refusing to zoom.
 */
@Composable
private fun ZoomableImage(
    imageUri: String,
    contentDescription: String,
    pixelWidth: Int,
    pixelHeight: Int,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isZoomed by remember { mutableStateOf(false) }

    LaunchedEffect(isZoomed) {
        onZoomChanged(isZoomed)
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        val newOffset = if (newScale > 1f) {
            Offset(x = offset.x + panChange.x, y = offset.y + panChange.y)
        } else {
            Offset.Zero
        }

        scale = newScale
        offset = newOffset
        isZoomed = newScale > ZOOMED_THRESHOLD
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // ContentScale.Fit letterboxes, so the displayed size is whichever of
        // the two constraints binds — the same "which axis binds" question the
        // selector layout answers, one frame at a time.
        val oneToOneScale = remember(pixelWidth, pixelHeight, constraints) {
            if (pixelWidth <= 0 || pixelHeight <= 0) {
                FALLBACK_ZOOM
            } else {
                val boxWidth = constraints.maxWidth.toFloat()
                val boxHeight = constraints.maxHeight.toFloat()
                val aspect = pixelWidth.toFloat() / pixelHeight.toFloat()
                val displayedWidth = minOf(boxWidth, boxHeight * aspect)
                if (displayedWidth <= 0f) {
                    FALLBACK_ZOOM
                } else {
                    (pixelWidth / displayedWidth).coerceIn(MIN_MEANINGFUL_ZOOM, MAX_ZOOM)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(oneToOneScale) {
                    detectTapGestures(
                        onTap = { if (!isZoomed) onTap() },
                        onDoubleTap = {
                            if (isZoomed) {
                                scale = 1f
                                offset = Offset.Zero
                                isZoomed = false
                            } else {
                                scale = oneToOneScale
                                offset = Offset.Zero
                                isZoomed = true
                            }
                        },
                    )
                }
                .transformable(state = transformState, enabled = scale > 1f),
            contentAlignment = Alignment.Center,
        ) {
            val parsedUri = remember(imageUri) { Uri.parse(imageUri) }
            AsyncImage(
                model = parsedUri,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Beyond this the pager stops scrolling and the navigator thumbnail appears. */
private const val ZOOMED_THRESHOLD = 1.05f

/** Hard ceiling on magnification, pinch or double-tap. */
private const val MAX_ZOOM = 8f

/**
 * A frame already displayed at or above 1:1 would double-tap to no visible
 * change, which reads as a broken control. Give it something to do.
 */
private const val MIN_MEANINGFUL_ZOOM = 1.6f

/** Used when the frame's pixel dimensions were never read. */
private const val FALLBACK_ZOOM = 2.5f
