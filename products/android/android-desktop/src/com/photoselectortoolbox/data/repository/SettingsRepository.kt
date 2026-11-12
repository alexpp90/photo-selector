package com.photoselectortoolbox.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.photoselector.saf.FolderAccessManager
import com.photoselector.saf.FolderAccessPurpose
import com.photoselectortoolbox.domain.curation.DestinationMode
import com.photoselectortoolbox.domain.curation.FileIdentity
import com.photoselectortoolbox.domain.curation.PendingDeleteJournal
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.guidance.SelectorHint
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.session.SelectorSortOrder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The settings store, one per process. Internal so tests can write what an earlier version left
 * on a device (the upgrade test) and clear the process-wide singleton between test classes.
 */
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "photo_selector_settings",
    produceMigrations = { SETTINGS_MIGRATIONS },
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderAccessManager: FolderAccessManager,
) {

    constructor(context: Context) : this(context, FolderAccessManager(context))

    companion object {
        private val KEY_SELECTION_FOLDER_NAME = stringPreferencesKey("selection_folder_name")
        private val KEY_SORTING_ENABLED = booleanPreferencesKey("sorting_enabled")
        private val KEY_GROUPING_ENABLED = booleanPreferencesKey("grouping_enabled")
        private val KEY_GROUPING_LEVEL = stringPreferencesKey("grouping_level")
        private val KEY_SELECTOR_SORT_ORDER = stringPreferencesKey("selector_sort_order")
        private val KEY_LAST_FOLDER_URI = stringPreferencesKey("last_folder_uri")
        private val KEY_ANALYSIS_THREAD_COUNT = intPreferencesKey("analysis_thread_count")

        // Phone-mode settings
        private val KEY_PHONE_COLLECTION_ACTION = stringPreferencesKey("phone_collection_action")
        private val KEY_PHONE_COLLECTION_URI = stringPreferencesKey("phone_collection_uri")
        private val KEY_PHONE_DELETE_CONFIRM = booleanPreferencesKey("phone_delete_confirm")
        private val KEY_PHONE_SORT_BY_ORIENTATION = booleanPreferencesKey("phone_sort_by_orientation")
        private val KEY_PHONE_GESTURE_TUTORIAL_TS = longPreferencesKey("phone_gesture_tutorial_ts")
        private val KEY_PHONE_FORCE_MODE = stringPreferencesKey("phone_force_mode")
        private val KEY_PHONE_FILE_TYPE_FILTER = stringPreferencesKey("phone_file_type_filter")
        private val KEY_FULLSCREEN_BUTTONS_ENABLED = booleanPreferencesKey("fullscreen_buttons_enabled")
        private val KEY_FULLSCREEN_GESTURE_ACTION = stringPreferencesKey("fullscreen_gesture_action")

        // Expanded-layout (tablet/DeX) selector view preferences
        private val KEY_HAS_SEEN_NAV_HINT = booleanPreferencesKey("has_seen_nav_hint")
        private val KEY_OVERLAY_VALUES_VISIBLE = booleanPreferencesKey("overlay_values_visible")
        private val KEY_FILMSTRIP_VISIBLE = booleanPreferencesKey("filmstrip_visible")
        private val KEY_DETAILS_VISIBLE = booleanPreferencesKey("details_visible")
        private val KEY_SEEN_FULLSCREEN_HINT = booleanPreferencesKey("seen_fullscreen_gesture_hint")
        private val KEY_FULLSCREEN_INFO_PINNED = booleanPreferencesKey("fullscreen_info_pinned")

        /**
         * Which one-time hints the user has already dismissed.
         *
         * A set rather than one boolean per hint, so adding a hint does not add
         * a preference key and "Reset guidance" is a single removal instead of a
         * list that someone will forget to extend.
         */
        private val KEY_SEEN_HINTS = stringSetPreferencesKey("seen_first_run_hints")

        /**
         * Durable journal of deferred-delete URIs not yet committed to disk,
         * so a killed process can offer recovery on next startup (PRP WP-11 / U-05).
         */
        private val KEY_PENDING_DELETE_JOURNAL = stringSetPreferencesKey("pending_delete_journal")

        /**
         * Beside [KEY_PENDING_DELETE_JOURNAL]: the identity (size, last-modified) of each journalled
         * file, encoded by [PendingDeleteJournal.encode]. A journal URI without an entry here (one
         * an earlier version wrote) is unverifiable and never deleted by "Delete now".
         */
        private val KEY_PENDING_DELETE_IDENTITIES = stringSetPreferencesKey("pending_delete_identities")
        private val KEY_SECONDARY_SELECTION_FOLDER_NAME = stringPreferencesKey("secondary_selection_folder_name")
        private val KEY_CUSTOM_SELECTION_DESTINATION_URI = stringPreferencesKey("custom_selection_destination_uri")
        private val KEY_TV_DISPLAY_SCALE_ENABLED = booleanPreferencesKey("tv_display_scale_enabled")

        const val DEFAULT_SELECTION_FOLDER_NAME = "Selection"
        const val DEFAULT_SECONDARY_SELECTION_FOLDER_NAME = "Final"
        const val DEFAULT_SORTING_ENABLED = true
        const val DEFAULT_GROUPING_ENABLED = false
        const val DEFAULT_TV_DISPLAY_SCALE_ENABLED = false

        /**
         * Off for a new install (`[USER 2026-11-12]` decision #16): a rating does not change the
         * photo unless asked to. An earlier install keeps writing ([WriteRatingsIntoPhotosMigration]).
         */
        const val DEFAULT_WRITE_RATINGS_INTO_PHOTOS = false
        const val TV_DISPLAY_SCALE_FACTOR = 1.25f

        /**
         * The filmstrip starts hidden.
         *
         * The compare-and-cull loop is Previous/Next and the two neighbour
         * frames; the strip is random access to the rest of the shoot, which is
         * a second-order need. Starting it off means the screen a photographer
         * first sees is the three photographs and nothing else, and the toggle
         * in the view cluster turns it on for the sessions that want it — the
         * preference is persisted, so that decision is made once.
         */
        const val DEFAULT_FILMSTRIP_VISIBLE = false
        const val DEFAULT_FULLSCREEN_INFO_PINNED = false

        /** Scheme of the removed Google Drive source; see [lastFolderUri]. */
        const val RETIRED_DRIVE_SCHEME = "gdrive://"

        val DEFAULT_GROUPING_LEVEL = GroupingLevel.TIME_FILENAME

        /**
         * Newest first, on a fresh install and on upgrade alike: there was no
         * setting before, and the order the selector showed then (filename,
         * which for camera files is oldest first) is what was reported as wrong.
         */
        val DEFAULT_SELECTOR_SORT_ORDER: SelectorSortOrder = SelectorSortOrder.DEFAULT

        /** The destination mode [prefs] stand for: a stored custom folder is Custom. */
        internal fun destinationModeOf(prefs: Preferences): DestinationMode =
            DestinationMode.of(prefs[KEY_CUSTOM_SELECTION_DESTINATION_URI])

        /** The persisted sort order in [prefs]; absent or unknown is the default. */
        internal fun selectorSortOrderOf(prefs: Preferences): SelectorSortOrder =
            prefs[KEY_SELECTOR_SORT_ORDER]
                ?.let(SelectorSortOrder::fromStoredName)
                ?: DEFAULT_SELECTOR_SORT_ORDER
        val DEFAULT_ANALYSIS_THREAD_COUNT = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }

    /**
     * Whether a star rating is embedded in a JPEG (REQ-AND-RATING.01). RAW and TIFF ratings go to
     * an `.xmp` sidecar whatever this says, because a sidecar never changes the photo. Read by
     * the rating writer ([com.photoselectortoolbox.data.metadata.MetadataWriterImpl]).
     */
    val writeRatingsIntoPhotos: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[WriteRatingsIntoPhotosMigration.KEY] ?: DEFAULT_WRITE_RATINGS_INTO_PHOTOS
    }

    suspend fun setWriteRatingsIntoPhotos(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[WriteRatingsIntoPhotosMigration.KEY] = enabled }
    }

    val selectionFolderName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTION_FOLDER_NAME] ?: DEFAULT_SELECTION_FOLDER_NAME
    }

    val sortingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SORTING_ENABLED] ?: DEFAULT_SORTING_ENABLED
    }

    val groupingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_GROUPING_ENABLED] ?: DEFAULT_GROUPING_ENABLED
    }

    val groupingLevel: Flow<GroupingLevel> = context.dataStore.data.map { prefs ->
        val value = prefs[KEY_GROUPING_LEVEL]
        if (value != null) {
            try {
                GroupingLevel.valueOf(value)
            } catch (_: IllegalArgumentException) {
                DEFAULT_GROUPING_LEVEL
            }
        } else {
            DEFAULT_GROUPING_LEVEL
        }
    }

    /** The order the selector shows a folder in (`selector_sort_order`). */
    val selectorSortOrder: Flow<SelectorSortOrder> = context.dataStore.data.map { prefs -> selectorSortOrderOf(prefs) }

    /**
     * The folder to reopen on launch, or null.
     *
     * Sessions saved before the Google Drive source was removed persisted a
     * `gdrive://` URI here. Nothing can open one now, and handing it to the
     * selector would greet a returning photographer with "permission revoked or
     * directory deleted" on a folder they never lost. It is dropped on read
     * instead, so the app opens on the empty state and the next folder they pick
     * overwrites it.
     */
    val lastFolderUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_FOLDER_URI]?.takeUnless { it.startsWith(RETIRED_DRIVE_SCHEME) }
    }

    val analysisThreadCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_ANALYSIS_THREAD_COUNT] ?: DEFAULT_ANALYSIS_THREAD_COUNT
    }

    val fullscreenButtonsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_FULLSCREEN_BUTTONS_ENABLED] ?: true
    }

    /**
     * Which verb the emphasised filing control performs: copy, or move.
     *
     * The DataStore key is still `fullscreen_gesture_action` because it is on
     * users' devices, but the setting no longer has anything to do with a
     * gesture — double-tap files nothing now (see [FilingAction] and the
     * fullscreen viewer). It is surfaced as *Filing Action* in Settings, and it
     * decides which of Copy and Move is the primary control in the selector as
     * well as in fullscreen.
     */
    val filingAction: Flow<FilingAction> = context.dataStore.data.map { prefs ->
        FilingAction.fromStored(prefs[KEY_FULLSCREEN_GESTURE_ACTION])
    }

    suspend fun setSelectionFolderName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SELECTION_FOLDER_NAME] = name
        }
    }

    suspend fun setSortingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SORTING_ENABLED] = enabled
        }
    }

    suspend fun setGroupingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GROUPING_ENABLED] = enabled
        }
    }

    suspend fun setGroupingLevel(level: GroupingLevel) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GROUPING_LEVEL] = level.name
        }
    }

    suspend fun setSelectorSortOrder(order: SelectorSortOrder) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SELECTOR_SORT_ORDER] = order.name
        }
    }

    suspend fun setLastFolderUri(uri: String?) {
        var previousUri: String? = null
        var referencedAfter: Map<FolderAccessPurpose, Set<String>> = emptyMap()
        context.dataStore.edit { prefs ->
            previousUri = prefs[KEY_LAST_FOLDER_URI]?.takeUnless { it.startsWith(RETIRED_DRIVE_SCHEME) }
            if (uri != null) {
                prefs[KEY_LAST_FOLDER_URI] = uri
            } else {
                prefs.remove(KEY_LAST_FOLDER_URI)
            }
            referencedAfter = referencedUrisByPurpose(prefs)
        }
        folderAccessManager.syncReferences(referencedAfter)
        if (uri != null && !uri.startsWith(RETIRED_DRIVE_SCHEME)) {
            folderAccessManager.takeGrant(Uri.parse(uri), FolderAccessPurpose.SOURCE)
        }
        val old = previousUri
        if (old != null && old != uri) {
            folderAccessManager.releaseIfUnreferenced(old, referencedAfter.values.flatten().toSet())
        }
    }

    suspend fun setAnalysisThreadCount(count: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ANALYSIS_THREAD_COUNT] = count.coerceIn(1, 4)
        }
    }

    suspend fun setFullscreenButtonsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FULLSCREEN_BUTTONS_ENABLED] = enabled
        }
    }

    suspend fun setFilingAction(action: FilingAction) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FULLSCREEN_GESTURE_ACTION] = action.storedValue
        }
    }

    // ── Expanded (tablet/DeX) selector layout ────────────────────────────

    /**
     * Whether the user has already seen the on-image previous/next navigation
     * arrows once. After the first viewing they are suppressed to keep the
     * images unobstructed. Defaults to false (arrows shown on first run).
     */
    val hasSeenNavHint: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_HAS_SEEN_NAV_HINT] ?: false
    }

    suspend fun setHasSeenNavHint(seen: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HAS_SEEN_NAV_HINT] = seen
        }
    }

    /**
     * Whether the vertical filmstrip beside the control block is shown.
     *
     * Persisted because a photographer who has decided either way should not
     * have to decide again next launch. It defaults off — see
     * [DEFAULT_FILMSTRIP_VISIBLE]. Where it sits it costs the frames no height
     * (`FrameGeometry`), so this is a preference about clutter, not a price.
     */
    val filmstripVisible: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_FILMSTRIP_VISIBLE] ?: DEFAULT_FILMSTRIP_VISIBLE
    }

    /** Whether the readout block beside the current frame is shown. */
    val detailsVisible: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DETAILS_VISIBLE] ?: true
    }

    /**
     * Whether Previous and Next carry their value overlay.
     *
     * The overlay covers roughly a quarter of each neighbour's width, which is
     * a real cost on a screen whose entire job is judging photographs. Default
     * on, because a photographer who cannot see the numbers cannot cull; but
     * one control turns them all off for a clean look, and that decision
     * persists so it does not have to be made every launch.
     */
    val overlayValuesVisible: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_OVERLAY_VALUES_VISIBLE] ?: true
    }

    suspend fun setOverlayValuesVisible(visible: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_VALUES_VISIBLE] = visible
        }
    }

    suspend fun setFilmstripVisible(visible: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FILMSTRIP_VISIBLE] = visible
        }
    }

    suspend fun setDetailsVisible(visible: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DETAILS_VISIBLE] = visible
        }
    }

    /**
     * Whether the fullscreen gesture hint card has been dismissed.
     *
     * Fullscreen has no visible affordance for pinch, double-tap or swipe, so
     * the card is the only place those are taught — but once taught it is pure
     * obstruction over the frame being inspected, so it shows exactly once.
     */
    val hasSeenFullscreenGestureHint: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SEEN_FULLSCREEN_HINT] ?: false
    }

    suspend fun setHasSeenFullscreenGestureHint(seen: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SEEN_FULLSCREEN_HINT] = seen
        }
    }

    /**
     * Whether the fullscreen top bar (file name, position, EXIF) stays up instead of
     * fading out after a few seconds. Toggled with `I` in the fullscreen viewer.
     *
     * Default off ([DEFAULT_FULLSCREEN_INFO_PINNED]): a bar that never leaves sits on
     * the sky of the frame being inspected. A photographer who wants the numbers
     * beside every frame pins it once and the decision survives a relaunch.
     */
    val fullscreenInfoPinned: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_FULLSCREEN_INFO_PINNED] ?: DEFAULT_FULLSCREEN_INFO_PINNED
    }

    suspend fun setFullscreenInfoPinned(pinned: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FULLSCREEN_INFO_PINNED] = pinned
        }
    }

    // ── First-run guidance ───────────────────────────────────────────────

    /**
     * The one-time hints already dismissed.
     *
     * Unknown stored keys are dropped by [SelectorHint.decode] rather than
     * failing, so a build that no longer knows a hint simply ignores it.
     */
    val seenHints: Flow<Set<SelectorHint>> = context.dataStore.data.map { prefs ->
        SelectorHint.decode(prefs[KEY_SEEN_HINTS])
    }

    /** Record that [hint] has now been shown. Idempotent. */
    suspend fun markHintSeen(hint: SelectorHint) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SEEN_HINTS] = prefs[KEY_SEEN_HINTS].orEmpty() + hint.key
        }
    }

    /**
     * Show every explanation again.
     *
     * Clears the hint set **and** the two older one-shot flags, which predate it
     * and are stored separately. They are cleared here rather than migrated
     * because both are already on users' devices, and "Reset guidance" that
     * leaves two hints permanently dismissed is not a reset.
     */
    suspend fun resetGuidance() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_SEEN_HINTS)
            prefs.remove(KEY_HAS_SEEN_NAV_HINT)
            prefs.remove(KEY_SEEN_FULLSCREEN_HINT)
        }
    }

    // ── Phone-mode settings ──────────────────────────────────────────────

    /** "copy" (default) or "move" */
    val phoneCollectionAction: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PHONE_COLLECTION_ACTION] ?: "copy"
    }

    /**
     * Legacy: the folder Settings stored before the custom Selection destination existed.
     * Copy and Move never read it — [customSelectionDestinationUri] is the setting — but it
     * still holds its grant ([referencedUrisByPurpose]) and is kept in step by
     * [setCustomSelectionDestinationUri].
     */
    val phoneCollectionUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_PHONE_COLLECTION_URI]
    }

    /** Whether to show delete confirmation dialog (default true). */
    val phoneDeleteConfirmEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PHONE_DELETE_CONFIRM] ?: true
    }

    /** Sort images horizontal-first, then vertical (default true). */
    val phoneSortByOrientation: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PHONE_SORT_BY_ORIENTATION] ?: true
    }

    /** Timestamp (millis) when gesture tutorial was last shown. 0 = never. */
    val phoneGestureTutorialTs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_PHONE_GESTURE_TUTORIAL_TS] ?: 0L
    }

    /**
     * Force mode for large-screen devices: "phone", "desktop", or null (auto).
     * On compact devices this is ignored.
     */
    val phoneForcedMode: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_PHONE_FORCE_MODE]
    }

    suspend fun setPhoneCollectionAction(action: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PHONE_COLLECTION_ACTION] = action
        }
    }

    suspend fun setPhoneCollectionUri(uri: String?) {
        var previousUri: String? = null
        var referencedAfter: Map<FolderAccessPurpose, Set<String>> = emptyMap()
        context.dataStore.edit { prefs ->
            previousUri = prefs[KEY_PHONE_COLLECTION_URI]
            if (uri != null) prefs[KEY_PHONE_COLLECTION_URI] = uri
            else prefs.remove(KEY_PHONE_COLLECTION_URI)
            referencedAfter = referencedUrisByPurpose(prefs)
        }
        folderAccessManager.syncReferences(referencedAfter)
        if (uri != null) {
            folderAccessManager.takeGrant(Uri.parse(uri), FolderAccessPurpose.SELECTION_TARGET)
        }
        val old = previousUri
        if (old != null && old != uri) {
            folderAccessManager.releaseIfUnreferenced(old, referencedAfter.values.flatten().toSet())
        }
    }

    /**
     * Release the persisted grant of [folderUri] now if nothing references it any more — called
     * after the last journal entry of a folder that was left was committed or recovered.
     */
    suspend fun releaseFolderGrantIfUnreferenced(folderUri: String) {
        val prefs = context.dataStore.data.first()
        val referenced = referencedUrisByPurpose(prefs)
        folderAccessManager.syncReferences(referenced)
        folderAccessManager.releaseIfUnreferenced(folderUri, referenced.values.flatten().toSet())
    }

    /** Keep (or drop) the grants of the folders the in-memory undo records still operate on. */
    fun pinUndoHistoryFolders(folderUris: Set<String>) {
        folderAccessManager.pin(FolderAccessPurpose.UNDO_HISTORY, folderUris)
    }

    suspend fun reconcilePersistedGrants(): Set<String> {
        val prefs = context.dataStore.data.first()
        return folderAccessManager.reconcilePersistedGrants(referencedUrisByPurpose(prefs))
    }

    internal fun referencedUrisByPurpose(prefs: Preferences): Map<FolderAccessPurpose, Set<String>> {
        val source = prefs[KEY_LAST_FOLDER_URI]
            ?.takeUnless { it.startsWith(RETIRED_DRIVE_SCHEME) }
            ?.let(::setOf)
            .orEmpty()
        // The custom destination Copy and Move read, and the legacy key Settings wrote before
        // it existed (left in place, so a grant taken then is not dropped by an upgrade).
        val selection = listOfNotNull(prefs[KEY_CUSTOM_SELECTION_DESTINATION_URI], prefs[KEY_PHONE_COLLECTION_URI])
            .filter { it.isNotBlank() }
            .toSet()
        // Uncommitted deletions still need the grant of the folder they live in (finding D-10).
        val journal = prefs[KEY_PENDING_DELETE_JOURNAL].orEmpty()
        return mapOf(
            FolderAccessPurpose.SOURCE to source,
            FolderAccessPurpose.SELECTION_TARGET to selection,
            FolderAccessPurpose.PENDING_DELETE_JOURNAL to journal,
        )
    }

    suspend fun setPhoneDeleteConfirmEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PHONE_DELETE_CONFIRM] = enabled
        }
    }

    suspend fun setPhoneSortByOrientation(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PHONE_SORT_BY_ORIENTATION] = enabled
        }
    }

    suspend fun setPhoneGestureTutorialTs(ts: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PHONE_GESTURE_TUTORIAL_TS] = ts
        }
    }

    suspend fun setPhoneForcedMode(mode: String?) {
        context.dataStore.edit { prefs ->
            if (mode != null) prefs[KEY_PHONE_FORCE_MODE] = mode
            else prefs.remove(KEY_PHONE_FORCE_MODE)
        }
    }

    // ── File type filter ─────────────────────────────────────────────────

    /** "all" (default), "raw", or "jpg" */
    val phoneFileTypeFilter: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PHONE_FILE_TYPE_FILTER] ?: "all"
    }

    suspend fun setPhoneFileTypeFilter(filter: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PHONE_FILE_TYPE_FILTER] = filter
        }
    }

    // ── Per-folder last position ─────────────────────────────────────────

    /**
     * The photo on screen when [folderUri] was last culled, by URI — an index shifts after every
     * move or delete and under another sort order (backlog 2026-11-11). Written debounced by
     * `SelectorViewModel` once the photographer moves; read when the folder is reopened.
     */
    suspend fun setFolderLastPhoto(folderUri: String, photoUri: String) {
        val key = stringPreferencesKey("folder_photo_${folderUri.hashCode()}")
        context.dataStore.edit { prefs -> prefs[key] = photoUri }
    }

    /** The photo [setFolderLastPhoto] stored for [folderUri], or null. */
    suspend fun getFolderLastPhoto(folderUri: String): String? {
        val key = stringPreferencesKey("folder_photo_${folderUri.hashCode()}")
        return context.dataStore.data.map { prefs -> prefs[key] }.first()
    }

    /**
     * The index v0.3.0 / v0.4.0 stored per folder (`folder_pos_<hash>`); nothing writes it any
     * more. Read only as the resume point of a folder that has no [getFolderLastPhoto] yet, so an
     * upgrade keeps that place. Returns 0 if none stored.
     */
    suspend fun getFolderLastPosition(folderUri: String): Int {
        val key = intPreferencesKey("folder_pos_${folderUri.hashCode()}")
        return context.dataStore.data.map { prefs -> prefs[key] ?: 0 }.first()
    }

    // ── Durable pending-delete journal (PRP WP-11 / U-05) ────────────────

    /**
     * URIs of pending deletions not yet committed to disk.
     *
     * Recorded when a deletion is deferred into the undo history and removed
     * when it is either reverted or committed, so a process killed mid-session
     * can surface an honest recovery prompt on next launch instead of silently
     * resurrecting or losing the pending operations.
     */
    val pendingDeleteJournal: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_PENDING_DELETE_JOURNAL] ?: emptySet()
    }

    /**
     * The identity recorded for each journalled URI when it was deleted (finding 2026-11-05).
     * A URI of [pendingDeleteJournal] missing here has none and is unverifiable.
     */
    val pendingDeleteIdentities: Flow<Map<String, FileIdentity>> = context.dataStore.data.map { prefs ->
        PendingDeleteJournal.identities(prefs[KEY_PENDING_DELETE_IDENTITIES].orEmpty())
    }

    suspend fun setPendingDeleteJournal(uris: Set<String>) {
        context.dataStore.edit { prefs ->
            if (uris.isEmpty()) {
                prefs.remove(KEY_PENDING_DELETE_JOURNAL)
            } else {
                prefs[KEY_PENDING_DELETE_JOURNAL] = uris
            }
            retainIdentities(prefs, uris)
        }
    }

    /**
     * Journal [uris] as deleted but not yet committed, each with the [identities] it had when it
     * was deleted. A URI with no identity is journalled anyway (the grant and the recovery prompt
     * still need it) and is unverifiable on recovery.
     */
    suspend fun addPendingDeletes(
        uris: Collection<String>,
        identities: Map<String, FileIdentity> = emptyMap(),
    ) {
        if (uris.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PENDING_DELETE_JOURNAL] ?: emptySet()
            prefs[KEY_PENDING_DELETE_JOURNAL] = current + uris
            // A URI journalled again replaces what was recorded for it, an identity included.
            val added = uris.toSet()
            val recorded = PendingDeleteJournal.identities(prefs[KEY_PENDING_DELETE_IDENTITIES].orEmpty()) -
                added + identities.filterKeys { it in added }
            putIdentities(prefs, recorded)
        }
    }

    suspend fun removePendingDeletes(uris: Collection<String>) {
        if (uris.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PENDING_DELETE_JOURNAL] ?: emptySet()
            val updated = current - uris.toSet()
            if (updated.isEmpty()) {
                prefs.remove(KEY_PENDING_DELETE_JOURNAL)
            } else {
                prefs[KEY_PENDING_DELETE_JOURNAL] = updated
            }
            retainIdentities(prefs, updated)
        }
    }

    /** Store [identities] as the whole identity half of the journal. */
    private fun putIdentities(prefs: MutablePreferences, identities: Map<String, FileIdentity>) {
        val entries = identities.map { (uri, identity) -> PendingDeleteJournal.encode(uri, identity) }.toSet()
        if (entries.isEmpty()) prefs.remove(KEY_PENDING_DELETE_IDENTITIES)
        else prefs[KEY_PENDING_DELETE_IDENTITIES] = entries
    }

    /** Drop the identities of every URI no longer in the journal ([journal]). */
    private fun retainIdentities(prefs: MutablePreferences, journal: Set<String>) {
        val stored = PendingDeleteJournal.identities(prefs[KEY_PENDING_DELETE_IDENTITIES].orEmpty())
        putIdentities(prefs, stored.filterKeys { it in journal })
    }

    // ── Multi-pass culling, portable culling station, and TV display scale (U-32, U-33, U-36) ──

    val secondarySelectionFolderName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SECONDARY_SELECTION_FOLDER_NAME] ?: DEFAULT_SECONDARY_SELECTION_FOLDER_NAME
    }

    suspend fun setSecondarySelectionFolderName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SECONDARY_SELECTION_FOLDER_NAME] = name
        }
    }

    /**
     * The custom Selection destination (`custom_selection_destination_uri`), or null for
     * the working folder. This key is what Copy and Move read (`SelectionDestinationPlan`).
     */
    val customSelectionDestinationUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_CUSTOM_SELECTION_DESTINATION_URI]
    }

    /**
     * Working folder or Custom, read from the stored custom destination: there is no mode
     * key, so an upgrade with a custom folder reads as Custom and one without as Working
     * folder — the place files already went ([destinationModeOf]).
     */
    val destinationMode: Flow<DestinationMode> = context.dataStore.data.map { prefs -> destinationModeOf(prefs) }

    /**
     * Set (a folder from the picker) or clear (back to the working folder) the custom
     * Selection destination, with its persisted grant.
     *
     * One DataStore edit writes `custom_selection_destination_uri` and mirrors it into the
     * legacy `phone_collection_uri` (the key Settings wrote before the custom destination
     * existed; kept readable and in step for at least one release). The new folder's grant
     * is taken through [FolderAccessManager] as [FolderAccessPurpose.SELECTION_TARGET];
     * a previous folder nothing references any more — source, journal, undo pins — is
     * released at once.
     */
    suspend fun setCustomSelectionDestinationUri(uri: String?) {
        val newUri = uri?.trim()?.takeIf { it.isNotEmpty() }
        val previous = LinkedHashSet<String>()
        var referencedAfter: Map<FolderAccessPurpose, Set<String>> = emptyMap()
        context.dataStore.edit { prefs ->
            prefs[KEY_CUSTOM_SELECTION_DESTINATION_URI]?.let(previous::add)
            prefs[KEY_PHONE_COLLECTION_URI]?.let(previous::add)
            if (newUri != null) {
                prefs[KEY_CUSTOM_SELECTION_DESTINATION_URI] = newUri
                prefs[KEY_PHONE_COLLECTION_URI] = newUri
            } else {
                prefs.remove(KEY_CUSTOM_SELECTION_DESTINATION_URI)
                prefs.remove(KEY_PHONE_COLLECTION_URI)
            }
            referencedAfter = referencedUrisByPurpose(prefs)
        }
        folderAccessManager.syncReferences(referencedAfter)
        if (newUri != null) {
            folderAccessManager.takeGrant(Uri.parse(newUri), FolderAccessPurpose.SELECTION_TARGET)
        }
        val stillReferenced = referencedAfter.values.flatten().toSet()
        for (old in previous) {
            if (old != newUri) folderAccessManager.releaseIfUnreferenced(old, stillReferenced)
        }
    }

    val tvDisplayScaleEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_TV_DISPLAY_SCALE_ENABLED] ?: DEFAULT_TV_DISPLAY_SCALE_ENABLED
    }

    suspend fun setTvDisplayScaleEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TV_DISPLAY_SCALE_ENABLED] = enabled
        }
    }
}
