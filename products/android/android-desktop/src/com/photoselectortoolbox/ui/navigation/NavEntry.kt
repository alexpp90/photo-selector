package com.photoselectortoolbox.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.ui.graphics.vector.ImageVector
import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.text

/**
 * One cell of the navigation: a screen, or the Tools cell that opens Learn and Dupes.
 *
 * Frameduel is one culling desk with tools beside it ([USER 2026-11-12] decision #18): the
 * sidebar, the rail and the bottom bar show Cull, Stats, Tools and Setup, in that order, and
 * Learn and Dupes are reached through Tools. All five screens stay routes ([Screen.all]); only
 * where they are offered changed.
 */
sealed interface NavEntry {
    /** The one word under the glyph. */
    val label: UiText
    val icon: ImageVector
    /** `nav_<route>` for a screen, `nav_tools` for Tools: one selector on every surface. */
    val testTag: String
    /** One line for the guide's screen legend. */
    val meaning: UiText

    /** True when [route] is this entry's screen, or one of its tools. */
    fun isSelected(route: String?): Boolean

    /** What a screen reader says for the cell while [route] is open. */
    fun spoken(route: String?): UiText

    data class Destination(val screen: Screen) : NavEntry {
        override val label: UiText get() = screen.label
        override val icon: ImageVector get() = screen.icon
        override val testTag: String get() = "nav_${screen.route}"
        override val meaning: UiText get() = screen.meaning
        override fun isSelected(route: String?): Boolean = route == screen.route
        override fun spoken(route: String?): UiText = screen.label
    }

    data object Tools : NavEntry {
        /** The screens the Tools menu opens, in menu order. */
        val screens: List<Screen> = listOf(Screen.Inspect, Screen.DuplicateFinder)

        override val label: UiText = text(R.string.label_screen_tools)
        override val icon: ImageVector = Icons.Filled.Handyman
        override val testTag: String = "nav_tools"
        override val meaning: UiText = text(R.string.hint_body_screen_tools)

        override fun isSelected(route: String?): Boolean = screens.any { it.route == route }

        override fun spoken(route: String?): UiText {
            val open = screens.firstOrNull { it.route == route }
            return if (open != null) text(R.string.cd_tools_selected, open.label) else text(R.string.cd_tools)
        }
    }

    companion object {
        /** The navigation cells, top to bottom (rail, sidebar) or start to end (bottom bar). */
        val all: List<NavEntry> = listOf(
            Destination(Screen.PhotoSelector),
            Destination(Screen.Statistics),
            Tools,
            Destination(Screen.Settings),
        )
    }
}
