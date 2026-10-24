package com.phototok.data.model

import com.photoselector.core.storage.SourceKind
import com.phototok.domain.CollectionAction
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.PhotoFolders
import com.phototok.domain.ProModePolicy
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb

/**
 * Per-source-kind workflow settings (`PHONE_LIBRARY`, `REMOVABLE`, `CAMERA_MTP`, `CLOUD_OTHER`).
 */
data class SourceSettings(
    val swipeRightVerb: SwipeRightVerb = SwipeRightVerb.FAVOURITE,
    val swipeLeftVerb: SwipeAction = SwipeAction.DELETE,
    val feedOrderMode: FeedOrderMode = FeedOrderMode.NEWEST_FIRST,
    val fileTypeFilter: FileTypeFilter = FileTypeFilter.ONE_PER_SHOT,
    val moveRelatedFiles: Boolean = true,
    val sortingEnabled: Boolean = false,
    val directDeleteConfirmEnabled: Boolean = true,
    val collectionUri: String? = null,
    val leftSwipeUri: String? = null,
    /** Name of the subfolder swipe-right copies/moves land in (`Selection`, or `Jackpix` for Casual imports). */
    val selectionSubfolder: String = PhotoFolders.SELECTION,
    /**
     * Casual mode on an SD card or camera: swipe right copies into the phone's camera folder
     * (`ProModePolicy.CAMERA_IMPORT_DOCUMENT_ID`), subfolder [selectionSubfolder], instead of
     * `<card>/Selection`. Always false for Pro values.
     */
    val importToPhoneCamera: Boolean = false,
) {
    val collectionAction: CollectionAction
        get() = swipeRightVerb.toCollectionAction()

    val feedOrder: FeedOrderMode
        get() = feedOrderMode

    val leftSwipeAction: SwipeAction
        get() = swipeLeftVerb

    val collectionFolderUri: String?
        get() = collectionUri

    val leftSwipeFolderUri: String?
        get() = leftSwipeUri

    val randomizeOrder: Boolean
        get() = feedOrderMode.isRandomized

    val sortByOrientation: Boolean
        get() = feedOrderMode.isSortByOrientation

    companion object {
        fun defaultFor(kind: SourceKind): SourceSettings = when (kind) {
            SourceKind.PHONE_LIBRARY,
            SourceKind.CLOUD_OTHER -> SourceSettings(
                swipeRightVerb = SwipeRightVerb.FAVOURITE,
                swipeLeftVerb = SwipeAction.DELETE,
                feedOrderMode = FeedOrderMode.NEWEST_FIRST,
                fileTypeFilter = FileTypeFilter.ONE_PER_SHOT,
                moveRelatedFiles = true,
                sortingEnabled = false,
                directDeleteConfirmEnabled = true,
                collectionUri = null,
                leftSwipeUri = null,
            )
            SourceKind.REMOVABLE,
            SourceKind.CAMERA_MTP -> SourceSettings(
                swipeRightVerb = SwipeRightVerb.COPY,
                swipeLeftVerb = SwipeAction.DELETE,
                feedOrderMode = FeedOrderMode.SHOOTING,
                fileTypeFilter = FileTypeFilter.ONE_PER_SHOT,
                moveRelatedFiles = true,
                sortingEnabled = true,
                directDeleteConfirmEnabled = true,
                collectionUri = null,
                leftSwipeUri = null,
            )
        }
    }
}

/**
 * App-wide settings that apply across all source kinds.
 */
data class AppSettings(
    val selectionFolderName: String = "Selection",
    val sortingEnabled: Boolean = true,
    val directDeleteConfirmEnabled: Boolean = true,
    val showExifOverlay: Boolean = false,
    val recentPathsEnabled: Boolean = true,
    val recentPathsCount: Int = 3,
    val recentPaths: List<RecentPath> = emptyList(),
    val seenFirstRunHints: Set<String> = emptySet(),
    val celebrationsEnabled: Boolean = true,
    val cloudInheritsPhone: Boolean = true,
    val proMode: Boolean = ProModePolicy.DEFAULT_PRO_MODE,
)

/**
 * Typed snapshot of all simple phone-mode settings, produced as a single flow
 * by the settings repository (replaces a fragile 12-way positional combine).
 */
data class PhoneSettings(
    val collectionAction: CollectionAction = CollectionAction.DEFAULT,
    val directDeleteConfirmEnabled: Boolean = true,
    val sortByOrientation: Boolean = false,
    val randomizeOrder: Boolean = false,
    val fileTypeFilter: FileTypeFilter = FileTypeFilter.DEFAULT,
    val leftSwipeAction: SwipeAction = SwipeAction.DEFAULT,
    val showExifOverlay: Boolean = false,
    val moveRelatedFiles: Boolean = false,
    val recentPathsEnabled: Boolean = true,
    val recentPathsCount: Int = 3,
    val recentPaths: List<RecentPath> = emptyList(),
    /** Keys of the one-time action explanations the user has already seen. */
    val seenFirstRunHints: Set<String> = emptySet(),
    val celebrationsEnabled: Boolean = true,
    val cloudInheritsPhone: Boolean = true,
    /** Pro mode on? Every other field is already the effective (gated) value. */
    val proMode: Boolean = ProModePolicy.DEFAULT_PRO_MODE,
    val phoneSourceSettings: SourceSettings = ProModePolicy.casualSourceSettings(SourceKind.PHONE_LIBRARY),
    val removableSourceSettings: SourceSettings = ProModePolicy.casualSourceSettings(SourceKind.REMOVABLE),
    val cameraMtpSourceSettings: SourceSettings = ProModePolicy.casualSourceSettings(SourceKind.CAMERA_MTP),
    val cloudSourceSettings: SourceSettings = ProModePolicy.casualSourceSettings(SourceKind.CLOUD_OTHER),
) {
    fun settingsFor(kind: SourceKind): SourceSettings = when (kind) {
        SourceKind.PHONE_LIBRARY -> phoneSourceSettings
        SourceKind.REMOVABLE -> removableSourceSettings
        SourceKind.CAMERA_MTP -> cameraMtpSourceSettings
        SourceKind.CLOUD_OTHER -> if (cloudInheritsPhone) phoneSourceSettings else cloudSourceSettings
    }
}

