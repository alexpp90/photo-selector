package com.photoselectortoolbox.ui.fit

/**
 * The defects the font-scale x window-size matrix reproduces on `main` today. Each entry is a
 * strict known failure: [assertFitsExceptKnown] fails the day one stops reproducing, so the
 * entry is removed together with the fix. Ids `F-FD-…` are ledger rows; `NEW-FD-…` are findings
 * this matrix made that have no ledger row yet.
 */
object KnownFitDefects {

    /** The matrix tests' names, as they pass them to [assertFitsExceptKnown]. */
    const val SELECTOR = "selector"
    const val SELECTOR_FILMSTRIP = "selector+filmstrip"
    const val INSPECT = "inspect"

    private fun FitViolation.on(vararg checks: FitCheck) = check in checks

    /** F-FD-051 — Learn tabs wrap mid-word, guide toggles set their word a letter per line. */
    val learnTabsAndGuideToggles = KnownFitDefect(
        id = "F-FD-051",
        tests = setOf(INSPECT),
        summary = "Learn tabs break mid-word (\"Lesson/s\") and the composition-guide toggles squeeze " +
            "their word to a letter per line and under 48 dp, at font scale 2.0 and in narrow windows",
        cause = "products/android/android-desktop/src/com/photoselectortoolbox/ui/inspect/InspectScreen.kt:821 " +
            "(four weight(1f) tabs with an unbounded Text) and :768 (GuideToggleButton, one Row of seven " +
            "toggles with no wrap or step-down)",
        matches = { v ->
            val tab = v.node in setOf("\"Explore\"", "\"Lessons\"", "\"Compare\"", "\"Practice\"")
            val guide = v.node in setOf(
                "\"Thirds\"", "\"Phi\"", "\"Spiral\"", "\"Flip (TL)\"", "\"Diagonals\"", "\"Horizon\"",
            ) || v.node.startsWith("#toggle_")
            (tab && v.on(FitCheck.MID_WORD_BREAK, FitCheck.TEXT_OVERFLOW)) ||
                (guide && v.on(FitCheck.MID_WORD_BREAK, FitCheck.TEXT_OVERFLOW, FitCheck.SMALL_TARGET))
        },
    )

    val lessonPreviewCut = KnownFitDefect(
        id = "NEW-FD-1",
        tests = setOf(INSPECT),
        screen = Regex("^inspect/learn_tab_(lessons|practice)/"),
        summary = "Lesson rows cut the \"Try this\" prompt after two lines with no ellipsis, mid-sentence, " +
            "even at 1480 x 924 and font scale 1.0",
        cause = "products/android/android-desktop/src/com/photoselectortoolbox/ui/inspect/InspectScreen.kt:1285 " +
            "(maxLines = 2, default TextOverflow.Clip)",
        matches = { v -> v.on(FitCheck.TEXT_OVERFLOW) && "with no ellipsis" in v.detail },
    )

    val blurRiskBadge = KnownFitDefect(
        id = "NEW-FD-2",
        tests = setOf(INSPECT),
        summary = "The Photo Coach blur-risk label and the \"Hotkeys 0–5, X\" caption break mid-word " +
            "(\"Lo/w Blur Risk\", \"Hotkey/s\") at font scale 2.0",
        cause = "products/android/android-desktop/src/com/photoselectortoolbox/ui/inspect/InspectScreen.kt:1523 " +
            "(SpaceBetween Row of two unweighted Texts) and :1828",
        matches = { v ->
            (v.node.contains("Blur Risk") || v.node.contains("Hotkeys")) &&
                v.on(FitCheck.MID_WORD_BREAK, FitCheck.TEXT_OVERFLOW)
        },
    )

    val compactActionRow = KnownFitDefect(
        id = "NEW-FD-3",
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
        id = "NEW-FD-4",
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

    val actionWordsAtLargeFont = KnownFitDefect(
        id = "NEW-FD-5",
        tests = setOf(SELECTOR, SELECTOR_FILMSTRIP),
        summary = "Control block at font scale 1.3-2.0: \"Delete\" ellipsises even at the 9 sp step " +
            "(1024 x 720, 720 x 560, 600 x 480; 1280 x 800 with the filmstrip) and the key cap loses its line " +
            "at 2.0 (REQ-AND-LAYOUT.07: the word steps down before it could ellipsise, the key cap is never shortened)",
        cause = "products/android/android-desktop/src/com/photoselectortoolbox/ui/selector/SelectorControlBlock.kt:562 " +
            "(StepBased 13-9 sp word in a cell priced for font scale 1.0) and :572 (key cap, 10 sp, softWrap false)",
        matches = { v -> v.node in setOf("#action_word", "#action_key") && v.on(FitCheck.TEXT_OVERFLOW) },
    )

    val filmstripSqueezed = KnownFitDefect(
        id = "NEW-FD-6",
        tests = setOf(SELECTOR_FILMSTRIP),
        summary = "At 1024 x 720 with the filmstrip on, the strip is narrowed to 6 dp instead of being dropped: " +
            "thumbnails 6 dp wide, the range label broken a letter per line (REQ-AND-LAYOUT.14)",
        cause = "products/android/android-desktop/src/com/photoselectortoolbox/ui/selector/FrameGeometry.kt:277 " +
            "(filmstripWidth coerced down to the flank's slack instead of 0)",
        matches = { v -> v.node in setOf("#filmstrip_range", "#filmstrip_thumbnail") },
    )

    val narrowScreensAtLargeFont = KnownFitDefect(
        id = "NEW-FD-7",
        tests = setOf("statistics", "settings", "duplicates/found"),
        summary = "Compact 360 dp window at font scale 2.0: Statistics, Settings and Duplicates break words " +
            "(\"Image/s\", \"35m/m\", \"explanati/ons\", \"s/elected\")",
        cause = "products/android/android-desktop/src/com/photoselectortoolbox/ui/statistics/StatisticsScreen.kt:124 " +
            "(SummaryCard in a three-up Row), settings/SettingsScreen.kt:317, duplicates/DuplicatesScreen.kt:223",
        matches = { v -> v.cell.compact && v.cell.fontScale >= 2f && v.on(FitCheck.MID_WORD_BREAK) },
    )

    val all = listOf(
        learnTabsAndGuideToggles, lessonPreviewCut, blurRiskBadge,
        compactActionRow, selectorChromeAtLargeFont,
        actionWordsAtLargeFont, filmstripSqueezed, narrowScreensAtLargeFont,
    )
}
