package com.photoselectortoolbox.ui.selector

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.photoselectortoolbox.domain.format.ActionLabel
import com.photoselectortoolbox.domain.format.SelectionActionLabels
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.components.MetadataPanel
import com.photoselectortoolbox.ui.components.ScoreChipRow
import com.photoselectortoolbox.ui.theme.BrandAccentTonal
import com.photoselectortoolbox.ui.theme.ScoreBad
import com.photoselectortoolbox.ui.theme.ScoreGood
import com.photoselectortoolbox.ui.theme.Zinc50
import com.photoselectortoolbox.ui.theme.Zinc700
import com.photoselectortoolbox.ui.theme.Zinc900
import com.photoselectortoolbox.ui.theme.Zinc950
import com.photoselectortoolbox.viewmodel.SelectorUiState

/**
 * The phone-sized selector: one frame at a time, browsed by swiping.
 *
 * The geometry of the tablet refresh does not apply here — that layout is built
 * around the letterbox space a 4:3 photo leaves in a 16:10 landscape window,
 * and a phone in portrait has none. Two things from the refresh do apply, and
 * both are corrections rather than restyling:
 *
 * 1. **Horizontal swipe browses, and only browses.** This layout used to bind a
 *    leftward swipe on the info card to *delete*. The same gesture meant "next
 *    frame" on the pager directly above it and in the fullscreen viewer, which
 *    is how the viewer ended up shipping a hint card that called the delete
 *    gesture "navigate". A gesture that destroys a file cannot share a
 *    direction with the gesture that browses past it.
 * 2. **A control that changes a file carries its word.** The three actions were
 *    unlabelled tonal icon buttons; they are now labelled, and the filing one
 *    says Copy or Move according to the setting rather than a euphemism.
 */
@Composable
fun CompactSelectorLayout(
    uiState: SelectorUiState,
    onNavigateToImage: (Int) -> Unit,
    onFullscreen: () -> Unit,
    onMoveToSelection: () -> Unit,
    onCopyToSelection: () -> Unit,
    onDelete: () -> Unit,
    showGestureHint: Boolean = !uiState.hasSeenFullscreenHint,
    onDismissGestureHint: () -> Unit = {},
) {
    val pagerState = rememberPagerState(
        initialPage = uiState.currentIndex,
        pageCount = { uiState.images.size },
    )

    LaunchedEffect(uiState.currentIndex) {
        if (pagerState.currentPage != uiState.currentIndex) {
            pagerState.animateScrollToPage(uiState.currentIndex)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            onNavigateToImage(page)
        }
    }

    val currentImage = uiState.currentImage

    var userDismissedTutorial by remember { mutableStateOf(false) }
    val showGestureTutorial = showGestureHint && uiState.images.isNotEmpty() && !userDismissedTutorial

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { uiState.images[it].uri },
                ) { page ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Zinc950),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = uiState.images[page].uri,
                            contentDescription = uiState.images[page].fileName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }

                IconButton(
                    onClick = onFullscreen,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Fullscreen",
                        tint = Color.White.copy(alpha = 0.8f),
                    )
                }

                currentImage?.let { image ->
                    Text(
                        text = image.fileName,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .background(
                                Color.Black.copy(alpha = 0.5f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Zinc900),
            ) {
                ScoreChipRow(
                    scores = currentImage?.scanResult,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )

                currentImage?.exifData?.let { exif ->
                    MetadataPanel(
                        exifData = exif,
                        compact = true,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            // The same three verbs, in the same order, as the tablet layout —
            // configured filing action first, then the other, then Delete.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectionActionLabels.both(uiState.filingAction).forEach { label ->
                    CompactActionButton(
                        icon = if (label.action == FilingAction.MOVE) {
                            Icons.AutoMirrored.Filled.DriveFileMove
                        } else {
                            Icons.Default.ContentCopy
                        },
                        label = label,
                        onClick = if (label.action == FilingAction.MOVE) {
                            onMoveToSelection
                        } else {
                            onCopyToSelection
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(
                                if (label.action == FilingAction.MOVE) {
                                    "move_button_compact"
                                } else {
                                    "copy_button_compact"
                                }
                            ),
                    )
                }

                CompactDeleteButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("delete_button_compact"),
                )
            }
        }

        GestureTutorialOverlay(
            visible = showGestureTutorial,
            onDismiss = {
                userDismissedTutorial = true
                onDismissGestureHint()
            },
        )
    }
}

@Composable
private fun GestureTutorialOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable { onDismiss() }
            .testTag("gesture_tutorial_overlay"),
        contentAlignment = Alignment.Center,
    ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Text(
                    text = "Gestures",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "Swipe image to browse",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "Tap fullscreen to zoom & inspect",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Tap anywhere to dismiss",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
}

/**
 * One labelled filing control on the phone layout.
 *
 * Labelled rather than an icon button: this control moves or copies a file, and
 * an unlabelled glyph gives the user no way to know which. The verb comes from
 * [ActionLabel], so it tracks the Filing Action setting and can never be a
 * euphemism. The configured action is tinted; the alternative is quiet.
 */
@Composable
private fun CompactActionButton(
    icon: ImageVector,
    label: ActionLabel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(52.dp)
            .background(
                if (label.isPrimary) BrandAccentTonal else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = label.accessibilityLabel }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (label.isPrimary) ScoreGood else Zinc50,
        )
        Text(text = label.verb, fontSize = 14.sp, color = Zinc50)
        Text(
            text = label.shortcut,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Zinc700,
        )
    }
}

/** The delete control: worded, outlined, and never reachable by a swipe. */
@Composable
private fun CompactDeleteButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(52.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Delete this photo" }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = ScoreBad,
        )
        Text(text = "Delete", fontSize = 14.sp, color = ScoreBad)
    }
}
