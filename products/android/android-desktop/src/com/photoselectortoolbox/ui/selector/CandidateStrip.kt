package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.format.SelectorLabels
import com.photoselectortoolbox.domain.scoring.ScoreMetric
import com.photoselectortoolbox.ui.components.goodnessColor
import com.photoselectortoolbox.ui.theme.CurationAmber
import com.photoselectortoolbox.ui.theme.ErrorRed
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.StudioBorder
import com.photoselectortoolbox.ui.theme.StudioCard
import com.photoselectortoolbox.ui.theme.StudioPanel
import com.photoselectortoolbox.ui.theme.Zinc500

/**
 * Width of the strip, decided by the frame solver rather than here.
 *
 * Kept as an alias so call sites read as geometry and there is still exactly
 * one number — see [FrameGeometry.FilmstripWidth] for why it is 72 dp.
 */
val FilmstripWidth = FrameGeometry.FilmstripWidth

/**
 * Thumbnail width. Constant, because in a vertical strip width is the cross
 * axis: a column of ragged-width thumbnails is a column that cannot be scanned.
 */
private val ThumbnailWidth = 60.dp
private val LandscapeThumbnailHeight = 40.dp
private val PortraitThumbnailHeight = 80.dp

/** Extra space that separates one burst from the next. */
private val BurstGap = 10.dp

/**
 * Space between two thumbnails as drawn. It lies inside the touch targets, half
 * above and half below each thumbnail, so the targets abut instead of leaving a
 * dead strip between them.
 */
private val ThumbnailGap = 4.dp

/**
 * Smallest touch target a thumbnail gets (AD-8). A 40 dp landscape thumbnail is
 * centred in a 48 dp slot: the thumbnail stays 3:2 and the strip still scans as
 * a column, while the hit area meets the floor. At 40 dp tall on a 44 dp pitch
 * it failed the accessibility check on the Pixel Tablet and the Tab S11 Ultra,
 * the only devices wide enough to show the strip (device matrix, 2026-10-19).
 */
internal val ThumbnailTouchTarget = 48.dp

/**
 * The filmstrip down the outer edge of the control flank: where the current
 * frame sits in the folder, and roughly how good its neighbours are.
 *
 * **Vertical, not horizontal, and that is the whole point.** This layout is
 * height-bound and width-surplus ([FrameGeometry]), so a bar across the bottom
 * is the most expensive place on the screen to put anything — measured, 76 dp
 * of full-width strip took the reference frames from 675 × 450 to 618 × 412. A
 * column in the flank costs the frames nothing *and* shows more of the folder:
 * 72 dp of width against a 450 dp flank carries ~10 thumbnails where the
 * horizontal strip squeezed into the same flank carried four.
 *
 * Thumbnails carry a single score dot rather than text. At this size there is
 * no room for a legible number, and the question the strip answers is "is there
 * anything better nearby", which a colour answers faster than digits. Sharpness
 * is the metric shown because it is the one that most often decides a cull.
 */
