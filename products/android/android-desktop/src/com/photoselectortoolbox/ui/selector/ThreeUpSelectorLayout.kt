package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.photoselectortoolbox.ui.components.ScoreChipRow
import com.photoselectortoolbox.ui.theme.StudioCanvas
import com.photoselectortoolbox.ui.theme.Zinc50
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.guidance.HintSlot
import com.photoselectortoolbox.domain.guidance.SelectorGuidance
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.scoring.BurstRank
import com.photoselectortoolbox.domain.scoring.ScoreMetric
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.clipToBounds
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.ui.components.StarRatingBar
import com.photoselectortoolbox.viewmodel.SelectorFrame
import com.photoselectortoolbox.viewmodel.SelectorHintUi
import androidx.compose.ui.res.stringResource
import com.photoselectortoolbox.R
import com.photoselectortoolbox.ui.text.asString

/**
 * The selector: three equal frames, one over two, the current one centred.
 *
 * ```
 * ┌────┬──────────┬──────────────┬────────┬─┐
 * │    │ readouts │   CURRENT    │controls│f│   row 1
 * │side│          ├──────┬───────┴────────┤i│
 * │bar │          │ PREV │  NEXT │        │l│   row 2
 * └────┴──────────┴──────┴───────┴────────┴─┘
 * ```
 *
 * Nothing is above or below the frames — not even the filmstrip, which is a
 * *vertical* strip down the outer edge of the control flank. Measured on the
 * reference device, a 76 dp full-width strip took the frames from 675 × 450 to
 * 618 × 412; 72 dp of vertical strip takes nothing, because the flank has
 * 82.5 dp of horizontal slack the photographs cannot use.
 *
 * Three design facts, each of which replaced a shipped mistake:
 *
 * 1. **One over two, not a row.** Three 4:3 frames abreast are width-bound and
 *    cap at 493 × 370 dp while abandoning 60 % of the display's height. One over
 *    two is height-bound and reaches 600 × 450 — 76 % more area. See
 *    [FrameGeometry] for the arithmetic.
 * 2. **The current frame is centred**, flanked by its readouts and its controls.
 *    Centring plus the 2 dp accent border is what marks it as the frame under
 *    judgement. It is never made *larger* than its neighbours: a frame you
 *    cannot compare at equal size is a frame you cannot judge.
 * 3. **Every value touches the frame it describes.** Named beside the current
 *    frame, icon-and-value on each neighbour's own right edge. An earlier draft
 *    put all fifteen numbers in one matrix, which compared well and left the
 *    user unable to say whose number was whose.
 */
