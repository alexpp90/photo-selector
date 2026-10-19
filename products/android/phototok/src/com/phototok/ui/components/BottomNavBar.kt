package com.phototok.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phototok.ui.phonemode.ControlsGuideAnchors
import com.phototok.ui.phonemode.GuideAnchor
import com.phototok.ui.phonemode.PhoneModeChromeMetrics
import com.phototok.ui.phonemode.guideAnchor

enum class NavTab { Sources, Cards, History }

/**
 * Bottom action bar shown while viewing photos (portrait).
 *
 * Left   = Folder → go back to landing page.
 * Middle = Selection → jump to the read-only Selection-folder view.
 * Right  = Revert last deletion from trash (active only when [canRevert]).
 */
@Composable
fun ViewerBottomBar(
    canRevert: Boolean,
    onRevert: () -> Unit,
    onJumpToSelection: () -> Unit,
    onGoToLanding: () -> Unit,
    modifier: Modifier = Modifier,
    /** Registers the bar and its buttons for the controls guide's callouts. */
    anchors: ControlsGuideAnchors? = null,
) {
    val colors = MaterialTheme.colorScheme
    val metrics = PhoneModeChromeMetrics

    Row(
        modifier = modifier
            .fillMaxWidth()
            .guideAnchor(anchors, GuideAnchor.BOTTOM_BAR)
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(colors.surfaceContainerLowest.copy(alpha = 0.8f))
            .navigationBarsPadding()
            .padding(
                horizontal = metrics.BOTTOM_BAR_HORIZONTAL_PADDING,
                vertical = metrics.BOTTOM_BAR_VERTICAL_PADDING,
            )
            .testTag("viewer_bottom_bar"),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: Go back to landing (Sources)
        ActionButton(
            icon = Icons.Default.FolderOpen,
            label = "Sources",
            isActive = false,
            enabled = true,
            onClick = onGoToLanding,
            modifier = Modifier.guideAnchor(anchors, GuideAnchor.SOURCES).testTag("viewer_sources_button"),
        )
        // Middle: Jump to selection folder
        ActionButton(
            icon = Icons.Default.CollectionsBookmark,
            label = "Selection",
            isActive = false,
            enabled = true,
            onClick = onJumpToSelection,
            modifier = Modifier.guideAnchor(anchors, GuideAnchor.SELECTION).testTag("viewer_selection_button"),
        )
        // Right: Revert (restore pending deleted photo from trash)
        ActionButton(
            icon = Icons.Default.RestoreFromTrash,
            label = "Revert",
            isActive = canRevert,
            enabled = canRevert,
            onClick = onRevert,
            modifier = Modifier.guideAnchor(anchors, GuideAnchor.REVERT).testTag("viewer_revert_button"),
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
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

    Box(
        modifier = modifier
            .size(
                width = PhoneModeChromeMetrics.BOTTOM_BAR_BUTTON_WIDTH,
                height = PhoneModeChromeMetrics.BOTTOM_BAR_BUTTON_HEIGHT,
            )
            .then(
                if (isActive) Modifier.clip(RoundedCornerShape(14.dp)).background(colors.primaryContainer)
                else Modifier,
            )
            .then(
                if (enabled) Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClick,
                ) else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                color = tint,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun BottomNavBar(
    activeTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(colors.surfaceContainerLowest.copy(alpha = 0.8f))
            .navigationBarsPadding()
            .padding(horizontal = 32.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(Icons.Default.FolderOpen, "Sources", activeTab == NavTab.Sources) {
            onTabSelected(NavTab.Sources)
        }
        NavItem(Icons.Default.Style, "Cards", activeTab == NavTab.Cards) {
            onTabSelected(NavTab.Cards)
        }
        NavItem(Icons.Default.History, "History", activeTab == NavTab.History) {
            onTabSelected(NavTab.History)
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .size(48.dp)
            .then(
                if (isActive) {
                    Modifier
                        .clip(CircleShape)
                        .background(colors.primaryContainer)
                } else {
                    Modifier
                },
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) colors.onPrimaryContainer else colors.secondary,
            modifier = Modifier.size(24.dp),
        )
    }
}
