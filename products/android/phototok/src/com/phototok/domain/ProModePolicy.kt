package com.phototok.domain

import com.photoselector.core.storage.SourceKind
import com.phototok.R
import com.phototok.data.model.SourceSettings

/**
 * Casual vs Pro, in one pure place (REQ-TOK-FEATURE.20 in `docs/products/phototok/REQUIREMENTS.md`).
 *
 * The model is **gate, don't rewrite**: stored values are never erased when Pro mode is off. A
 * reader asks [effective] (or one of the `effective…` helpers) and gets the Casual value while
 * Pro mode is off, the stored value while it is on. A setting the user cannot see therefore never
 * acts, and turning Pro on again brings every stored value back exactly.
 *
 * Pro-only settings: swipe-right verb, swipe-left verb, left-swipe folder, feed order, format
 * filter, link RAW + JPEG, Selection folder name, Selection location, RAW/JPEG subfolders
 * (sorting), recent-folders show/count, camera info (the EXIF overlay), cloud-inherits-phone.
 * Both modes: Confirm delete, Milestones, the actions and About.
 *
 * No Android dependencies apart from resource ids ([UiText]).
 */
object ProModePolicy {

    /** A fresh install starts in Casual mode. */
    const val DEFAULT_PRO_MODE = false

    const val CASUAL_SELECTION_FOLDER_NAME = PhotoFolders.SELECTION
    const val CASUAL_SORTING_ENABLED = false
    const val CASUAL_SHOW_EXIF_OVERLAY = false
    const val CASUAL_CLOUD_INHERITS_PHONE = true
    const val CASUAL_RECENT_PATHS_ENABLED = true
    const val CASUAL_RECENT_PATHS_COUNT = 3

    /** Recent-folders count written the first time Pro mode is ever switched on. */
    const val FIRST_PRO_RECENT_PATHS_COUNT = 5

    /** The external-storage provider and the phone's camera folder a Casual card import lands in. */
    const val CAMERA_IMPORT_AUTHORITY = "com.android.externalstorage.documents"
    const val CAMERA_IMPORT_DOCUMENT_ID = "primary:DCIM/Camera"

    /** SD cards and cameras are the sources whose Casual swipe right imports into the phone. */
    fun importsToPhoneCamera(kind: SourceKind): Boolean =
        kind == SourceKind.REMOVABLE || kind == SourceKind.CAMERA_MTP

    /**
     * The Casual value of every per-source setting. Phone and cloud keep the factory defaults;
     * SD card and camera copy into `DCIM/Camera/Jackpix` on the phone, flat (no RAW/JPEG
     * folders). Confirm delete is not Casual-specific, see [effective].
     */
    fun casualSourceSettings(kind: SourceKind): SourceSettings {
        val base = SourceSettings.defaultFor(kind).copy(
            sortingEnabled = CASUAL_SORTING_ENABLED,
            collectionUri = null,
            leftSwipeUri = null,
        )
        return if (importsToPhoneCamera(kind)) {
            base.copy(selectionSubfolder = PhotoFolders.CASUAL_IMPORT, importToPhoneCamera = true)
        } else {
            base
        }
    }

    /**
     * The settings that act: [stored] in Pro mode, the Casual values otherwise. The Confirm-delete
     * choice belongs to both modes and always comes from [stored].
     */
    fun effective(stored: SourceSettings, kind: SourceKind, proMode: Boolean): SourceSettings =
        if (proMode) {
            stored
        } else {
            casualSourceSettings(kind).copy(directDeleteConfirmEnabled = stored.directDeleteConfirmEnabled)
        }

    fun effectiveSelectionFolderName(stored: String, proMode: Boolean): String =
        if (proMode) stored else CASUAL_SELECTION_FOLDER_NAME

    fun effectiveSortingEnabled(stored: Boolean, proMode: Boolean): Boolean =
        if (proMode) stored else CASUAL_SORTING_ENABLED

    fun effectiveShowExifOverlay(stored: Boolean, proMode: Boolean): Boolean =
        if (proMode) stored else CASUAL_SHOW_EXIF_OVERLAY

    fun effectiveCloudInheritsPhone(stored: Boolean, proMode: Boolean): Boolean =
        if (proMode) stored else CASUAL_CLOUD_INHERITS_PHONE

    fun effectiveRecentPathsEnabled(stored: Boolean, proMode: Boolean): Boolean =
        if (proMode) stored else CASUAL_RECENT_PATHS_ENABLED

    fun effectiveRecentPathsCount(stored: Int, proMode: Boolean): Int =
        if (proMode) stored else CASUAL_RECENT_PATHS_COUNT

    // ── Transitions ───────────────────────────────────────────────────────

