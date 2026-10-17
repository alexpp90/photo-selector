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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalDensity
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.ui.components.StarRatingBar
import com.photoselectortoolbox.viewmodel.SelectorFrame
import com.photoselectortoolbox.viewmodel.SelectorHintUi

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
 *    Centring plus the 2 dp Indigo border is what marks it as the frame under
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
                bestMetrics = bestSets[maximisedFrame.readoutIndex],
                detailsVisible = detailsVisible,
                onExit = { onMaximise(maximisedFrame) },
                onLongPress = onLongPressFrame,
                pendingHint = pendingHint,
                onDismissHint = onDismissHint,
                onSetRating = onSetRating,
                canGoPrevious = previous != null,
                canGoNext = next != null,
                onNavigatePrevious = onNavigatePrevious,
                onNavigateNext = onNavigateNext,
                modifier = Modifier.weight(1f),
            )
            return@Row
        }

        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxSize()) {
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
                        emptyLabel = "No image",
                        badgeAlignment = Alignment.BottomEnd,
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
                    )
                }
            }

            // The only overlay allowed near a photograph besides the values,
            // and only once ever.
            FirstRunNavigationHint(
                visible = showFirstRunHint,
                onDismiss = onDismissFirstRunHint,
                modifier = Modifier.align(Alignment.BottomCenter).padding(
                    bottom = FrameGeometry.firstRunPillBottomInset(maxHeight, frameSize.height),
                ),
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
                emptyLabel = if (frame == SelectorFrame.PREVIOUS) "No previous" else "No next",
                // Bottom-left here, bottom-right on the current frame: the
                // right edge belongs to the value overlay, and two controls
                // must never share bounds.
                badgeAlignment = Alignment.BottomStart,
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
    }
}

/**
 * One frame filling the whole region.
 *
 * On the reference tablet this is 1211 × 908 dp against 600 × 450 in three-up —
 * 4.1× the area. The readouts stay beside it so the numbers do not vanish
 * exactly when the user looks closest; the neighbours' overlays go, because
 * their frames have.
 */
@Composable
private fun MaximisedFrame(
    frame: SelectorFrame,
    image: ImageItem?,
    bestMetrics: Set<ScoreMetric>,
    detailsVisible: Boolean,
    onExit: () -> Unit,
    onLongPress: () -> Unit,
    pendingHint: SelectorHintUi?,
    onDismissHint: () -> Unit,
    onSetRating: (Int) -> Unit = {},
    canGoPrevious: Boolean = false,
    canGoNext: Boolean = false,
    onNavigatePrevious: () -> Unit = {},
    onNavigateNext: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val aspect = image?.aspectRatio?.let { raw ->
            if (raw < 1f) 1f / raw else raw
        } ?: FrameGeometry.DefaultLandscapeAspect

        val readoutWidth = if (detailsVisible) FrameGeometry.FlankWidth else 0.dp
        val size = FrameGeometry.maximisedFrameSize(
            regionWidth = maxWidth - readoutWidth - FrameGeometry.Gap,
            regionHeight = maxHeight,
            aspect = aspect,
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(
                FrameGeometry.Gap,
                Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (detailsVisible) {
                // The maximise explanation fires exactly here — on the way into
                // this state — so the flank that carries it in three-up carries
                // it here too, and for the same reason: it is the only slack.
                Column(
                    modifier = Modifier.width(FrameGeometry.FlankWidth).fillMaxHeight(),
                    horizontalAlignment = Alignment.End,
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        CurrentFrameReadout(image = image, bestMetrics = bestMetrics)
                    }
                    HintSlotCard(
                        pendingHint = pendingHint,
                        flankWidth = FrameGeometry.FlankWidth,
                        detailsVisible = true,
                        onDismiss = onDismissHint,
                    )
                }
            }

            Box(
                modifier = Modifier.comparisonSwipeNavigation(
                    canGoPrevious = canGoPrevious,
                    canGoNext = canGoNext,
                    onNavigatePrevious = onNavigatePrevious,
                    onNavigateNext = onNavigateNext,
                ),
            ) {
                ImageTile(
                    image = image,
                    isCurrent = frame == SelectorFrame.CURRENT,
                    onClick = onExit,
                    onLongClick = onLongPress,
                    emptyLabel = "No image",
                    modifier = Modifier
                        .size(size)
                        .testTag("maximised_frame"),
                )
                MaximiseBadge(
                    active = true,
                    onClick = onExit,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                )
            }
        }
    }
}
