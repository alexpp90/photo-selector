package com.photoselectortoolbox.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.ui.graphics.vector.ImageVector

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
    val label: String
    val icon: ImageVector
    /** `nav_<route>` for a screen, `nav_tools` for Tools: one selector on every surface. */
    val testTag: String
    /** One line for the guide's screen legend. */
    val meaning: String

    /** True when [route] is this entry's screen, or one of its tools. */
    fun isSelected(route: String?): Boolean

    /** What a screen reader says for the cell while [route] is open. */
    fun spoken(route: String?): String

    data class Destination(val screen: Screen) : NavEntry {
        override val label: String get() = screen.label
        override val icon: ImageVector get() = screen.icon
        override val testTag: String get() = "nav_${screen.route}"
        override val meaning: String get() = screen.meaning
        override fun isSelected(route: String?): Boolean = route == screen.route
        override fun spoken(route: String?): String = screen.label
    }

    data object Tools : NavEntry {
        /** The screens the Tools menu opens, in menu order. */
        val screens: List<Screen> = listOf(Screen.Inspect, Screen.DuplicateFinder)

        override val label: String = "Tools"
        override val icon: ImageVector = Icons.Filled.Handyman
        override val testTag: String = "nav_tools"
        override val meaning: String = "Learn (beta) and Dupes."

        override fun isSelected(route: String?): Boolean = screens.any { it.route == route }

        override fun spoken(route: String?): String {
            val open = screens.firstOrNull { it.route == route }
            return if (open != null) "Tools, ${open.label} selected" else "Tools. Opens Learn and Dupes."
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

/** The word "Beta" on Learn's chip, and the spoken name of a beta screen. */
object BetaLabel {
    /** `chip_` budget: one word. */
    const val CHIP = "Beta"

    fun spoken(screen: Screen): String = if (screen.beta) "${screen.label}, beta" else screen.label
}