@Composable
fun ThreeUpSelectorLayout(
    current: ImageItem?,
    previous: ImageItem?,
    next: ImageItem?,
    currentIndex: Int,
    total: Int,
    filingAction: FilingAction,
    folderName: String,
    burstLabel: String?,
    detailsVisible: Boolean,
    filmstripVisible: Boolean,
    overlayValuesVisible: Boolean,
    maximisedFrame: SelectorFrame?,
    actions: SelectorActions,
    /** The maximised frame's zoom (`Space`, `Shift`+arrows, drag), owned by the screen's key handler. */
    maximisedZoom: PhotoZoom = remember { PhotoZoom() },
    onNavigatePrevious: () -> Unit,
    onNavigateNext: () -> Unit,
    onMaximise: (SelectorFrame) -> Unit,
    onLongPressFrame: () -> Unit,
    showFirstRunHint: Boolean,
    onDismissFirstRunHint: () -> Unit,
    modifier: Modifier = Modifier,
    /** Folder enumeration is still running, so [total] is a running total. */
    stillEnumerating: Boolean = false,
    /** The one-time explanation waiting to be shown, already worded. */
    pendingHint: SelectorHintUi? = null,
    onDismissHint: () -> Unit = {},
    ratingFilter: RatingFilter = RatingFilter.All,
    onRatingFilterSelected: (RatingFilter) -> Unit = {},
    onSetRating: (Int) -> Unit = {},
    /** What the copied mark says for a photo copied in this session; null draws no mark. */
    copiedMark: (ImageItem) -> String? = { null },
    /** The current frame's sharpness as its place in its burst, for the readout; null keeps the figure. */
    sharpnessRank: BurstRank? = null,
    /** The Sort chip in the control block's locator row, and its menu. */
    sort: SortControl = SortControl(),
    /**
     * The filmstrip, drawn as a vertical column at the outer edge of the
     * control flank.
     *
     * A slot rather than a list plus three callbacks, because where it goes is
     * this layout's business and what it contains is not. It is placed in the
     * flank's horizontal slack and never across the bottom: 76 dp of full-width
     * strip is 38 dp off the height of all three frames on the reference device
     * (measured), while 72 dp of width there is free.
     */
    filmstrip: @Composable () -> Unit = {},
    /**
     * The confirmation card's measured top in root pixels while one is shown: the first-run
     * pill then sits [PillAboveSnackbarGap] above it instead of under it.
     */
    snackbarTopInRoot: Float? = null,
) {
    val bestSets = bestMetricSets(
        listOf(previous?.scanResult, current?.scanResult, next?.scanResult)
    )

    Row(
        modifier = modifier.fillMaxSize().padding(FrameGeometry.OuterPadding),
        horizontalArrangement = Arrangement.spacedBy(FrameGeometry.Gap),
    ) {
        if (maximisedFrame != null) {
            MaximisedFrame(
                frame = maximisedFrame,
                image = when (maximisedFrame) {
                    SelectorFrame.PREVIOUS -> previous
                    SelectorFrame.CURRENT -> current
                    SelectorFrame.NEXT -> next
                },
                position = when (maximisedFrame) {
                    SelectorFrame.PREVIOUS -> currentIndex
                    SelectorFrame.CURRENT -> currentIndex + 1
                    SelectorFrame.NEXT -> currentIndex + 2
                },
                total = total,
                bestMetrics = bestSets[maximisedFrame.readoutIndex],
                detailsVisible = detailsVisible,
                onToggleDetails = actions.onToggleDetails,
                onExit = { onMaximise(maximisedFrame) },
                onLongPress = onLongPressFrame,
                pendingHint = pendingHint,
                onDismissHint = onDismissHint,
                canGoPrevious = previous != null,
                canGoNext = next != null,
                onNavigatePrevious = onNavigatePrevious,
                onNavigateNext = onNavigateNext,
                copiedLabel = when (maximisedFrame) {
                    SelectorFrame.PREVIOUS -> previous
                    SelectorFrame.CURRENT -> current
                    SelectorFrame.NEXT -> next
                }?.let(copiedMark),
                zoom = maximisedZoom,
                modifier = Modifier.weight(1f),
            )
            return@Row
        }

        var regionBottomInRoot by remember { mutableFloatStateOf(Float.NaN) }
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .onGloballyPositioned { regionBottomInRoot = it.boundsInRoot().bottom },
        ) {
            val activeImage = current ?: previous ?: next
            val aspect = activeImage?.aspectRatio?.let { raw ->
                if (raw < 1f) 1f / raw else raw
            } ?: FrameGeometry.DefaultLandscapeAspect

            // One solver call, shared with the coach-mark overlay so the two
            // cannot disagree about where the slack is.
            val layout = FrameGeometry.threeUpLayout(
                regionWidth = maxWidth,
                regionHeight = maxHeight,
                aspect = aspect,
                detailsVisible = detailsVisible,
                filmstripVisible = filmstripVisible,
            )
            val frameSize = layout.frame
            val flankWidth = layout.flankWidth
            val overlayOutside = layout.overlayOutside

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(FrameGeometry.Gap),
            ) {
                // ── Row 1: readouts · CURRENT · controls ────────────────
                //
                // Centred as well as balanced. The solver already sizes the two
                // flanks to consume the region exactly, so this changes nothing
                // in the normal case — but the arithmetic can round a few dp
                // over, and the default `Arrangement.Start` pays for that
                // entirely on the right, shifting the frame off centre. Splitting
                // any residual keeps the current frame where the neighbours
                // below it already centre themselves.
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // A column, not a box: the explanation card takes its own
                    // space under the readout rather than floating over it, so
                    // it can neither cover a frame nor collide with a control.
                    Column(
                        modifier = Modifier.width(flankWidth).fillMaxHeight(),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            if (detailsVisible) {
                                CurrentFrameReadout(
                                    image = current,
                                    bestMetrics = bestSets[1],
                                    folderName = folderName,
                                    burstLabel = burstLabel,
                                    sharpnessRank = sharpnessRank,
                                )
                            }
                        }
                        HintSlotCard(
                            pendingHint = pendingHint,
                            flankWidth = flankWidth,
                            detailsVisible = detailsVisible,
                            onDismiss = onDismissHint,
                        )
                    }

                    Spacer(modifier = Modifier.width(FrameGeometry.Gap))

                    FrameTile(
                        image = current,
                        size = frameSize,
                        isCurrent = true,
                        emptyLabel = stringResource(R.string.label_no_image),
                        badgeAlignment = Alignment.BottomEnd,
                        copiedLabel = current?.let(copiedMark),
                        onClick = actions.onFullscreen,
                        onLongClick = onLongPressFrame,
                        onMaximise = { onMaximise(SelectorFrame.CURRENT) },
                        testTag = "column_current",
                        outerTestTag = "selector.frame.current",
                        modifier = Modifier.comparisonSwipeNavigation(
                            canGoPrevious = previous != null,
                            canGoNext = next != null,
                            onNavigatePrevious = onNavigatePrevious,
                            onNavigateNext = onNavigateNext,
                        ),
                    )

                    Spacer(modifier = Modifier.width(FrameGeometry.Gap))

                    // The control flank carries the strip down its outer edge —
                    // the far right, because the sidebar owns the far left.
                    // A row, so the two can never share bounds; the strip's
                    // width comes from the solver, which is what keeps the
                    // coach-mark overlay reserving the same column.
                    Row(
                        modifier = Modifier.width(flankWidth).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            SelectorControlBlock(
                                actions = actions,
                                filingAction = filingAction,
                                position = currentIndex + 1,
                                total = total,
                                canGoPrevious = previous != null,
                                canGoNext = next != null,
                                detailsVisible = detailsVisible,
                                filmstripVisible = filmstripVisible,
                                overlayValuesVisible = overlayValuesVisible,
                                onNavigatePrevious = onNavigatePrevious,
                                onNavigateNext = onNavigateNext,
                                stillEnumerating = stillEnumerating,
                                ratingFilter = ratingFilter,
                                onRatingFilterSelected = onRatingFilterSelected,
                                currentRating = current?.rating ?: 0,
                                onSetRating = onSetRating,
                                sort = sort,
                            )
                        }

                        if (layout.filmstripWidth > 0.dp) {
                            Spacer(modifier = Modifier.width(FrameGeometry.Gap))
                            Box(
                                modifier = Modifier
                                    .width(layout.filmstripWidth)
                                    .fillMaxHeight(),
                            ) {
                                filmstrip()
                            }
                        }
                    }
                }

                // ── Row 2: PREVIOUS · NEXT, centred as a pair ───────────
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .comparisonSwipeNavigation(
                            canGoPrevious = previous != null,
                            canGoNext = next != null,
                            onNavigatePrevious = onNavigatePrevious,
                            onNavigateNext = onNavigateNext,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(
                        FrameGeometry.Gap,
                        Alignment.CenterHorizontally,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeighbourFrame(
                        frame = SelectorFrame.PREVIOUS,
                        image = previous,
                        size = frameSize,
                        position = if (previous != null) currentIndex else null,
                        total = total,
                        bestMetrics = bestSets[0],
                        showValues = overlayValuesVisible,
                        overlayOutside = overlayOutside,
                        onClick = onNavigatePrevious,
                        onLongClick = onLongPressFrame,
                        onMaximise = { onMaximise(SelectorFrame.PREVIOUS) },
                        testTag = "column_previous",
                        copiedLabel = previous?.let(copiedMark),
                    )
                    NeighbourFrame(
                        frame = SelectorFrame.NEXT,
                        image = next,
                        size = frameSize,
                        position = if (next != null) currentIndex + 2 else null,
                        total = total,
                        bestMetrics = bestSets[2],
                        showValues = overlayValuesVisible,
                        overlayOutside = overlayOutside,
                        onClick = onNavigateNext,
                        onLongClick = onLongPressFrame,
                        onMaximise = { onMaximise(SelectorFrame.NEXT) },
                        testTag = "column_next",
                        copiedLabel = next?.let(copiedMark),
                    )
                }
            }

            // The only overlay allowed near a photograph besides the values,
            // and only once ever. While a confirmation card is up, the pill stacks above the
            // card's measured top rather than sitting under it (it read "Iap either side").
            val density = LocalDensity.current
            val baseInset = FrameGeometry.firstRunPillBottomInset(maxHeight, frameSize.height)
            val aboveSnackbar = snackbarTopInRoot
                ?.takeIf { !regionBottomInRoot.isNaN() }
                ?.let { top -> with(density) { (regionBottomInRoot - top).toDp() } + PillAboveSnackbarGap }
            val pillInset by animateDpAsState(
                targetValue = maxOf(baseInset, aboveSnackbar ?: 0.dp),
                animationSpec = tween(150),
                label = "firstRunPillInset",
            )
            FirstRunNavigationHint(
                visible = showFirstRunHint,
                onDismiss = onDismissFirstRunHint,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = pillInset),
            )
        }
    }
}

