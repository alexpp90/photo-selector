package com.phototok.data.repository

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
import com.photoselector.core.storage.SourceKind
import com.photoselector.saf.FolderAccessManager
import com.photoselector.saf.FolderAccessPurpose
import com.phototok.data.model.AppSettings
import com.phototok.data.model.PhoneSettings
import com.phototok.data.model.RecentPath
import com.phototok.data.model.SourceSettings
import com.phototok.domain.CollectionAction
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.FirstRunHint
import com.phototok.domain.FolderScanInfo
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "phototok_settings"
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderAccessManager: FolderAccessManager,
) {

    constructor(context: Context) : this(context, FolderAccessManager(context))

    companion object {
        private const val TAG = "SettingsRepository"
        const val CURRENT_SCHEMA_VERSION = 2

        internal val KEY_SETTINGS_SCHEMA_VERSION = intPreferencesKey("settings_schema_version")
        private val KEY_SELECTION_FOLDER_NAME = stringPreferencesKey("selection_folder_name")
        private val KEY_SORTING_ENABLED = booleanPreferencesKey("sorting_enabled")
        private val KEY_LAST_FOLDER_URI = stringPreferencesKey("last_folder_uri")

        private val KEY_COLLECTION_ACTION = stringPreferencesKey("collection_action")
        private val KEY_COLLECTION_URI = stringPreferencesKey("collection_uri")
        private val KEY_LEFT_SWIPE_ACTION = stringPreferencesKey("left_swipe_action")
        private val KEY_LEFT_SWIPE_URI = stringPreferencesKey("left_swipe_uri")
        private val KEY_DIRECT_DELETE_CONFIRM = booleanPreferencesKey("direct_delete_confirm")
        private val KEY_SORT_BY_ORIENTATION = booleanPreferencesKey("sort_by_orientation")
        private val KEY_RANDOMIZE_ORDER = booleanPreferencesKey("randomize_order")
        private val KEY_GESTURE_TUTORIAL_TS = longPreferencesKey("gesture_tutorial_ts")
        private val KEY_FILE_TYPE_FILTER = stringPreferencesKey("file_type_filter")
        private val KEY_SHOW_EXIF_OVERLAY = booleanPreferencesKey("show_exif_overlay")
        private val KEY_MOVE_RELATED_FILES = booleanPreferencesKey("move_related_files")
        private val KEY_RECENT_PATHS_ENABLED = booleanPreferencesKey("recent_paths_enabled")
        private val KEY_RECENT_PATHS_COUNT = intPreferencesKey("recent_paths_count")
        private val KEY_RECENT_PATHS = stringPreferencesKey("recent_paths")
        private val KEY_SEEN_FIRST_RUN_HINTS = stringSetPreferencesKey("seen_first_run_hints")
        private val KEY_LAST_APP_USED_TS = longPreferencesKey("last_app_used_ts")
        private val KEY_CELEBRATIONS_ENABLED = booleanPreferencesKey("celebrations_enabled")
        private val KEY_CLOUD_INHERITS_PHONE = booleanPreferencesKey("cloud_inherits_phone")
        private val KEY_PERSISTED_PENDING_DELETE_URIS = stringSetPreferencesKey("persisted_pending_delete_uris")

        const val DEFAULT_SELECTION_FOLDER_NAME = "Selection"
        const val DEFAULT_SORTING_ENABLED = false
        const val DEFAULT_RECENT_PATHS_COUNT = 3
        private const val MAX_STORED_RECENT_PATHS = RecentPathCodec.MAX_STORED

        private fun SourceKind.keyPrefix(): String = when (this) {
            SourceKind.PHONE_LIBRARY -> "phone"
            SourceKind.REMOVABLE -> "removable"
            SourceKind.CAMERA_MTP -> "camera_mtp"
            SourceKind.CLOUD_OTHER -> "cloud"
        }

        internal fun swipeRightVerbKey(kind: SourceKind) =
            stringPreferencesKey("source_${kind.keyPrefix()}_swipe_right_verb")

        internal fun swipeLeftVerbKey(kind: SourceKind) =
            stringPreferencesKey("source_${kind.keyPrefix()}_swipe_left_verb")

        internal fun feedOrderKey(kind: SourceKind) =
            stringPreferencesKey("source_${kind.keyPrefix()}_feed_order")

        internal fun fileTypeFilterKey(kind: SourceKind) =
            stringPreferencesKey("source_${kind.keyPrefix()}_file_type_filter")

        internal fun moveRelatedFilesKey(kind: SourceKind) =
            booleanPreferencesKey("source_${kind.keyPrefix()}_move_related_files")

        internal fun collectionUriKey(kind: SourceKind) =
            stringPreferencesKey("source_${kind.keyPrefix()}_collection_uri")

        internal fun leftSwipeUriKey(kind: SourceKind) =
            stringPreferencesKey("source_${kind.keyPrefix()}_left_swipe_uri")

        internal fun sortingEnabledKey(kind: SourceKind) =
            booleanPreferencesKey("source_${kind.keyPrefix()}_sorting_enabled")

        internal fun directDeleteConfirmKey(kind: SourceKind) =
            booleanPreferencesKey("source_${kind.keyPrefix()}_direct_delete_confirm")

        /**
         * Migrate v1 flat keys into the `PHONE_LIBRARY` slot so existing users see zero
         * behaviour change on upgrade, seed `REMOVABLE` and `CAMERA_MTP` with their table
         * defaults, and record `settings_schema_version = 2` so the migration runs once.
         */
        internal fun migrateToV2(prefs: MutablePreferences) {
            val currentVersion = prefs[KEY_SETTINGS_SCHEMA_VERSION] ?: 0
            if (currentVersion >= CURRENT_SCHEMA_VERSION) return

            val phoneDefault = SourceSettings.defaultFor(SourceKind.PHONE_LIBRARY)
            val v1CollectionAction = prefs[KEY_COLLECTION_ACTION]
            val v1LeftSwipeAction = prefs[KEY_LEFT_SWIPE_ACTION]
            val v1FileTypeFilter = prefs[KEY_FILE_TYPE_FILTER]
            val v1SortByOrientation = prefs[KEY_SORT_BY_ORIENTATION]
            val v1RandomizeOrder = prefs[KEY_RANDOMIZE_ORDER]
            val v1MoveRelated = prefs[KEY_MOVE_RELATED_FILES]
            val v1CollectionUri = prefs[KEY_COLLECTION_URI]
            val v1LeftSwipeUri = prefs[KEY_LEFT_SWIPE_URI]

            val phoneRightVerb = if (v1CollectionAction != null) {
                CollectionAction.fromKey(v1CollectionAction).toSwipeRightVerb()
            } else {
                phoneDefault.swipeRightVerb
            }
            val phoneLeftVerb = if (v1LeftSwipeAction != null) {
                SwipeAction.fromKey(v1LeftSwipeAction)
            } else {
                phoneDefault.swipeLeftVerb
            }
            val phoneFeedOrder = if (v1SortByOrientation != null || v1RandomizeOrder != null) {
                FeedOrderMode.fromLegacy(
                    randomize = v1RandomizeOrder ?: false,
                    sortByOrientation = v1SortByOrientation ?: false,
                )
            } else {
                phoneDefault.feedOrderMode
            }
            val phoneFilter = if (v1FileTypeFilter != null) {
                FileTypeFilter.fromKey(v1FileTypeFilter, phoneDefault.fileTypeFilter)
            } else {
                phoneDefault.fileTypeFilter
            }
            val phoneMoveRelated = v1MoveRelated ?: phoneDefault.moveRelatedFiles

            if (prefs[swipeRightVerbKey(SourceKind.PHONE_LIBRARY)] == null) {
                prefs[swipeRightVerbKey(SourceKind.PHONE_LIBRARY)] = phoneRightVerb.key
            }
            if (prefs[swipeLeftVerbKey(SourceKind.PHONE_LIBRARY)] == null) {
                prefs[swipeLeftVerbKey(SourceKind.PHONE_LIBRARY)] = phoneLeftVerb.key
            }
            if (prefs[feedOrderKey(SourceKind.PHONE_LIBRARY)] == null) {
                prefs[feedOrderKey(SourceKind.PHONE_LIBRARY)] = phoneFeedOrder.key
            }
            if (prefs[fileTypeFilterKey(SourceKind.PHONE_LIBRARY)] == null) {
                prefs[fileTypeFilterKey(SourceKind.PHONE_LIBRARY)] = phoneFilter.key
            }
            if (prefs[moveRelatedFilesKey(SourceKind.PHONE_LIBRARY)] == null) {
                prefs[moveRelatedFilesKey(SourceKind.PHONE_LIBRARY)] = phoneMoveRelated
            }
            if (v1CollectionUri != null && prefs[collectionUriKey(SourceKind.PHONE_LIBRARY)] == null) {
                prefs[collectionUriKey(SourceKind.PHONE_LIBRARY)] = v1CollectionUri
            }
            if (v1LeftSwipeUri != null && prefs[leftSwipeUriKey(SourceKind.PHONE_LIBRARY)] == null) {
                prefs[leftSwipeUriKey(SourceKind.PHONE_LIBRARY)] = v1LeftSwipeUri
            }

            listOf(SourceKind.REMOVABLE, SourceKind.CAMERA_MTP).forEach { kind ->
                val def = SourceSettings.defaultFor(kind)
                if (prefs[swipeRightVerbKey(kind)] == null) {
                    prefs[swipeRightVerbKey(kind)] = def.swipeRightVerb.key
                }
                if (prefs[swipeLeftVerbKey(kind)] == null) {
                    prefs[swipeLeftVerbKey(kind)] = def.swipeLeftVerb.key
                }
                if (prefs[feedOrderKey(kind)] == null) {
                    prefs[feedOrderKey(kind)] = def.feedOrderMode.key
                }
                if (prefs[fileTypeFilterKey(kind)] == null) {
                    prefs[fileTypeFilterKey(kind)] = def.fileTypeFilter.key
                }
                if (prefs[moveRelatedFilesKey(kind)] == null) {
                    prefs[moveRelatedFilesKey(kind)] = def.moveRelatedFiles
                }
            }

            prefs[KEY_SETTINGS_SCHEMA_VERSION] = CURRENT_SCHEMA_VERSION
        }

        internal fun sourceSettingsOf(prefs: Preferences, kind: SourceKind): SourceSettings {
            val effectiveKind = if (
                kind == SourceKind.CLOUD_OTHER &&
                (prefs[KEY_CLOUD_INHERITS_PHONE] ?: true)
            ) {
                SourceKind.PHONE_LIBRARY
            } else {
                kind
            }
            val def = SourceSettings.defaultFor(effectiveKind)
            return SourceSettings(
                swipeRightVerb = SwipeRightVerb.fromKey(
                    prefs[swipeRightVerbKey(effectiveKind)],
                    def.swipeRightVerb,
                ),
                swipeLeftVerb = prefs[swipeLeftVerbKey(effectiveKind)]
                    ?.let { SwipeAction.fromKey(it) }
                    ?: def.swipeLeftVerb,
                feedOrderMode = FeedOrderMode.fromKey(
                    prefs[feedOrderKey(effectiveKind)],
                    def.feedOrderMode,
                ),
                fileTypeFilter = FileTypeFilter.fromKey(
                    prefs[fileTypeFilterKey(effectiveKind)],
                    def.fileTypeFilter,
                ),
                moveRelatedFiles = prefs[moveRelatedFilesKey(effectiveKind)] ?: def.moveRelatedFiles,
                sortingEnabled = prefs[sortingEnabledKey(effectiveKind)] ?: def.sortingEnabled,
                directDeleteConfirmEnabled = prefs[directDeleteConfirmKey(effectiveKind)]
                    ?: prefs[KEY_DIRECT_DELETE_CONFIRM]
                    ?: def.directDeleteConfirmEnabled,
                collectionUri = prefs[collectionUriKey(effectiveKind)],
                leftSwipeUri = prefs[leftSwipeUriKey(effectiveKind)],
            )
        }

        internal fun appSettingsOf(prefs: Preferences): AppSettings = AppSettings(
            selectionFolderName = prefs[KEY_SELECTION_FOLDER_NAME] ?: DEFAULT_SELECTION_FOLDER_NAME,
            sortingEnabled = prefs[KEY_SORTING_ENABLED] ?: DEFAULT_SORTING_ENABLED,
            directDeleteConfirmEnabled = prefs[KEY_DIRECT_DELETE_CONFIRM] ?: true,
            showExifOverlay = prefs[KEY_SHOW_EXIF_OVERLAY] ?: false,
            recentPathsEnabled = prefs[KEY_RECENT_PATHS_ENABLED] ?: true,
            recentPathsCount = prefs[KEY_RECENT_PATHS_COUNT] ?: DEFAULT_RECENT_PATHS_COUNT,
            recentPaths = RecentPathCodec.decode(prefs[KEY_RECENT_PATHS]),
            seenFirstRunHints = prefs[KEY_SEEN_FIRST_RUN_HINTS] ?: emptySet(),
            celebrationsEnabled = prefs[KEY_CELEBRATIONS_ENABLED] ?: true,
            cloudInheritsPhone = prefs[KEY_CLOUD_INHERITS_PHONE] ?: true,
        )

        /**
         * Map raw preferences to the typed settings snapshot. Companion-level and
         * internal so the mapping is unit-testable without a DataStore instance.
         */
        internal fun phoneSettingsOf(prefs: Preferences): PhoneSettings = PhoneSettings(
            collectionAction = CollectionAction.fromKey(prefs[KEY_COLLECTION_ACTION]),
            directDeleteConfirmEnabled = prefs[KEY_DIRECT_DELETE_CONFIRM] ?: true,
            sortByOrientation = prefs[KEY_SORT_BY_ORIENTATION] ?: false,
            randomizeOrder = prefs[KEY_RANDOMIZE_ORDER] ?: false,
            fileTypeFilter = FileTypeFilter.fromKey(prefs[KEY_FILE_TYPE_FILTER]),
            leftSwipeAction = SwipeAction.fromKey(prefs[KEY_LEFT_SWIPE_ACTION]),
            showExifOverlay = prefs[KEY_SHOW_EXIF_OVERLAY] ?: false,
            moveRelatedFiles = prefs[KEY_MOVE_RELATED_FILES] ?: false,
            recentPathsEnabled = prefs[KEY_RECENT_PATHS_ENABLED] ?: true,
            recentPathsCount = prefs[KEY_RECENT_PATHS_COUNT] ?: DEFAULT_RECENT_PATHS_COUNT,
            recentPaths = RecentPathCodec.decode(prefs[KEY_RECENT_PATHS]),
            seenFirstRunHints = prefs[KEY_SEEN_FIRST_RUN_HINTS] ?: emptySet(),
            celebrationsEnabled = prefs[KEY_CELEBRATIONS_ENABLED] ?: true,
            cloudInheritsPhone = prefs[KEY_CLOUD_INHERITS_PHONE] ?: true,
            phoneSourceSettings = sourceSettingsOf(prefs, SourceKind.PHONE_LIBRARY),
            removableSourceSettings = sourceSettingsOf(prefs, SourceKind.REMOVABLE),
            cameraMtpSourceSettings = sourceSettingsOf(prefs, SourceKind.CAMERA_MTP),
            cloudSourceSettings = sourceSettingsOf(prefs, SourceKind.CLOUD_OTHER),
        )
    }

    /** Run one-time v1 -> v2 preference migration if not yet applied. */
    suspend fun ensureMigratedToV2() {
        context.dataStore.edit { prefs ->
            migrateToV2(prefs)
        }
    }

    /**
     * All simple phone-mode settings as one typed flow. DataStore already emits a
     * single Preferences snapshot, so this needs no combine and no positional casts.
     */
    val phoneSettings: Flow<PhoneSettings> = context.dataStore.data.map { prefs ->
        phoneSettingsOf(prefs)
    }

    /** App-wide settings snapshot flow. */
    val appSettings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        appSettingsOf(prefs)
    }

    /** Per-source-kind settings flow (`U-10`). */
    fun sourceSettingsFlow(kind: SourceKind): Flow<SourceSettings> =
        context.dataStore.data.map { prefs ->
            sourceSettingsOf(prefs, kind)
        }

    val selectionFolderName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTION_FOLDER_NAME] ?: DEFAULT_SELECTION_FOLDER_NAME
    }

    val sortingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SORTING_ENABLED] ?: DEFAULT_SORTING_ENABLED
    }

    val lastFolderUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_FOLDER_URI]
    }

    val celebrationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CELEBRATIONS_ENABLED] ?: true
    }

    val cloudInheritsPhone: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_INHERITS_PHONE] ?: true
    }

    val persistedPendingDeleteUris: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_PERSISTED_PENDING_DELETE_URIS] ?: emptySet()
    }

    suspend fun setPersistedPendingDeleteUris(uris: Set<String>) {
        context.dataStore.edit { prefs ->
            if (uris.isEmpty()) prefs.remove(KEY_PERSISTED_PENDING_DELETE_URIS)
            else prefs[KEY_PERSISTED_PENDING_DELETE_URIS] = uris
        }
    }

    suspend fun setCelebrationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CELEBRATIONS_ENABLED] = enabled
        }
    }

    suspend fun setCloudInheritsPhone(inherits: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CLOUD_INHERITS_PHONE] = inherits
        }
    }

    suspend fun setSwipeRightVerb(kind: SourceKind, verb: SwipeRightVerb) {
        context.dataStore.edit { prefs ->
            prefs[swipeRightVerbKey(kind)] = verb.key
            if (kind == SourceKind.PHONE_LIBRARY && verb != SwipeRightVerb.FAVOURITE) {
                prefs[KEY_COLLECTION_ACTION] = verb.toCollectionAction().key
            }
        }
    }

    suspend fun setSwipeLeftVerb(kind: SourceKind, verb: SwipeAction) {
        context.dataStore.edit { prefs ->
            prefs[swipeLeftVerbKey(kind)] = verb.key
            if (kind == SourceKind.PHONE_LIBRARY) {
                prefs[KEY_LEFT_SWIPE_ACTION] = verb.key
            }
        }
    }

    suspend fun setFeedOrderMode(kind: SourceKind, mode: FeedOrderMode) {
        context.dataStore.edit { prefs ->
            prefs[feedOrderKey(kind)] = mode.key
            if (kind == SourceKind.PHONE_LIBRARY) {
                prefs[KEY_RANDOMIZE_ORDER] = mode.isRandomized
                prefs[KEY_SORT_BY_ORIENTATION] = mode.isSortByOrientation
            }
        }
    }

    suspend fun setFileTypeFilter(kind: SourceKind, filter: FileTypeFilter) {
        context.dataStore.edit { prefs ->
            prefs[fileTypeFilterKey(kind)] = filter.key
            if (kind == SourceKind.PHONE_LIBRARY) {
                prefs[KEY_FILE_TYPE_FILTER] = filter.key
            }
        }
    }

    suspend fun setMoveRelatedFiles(kind: SourceKind, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[moveRelatedFilesKey(kind)] = enabled
            if (kind == SourceKind.PHONE_LIBRARY) {
                prefs[KEY_MOVE_RELATED_FILES] = enabled
            }
        }
    }

    suspend fun setCollectionUri(kind: SourceKind, uri: String?) {
        context.dataStore.edit { prefs ->
            val key = collectionUriKey(kind)
            if (uri != null) prefs[key] = uri else prefs.remove(key)
            if (kind == SourceKind.PHONE_LIBRARY) {
                if (uri != null) prefs[KEY_COLLECTION_URI] = uri else prefs.remove(KEY_COLLECTION_URI)
            }
        }
    }

    suspend fun setLeftSwipeUri(kind: SourceKind, uri: String?) {
        context.dataStore.edit { prefs ->
            val key = leftSwipeUriKey(kind)
            if (uri != null) prefs[key] = uri else prefs.remove(key)
            if (kind == SourceKind.PHONE_LIBRARY) {
                if (uri != null) prefs[KEY_LEFT_SWIPE_URI] = uri else prefs.remove(KEY_LEFT_SWIPE_URI)
            }
        }
    }

    suspend fun setSourceSwipeRightVerb(kind: SourceKind, verb: SwipeRightVerb) =
        setSwipeRightVerb(kind, verb)

    suspend fun setSourceFeedOrder(kind: SourceKind, mode: FeedOrderMode) =
        setFeedOrderMode(kind, mode)

    suspend fun setSourceCollectionUri(kind: SourceKind, uri: String?) =
        setCollectionUri(kind, uri)

    suspend fun setSourceFileTypeFilter(kind: SourceKind, filter: FileTypeFilter) =
        setFileTypeFilter(kind, filter)

    suspend fun setSourceMoveRelatedFiles(kind: SourceKind, enabled: Boolean) =
        setMoveRelatedFiles(kind, enabled)

    suspend fun setSourceLeftSwipeAction(kind: SourceKind, action: SwipeAction) =
        setSwipeLeftVerb(kind, action)

    suspend fun setSourceLeftSwipeUri(kind: SourceKind, uri: String?) =
        setLeftSwipeUri(kind, uri)

    suspend fun setSourceDirectDeleteConfirmEnabled(kind: SourceKind, enabled: Boolean) =
        setPhoneDirectDeleteConfirmEnabled(enabled)

    suspend fun removeFolderSeenProgress(folderUri: String) {
        context.dataStore.edit { prefs ->
            prefs.remove(folderSeenCountKey(folderUri))
            prefs.remove(folderTotalCountKey(folderUri))
        }
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

    suspend fun setLastFolderUri(uri: String?) {
        var previousUri: String? = null
        var referencedAfter: Map<FolderAccessPurpose, Set<String>> = emptyMap()
        context.dataStore.edit { prefs ->
            previousUri = prefs[KEY_LAST_FOLDER_URI]
            if (uri != null) prefs[KEY_LAST_FOLDER_URI] = uri
            else prefs.remove(KEY_LAST_FOLDER_URI)
            referencedAfter = referencedUrisByPurpose(prefs)
        }
        folderAccessManager.syncReferences(referencedAfter)
        if (uri != null) {
            folderAccessManager.takeGrant(Uri.parse(uri), FolderAccessPurpose.SOURCE)
        }
        val old = previousUri
        if (old != null && old != uri) {
            folderAccessManager.releaseIfUnreferenced(old, referencedAfter.values.flatten().toSet())
        }
    }

    // ── Collection & browsing settings ──────────────────────────────────

    /** Action to perform when adding a photo to the collection. */
    val phoneCollectionAction: Flow<CollectionAction> = context.dataStore.data.map { prefs ->
        CollectionAction.fromKey(prefs[KEY_COLLECTION_ACTION])
    }

    /** Optional custom collection target folder URI. */
    val phoneCollectionUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_COLLECTION_URI] ?: prefs[collectionUriKey(SourceKind.PHONE_LIBRARY)]
    }

    /** Action to perform on a left swipe. */
    val phoneLeftSwipeAction: Flow<SwipeAction> = context.dataStore.data.map { prefs ->
        SwipeAction.fromKey(prefs[KEY_LEFT_SWIPE_ACTION])
    }

    /** Optional custom left swipe target folder URI. */
    val phoneLeftSwipeUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_LEFT_SWIPE_URI] ?: prefs[leftSwipeUriKey(SourceKind.PHONE_LIBRARY)]
    }

    /** Whether to show direct delete confirmation dialog (default true). */
    val phoneDirectDeleteConfirmEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DIRECT_DELETE_CONFIRM] ?: true
    }

    /** Sort images horizontal-first, then vertical (default false; base order is by date). */
    val phoneSortByOrientation: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SORT_BY_ORIENTATION] ?: false
    }

    /** Randomize picture order (default false). */
    val phoneRandomizeOrder: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_RANDOMIZE_ORDER] ?: false
    }

    /** Timestamp (millis) when gesture tutorial was last shown. 0 = never. */
    val phoneGestureTutorialTs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_GESTURE_TUTORIAL_TS] ?: 0L
    }

    /** File-type filter applied to the feed. */
    val phoneFileTypeFilter: Flow<FileTypeFilter> = context.dataStore.data.map { prefs ->
        FileTypeFilter.fromKey(prefs[KEY_FILE_TYPE_FILTER])
    }

    /** Whether to show EXIF stats overlay (default false; toggled via the logo). */
    val phoneShowExifOverlay: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_EXIF_OVERLAY] ?: false
    }

    /** Whether collection/delete actions also affect same-name sibling files (default false). */
    val phoneMoveRelatedFiles: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_MOVE_RELATED_FILES] ?: false
    }

    /** Whether to show the recent-folders list on the landing screen (default true). */
    val phoneRecentPathsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_RECENT_PATHS_ENABLED] ?: true
    }

    /** How many recent folders to show on the landing screen (default 3). */
    val phoneRecentPathsCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_RECENT_PATHS_COUNT] ?: DEFAULT_RECENT_PATHS_COUNT
    }

    /** Most-recently-used source folders, newest first. */
    val phoneRecentPaths: Flow<List<RecentPath>> = context.dataStore.data.map { prefs ->
        RecentPathCodec.decode(prefs[KEY_RECENT_PATHS])
    }

    /** Keys of the one-time action explanations the user has already seen. */
    val phoneSeenFirstRunHints: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_SEEN_FIRST_RUN_HINTS] ?: emptySet()
    }

    /** Timestamp (millis) when the app was last opened/used. 0 = never recorded. */
    val lastAppUsedTs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_APP_USED_TS] ?: 0L
    }

    suspend fun recordAppUsed(timestamp: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_APP_USED_TS] = timestamp
        }
    }

    /** Record that [hint] has been shown, so it never fires again. */
    suspend fun markFirstRunHintSeen(hint: FirstRunHint) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SEEN_FIRST_RUN_HINTS] = (prefs[KEY_SEEN_FIRST_RUN_HINTS] ?: emptySet()) + hint.key
        }
    }

    /**
     * Bring back every one-time explanation *and* the full gesture tutorial,
     * so the app behaves like a fresh install as far as guidance is concerned.
     */
    suspend fun resetFirstRunHints() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_SEEN_FIRST_RUN_HINTS)
            prefs[KEY_GESTURE_TUTORIAL_TS] = 0L
        }
    }

    suspend fun setPhoneCollectionAction(action: CollectionAction) {
        context.dataStore.edit { prefs ->
            prefs[KEY_COLLECTION_ACTION] = action.key
            prefs[swipeRightVerbKey(SourceKind.PHONE_LIBRARY)] = action.toSwipeRightVerb().key
        }
    }

    suspend fun setPhoneCollectionUri(uri: String?) {
        var previousUri: String? = null
        var referencedAfter: Map<FolderAccessPurpose, Set<String>> = emptyMap()
        context.dataStore.edit { prefs ->
            previousUri = prefs[KEY_COLLECTION_URI]
            if (uri != null) {
                prefs[KEY_COLLECTION_URI] = uri
                prefs[collectionUriKey(SourceKind.PHONE_LIBRARY)] = uri
            } else {
                prefs.remove(KEY_COLLECTION_URI)
                prefs.remove(collectionUriKey(SourceKind.PHONE_LIBRARY))
            }
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

    suspend fun setPhoneLeftSwipeAction(action: SwipeAction) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LEFT_SWIPE_ACTION] = action.key
            prefs[swipeLeftVerbKey(SourceKind.PHONE_LIBRARY)] = action.key
        }
    }

    suspend fun setPhoneLeftSwipeUri(uri: String?) {
        var previousUri: String? = null
        var referencedAfter: Map<FolderAccessPurpose, Set<String>> = emptyMap()
        context.dataStore.edit { prefs ->
            previousUri = prefs[KEY_LEFT_SWIPE_URI]
            if (uri != null) {
                prefs[KEY_LEFT_SWIPE_URI] = uri
                prefs[leftSwipeUriKey(SourceKind.PHONE_LIBRARY)] = uri
            } else {
                prefs.remove(KEY_LEFT_SWIPE_URI)
                prefs.remove(leftSwipeUriKey(SourceKind.PHONE_LIBRARY))
            }
            referencedAfter = referencedUrisByPurpose(prefs)
        }
        folderAccessManager.syncReferences(referencedAfter)
        if (uri != null) {
            folderAccessManager.takeGrant(Uri.parse(uri), FolderAccessPurpose.LEFT_SWIPE_TARGET)
        }
        val old = previousUri
        if (old != null && old != uri) {
            folderAccessManager.releaseIfUnreferenced(old, referencedAfter.values.flatten().toSet())
        }
    }

    suspend fun setPhoneDirectDeleteConfirmEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_DIRECT_DELETE_CONFIRM] = enabled }
    }

    suspend fun setPhoneSortByOrientation(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SORT_BY_ORIENTATION] = enabled
            val randomize = prefs[KEY_RANDOMIZE_ORDER] ?: false
            prefs[feedOrderKey(SourceKind.PHONE_LIBRARY)] =
                FeedOrderMode.fromLegacy(randomize, enabled).key
        }
    }

    suspend fun setPhoneRandomizeOrder(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_RANDOMIZE_ORDER] = enabled
            val sortByOrientation = prefs[KEY_SORT_BY_ORIENTATION] ?: false
            prefs[feedOrderKey(SourceKind.PHONE_LIBRARY)] =
                FeedOrderMode.fromLegacy(enabled, sortByOrientation).key
        }
    }

    suspend fun setPhoneGestureTutorialTs(ts: Long) {
        context.dataStore.edit { prefs -> prefs[KEY_GESTURE_TUTORIAL_TS] = ts }
    }

    suspend fun setPhoneFileTypeFilter(filter: FileTypeFilter) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FILE_TYPE_FILTER] = filter.key
            prefs[fileTypeFilterKey(SourceKind.PHONE_LIBRARY)] = filter.key
        }
    }

    suspend fun setPhoneShowExifOverlay(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SHOW_EXIF_OVERLAY] = enabled }
    }

    suspend fun setPhoneMoveRelatedFiles(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MOVE_RELATED_FILES] = enabled
            prefs[moveRelatedFilesKey(SourceKind.PHONE_LIBRARY)] = enabled
        }
    }

    suspend fun setPhoneRecentPathsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_RECENT_PATHS_ENABLED] = enabled }
    }

    suspend fun setPhoneRecentPathsCount(count: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_RECENT_PATHS_COUNT] = count.coerceAtLeast(1) }
    }

    /**
     * Record a folder as recently used, moving it to the front and de-duplicating.
     * Folders evicted from the recents list also get their scan info (and any retired
     * last-position key) removed and their persisted SAF grant released if no other
     * purpose still references the URI.
     */
    suspend fun addRecentPath(uri: String, name: String) {
        val parsed = android.net.Uri.parse(uri)
        val kind = if (parsed.authority?.contains('.') == true) {
            com.photoselector.core.storage.SourceKindClassifier.classify(parsed)
        } else {
            SourceKind.PHONE_LIBRARY
        }
        val volUuid = com.photoselector.core.storage.SourceKindClassifier.extractRemovableUuid(parsed)
        addRecentPath(uri, name, kind, volUuid)
    }

    suspend fun addRecentPath(
        uri: String,
        name: String,
        kind: SourceKind,
        volumeUuid: String? = null,
    ) {
        var evictedUris: List<String> = emptyList()
        var referencedAfter: Map<FolderAccessPurpose, Set<String>> = emptyMap()
        context.dataStore.edit { prefs ->
            val current = RecentPathCodec.decode(prefs[KEY_RECENT_PATHS])
            val updated = RecentPathCodec.add(current, uri, name, MAX_STORED_RECENT_PATHS, kind, volumeUuid)
            evictedUris = RecentPathCodec.evictedUris(current, updated)
            evictedUris.forEach { evictedUri ->
                prefs.remove(folderPositionKey(evictedUri))
                prefs.remove(legacyFolderPositionKey(evictedUri))
                prefs.remove(folderScanMaxTimeKey(evictedUri))
                prefs.remove(folderScanCountKey(evictedUri))
                prefs.remove(folderScanLastUriKey(evictedUri))
                prefs.remove(folderScanLastIndexKey(evictedUri))
                prefs.remove(folderSeenCountKey(evictedUri))
                prefs.remove(folderTotalCountKey(evictedUri))
            }
            prefs[KEY_RECENT_PATHS] = RecentPathCodec.encode(updated)
            referencedAfter = referencedUrisByPurpose(prefs)
        }
        folderAccessManager.syncReferences(referencedAfter)
        folderAccessManager.recordReference(uri, FolderAccessPurpose.RECENT_FOLDER)
        val allRemaining = referencedAfter.values.flatten().toSet()
        for (evictedUri in evictedUris) {
            folderAccessManager.releaseIfUnreferenced(evictedUri, allRemaining)
        }
    }

    /**
     * Reconcile OS persisted URI grants against the URIs currently referenced in
     * DataStore, releasing any orphaned grants from earlier sessions.
     */
    /** Remove [uris] from the persisted pending-delete queue, leaving entries queued meanwhile alone. */
    suspend fun removePersistedPendingDeleteUris(uris: Collection<String>) {
        if (uris.isEmpty()) return
        context.dataStore.edit { prefs ->
            val updated = (prefs[KEY_PERSISTED_PENDING_DELETE_URIS] ?: emptySet()) - uris.toSet()
            if (updated.isEmpty()) prefs.remove(KEY_PERSISTED_PENDING_DELETE_URIS)
            else prefs[KEY_PERSISTED_PENDING_DELETE_URIS] = updated
        }
    }

    /**
     * Release the persisted grant of [folderUri] now if nothing references it any more — called after
     * the deletions queued in a folder that was left have been committed.
     */
    suspend fun releaseFolderGrantIfUnreferenced(folderUri: String) {
        val prefs = context.dataStore.data.first()
        val referenced = referencedUrisByPurpose(prefs)
        folderAccessManager.syncReferences(referenced)
        folderAccessManager.releaseIfUnreferenced(folderUri, referenced.values.flatten().toSet())
    }

    /**
     * Keep the grants of the folders whose deletions are still on the undo stack or still being
     * carried out; an empty set unpins.
     */
    fun pinUndoHistoryFolders(folderUris: Set<String>) {
        folderAccessManager.pin(FolderAccessPurpose.UNDO_HISTORY, folderUris)
    }

    suspend fun reconcilePersistedGrants(): Set<String> {
        val prefs = context.dataStore.data.first()
        return folderAccessManager.reconcilePersistedGrants(referencedUrisByPurpose(prefs))
    }

    internal fun referencedUrisByPurpose(prefs: Preferences): Map<FolderAccessPurpose, Set<String>> {
        val source = prefs[KEY_LAST_FOLDER_URI]?.let(::setOf).orEmpty()
        val selection = prefs[KEY_COLLECTION_URI]?.let(::setOf).orEmpty()
        val leftSwipe = prefs[KEY_LEFT_SWIPE_URI]?.let(::setOf).orEmpty()
        val recents = RecentPathCodec.decode(prefs[KEY_RECENT_PATHS])
            .mapTo(LinkedHashSet()) { it.uri }
        // Queued deletions still need the grant of the folder they live in (finding D-10).
        val pendingDeletes = prefs[KEY_PERSISTED_PENDING_DELETE_URIS].orEmpty()
        return mapOf(
            FolderAccessPurpose.SOURCE to source,
            FolderAccessPurpose.SELECTION_TARGET to selection,
            FolderAccessPurpose.LEFT_SWIPE_TARGET to leftSwipe,
            FolderAccessPurpose.RECENT_FOLDER to recents,
            FolderAccessPurpose.PENDING_DELETE_JOURNAL to pendingDeletes,
        )
    }

    // ── Legacy per-folder last position keys (cleaned up on eviction/clear) ──

    /** Keyed by the full URI. */
    private fun folderPositionKey(folderUri: String) =
        intPreferencesKey("folder_pos_v2_$folderUri")

    /** Older hashCode-based key. */
    private fun legacyFolderPositionKey(folderUri: String) =
        intPreferencesKey("folder_pos_${folderUri.hashCode()}")

    // ── Per-folder scan info, resume position & seen progress ───────────

    private fun folderScanMaxTimeKey(folderUri: String) =
        longPreferencesKey("folder_scan_time_v1_$folderUri")

    private fun folderScanCountKey(folderUri: String) =
        intPreferencesKey("folder_scan_count_v1_$folderUri")

    private fun folderScanLastUriKey(folderUri: String) =
        stringPreferencesKey("folder_scan_last_uri_v1_$folderUri")

    private fun folderScanLastIndexKey(folderUri: String) =
        intPreferencesKey("folder_scan_last_idx_v1_$folderUri")

    private fun folderSeenCountKey(folderUri: String) =
        intPreferencesKey("folder_seen_count_v1_$folderUri")

    private fun folderTotalCountKey(folderUri: String) =
        intPreferencesKey("folder_total_count_v1_$folderUri")

    suspend fun setFolderScanInfo(folderUri: String, info: FolderScanInfo) {
        context.dataStore.edit { prefs ->
            prefs[folderScanMaxTimeKey(folderUri)] = info.maxLastModified
            prefs[folderScanCountKey(folderUri)] = info.fileCount
            if (info.lastViewedUri != null) {
                prefs[folderScanLastUriKey(folderUri)] = info.lastViewedUri
            }
            if (info.lastViewedUri != null || info.lastViewedIndex > 0) {
                prefs[folderScanLastIndexKey(folderUri)] = info.lastViewedIndex
            }
        }
    }

    suspend fun setFolderLastPosition(
        folderUri: String,
        lastViewedUri: String?,
        lastViewedIndex: Int,
    ) {
        context.dataStore.edit { prefs ->
            if (lastViewedUri != null) {
                prefs[folderScanLastUriKey(folderUri)] = lastViewedUri
            } else {
                prefs.remove(folderScanLastUriKey(folderUri))
            }
            prefs[folderScanLastIndexKey(folderUri)] = lastViewedIndex
        }
    }

    suspend fun getFolderScanInfo(folderUri: String): FolderScanInfo {
        val prefs = context.dataStore.data.first()
        val maxTime = prefs[folderScanMaxTimeKey(folderUri)] ?: 0L
        val count = prefs[folderScanCountKey(folderUri)] ?: 0
        val lastUri = prefs[folderScanLastUriKey(folderUri)]
        val lastIdx = prefs[folderScanLastIndexKey(folderUri)] ?: 0
        return FolderScanInfo(
            maxLastModified = maxTime,
            fileCount = count,
            lastViewedUri = lastUri,
            lastViewedIndex = lastIdx,
        )
    }

    suspend fun setFolderSeenProgress(folderUri: String, seenCount: Int, totalCount: Int) {
        context.dataStore.edit { prefs ->
            prefs[folderSeenCountKey(folderUri)] = seenCount.coerceAtLeast(0)
            prefs[folderTotalCountKey(folderUri)] = totalCount.coerceAtLeast(0)
        }
    }

    suspend fun getFolderSeenProgress(folderUri: String): Pair<Int, Int> {
        val prefs = context.dataStore.data.first()
        val seen = prefs[folderSeenCountKey(folderUri)] ?: 0
        val total = prefs[folderTotalCountKey(folderUri)] ?: 0
        return seen to total
    }

    val folderSeenProgressMap: Flow<Map<String, Pair<Int, Int>>> = context.dataStore.data.map { prefs ->
        val recents = RecentPathCodec.decode(prefs[KEY_RECENT_PATHS])
        recents.associate { path ->
            val seen = prefs[folderSeenCountKey(path.uri)] ?: 0
            val total = prefs[folderTotalCountKey(path.uri)] ?: 0
            path.uri to (seen to total)
        }
    }

    /** Clear per-folder scan metadata, resume positions, seen progress, and any retired last-position keys. */
    suspend fun clearFolderPositions() {
        context.dataStore.edit { prefs ->
            val keysToRemove = prefs.asMap().keys.filter {
                it.name.startsWith("folder_pos_") ||
                    it.name.startsWith("folder_scan_") ||
                    it.name.startsWith("folder_seen_") ||
                    it.name.startsWith("folder_total_")
            }
            keysToRemove.forEach { prefs.remove(it) }
        }
    }

    /** Clear image caches (Coil memory and disk) and all per-folder scan info. */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun clearCache() {
        try {
            coil.Coil.imageLoader(context).memoryCache?.clear()
            coil.Coil.imageLoader(context).diskCache?.clear()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to clear Coil cache", e)
        }
        clearFolderPositions()
    }
}

