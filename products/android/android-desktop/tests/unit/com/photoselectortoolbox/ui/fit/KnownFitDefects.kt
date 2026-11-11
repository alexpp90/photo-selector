package com.photoselectortoolbox.ui.fit

/**
 * The defects the font-scale x window-size matrix reproduces on `main` today. Each entry is a
 * strict known failure: [assertFitsExceptKnown] fails the day one stops reproducing, so the
 * entry is removed together with the fix. Each id is a findings-history row;
 * F-FD-080 to F-FD-087 are the ones this matrix found on 2026-11-09. F-FD-051 (Learn tabs and
 * guide toggles), F-FD-081 (coach blur-risk badge) and F-FD-084 (action word and key cap) were
 * fixed on 2026-11-10 and their markers removed.
 */
object KnownFitDefects {

    /** The matrix tests' names, as they pass them to [assertFitsExceptKnown]. */
    const val SELECTOR = "selector"
    const val SELECTOR_FILMSTRIP = "selector+filmstrip"
    const val INSPECT = "inspect"

    private fun FitViolation.on(vararg checks: FitCheck) = check in checks

    val lessonPreviewCut = KnownFitDefect(
        id = "F-FD-080",
        tests = setOf(INSPECT),
        screen = Regex("^inspect/learn_tab_(lessons|practice)/"),
        summary = "Lesson rows cut the \"Try this\" prompt after two lines with no ellipsis, mid-sentence, " +
            "even at 1480 x 924 and font scale 1.0",
        cause = "products/android/android-desktop/src/com/photoselectortoolbox/ui/inspect/InspectScreen.kt:1285 " +
            "(maxLines = 2, default TextOverflow.Clip)",
        matches = { v -> v.on(FitCheck.TEXT_OVERFLOW) && "with no ellipsis" in v.detail },
    )

    val compactActionRow = KnownFitDefect(
        id = "F-FD-082",
        tests = setOf(SELECTOR, SELECTOR_FILMSTRIP),
        summary = "Compact (< 600 dp) selector: Copy / Move / Delete break mid-word (\"Mov/e\", \"Dele/te\") and " +
            "their key caps are squeezed to 0 px at 360 dp; the Fullscreen button is 40 dp (REQ-AND-INPUT.04)",
        cause = "products/android/android-desktop/src/com/photoselectortoolbox/ui/selector/CompactSelectorLayout.kt:367 " +
            "(CompactActionButton: weight(1f), fixed 52 dp height, 14 sp word, no step-down) and :175 " +
            "(IconButton with a 40 dp clickable node)",
        matches = { v ->
            v.cell.compact && (
                (v.node in setOf("\"Copy\"", "\"Move\"", "\"Delete\"", "\"C\"", "\"M\"") &&
                    v.on(FitCheck.TEXT_OVERFLOW, FitCheck.MID_WORD_BREAK)) ||
                    (v.node == "<Fullscreen>" && v.on(FitCheck.SMALL_TARGET))
                )
        },
    )

    val selectorChromeAtLargeFont = KnownFitDefect(
        id = "F-FD-083",
        tests = setOf(SELECTOR, SELECTOR_FILMSTRIP),
        summary = "Three-up selector at font scale 1.3-2.0: the Sort chip word, the position counter, the \"Not scanned\" readout " +
            "exposure tokens and the lens line are cut, lose their line or break mid-word (\"scanne/d\")",
        cause = "products/android/android-desktop/src/com/photoselectortoolbox/ui/selector/LocatorRow.kt:228 " +
            "(sort chip word, maxLines 1, softWrap false), ValueReadouts.kt:497 (\"Not scanned\") and :559 " +
            "(exposure token, softWrap false), :158 (lens line, squeezed to 0 px at 600 x 480)",
        matches = { v ->
            !v.cell.compact && (
                v.node in setOf(
                    "#sort_chip_word", "#sort_chip_key", "#go_chip_key", "#exposure_token", "#readout_lens", "#position_counter",
                ) ||
                    v.node == "\"Not scanned\""
                ) && v.on(FitCheck.TEXT_OVERFLOW, FitCheck.MID_WORD_BREAK)
        },
    )

    val filmstripSqueezed = KnownFitDefect(
        id = "F-FD-085",
        tests = setOf(SELECTOR_FILMSTRIP),
        summary = "At 1024 x 720 with the filmstrip on, the strip is narrowed to 6 dp instead of being dropped: " +
            "thumbnails 6 dp wide, the range label broken a letter per line (REQ-AND-LAYOUT.14)",
        cause = "products/android/android-desktop/src/com/photoselectortoolbox/ui/selector/FrameGeometry.kt:277 " +
            "(filmstripWidth coerced down to the flank's slack instead of 0)",
        matches = { v -> v.node in setOf("#filmstrip_range", "#filmstrip_thumbnail") },
    )

    val narrowScreensAtLargeFont = KnownFitDefect(
        id = "F-FD-086",
        tests = setOf("statistics", "settings", "duplicates/found"),
        summary = "Compact 360 dp window at font scale 2.0: Statistics, Settings and Duplicates break words " +
            "(\"Image/s\", \"35m/m\", \"explanati/ons\", \"s/elected\")",
        cause = "products/android/android-desktop/src/com/photoselectortoolbox/ui/statistics/StatisticsScreen.kt:124 " +
            "(SummaryCard in a three-up Row), settings/SettingsScreen.kt:317, duplicates/DuplicatesScreen.kt:223",
        matches = { v -> v.cell.compact && v.cell.fontScale >= 2f && v.on(FitCheck.MID_WORD_BREAK) },
    )

    val all = listOf(
        lessonPreviewCut,
        compactActionRow, selectorChromeAtLargeFont,
        filmstripSqueezed, narrowScreensAtLargeFont,
    )
}
