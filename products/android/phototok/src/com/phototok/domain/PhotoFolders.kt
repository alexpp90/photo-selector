package com.phototok.domain

/**
 * Single source of truth for the app-managed folder names created inside
 * user-selected folders (previously duplicated as constants on the repository).
 */
object PhotoFolders {
    const val SELECTION = "Selection"
    const val LEFT_SWIPE = "PhotoTok_LeftSwipe"

    /**
     * Where a Casual-mode swipe right on an SD card or camera copies to: this subfolder of the
     * phone's camera folder. An on-disk name, never translated.
     */
    const val CASUAL_IMPORT = "Jackpix"
}