/**
 * The explanation card, if this flank is allowed to carry one.
 *
 * The predicate is [SelectorGuidance.slotFor], not an `if` written here: whether
 * there is room is a decision, and decisions inside composables are decisions no
 * JVM test can reach (`ai/memory/code_health.md`, `[OPEN] 2026-08-07`). Where it
 * returns [HintSlot.NONE] nothing is drawn *and* nothing is marked seen, so the
 * explanation survives to a window that has room for it.
 */
@Composable
private fun HintSlotCard(
    pendingHint: SelectorHintUi?,
    flankWidth: Dp,
    detailsVisible: Boolean,
    onDismiss: () -> Unit,
) {
    val slot = SelectorGuidance.slotFor(flankWidth.value, detailsVisible)
    if (slot == HintSlot.NONE) return

    SelectorHintCard(
        hint = pendingHint,
        onDismiss = onDismiss,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** Which readout column a frame's best-metric set lives in. */
private val SelectorFrame.readoutIndex: Int
    get() = when (this) {
        SelectorFrame.PREVIOUS -> 0
        SelectorFrame.CURRENT -> 1
        SelectorFrame.NEXT -> 2
    }

/**
 * A neighbour and its values.
 *
 * The values go outside the frame when there is room and on it when there is
 * not — one measurement, both branches, rather than a hard-coded choice. On the
 * reference tablet the bottom row leaves 84 dp per side so they overlay; a wide
 * DeX window puts them outside with no code change.
 */
@Composable
private fun NeighbourFrame(
    frame: SelectorFrame,
    image: ImageItem?,
    size: DpSize,
    position: Int?,
    total: Int,
    bestMetrics: Set<ScoreMetric>,
    showValues: Boolean,
    overlayOutside: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMaximise: () -> Unit,
    testTag: String,
    copiedLabel: String? = null,
) {
    // A frame too small to carry the overlay without being covered by it shows
    // the photograph and nothing else (AD-16).
    val overlayHidden = FrameGeometry.overlayHiddenInFrame(size, overlayOutside)
    val outerTag = if (frame == SelectorFrame.PREVIOUS) {
        "selector.frame.previous"
    } else {
        "selector.frame.next"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            FrameTile(
                image = image,
                size = size,
                isCurrent = false,
                emptyLabel = stringResource(if (frame == SelectorFrame.PREVIOUS) R.string.label_no_previous else R.string.label_no_next),
                // Bottom-left here, bottom-right on the current frame: the
                // right edge belongs to the value overlay, and two controls
                // must never share bounds.
                badgeAlignment = Alignment.BottomStart,
                copiedLabel = copiedLabel,
                onClick = onClick,
                onLongClick = onLongClick,
                onMaximise = onMaximise,
                testTag = testTag,
                outerTestTag = outerTag,
            )

            if (showValues && !overlayOutside && !overlayHidden) {
                NeighbourValueOverlay(
                    image = image,
                    position = position,
                    total = total,
                    bestMetrics = bestMetrics,
                    isOverlaid = true,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(8.dp),
                )
            }
        }

        if (showValues && overlayOutside) {
            NeighbourValueOverlay(
                image = image,
                position = position,
                total = total,
                bestMetrics = bestMetrics,
                isOverlaid = false,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * Enables horizontal touch swipe navigation across the photograph comparison area.
 *
 * Swipe left = next photograph; swipe right = previous photograph.
 * Conforms to REQ-AND-INPUT.01 and REQ-AND-INPUT.02.
 */
@Composable
private fun Modifier.comparisonSwipeNavigation(
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onNavigatePrevious: () -> Unit,
    onNavigateNext: () -> Unit,
): Modifier {
    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { 48.dp.toPx() } }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    return this.pointerInput(canGoPrevious, canGoNext, thresholdPx) {
        detectHorizontalDragGestures(
            onDragStart = { dragAccumulator = 0f },
            onDragEnd = {
                if (dragAccumulator < -thresholdPx && canGoNext) {
                    onNavigateNext()
                } else if (dragAccumulator > thresholdPx && canGoPrevious) {
                    onNavigatePrevious()
                }
                dragAccumulator = 0f
            },
            onDragCancel = { dragAccumulator = 0f },
            onHorizontalDrag = { _, dragAmount ->
                dragAccumulator += dragAmount
            },
        )
    }
}

/**
 * One frame at exactly [size], with its maximise badge.
 *
 * The size arrives as an explicit [DpSize] rather than as a modifier chain, so
 * all three tiles are the same by construction rather than by all three
 * happening to resolve their constraints the same way.
 */
@Composable
private fun FrameTile(
    image: ImageItem?,
    size: DpSize,
    isCurrent: Boolean,
    emptyLabel: String,
    badgeAlignment: Alignment,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMaximise: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    outerTestTag: String? = null,
    /** Spoken text of the copied mark when this photo was copied in this session. */
    copiedLabel: String? = null,
) {
    Box(
        modifier = modifier.then(
            if (outerTestTag != null) Modifier.testTag(outerTestTag) else Modifier
        ),
    ) {
        ImageTile(
            image = image,
            isCurrent = isCurrent,
            onClick = onClick,
            onLongClick = onLongClick,
            emptyLabel = emptyLabel,
            modifier = Modifier
                .size(size)
                .testTag(testTag),
        )
        if (image != null) {
            MaximiseBadge(
                onClick = onMaximise,
                modifier = Modifier.align(badgeAlignment).padding(MaximiseBadgeInset),
            )
        }
        // Top-start: both badges sit at the bottom and the neighbours' values at the end.
        if (image != null && copiedLabel != null) {
            CopiedMark(
                size = CopiedMarkFrameSize,
                spoken = copiedLabel,
                testTag = "copied_mark",
                modifier = Modifier.align(Alignment.TopStart).padding(MaximiseBadgeInset),
            )
        }
    }
}

/**
 * One frame filling the whole region.
 *
 * On the reference tablet this is 1008 × 756 dp for a 4:3 photograph against
 * 600 × 450 in three-up — 2.8× the area — and the photograph owns the full height
 * of the region whether or not the details are shown. The readout used to be a
 * 388 dp column beside the frame; measured on the Pixel Tablet that took the frame
 * to 778 × 584 and left 600 dp of mostly empty flank. It is an overlay now, like
 * the fullscreen viewer's: a flat info panel at the top-left and compact score
 * chips at the bottom-left, both inside the frame and neither taking input, so the
 * photograph stays swipeable under them. The ⓘ toggle beside the restore badge
 * turns them off (the same persisted setting as the three-up toggle, which is not
 * on screen here). The neighbours' value overlays go, because their frames have.
 *
 * The one-time maximise card is the only thing that may take slack, and it takes
 * the slack *beside* the frame, never a part of it. Where that is narrower than the
 * card's minimum nothing is drawn and the hint stays unspent.
 */
@Composable
private fun MaximisedFrame(
    frame: SelectorFrame,
    image: ImageItem?,
    position: Int,
    total: Int,
    bestMetrics: Set<ScoreMetric>,
    detailsVisible: Boolean,
    onToggleDetails: () -> Unit,
    onExit: () -> Unit,
    onLongPress: () -> Unit,
    pendingHint: SelectorHintUi?,
    onDismissHint: () -> Unit,
    canGoPrevious: Boolean = false,
    canGoNext: Boolean = false,
    onNavigatePrevious: () -> Unit = {},
    onNavigateNext: () -> Unit = {},
    copiedLabel: String? = null,
    zoom: PhotoZoom = remember { PhotoZoom() },
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val aspect = image?.aspectRatio?.let { raw ->
            if (raw < 1f) 1f / raw else raw
        } ?: FrameGeometry.DefaultLandscapeAspect

        // The whole region, always: the details are drawn on the photograph, so
        // they cost it nothing.
        val size = FrameGeometry.maximisedFrameSize(
            regionWidth = maxWidth,
            regionHeight = maxHeight,
            aspect = aspect,
        )

        // The maximise explanation fires exactly on the way into this state, so
        // it is drawn here, in the slack left of the frame.
        val cardWidth = (maxWidth - size.width) / 2 - FrameGeometry.Gap
        if (SelectorGuidance.slotFor(cardWidth.value, detailsVisible = true) != HintSlot.NONE) {
            Box(
                modifier = Modifier.align(Alignment.CenterStart).width(cardWidth),
                contentAlignment = Alignment.Center,
            ) {
                HintSlotCard(
                    pendingHint = pendingHint,
                    flankWidth = cardWidth,
                    detailsVisible = true,
                    onDismiss = onDismissHint,
                )
            }
        }

        // 100 % on the maximised frame: one image pixel per device pixel at this frame size.
        val density = LocalDensity.current
        LaunchedEffect(image?.uri, size) {
            val w = with(density) { size.width.toPx() }
            val h = with(density) { size.height.toPx() }
            zoom.oneToOne = PhotoZoom.oneToOneScale(image?.imageWidth ?: 0, image?.imageHeight ?: 0, w, h)
            zoom.viewport = IntSize(w.roundToInt(), h.roundToInt())
        }
        val zoomed = zoom.isZoomed
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                // Zoomed, a swipe pans the photo instead of stepping (the arrows still step).
                .comparisonSwipeNavigation(
                    canGoPrevious = canGoPrevious && !zoomed,
                    canGoNext = canGoNext && !zoomed,
                    onNavigatePrevious = onNavigatePrevious,
                    onNavigateNext = onNavigateNext,
                ),
        ) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clipToBounds()
                    .pointerInput(zoomed) {
                        if (zoomed) detectDragGestures { change, drag -> change.consume(); zoom.panBy(drag) }
                    },
            ) {
                ImageTile(
                    image = image,
                    isCurrent = frame == SelectorFrame.CURRENT,
                    onClick = onExit,
                    onLongClick = onLongPress,
                    emptyLabel = stringResource(R.string.label_no_image),
                    modifier = Modifier
                        .size(size)
                        .graphicsLayer {
                            scaleX = zoom.scale
                            scaleY = zoom.scale
                            translationX = zoom.offset.x
                            translationY = zoom.offset.y
                        }
                        .testTag("maximised_frame"),
                )
            }

            if (detailsVisible && image != null) {
                PhotoInfoPanel(
                    image = image,
                    position = position,
                    total = total,
                    availableWidth = size.width,
                    tagPrefix = "maximised",
                    modifier = Modifier.align(Alignment.TopStart).padding(MaximisedOverlayInset),
                )
                // Clear of the toggle and the badge: the chips wrap inside what is
                // left of the frame's width rather than running under them.
                image.scanResult?.let { scores ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .widthIn(max = (size.width - MaximisedChipsReservedEnd).coerceAtLeast(0.dp))
                            .padding(start = MaximisedOverlayInset, bottom = MaximisedOverlayInset),
                    ) {
                        ScoreChipRow(
                            scores = scores,
                            compact = true,
                            bestMetrics = bestMetrics,
                            modifier = Modifier.testTag("maximised_score_chips"),
                        )
                    }
                }
            }

            MaximisedInfoToggle(
                checked = detailsVisible,
                onCheckedChange = { onToggleDetails() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = MaximiseBadgeInset * 2 + MaximiseBadgeSize,
                        bottom = MaximiseBadgeInset,
                    ),
            )
            MaximiseBadge(
                active = true,
                onClick = onExit,
                modifier = Modifier.align(Alignment.BottomEnd).padding(MaximiseBadgeInset),
            )
            // Top-end: the info panel holds top-start here, the toggle and badge the bottom.
            if (image != null && copiedLabel != null) {
                CopiedMark(
                    size = CopiedMarkFrameSize,
                    spoken = copiedLabel,
                    testTag = "copied_mark",
                    modifier = Modifier.align(Alignment.TopEnd).padding(MaximisedOverlayInset),
                )
            }
        }
    }
}

