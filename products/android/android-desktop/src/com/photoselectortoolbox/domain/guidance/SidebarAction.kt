package com.photoselectortoolbox.domain.guidance

import androidx.annotation.StringRes
import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.text

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
    @StringRes private val labelRes: Int,
    @StringRes private val descriptionRes: Int,
    @StringRes private val meaningRes: Int,
) {
    FOLDER(R.string.label_sidebar_folder, R.string.cd_sidebar_folder, R.string.hint_body_sidebar_folder),
    SCAN(R.string.label_sidebar_scan, R.string.cd_sidebar_scan, R.string.hint_body_sidebar_scan),
    BURSTS(R.string.label_sidebar_bursts, R.string.cd_sidebar_bursts, R.string.hint_body_sidebar_bursts),
    LEGEND(R.string.label_sidebar_legend, R.string.cd_sidebar_legend, R.string.hint_body_sidebar_legend),
    MORE(R.string.label_sidebar_more, R.string.cd_sidebar_more, R.string.hint_body_sidebar_more),
    ;

    /** The word under the glyph. */
    val label: UiText get() = text(labelRes)

    /** What a screen reader says for the item. */
    val description: UiText get() = text(descriptionRes)

    /** The guide's line beside it: what the control does. */
    val meaning: UiText get() = text(meaningRes)
}
