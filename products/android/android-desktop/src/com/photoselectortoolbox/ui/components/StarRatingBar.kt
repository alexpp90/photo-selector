package com.photoselectortoolbox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.ui.theme.ScoreBad
import com.photoselectortoolbox.ui.theme.Zinc600
import com.photoselectortoolbox.ui.theme.Zinc700

// Star color tokens
private val StarGold = Color(0xFFFACC15)
private val StarAmber500 = Color(0xFFF59E0B)
private val StarAmber400 = Color(0xFFFBBF24)

/**
 * Slot geometry. Each control is a whole slot of at least [MinTouchTarget] with no gap to its
 * neighbour (the visual gap lies inside the abutting slots), so the 48 dp target is the node
 * that carries the click and the test tag.
 */
object StarRatingBarDefaults {
    fun slotSize(starSize: Dp): Dp = maxOf(starSize + 4.dp, MinTouchTarget)
}

/**
 * Interactive star rating component displaying 1 to 5 stars, unrated/clear, and reject options.
 *
 * Supports touch clicks, S-Pen stylus hover/tap, and accessibility labels.
 *
 * Ratings:
 * - `0`: Unrated
 * - `1..5`: Star ratings (1 to 5 stars)
 * - `-1`: Rejected
 */
@Composable
fun StarRatingBar(
    rating: Int,
    onRatingChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    starSize: Dp = 24.dp,
    showRejectButton: Boolean = true,
    showClearButton: Boolean = true,
) {
    // Seven 48 dp slots are 336 dp; a narrower panel scrolls the row instead of squeezing a target.
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .testTag("star_rating_bar")
            .semantics { contentDescription = "Rating bar, current rating: $rating" },
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showRejectButton) {
            val isRejected = rating == -1
            Box(
                modifier = Modifier
                    .size(StarRatingBarDefaults.slotSize(starSize))
                    .clip(CircleShape)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        enabled = enabled,
                        role = Role.Button,
                    ) {
                        if (isRejected) onRatingChanged(0) else onRatingChanged(-1)
                    }
                    .padding(2.dp)
                    .testTag("star_-1")
                    .semantics {
                        role = Role.Button
                        contentDescription = if (isRejected) "Rejected, click to clear" else "Reject photo"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Block,
                    contentDescription = null,
                    tint = if (isRejected) ScoreBad else Zinc600,
                    modifier = Modifier.size(starSize),
                )
            }
        }

        // 1 to 5 Stars
        for (i in 1..5) {
            val isSelected = rating in 1..5 && rating >= i
            val starColor = when {
                !isSelected -> Zinc600
                rating == 5 -> StarGold
                rating >= 3 -> StarAmber500
                else -> StarAmber400
            }

            Box(
                modifier = Modifier
                    .size(StarRatingBarDefaults.slotSize(starSize))
                    .clip(CircleShape)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        enabled = enabled,
                        role = Role.RadioButton,
                    ) {
                        // Tapping the same star clears to 0 (unrate)
                        if (rating == i) {
                            onRatingChanged(0)
                        } else {
                            onRatingChanged(i)
                        }
                    }
                    .padding(2.dp)
                    .testTag("star_$i")
                    .semantics {
                        role = Role.RadioButton
                        contentDescription = "$i star${if (i > 1) "s" else ""}${if (rating == i) ", selected" else ""}"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = null,
                    tint = starColor,
                    modifier = Modifier.size(starSize),
                )
            }
        }

        // Optional clear / unrate button when a rating or reject is active
        if (showClearButton && rating != 0) {
            Box(
                modifier = Modifier
                    .size(StarRatingBarDefaults.slotSize(starSize))
                    .clip(CircleShape)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        enabled = enabled,
                        role = Role.Button,
                    ) {
                        onRatingChanged(0)
                    }
                    .padding(2.dp)
                    .testTag("star_0")
                    .testTag("clear_rating")
                    .semantics {
                        role = Role.Button
                        contentDescription = "Clear rating"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = Zinc700,
                    modifier = Modifier.size(starSize - 4.dp),
                )
            }
        }
    }
}
