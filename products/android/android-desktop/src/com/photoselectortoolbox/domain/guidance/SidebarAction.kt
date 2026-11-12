package com.photoselectortoolbox.domain.guidance

/**
 * The session actions in the upper half of the sidebar, as data.
 *
 * One declaration, read twice: [com.photoselectortoolbox.ui.selector.SelectorSidebar]
 * renders the word under each glyph, and the guide explains what the glyph
 * means beside the real item. Written out in two places they drift, and a
 * legend that names a control something the control does not call itself is
 * worse than no legend.
 *
 * [label] is the word that fits under an 88 dp glyph. [meaning] is the line the
 * guide shows beside it — what the control *does*, not what it is called, since
 * the name is already legible on screen.
 *
 * Compose-free so the wording stays unit-testable on the JVM; the glyph for each
 * is chosen by an exhaustive `when` in the UI, so an action added here without
 * one does not build.
 */
enum class SidebarAction(
    val label: String,
    val description: String,
    val meaning: String,
) {
    FOLDER(
        label = "Folder",
        description = "Open a folder",
        meaning = "Pick the shoot to cull. Sub-folders are included.",
    ),
    SCAN(
        label = "Scan",
        description = "Scan images for quality scores",
        meaning = "Measure every frame, so the numbers beside the picture have something to say.",
    ),
    BURSTS(
        label = "Bursts",
        description = "Bursts, on/off",
        meaning = "Mark frames shot seconds apart as one series.",
    ),
    LEGEND(
        label = "Legend",
        description = "What everything on this screen means",
        meaning = "This guide: every icon named where it sits.",
    ),
    MORE(
        label = "More",
        description = "More options",
        meaning = "Undo, open folder, scan, sort, go to, guide.",
    ),
}
