package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.domain.format.SelectorLabels
import com.photoselectortoolbox.domain.interaction.SelectorShortcut
import com.photoselectortoolbox.ui.components.handCursor

/**
 * What the More menu offers, as values and lambdas: one list for the sidebar's More (wide
 * windows) and the compact action row's More (below 600 dp), so a window dragged narrow keeps
 * every session action (REQ-AND-LAYOUT.02). A null action is a row this layout does not draw.
 */
data class SelectorMoreMenu(
    /** Entries in the undo history, the same ones `Ctrl+Z` steps through (REQ-AND-LAYOUT.22). */
    val undoSteps: Int,
    val hasImages: Boolean,
    val canScan: Boolean,
    val hasAnyScores: Boolean,
    val sortChoice: SortChoice,
    val onUndo: () -> Unit,
    val onScan: () -> Unit,
    val onOpenFolder: () -> Unit,
    val onSort: (SortChoice) -> Unit,
    val onGoToPhoto: () -> Unit,
    val onClearScores: () -> Unit,
    /** Null where the guide cannot be drawn (the compact layout has no coach overlay). */
    val onShowGuide: (() -> Unit)?,
)

/** The More menu's rows; every row closes the menu through [onDismiss] before it acts. */
@Composable
internal fun SelectorMoreMenuItems(menu: SelectorMoreMenu, onDismiss: () -> Unit) {
    val act: (() -> Unit) -> () -> Unit = { action -> { onDismiss(); action() } }
    DropdownMenuItem(
        text = { Text(SelectorLabels.UNDO_MENU) },
        trailingIcon = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                KeyCap(SelectorShortcut.UNDO.input)
                Text(
                    text = menu.undoSteps.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("menu_undo_count"),
                )
            }
        },
        // Undo stays in the menu, not only on the card that leaves after 4 s, so touch and
        // pointer reach every one of the steps the keyboard does.
        onClick = { menu.onUndo() },
        enabled = menu.undoSteps > 0,
        modifier = Modifier
            .testTag("menu_undo")
            .semantics { contentDescription = SelectorLabels.undoMenuSpoken(menu.undoSteps) }
            .handCursor(),
    )
    HorizontalDivider()
    DropdownMenuItem(
        modifier = Modifier.testTag("menu_open_folder").handCursor(),
        text = { Text("Open folder") },
        onClick = act(menu.onOpenFolder),
    )
    DropdownMenuItem(
        modifier = Modifier.testTag("menu_scan").handCursor(),
        text = { Text("Scan images") },
        onClick = act(menu.onScan),
        enabled = menu.hasImages && menu.canScan,
    )
    HorizontalDivider()
    // The same radio group the Sort chip opens (one list, two doors).
    SortMenuItems(
        selected = menu.sortChoice,
        blurriestEnabled = menu.hasAnyScores,
        onSelect = { choice -> onDismiss(); menu.onSort(choice) },
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text(SelectorLabels.GO_TO_PHOTO_MENU) },
        trailingIcon = { KeyCap(SelectorShortcut.GO_TO.input) },
        onClick = act(menu.onGoToPhoto),
        enabled = menu.hasImages,
        modifier = Modifier.testTag("menu_go_to_photo").handCursor(),
    )
    DropdownMenuItem(
        modifier = Modifier.handCursor(),
        text = { Text("Clear scores") },
        onClick = act(menu.onClearScores),
        enabled = menu.hasAnyScores,
    )
    menu.onShowGuide?.let { showGuide ->
        DropdownMenuItem(
            modifier = Modifier.handCursor(),
            text = { Text("Shortcuts and guide") },
            onClick = act(showGuide),
        )
    }
}

/** A mono key cap, the key hint shown at the end of a menu row. */
@Composable
internal fun KeyCap(key: String) {
    Text(
        text = key,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
