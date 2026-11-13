package com.photoselectortoolbox.ui.navigation

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import com.photoselectortoolbox.ui.components.MinTouchTarget
import com.photoselectortoolbox.ui.components.handCursor
import com.photoselectortoolbox.ui.text.asString

/**
 * The Tools cell's menu state, one per navigation surface.
 *
 * Focus goes back to the Tools cell when the menu closes without opening a screen, but only if
 * the cell had focus when it opened — a keyboard user pressed Enter on it. A pointer opened it
 * otherwise, and moving focus onto the cell then would take the arrow keys from the screen
 * (Learn steps photos with them).
 */
@Stable
class ToolsMenuState {
    var expanded by mutableStateOf(false)
        private set
    val cellFocus = FocusRequester()
    private var cellFocused = false
    private var restoreFocus = false

    /** On the Tools cell: tracks whether a keyboard user is on it. */
    fun Modifier.toolsCell(): Modifier = this
        .focusRequester(cellFocus)
        .onFocusChanged { cellFocused = it.isFocused }

    fun open() {
        restoreFocus = cellFocused
        expanded = true
    }

    /** Closed by `Esc` or a tap outside: back to the Tools cell for a keyboard user. */
    fun dismiss() {
        expanded = false
        if (restoreFocus) cellFocus.requestFocus()
        restoreFocus = false
    }

    /** A row was chosen: the screen it opens takes focus. */
    fun chose() {
        expanded = false
        restoreFocus = false
    }
}

@Composable
fun rememberToolsMenuState(): ToolsMenuState = remember { ToolsMenuState() }

/**
 * Learn and Dupes, opened from the Tools cell ([USER 2026-11-12] decision #18). Each row is a
 * 48 dp target tagged `nav_<route>`, as the cells were, spoken by its screen's name. Learn's Beta
 * chip went once Compare showed both frames and Learn's progress was stored. Arrows move between the rows, Enter opens one, `Esc` closes the menu
 * (Material's `DropdownMenu`, a focusable popup).
 */
@Composable
fun ToolsMenu(
    state: ToolsMenuState,
    currentRoute: String?,
    onOpen: (Screen) -> Unit,
    offset: DpOffset = DpOffset.Zero,
) {
    DropdownMenu(
        expanded = state.expanded,
        onDismissRequest = state::dismiss,
        offset = offset,
        modifier = Modifier.testTag("tools_menu"),
    ) {
        NavEntry.Tools.screens.forEach { screen ->
            val label = screen.label.asString()
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(imageVector = screen.icon, contentDescription = null) },
                onClick = {
                    state.chose()
                    onOpen(screen)
                },
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .handCursor()
                    .semantics {
                        contentDescription = label
                        selected = currentRoute == screen.route
                    }
                    .testTag("nav_${screen.route}"),
            )
        }
    }
}
