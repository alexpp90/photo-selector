package com.photoselectortoolbox.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.text

sealed class Screen(
    val route: String,
    val icon: ImageVector,
    /**
     * The screen's one name, on the sidebar, the rail, the bottom bar and in spoken text alike
     * (backlog 2026-11-11: the cull screen was "Cull", "Selector" and "Culling"). It is the word
     * that fits under an 80 dp sidebar glyph, so no surface needs a second one.
     */
    val label: UiText,
    /** One line for the guide: what this screen is for. */
    val meaning: UiText,
) {
    /** [label]; kept so the sidebar's call reads as before. */
    val shortLabel: UiText get() = label

    data object PhotoSelector : Screen(
        route = "selector",
        meaning = text(R.string.hint_body_screen_cull),
        icon = Icons.Default.PhotoCamera,
        label = text(R.string.label_screen_cull),
    )

    data object Inspect : Screen(
        route = "inspect",
        meaning = text(R.string.hint_body_screen_learn),
        icon = Icons.Default.CenterFocusStrong,
        label = text(R.string.label_screen_learn),
    )

    data object Statistics : Screen(
        route = "statistics",
        meaning = text(R.string.hint_body_screen_stats),
        icon = Icons.Default.BarChart,
        label = text(R.string.label_screen_stats),
    )

    data object DuplicateFinder : Screen(
        route = "duplicates",
        meaning = text(R.string.hint_body_screen_dupes),
        icon = Icons.Default.ContentCopy,
        label = text(R.string.label_screen_dupes),
    )

    data object Settings : Screen(
        route = "settings",
        meaning = text(R.string.hint_body_screen_setup),
        icon = Icons.Default.Settings,
        label = text(R.string.label_screen_setup),
    )

    companion object {
        /**
         * Every screen, each a route of the nav host. The navigation bars draw [NavEntry.all]:
         * Learn and Dupes are offered through Tools, not as cells of their own.
         */
        val all: List<Screen>
            get() = listOf(PhotoSelector, Inspect, Statistics, DuplicateFinder, Settings)
    }
}
