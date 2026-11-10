package com.phototok.domain

import com.phototok.R

/**
 * One name for the place swipe right fills, used by the bar's Selection button (spoken name and
 * tooltip), its controls-guide callout and the Selection viewer's tab that opens first
 * (REQ-TOK-FEATURE.17): "Favourites" when swipe right marks a favourite, else the Selection
 * folder's name ("Selection", the configured name in Pro, `Jackpix` for a Casual card import).
 * Never "Keep" (usability panel 2026-10-24: the guide said "Selection", the view "Favourites 2").
 */
object SelectionPlace {
    fun name(verb: SwipeRightVerb, selectionFolderName: String): UiText = when (verb) {
        SwipeRightVerb.FAVOURITE -> UiText.of(R.string.chip_favourites)
        SwipeRightVerb.COPY, SwipeRightVerb.MOVE -> UiText.raw(selectionFolderName.ifBlank { PhotoFolders.SELECTION })
    }
}
