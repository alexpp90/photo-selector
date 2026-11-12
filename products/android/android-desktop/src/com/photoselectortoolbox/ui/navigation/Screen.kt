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
    /** Full name, used for accessibility and anywhere there is room for it. */
    val label: String,
    /**
     * The word that fits under an 80 dp sidebar glyph.
     *
     * Separate from [label] rather than ellipsised from it: "Duplicat…" is not
     * a word, and the sidebar's whole purpose is that every control is readable
     * without decoding a glyph. Where the full name fits, [label] is used.
     */
    val shortLabel: String,
    /** One line for the guide: what this screen is for. */
    val meaning: String,
) {
    data object PhotoSelector : Screen(
        route = "selector",
        meaning = "Compare three frames and file or delete the middle one.",
        icon = Icons.Default.PhotoCamera,
        label = "Selector",
        shortLabel = "Cull",
    )

    data object Inspect : Screen(
        route = "inspect",
        meaning = "Inspect composition, exposure technique, and coaching guidance for the selected photograph.",
        icon = Icons.Default.CenterFocusStrong,
        label = "Learn",
        shortLabel = "Learn",
    )

    data object Statistics : Screen(
        route = "statistics",
        meaning = "How the folder scored overall, once it has been scanned.",
        icon = Icons.Default.BarChart,
        label = "Statistics",
        shortLabel = "Stats",
    )

    data object DuplicateFinder : Screen(
        route = "duplicates",
        meaning = "Frames that are the same picture twice.",
        icon = Icons.Default.ContentCopy,
        label = "Duplicates",
        shortLabel = "Dupes",
    )

    data object Settings : Screen(
        route = "settings",
        meaning = "Grouping, threads, where filed frames go.",
        icon = Icons.Default.Settings,
        label = "Settings",
        shortLabel = "Setup",
    )

    companion object {
        /** Screens shown in the desktop/tablet navigation bars. */
        val all: List<Screen>
            get() = listOf(PhotoSelector, Inspect, Statistics, DuplicateFinder, Settings)
    }
}
