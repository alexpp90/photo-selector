package com.phototok.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.photoselector.core.storage.SourceKind
import com.photoselector.saf.FolderAccessManager
import com.photoselector.saf.FolderAccessPurpose
import com.photoselector.saf.SafGrantBackend
import com.phototok.R
import com.phototok.data.model.SourceSettings
import com.phototok.domain.CollectionAction
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.PhotoFolders
import com.phototok.domain.ProModePolicy
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb
import com.phototok.domain.UiText
import java.lang.reflect.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "A hidden setting never acts": with every Pro-only value stored at a non-Casual value, every
 * production settings flow emits the Casual value while Pro mode is off and the stored value once
 * it is on. Also: the transition is one atomic edit and never rewrites stored values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProModeGateTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        runBlocking { context.dataStore.edit { it.clear() } }
        repository = SettingsRepository(context)
    }

    /** Every Pro-only key stored at a value that differs from Casual, plus the both-modes keys. */
    private suspend fun storeNonCasualValues() {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("selection_folder_name")] = "Keepers"
            prefs[booleanPreferencesKey("sorting_enabled")] = true
            prefs[stringPreferencesKey("collection_action")] = CollectionAction.MOVE.key
            prefs[stringPreferencesKey("collection_uri")] = "content://tree/coll"
            prefs[stringPreferencesKey("left_swipe_action")] = SwipeAction.MOVE.key
            prefs[stringPreferencesKey("left_swipe_uri")] = "content://tree/left"
            prefs[booleanPreferencesKey("sort_by_orientation")] = true
            prefs[booleanPreferencesKey("randomize_order")] = true
            prefs[stringPreferencesKey("file_type_filter")] = FileTypeFilter.RAW.key
            prefs[booleanPreferencesKey("show_exif_overlay")] = true
            prefs[booleanPreferencesKey("move_related_files")] = false
            prefs[booleanPreferencesKey("recent_paths_enabled")] = false
            prefs[intPreferencesKey("recent_paths_count")] = 8
            prefs[booleanPreferencesKey("cloud_inherits_phone")] = false
            // Both-modes settings.
            prefs[booleanPreferencesKey("direct_delete_confirm")] = false
            prefs[booleanPreferencesKey("celebrations_enabled")] = false
            for (kind in SourceKind.entries) {
                val tag = when (kind) {
                    SourceKind.PHONE_LIBRARY -> "phone"
                    SourceKind.REMOVABLE -> "removable"
                    SourceKind.CAMERA_MTP -> "camera_mtp"
                    SourceKind.CLOUD_OTHER -> "cloud"
                }
                prefs[SettingsRepository.swipeRightVerbKey(kind)] = SwipeRightVerb.MOVE.key
                prefs[SettingsRepository.swipeLeftVerbKey(kind)] = SwipeAction.MOVE.key
                prefs[SettingsRepository.feedOrderKey(kind)] = FeedOrderMode.SHUFFLE.key
                prefs[SettingsRepository.fileTypeFilterKey(kind)] = FileTypeFilter.RAW.key
                prefs[SettingsRepository.moveRelatedFilesKey(kind)] = false
                prefs[SettingsRepository.collectionUriKey(kind)] = "content://tree/coll-$tag"
                prefs[SettingsRepository.leftSwipeUriKey(kind)] = "content://tree/left-$tag"
                prefs[SettingsRepository.sortingEnabledKey(kind)] = true
            }
        }
    }

    private suspend fun setMode(pro: Boolean) {
        context.dataStore.edit { it[SettingsRepository.KEY_PRO_MODE] = pro }
    }

    @Test
    fun `every production settings flow emits the casual value while pro mode is off`() = runTest {
        storeNonCasualValues()
        setMode(false)

        assertFalse(repository.proMode.first())
        assertEquals("Selection", repository.selectionFolderName.first())
        assertFalse(repository.sortingEnabled.first())
        assertTrue(repository.cloudInheritsPhone.first())
        assertEquals(CollectionAction.COPY, repository.phoneCollectionAction.first())
        assertNull(repository.phoneCollectionUri.first())
        assertEquals(SwipeAction.DELETE, repository.phoneLeftSwipeAction.first())
        assertNull(repository.phoneLeftSwipeUri.first())
        assertFalse(repository.phoneSortByOrientation.first())
        assertFalse(repository.phoneRandomizeOrder.first())
        assertEquals(FileTypeFilter.ONE_PER_SHOT, repository.phoneFileTypeFilter.first())
        assertFalse(repository.phoneShowExifOverlay.first())
        assertTrue(repository.phoneMoveRelatedFiles.first())
        assertTrue(repository.phoneRecentPathsEnabled.first())
        assertEquals(3, repository.phoneRecentPathsCount.first())

        val phone = repository.phoneSettings.first()
        assertFalse(phone.proMode)
        assertEquals(CollectionAction.COPY, phone.collectionAction)
        assertEquals(SwipeAction.DELETE, phone.leftSwipeAction)
        assertEquals(FileTypeFilter.ONE_PER_SHOT, phone.fileTypeFilter)
        assertFalse(phone.sortByOrientation)
        assertFalse(phone.randomizeOrder)
        assertFalse(phone.showExifOverlay)
        assertTrue(phone.moveRelatedFiles)
        assertTrue(phone.recentPathsEnabled)
        assertEquals(3, phone.recentPathsCount)
        assertTrue(phone.cloudInheritsPhone)

        val app = repository.appSettings.first()
        assertFalse(app.proMode)
        assertEquals("Selection", app.selectionFolderName)
        assertFalse(app.sortingEnabled)
        assertFalse(app.showExifOverlay)
        assertTrue(app.recentPathsEnabled)
        assertEquals(3, app.recentPathsCount)
        assertTrue(app.cloudInheritsPhone)

        for (kind in SourceKind.entries) {
            val casualKind = if (kind == SourceKind.CLOUD_OTHER) SourceKind.PHONE_LIBRARY else kind
            val expected = ProModePolicy.casualSourceSettings(casualKind).copy(directDeleteConfirmEnabled = false)
            assertEquals("$kind flow", expected, repository.sourceSettingsFlow(kind).first())
            assertEquals("$kind mapping", expected, phone.settingsFor(kind))
        }

        // Both-modes settings are not gated.
        assertFalse(repository.phoneDirectDeleteConfirmEnabled.first())
        assertFalse(repository.celebrationsEnabled.first())
    }

    @Test
    fun `flipping pro mode on brings the stored values back exactly`() = runTest {
        storeNonCasualValues()
        setMode(false)
        assertEquals("Selection", repository.selectionFolderName.first())
        setMode(true)

        assertTrue(repository.proMode.first())
        assertEquals("Keepers", repository.selectionFolderName.first())
        assertTrue(repository.sortingEnabled.first())
        assertFalse(repository.cloudInheritsPhone.first())
        assertEquals(CollectionAction.MOVE, repository.phoneCollectionAction.first())
        assertEquals("content://tree/coll", repository.phoneCollectionUri.first())
        assertEquals(SwipeAction.MOVE, repository.phoneLeftSwipeAction.first())
        assertEquals("content://tree/left", repository.phoneLeftSwipeUri.first())
        assertTrue(repository.phoneSortByOrientation.first())
        assertTrue(repository.phoneRandomizeOrder.first())
        assertEquals(FileTypeFilter.RAW, repository.phoneFileTypeFilter.first())
        assertTrue(repository.phoneShowExifOverlay.first())
        assertFalse(repository.phoneMoveRelatedFiles.first())
        assertFalse(repository.phoneRecentPathsEnabled.first())
        assertEquals(8, repository.phoneRecentPathsCount.first())

        for (kind in SourceKind.entries) {
            val src = repository.sourceSettingsFlow(kind).first()
            val tag = if (kind == SourceKind.CLOUD_OTHER) "cloud" else kind.name
            assertEquals("$tag verb", SwipeRightVerb.MOVE, src.swipeRightVerb)
            assertEquals(FeedOrderMode.SHUFFLE, src.feedOrderMode)
            assertEquals(FileTypeFilter.RAW, src.fileTypeFilter)
            assertFalse(src.moveRelatedFiles)
            assertFalse(src.importToPhoneCamera)
            assertEquals(PhotoFolders.SELECTION, src.selectionSubfolder)
        }
        // Cloud no longer inherits: it reads its own stored values.
        assertEquals("content://tree/coll-cloud", repository.sourceSettingsFlow(SourceKind.CLOUD_OTHER).first().collectionUri)
        assertEquals("content://tree/coll-phone", repository.sourceSettingsFlow(SourceKind.PHONE_LIBRARY).first().collectionUri)
    }

    @Test
    fun `cloud inherits the phone in casual and follows its own values only in pro with inherit off`() = runTest {
        storeNonCasualValues()
        setMode(false)
        assertEquals(
            repository.sourceSettingsFlow(SourceKind.PHONE_LIBRARY).first(),
            repository.sourceSettingsFlow(SourceKind.CLOUD_OTHER).first(),
        )
        context.dataStore.edit { it[booleanPreferencesKey("cloud_inherits_phone")] = true }
        setMode(true)
        // Inheriting: cloud reads the phone slot even in Pro.
        assertEquals(
            repository.sourceSettingsFlow(SourceKind.PHONE_LIBRARY).first(),
            repository.sourceSettingsFlow(SourceKind.CLOUD_OTHER).first(),
        )
    }

    /**
     * Guard against a new flow bypassing the gate: every public Flow the repository exposes must be
     * classified here, so adding one forces a decision about its Casual value.
     */
    @Test
    fun `every flow the repository exposes is classified as gated or neutral`() {
        val gated = setOf(
            "getPhoneSettings", "getAppSettings", "sourceSettingsFlow", "getSelectionFolderName",
            "getSortingEnabled", "getCloudInheritsPhone", "getPhoneCollectionAction", "getPhoneCollectionUri",
            "getPhoneLeftSwipeAction", "getPhoneLeftSwipeUri", "getPhoneSortByOrientation",
            "getPhoneRandomizeOrder", "getPhoneFileTypeFilter", "getPhoneShowExifOverlay",
            "getPhoneMoveRelatedFiles", "getPhoneRecentPathsEnabled", "getPhoneRecentPathsCount",
        )
        val neutral = setOf(
            "getProMode", "getCasualCameraTargetUri", "getLastFolderUri", "getCelebrationsEnabled",
            "getPersistedPendingDeleteUris", "getPhoneDirectDeleteConfirmEnabled", "getPhoneGestureTutorialTs",
            "getPhoneRecentPaths", "getPhoneSeenFirstRunHints", "getLastAppUsedTs", "getFolderSeenProgressMap",
        )
        val exposed = SettingsRepository::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && Flow::class.java.isAssignableFrom(it.returnType) }
            .map { it.name }
            .toSet()
        assertEquals("unclassified or stale flows", gated + neutral, exposed)
    }

    // ── setProMode ────────────────────────────────────────────────────────

    @Test
    fun `first switch on writes camera info and five recents in one edit and says so`() = runTest {
        val message = repository.setProMode(true)

        assertEquals(UiText.of(R.string.snackbar_pro_camera_info_on), message)
        val prefs = context.dataStore.data.first()
        assertEquals(true, prefs[SettingsRepository.KEY_PRO_MODE])
        assertEquals(true, prefs[SettingsRepository.KEY_PRO_MODE_EVER_ENABLED])
        assertEquals(true, prefs[booleanPreferencesKey("show_exif_overlay")])
        assertEquals(5, prefs[intPreferencesKey("recent_paths_count")])
        assertTrue(repository.phoneShowExifOverlay.first())
        assertEquals(5, repository.phoneRecentPathsCount.first())
    }

    @Test
    fun `switching off pauses the pro values without rewriting any of them`() = runTest {
        storeNonCasualValues()
        setMode(true)
        val before = context.dataStore.data.first().asMap().toMap()

        val message = repository.setProMode(false)

        assertEquals(UiText.of(R.string.snackbar_pro_paused), message)
        val after = context.dataStore.data.first().asMap()
        assertEquals(before - SettingsRepository.KEY_PRO_MODE, after - SettingsRepository.KEY_PRO_MODE)
        assertEquals(false, after[SettingsRepository.KEY_PRO_MODE])
        // Camera info is gated, not rewritten: the stored value stays, the effective one is off.
        assertEquals(true, after[booleanPreferencesKey("show_exif_overlay")])
        assertFalse(repository.phoneShowExifOverlay.first())
    }

    @Test
    fun `later switch on restores the stored values and writes nothing else`() = runTest {
        repository.setProMode(true)
        repository.setPhoneRecentPathsCount(9)
        repository.setPhoneShowExifOverlay(false)
        repository.setProMode(false)
        val before = context.dataStore.data.first().asMap().toMap()

        val message = repository.setProMode(true)

        assertEquals(UiText.of(R.string.snackbar_pro_restored), message)
        val after = context.dataStore.data.first().asMap()
        assertEquals(before - SettingsRepository.KEY_PRO_MODE, after - SettingsRepository.KEY_PRO_MODE)
        assertEquals(9, repository.phoneRecentPathsCount.first())
        assertFalse(repository.phoneShowExifOverlay.first())
    }

    @Test
    fun `setting the mode it already has is a silent no-op`() = runTest {
        assertNull(repository.setProMode(false))
        repository.setProMode(true)
        assertNull(repository.setProMode(true))
    }

    // ── Casual camera target grant ────────────────────────────────────────

    @Test
    fun `the casual camera target is held by a durable purpose that reconciliation keeps`() = runTest {
        val persisted = mutableSetOf<String>()
        val released = mutableListOf<String>()
        val backend = object : SafGrantBackend {
            override fun takePersistableGrant(uri: android.net.Uri, modeFlags: Int) { persisted += uri.toString() }
            override fun releasePersistableGrant(uri: android.net.Uri, modeFlags: Int) {
                released += uri.toString(); persisted -= uri.toString()
            }
            override fun persistedGrantUris(): Set<String> = persisted.toSet()
        }
        val manager = FolderAccessManager(backend)
        val repo = SettingsRepository(context, manager)
        val camera = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera"

        repo.setCasualCameraTarget(camera)

        assertEquals(camera, repo.casualCameraTargetUri.first())
        assertTrue(camera in persisted)
        val referenced = repo.referencedUrisByPurpose(context.dataStore.data.first())
        assertTrue(camera in referenced.getValue(FolderAccessPurpose.SELECTION_TARGET))
        // Startup reconciliation (a new process) must not release it.
        assertTrue(repo.reconcilePersistedGrants().isEmpty())
        assertTrue(camera in persisted)
        assertTrue(released.isEmpty())
    }

    @Test
    fun `gated custom folders stay referenced so their grants survive casual mode`() = runTest {
        storeNonCasualValues()
        setMode(false)
        val referenced = repository.referencedUrisByPurpose(context.dataStore.data.first())
        assertTrue("content://tree/coll" in referenced.getValue(FolderAccessPurpose.SELECTION_TARGET))
        assertTrue("content://tree/left" in referenced.getValue(FolderAccessPurpose.LEFT_SWIPE_TARGET))
    }

    @Test
    fun `source settings default type is unaffected by the new fields`() {
        assertFalse(SourceSettings().importToPhoneCamera)
        assertEquals(PhotoFolders.SELECTION, SourceSettings().selectionSubfolder)
    }
}