/** Inset of the info panel and the chips from the frame's edges. */
private val MaximisedOverlayInset = 8.dp

/**
 * What the chip row leaves free at the frame's right: toggle, badge, three 6 dp
 * insets between and around them, and the chips' own left inset.
 */
private val MaximisedChipsReservedEnd: Dp =
    MaximiseBadgeSize * 2 + MaximiseBadgeInset * 3 + MaximisedOverlayInset

/**
 * The maximised frame's ⓘ switch: 48 dp, on the photograph beside the restore badge.
 *
 * The three-up control block carries the same toggle but is not drawn here, so
 * without this the details could not be turned off in the one state that puts
 * them on the photograph. It reads and writes the same persisted setting. Filled
 * glyph = shown, outline = hidden; a switch to a screen reader.
 */
@Composable
private fun MaximisedInfoToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spoken = stringResource(if (checked) R.string.cd_hide_photo_info else R.string.cd_show_photo_info)
    Box(
        modifier = modifier
            .size(MaximiseBadgeSize)
            .clip(RoundedCornerShape(8.dp))
            .background(StudioCanvas.copy(alpha = 0.72f))
            .pointerHoverIcon(PointerIcon.Hand)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { contentDescription = spoken }
            .testTag("maximised_info_toggle"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (checked) Icons.Filled.Info else Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Zinc50,
        )
    }
}

/** Space between the confirmation card's top and the first-run pill stacked above it. */
internal val PillAboveSnackbarGap: Dp = 8.dp