    /** A preference the repository writes, together with `pro_mode`, in one atomic edit. */
    sealed interface ProModeWrite {
        data class ShowExifOverlay(val enabled: Boolean) : ProModeWrite
        data class RecentPathsCount(val count: Int) : ProModeWrite

        /** Marks that the one-time Pro defaults were applied. */
        data object EverEnabled : ProModeWrite
    }

    /**
     * What switching Pro mode does: the new `pro_mode` value, the other preference writes (all
     * applied in one edit with it) and the snackbar message naming the visible change.
     */
    data class Transition(
        val proMode: Boolean,
        val writes: List<ProModeWrite>,
        val message: UiText?,
    )

    /**
     * The transition from [currentlyPro] to [targetPro]. [everEnabled] is `pro_mode_ever_enabled`.
     *
     * - Pro to Casual: nothing is rewritten, the Pro values are gated. "Pro settings paused".
     * - Casual to Pro, first time: camera info on and five recent folders. "Camera info on".
     * - Casual to Pro, later: the stored values apply again. "Pro settings restored".
     * - No change: no writes, no message.
     */
    fun transition(currentlyPro: Boolean, targetPro: Boolean, everEnabled: Boolean): Transition = when {
        currentlyPro == targetPro -> Transition(targetPro, emptyList(), null)
        !targetPro -> Transition(false, emptyList(), UiText.of(R.string.snackbar_pro_paused))
        !everEnabled -> Transition(
            proMode = true,
            writes = listOf(
                ProModeWrite.ShowExifOverlay(true),
                ProModeWrite.RecentPathsCount(FIRST_PRO_RECENT_PATHS_COUNT),
                ProModeWrite.EverEnabled,
            ),
            message = UiText.of(R.string.snackbar_pro_camera_info_on),
        )
        else -> Transition(true, emptyList(), UiText.of(R.string.snackbar_pro_restored))
    }

    // ── Migration decision ────────────────────────────────────────────────

    /**
     * What an existing install has stored for the Pro-only settings, read without the gate
     * (the values a Pro user would see).
     *
     * @property perKind the stored per-source settings of PHONE_LIBRARY, REMOVABLE, CAMERA_MTP and
     *   CLOUD_OTHER (the cloud entry counts only when it does not inherit the phone's)
     * @property hasCardOrCameraRecent the recent-folders list holds an SD card or camera folder
     */
    data class StoredProSettings(
        val perKind: Map<SourceKind, SourceSettings>,
        val cloudInheritsPhone: Boolean,
        val selectionFolderName: String,
        val sortingEnabled: Boolean,
        val showExifOverlay: Boolean,
        val recentPathsEnabled: Boolean,
        val recentPathsCount: Int,
        val hasCardOrCameraRecent: Boolean,
    )

    /**
     * Should an install being migrated to schema 3 start in Pro mode? Yes when any Pro-only
     * setting has a stored value that differs from its Casual value (values are compared, never
     * key presence), or when it has used an SD card or camera (Casual would move those copies
     * from `<card>/Selection` to the phone). Confirm delete and Milestones never count.
     */
    fun shouldStartInPro(stored: StoredProSettings): Boolean {
        if (stored.hasCardOrCameraRecent) return true
        if (stored.cloudInheritsPhone != CASUAL_CLOUD_INHERITS_PHONE) return true
        if (stored.selectionFolderName != CASUAL_SELECTION_FOLDER_NAME) return true
        if (stored.sortingEnabled != CASUAL_SORTING_ENABLED) return true
        if (stored.showExifOverlay != CASUAL_SHOW_EXIF_OVERLAY) return true
        if (stored.recentPathsEnabled != CASUAL_RECENT_PATHS_ENABLED) return true
        if (stored.recentPathsCount != CASUAL_RECENT_PATHS_COUNT) return true
        val kinds = buildList {
            add(SourceKind.PHONE_LIBRARY)
            add(SourceKind.REMOVABLE)
            add(SourceKind.CAMERA_MTP)
            if (!stored.cloudInheritsPhone) add(SourceKind.CLOUD_OTHER)
        }
        return kinds.any { kind ->
            val value = stored.perKind[kind] ?: return@any false
            differsFromCasual(value, casualSourceSettings(kind))
        }
    }

    /** The Pro-only fields of a per-source setting, compared by value. */
    private fun differsFromCasual(stored: SourceSettings, casual: SourceSettings): Boolean =
        stored.swipeRightVerb != casual.swipeRightVerb ||
            stored.swipeLeftVerb != casual.swipeLeftVerb ||
            stored.feedOrderMode != casual.feedOrderMode ||
            stored.fileTypeFilter != casual.fileTypeFilter ||
            stored.moveRelatedFiles != casual.moveRelatedFiles ||
            stored.collectionUri != casual.collectionUri ||
            stored.leftSwipeUri != casual.leftSwipeUri
}
