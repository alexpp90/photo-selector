package com.photoselectortoolbox.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val icon: ImageVector,
    /**
     * The screen's one name, on the sidebar, the rail, the bottom bar and in spoken text alike
     * (backlog 2026-11-11: the cull screen was "Cull", "Selector" and "Culling"). It is the word
     * that fits under an 80 dp sidebar glyph, so no surface needs a second one.
     */
    val label: String,
    /** One line for the guide: what this screen is for. */
    val meaning: String,
) {
    /** [label]; kept so the sidebar's call reads as before. */
    val shortLabel: String get() = label

    data object PhotoSelector : Screen(
        route = "selector",
        meaning = "Compare three frames and file or delete the middle one.",
        icon = Icons.Default.PhotoCamera,
        label = "Cull",
    )

    data object Inspect : Screen(
        route = "inspect",
        meaning = "Inspect composition, exposure technique, and coaching guidance for the selected photograph.",
        icon = Icons.Default.CenterFocusStrong,
        label = "Learn",
    )

    data object Statistics : Screen(
        route = "statistics",
        meaning = "How the folder scored overall, once it has been scanned.",
        icon = Icons.Default.BarChart,
        label = "Stats",
    )

    data object DuplicateFinder : Screen(
        route = "duplicates",
        meaning = "Frames that are the same picture twice.",
        icon = Icons.Default.ContentCopy,
        label = "Dupes",
    )

    data object Settings : Screen(
        route = "settings",
        meaning = "Where frames go, bursts, threads, fullscreen, cache.",
        icon = Icons.Default.Settings,
        label = "Setup",
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