@Composable
fun CandidateStrip(
    images: List<ImageItem>,
    currentIndex: Int,
    onImageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    groups: List<List<Int>>? = null,
    selectedUri: String? = null,
) {
    val listState = rememberLazyListState()

    // Index -> burst id, so a thumbnail knows both which burst it belongs to
    // and whether it starts a new one.
    val indexToGroup = remember(groups) {
        if (groups == null) {
            emptyMap()
        } else {
            buildMap {
                groups.forEachIndexed { groupIdx, memberIndices ->
                    memberIndices.forEach { imageIdx -> put(imageIdx, groupIdx) }
                }
            }
        }
    }
    val currentGroup = indexToGroup[currentIndex]

    // Determine the scroll target index honoring selectedUri if present
    val scrollToIndex = remember(images, selectedUri, currentIndex) {
        if (selectedUri != null) {
            val found = images.indexOfFirst { it.uri == selectedUri }
            if (found >= 0) found else currentIndex
        } else {
            currentIndex
        }
    }

    // Keep the current frame visible without yanking the strip about: scrolling
    // to a fixed offset puts it above centre, where the frames the user is
    // about to reach are still on screen.
    LaunchedEffect(scrollToIndex) {
        if (images.isNotEmpty() && scrollToIndex in images.indices) {
            listState.animateScrollToItem(index = scrollToIndex, scrollOffset = -200)
        }
    }

    val visibleRange by remember {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo
            if (info.isEmpty()) null else info.first().index to info.last().index
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(FilmstripWidth)
            .background(StudioPanel)
            .testTag("filmstrip"),
    ) {
        // A 1 dp rule on the leading edge, not a shadow. Surfaces on this screen
        // separate by outline and value step (DESIGN §3); a horizontal strip put
        // this rule along its top, a vertical one puts it down its left.
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(StudioBorder)
                .align(Alignment.TopStart),
        )

        Column(modifier = Modifier.fillMaxHeight().fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                // No spacing: each thumbnail carries its share of the gap inside
                // its own touch target (see [ThumbnailGap]).
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                itemsIndexed(
                    items = images,
                    key = { _, image -> image.uri },
                ) { index, image ->
                    val group = indexToGroup[index]
                    val startsNewBurst = group != null && group != indexToGroup[index - 1]
                    val isCurrent = if (selectedUri != null) {
                        image.uri == selectedUri
                    } else {
                        index == currentIndex
                    }

                    CandidateThumbnail(
                        image = image,
                        isCurrent = isCurrent,
                        inCurrentBurst = group != null && group == currentGroup,
                        position = index + 1,
                        total = images.size,
                        onClick = { onImageSelected(index) },
                        modifier = Modifier.padding(
                            top = if (startsNewBurst) BurstGap else 0.dp,
                        ),
                    )
                }
            }

            // The caption sits below the list rather than over it. Overlaid on a
            // 72 dp column it would cover a thumbnail outright, which a caption
            // reporting what is visible has no business doing. `115–156 of 842`
            // wraps to two lines at this width, which is why it is centred.
            visibleRange?.let { (first, last) ->
                Text(
                    text = SelectorLabels.filmstripRange(first, last, images.size),
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Zinc500,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .testTag("filmstrip_range"),
                )
            }
        }
    }
}

@Composable
private fun CandidateThumbnail(
    image: ImageItem,
    isCurrent: Boolean,
    inCurrentBurst: Boolean,
    position: Int,
    total: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(3.dp)
    val height = if (image.isLandscape) LandscapeThumbnailHeight else PortraitThumbnailHeight
    // The whole slot is the control; the ripple stays on the thumbnail it belongs to.
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .width(ThumbnailWidth + if (inCurrentBurst) 4.dp else 0.dp)
            .height(maxOf(height + ThumbnailGap, ThumbnailTouchTarget))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics {
                contentDescription = "${image.fileName}, $position of $total" +
                    if (image.rating != 0) ", rated ${image.rating}" else ""
            }
            .testTag("filmstrip_thumbnail"),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .width(ThumbnailWidth)
                .height(height)
                // Neighbours are held back a little so the current frame reads
                // first, but never so far that their content is unreadable.
                .alpha(if (isCurrent) 1f else 0.85f)
                .clip(shape)
                .background(StudioCard)
                .border(
                    width = if (isCurrent) 2.dp else 1.dp,
                    color = if (isCurrent) BrandAccent else StudioBorder,
                    shape = shape,
                )
                .indication(interactionSource, LocalIndication.current)
                .testTag("filmstrip_thumbnail_image"),
        ) {
            AsyncImage(
                model = image.uri,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )

            // Compact star rating badge (bottom start) staying strictly within 60dp thumbnail bounds
            if (image.rating != 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 2.dp, vertical = 1.dp)
                        .testTag("thumbnail_rating_${image.rating}"),
                ) {
                    if (image.rating in 1..5) {
                        Text(
                            text = "★${image.rating}",
                            color = CurationAmber,
                            fontSize = 8.sp,
                            lineHeight = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    } else if (image.rating == -1) {
                        Text(
                            text = "✕",
                            color = ErrorRed,
                            fontSize = 8.sp,
                            lineHeight = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            image.scanResult?.sharpnessScore?.let { sharpness ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .size(6.dp)
                        .background(
                            goodnessColor(ScoreMetric.SHARPNESS.goodness(sharpness)),
                            CircleShape,
                        ),
                )
            }
        }

        // Burst marker: the frames that belong to the same series as the current
        // one, so a photographer can see the shape of the burst they are inside
        // without reading filenames. It ran under the thumbnails when the strip
        // ran across the screen; down the strip's leading edge it is the same
        // 2 dp BrandAccent rule reading the same way — a bracket around a run.
        if (inCurrentBurst) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(2.dp)
                    .height(height)
                    .background(BrandAccent),
            )
        }
    }
}

/** Kept for callers that only need the dot colour. */
internal fun sharpnessDotColor(sharpness: Double): Color =
    goodnessColor(ScoreMetric.SHARPNESS.goodness(sharpness))
