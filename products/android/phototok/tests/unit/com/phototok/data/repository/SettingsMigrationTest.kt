package com.phototok.data.repository

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.photoselector.core.storage.SourceKind
import com.phototok.data.model.RecentPath
import com.phototok.domain.CollectionAction
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.ProModePolicy
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings survive updates: schema 3 migration (`settings_schema_version`), the legacy flat-key
 * step that never ran before, and the one-time decision whether an existing install starts in
 * Pro mode. The pure steps run on in-memory preferences; the wiring test runs the real DataStore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsMigrationTest {

    private val version = SettingsRepository.KEY_SETTINGS_SCHEMA_VERSION
    private val proMode = SettingsRepository.KEY_PRO_MODE
    private val everEnabled = SettingsRepository.KEY_PRO_MODE_EVER_ENABLED

    private fun migrated(vararg entries: Preferences.Pair<*>): MutablePreferences =
        mutablePreferencesOf(*entries).also { SettingsRepository.applyMigrations(it) }

    private fun cardRecentValue(kind: SourceKind = SourceKind.REMOVABLE) =
        RecentPathCodec.encode(listOf(RecentPath("content://card/tree/DCIM", "DCIM", kind, "ABCD-1234")))

    private fun cardRecent(kind: SourceKind = SourceKind.REMOVABLE) =
        stringPreferencesKey("recent_paths") to cardRecentValue(kind)

    // ── Fresh install ─────────────────────────────────────────────────────

    @Test
    fun `fresh install lands in casual at schema 3 and seeds nothing else`() {
        val prefs = migrated()

        assertEquals(3, SettingsRepository.CURRENT_SCHEMA_VERSION)
        assertEquals(mapOf<Preferences.Key<*>, Any>(Pair(version, 3), Pair(proMode, false)), prefs.asMap())
        assertNull(prefs[everEnabled])
    }

    @Test
    fun `the default pro mode is read from production code`() {
        assertEquals(ProModePolicy.DEFAULT_PRO_MODE, SettingsRepository.proModeOf(preferencesOf()))
        assertFalse(SettingsRepository.proModeOf(preferencesOf()))
        assertFalse(SettingsRepository.phoneSettingsOf(preferencesOf()).proMode)
        assertFalse(SettingsRepository.appSettingsOf(preferencesOf()).proMode)
    }

    // ── Legacy step (v0 / v1 flat keys) ───────────────────────────────────

    @Test
    fun `v1 flat keys reach the phone per-source keys with their values and land in pro`() {
        val prefs = migrated(
            stringPreferencesKey("collection_action") to CollectionAction.MOVE.key,
            stringPreferencesKey("left_swipe_action") to SwipeAction.COPY.key,
            stringPreferencesKey("file_type_filter") to FileTypeFilter.RAW.key,
            booleanPreferencesKey("move_related_files") to false,
            booleanPreferencesKey("randomize_order") to true,
            booleanPreferencesKey("sort_by_orientation") to false,
            stringPreferencesKey("collection_uri") to "content://tree/coll",
            stringPreferencesKey("left_swipe_uri") to "content://tree/left",
        )

        val phone = SettingsRepository.storedSourceSettingsOf(prefs, SourceKind.PHONE_LIBRARY)
        assertEquals(SwipeRightVerb.MOVE, phone.swipeRightVerb)
        assertEquals(SwipeAction.COPY, phone.swipeLeftVerb)
        assertEquals(FileTypeFilter.RAW, phone.fileTypeFilter)
        assertFalse(phone.moveRelatedFiles)
        assertEquals(FeedOrderMode.SHUFFLE, phone.feedOrderMode)
        assertEquals("content://tree/coll", phone.collectionUri)
        assertEquals("content://tree/left", phone.leftSwipeUri)
        assertEquals(true, prefs[proMode])
        assertEquals(true, prefs[everEnabled])
        assertEquals(3, prefs[version])
        // The legacy keys stay (older code and a downgrade still read them).
        assertEquals(CollectionAction.MOVE.key, prefs[stringPreferencesKey("collection_action")])
        assertEquals("content://tree/coll", prefs[stringPreferencesKey("collection_uri")])
    }

    @Test
    fun `the legacy step never overwrites a per-source key and never seeds an absent one`() {
        val prefs = migrated(
            stringPreferencesKey("collection_action") to CollectionAction.MOVE.key,
            SettingsRepository.swipeRightVerbKey(SourceKind.PHONE_LIBRARY) to SwipeRightVerb.FAVOURITE.key,
        )

        assertEquals(SwipeRightVerb.FAVOURITE.key, prefs[SettingsRepository.swipeRightVerbKey(SourceKind.PHONE_LIBRARY)])
        assertNull(prefs[SettingsRepository.swipeLeftVerbKey(SourceKind.PHONE_LIBRARY)])
        assertNull(prefs[SettingsRepository.feedOrderKey(SourceKind.PHONE_LIBRARY)])
        assertNull(prefs[SettingsRepository.fileTypeFilterKey(SourceKind.REMOVABLE)])
        assertNull(prefs[SettingsRepository.swipeRightVerbKey(SourceKind.CAMERA_MTP)])
        assertNull(prefs[SettingsRepository.collectionUriKey(SourceKind.PHONE_LIBRARY)])
    }

    @Test
    fun `legacy feed order flags map to the per-source feed order`() {
        val sort = migrated(booleanPreferencesKey("sort_by_orientation") to true)
        assertEquals(
            FeedOrderMode.ORIENTATION_THEN_NEWEST.key,
            sort[SettingsRepository.feedOrderKey(SourceKind.PHONE_LIBRARY)],
        )
        assertEquals(true, sort[proMode])
    }

    // ── v2 to v3 ──────────────────────────────────────────────────────────

    @Test
    fun `a v2 snapshot with every key set to a non-default value survives unchanged and lands in pro`() {
        val entries = mutableListOf<Preferences.Pair<*>>(
            version to 2,
            stringPreferencesKey("selection_folder_name") to "Keepers",
            booleanPreferencesKey("sorting_enabled") to true,
            stringPreferencesKey("last_folder_uri") to "content://tree/last",
            stringPreferencesKey("collection_action") to CollectionAction.MOVE.key,
            stringPreferencesKey("collection_uri") to "content://tree/coll",
            stringPreferencesKey("left_swipe_action") to SwipeAction.MOVE.key,
            stringPreferencesKey("left_swipe_uri") to "content://tree/left",
            booleanPreferencesKey("direct_delete_confirm") to false,
            booleanPreferencesKey("sort_by_orientation") to true,
            booleanPreferencesKey("randomize_order") to true,
            longPreferencesKey("gesture_tutorial_ts") to 1234L,
            stringPreferencesKey("file_type_filter") to FileTypeFilter.JPG.key,
            booleanPreferencesKey("show_exif_overlay") to true,
            booleanPreferencesKey("move_related_files") to true,
            booleanPreferencesKey("recent_paths_enabled") to false,
            intPreferencesKey("recent_paths_count") to 7,
            cardRecent(),
            stringSetPreferencesKey("seen_first_run_hints") to setOf("swipe_right", "tap_hud"),
            longPreferencesKey("last_app_used_ts") to 99L,
            booleanPreferencesKey("celebrations_enabled") to false,
            booleanPreferencesKey("cloud_inherits_phone") to false,
            stringSetPreferencesKey("persisted_pending_delete_uris") to setOf("content://tree/doc/1"),
            intPreferencesKey("folder_pos_v2_content://x") to 4,
            intPreferencesKey("folder_scan_count_v1_content://x") to 5,
            intPreferencesKey("folder_seen_count_v1_content://x") to 6,
            stringPreferencesKey("some_key_from_a_future_or_removed_feature") to "keep me",
        )
        for (kind in SourceKind.entries) {
            entries += SettingsRepository.swipeRightVerbKey(kind) to SwipeRightVerb.MOVE.key
            entries += SettingsRepository.swipeLeftVerbKey(kind) to SwipeAction.COPY.key
            entries += SettingsRepository.feedOrderKey(kind) to FeedOrderMode.SHUFFLE.key
            entries += SettingsRepository.fileTypeFilterKey(kind) to FileTypeFilter.RAW.key
            entries += SettingsRepository.moveRelatedFilesKey(kind) to false
            entries += SettingsRepository.collectionUriKey(kind) to "content://tree/c-$kind"
            entries += SettingsRepository.leftSwipeUriKey(kind) to "content://tree/l-$kind"
            entries += SettingsRepository.sortingEnabledKey(kind) to true
            entries += SettingsRepository.directDeleteConfirmKey(kind) to false
        }
        val before = mutablePreferencesOf(*entries.toTypedArray()).asMap().toMap()

        val after = mutablePreferencesOf(*entries.toTypedArray()).also { SettingsRepository.applyMigrations(it) }

        val expected = before + mapOf<Preferences.Key<*>, Any>(Pair(version, 3), Pair(proMode, true), Pair(everEnabled, true))
        assertEquals(expected, after.asMap())
        // The unknown key is left exactly where it was.
        assertEquals("keep me", after[stringPreferencesKey("some_key_from_a_future_or_removed_feature")])
    }

    @Test
    fun `an untouched existing install lands in casual with identical effective behaviour`() {
        val before = mutablePreferencesOf(
            stringPreferencesKey("last_folder_uri") to "content://tree/last",
            longPreferencesKey("gesture_tutorial_ts") to 1234L,
            booleanPreferencesKey("direct_delete_confirm") to false,
            booleanPreferencesKey("celebrations_enabled") to false,
            stringSetPreferencesKey("seen_first_run_hints") to setOf("swipe_right"),
            stringPreferencesKey("recent_paths") to RecentPathCodec.encode(
                listOf(RecentPath("content://phone/tree/Pictures", "Pictures")),
            ),
            // Values equal to the Casual ones are not a reason for Pro.
            SettingsRepository.swipeRightVerbKey(SourceKind.PHONE_LIBRARY) to SwipeRightVerb.FAVOURITE.key,
            SettingsRepository.feedOrderKey(SourceKind.PHONE_LIBRARY) to FeedOrderMode.NEWEST_FIRST.key,
        )
        val asPro = before.toMutablePreferences()
        asPro[proMode] = true
        val expectedEffective = SourceKind.entries.associateWith { SettingsRepository.sourceSettingsOf(asPro, it) }

        SettingsRepository.applyMigrations(before)

        assertEquals(false, before[proMode])
        assertNull(before[everEnabled])
        // Phone and cloud behave exactly as before the update; confirm delete is untouched.
        for (kind in listOf(SourceKind.PHONE_LIBRARY, SourceKind.CLOUD_OTHER)) {
            assertEquals("$kind", expectedEffective[kind], SettingsRepository.sourceSettingsOf(before, kind))
        }
        assertFalse(SettingsRepository.sourceSettingsOf(before, SourceKind.PHONE_LIBRARY).directDeleteConfirmEnabled)
        val app = SettingsRepository.appSettingsOf(before)
        assertFalse(app.celebrationsEnabled)
        assertEquals(setOf("swipe_right"), app.seenFirstRunHints)
        assertEquals(1, app.recentPaths.size)
    }

    @Test
    fun `an SD card in the recent folders lands in pro, and so does a camera`() {
        for (kind in listOf(SourceKind.REMOVABLE, SourceKind.CAMERA_MTP)) {
            val prefs = migrated(cardRecent(kind))
            assertEquals("$kind", true, prefs[proMode])
            assertEquals(true, prefs[everEnabled])
            // Pro SD keeps `<card>/Selection`, so nothing changes for this user.
            val sd = SettingsRepository.sourceSettingsOf(prefs, kind)
            assertFalse(sd.importToPhoneCamera)
            assertEquals("Selection", sd.selectionSubfolder)
        }
    }

    @Test
    fun `camera info on lands in pro, because casual would take it away`() {
        val prefs = migrated(booleanPreferencesKey("show_exif_overlay") to true)
        assertEquals(true, prefs[proMode])
        assertTrue(SettingsRepository.phoneSettingsOf(prefs).showExifOverlay)
    }

    @Test
    fun `confirm delete, milestones and hints alone do not decide pro`() {
        val prefs = migrated(
            booleanPreferencesKey("direct_delete_confirm") to false,
            booleanPreferencesKey("celebrations_enabled") to false,
            stringSetPreferencesKey("seen_first_run_hints") to setOf("swipe_right"),
        )
        assertEquals(false, prefs[proMode])
    }

    @Test
    fun `each pro-only value that differs lands in pro, per kind`() {
        val cases = listOf<Pair<String, Preferences.Pair<*>>>(
            "phone swipe right" to (SettingsRepository.swipeRightVerbKey(SourceKind.PHONE_LIBRARY) to SwipeRightVerb.COPY.key),
            "sd feed order" to (SettingsRepository.feedOrderKey(SourceKind.REMOVABLE) to FeedOrderMode.NEWEST_FIRST.key),
            "camera filter" to (SettingsRepository.fileTypeFilterKey(SourceKind.CAMERA_MTP) to FileTypeFilter.ALL.key),
            "phone link" to (SettingsRepository.moveRelatedFilesKey(SourceKind.PHONE_LIBRARY) to false),
            "phone selection location" to (SettingsRepository.collectionUriKey(SourceKind.PHONE_LIBRARY) to "content://tree/c"),
            "phone left folder" to (SettingsRepository.leftSwipeUriKey(SourceKind.PHONE_LIBRARY) to "content://tree/l"),
            "selection name" to (stringPreferencesKey("selection_folder_name") to "Keepers"),
            "sorting" to (booleanPreferencesKey("sorting_enabled") to true),
            "recents off" to (booleanPreferencesKey("recent_paths_enabled") to false),
            "recents count" to (intPreferencesKey("recent_paths_count") to 5),
            "cloud inherits" to (booleanPreferencesKey("cloud_inherits_phone") to false),
        )
        for ((label, entry) in cases) {
            assertEquals(label, true, migrated(entry)[proMode])
        }
    }

    @Test
    fun `values are compared, never key presence`() {
        val prefs = migrated(
            SettingsRepository.swipeRightVerbKey(SourceKind.PHONE_LIBRARY) to SwipeRightVerb.FAVOURITE.key,
            SettingsRepository.swipeLeftVerbKey(SourceKind.PHONE_LIBRARY) to SwipeAction.DELETE.key,
            SettingsRepository.fileTypeFilterKey(SourceKind.PHONE_LIBRARY) to FileTypeFilter.ONE_PER_SHOT.key,
            SettingsRepository.moveRelatedFilesKey(SourceKind.PHONE_LIBRARY) to true,
            SettingsRepository.feedOrderKey(SourceKind.REMOVABLE) to FeedOrderMode.SHOOTING.key,
            stringPreferencesKey("selection_folder_name") to "Selection",
            intPreferencesKey("recent_paths_count") to 3,
            booleanPreferencesKey("recent_paths_enabled") to true,
            booleanPreferencesKey("cloud_inherits_phone") to true,
        )
        assertEquals(false, prefs[proMode])
    }

    @Test
    fun `cloud values are ignored while cloud inherits the phone`() {
        val prefs = migrated(
            SettingsRepository.swipeRightVerbKey(SourceKind.CLOUD_OTHER) to SwipeRightVerb.MOVE.key,
            SettingsRepository.feedOrderKey(SourceKind.CLOUD_OTHER) to FeedOrderMode.SHUFFLE.key,
        )
        assertEquals(false, prefs[proMode])
    }

    // ── Idempotency and non-destruction ───────────────────────────────────

    @Test
    fun `migration is idempotent`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("collection_action") to CollectionAction.MOVE.key,
            cardRecent(),
        )
        SettingsRepository.applyMigrations(prefs)
        val once = prefs.asMap().toMap()
        SettingsRepository.applyMigrations(prefs)
        assertEquals(once, prefs.asMap())

        val casualUser = mutablePreferencesOf()
        SettingsRepository.applyMigrations(casualUser)
        val casualOnce = casualUser.asMap().toMap()
        SettingsRepository.applyMigrations(casualUser)
        assertEquals(casualOnce, casualUser.asMap())
    }

    @Test
    fun `a later change of the mode is never undone by another run`() {
        val prefs = migrated(cardRecent())
        assertEquals(true, prefs[proMode])
        prefs[proMode] = false
        SettingsRepository.applyMigrations(prefs)
        assertEquals(false, prefs[proMode])

        // Even a store whose version was lowered keeps its explicit mode.
        prefs[version] = 2
        SettingsRepository.applyMigrations(prefs)
        assertEquals(false, prefs[proMode])
        assertEquals(3, prefs[version])
    }

    @Test
    fun `the migration never deletes a key`() {
        val entries = arrayOf<Preferences.Pair<*>>(
            stringPreferencesKey("collection_action") to CollectionAction.MOVE.key,
            booleanPreferencesKey("randomize_order") to true,
            stringPreferencesKey("old_removed_setting") to "x",
            intPreferencesKey("folder_pos_v2_content://y") to 3,
        )
        val before = mutablePreferencesOf(*entries).asMap().keys.toSet()
        val after = migrated(*entries).asMap().keys
        assertTrue(after.containsAll(before))
    }

    // ── Wiring: a real DataStore migrates before its first read ────────────

    @Test
    fun `a datastore built with the production migrations migrates an old store before its first read`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = context.preferencesDataStoreFile("migration_wiring_test")
        val seedScope = CoroutineScope(Dispatchers.IO + Job())
        val seed = PreferenceDataStoreFactory.create(scope = seedScope, produceFile = { file })
        runBlocking {
            seed.edit {
                it[stringPreferencesKey("collection_action")] = CollectionAction.MOVE.key
                it[stringPreferencesKey("recent_paths")] = cardRecentValue()
            }
            seedScope.coroutineContext[Job]!!.cancelAndJoin()
        }

        val readScope = CoroutineScope(Dispatchers.IO + Job())
        val store = PreferenceDataStoreFactory.create(
            scope = readScope,
            migrations = SettingsRepository.dataStoreMigrations,
            produceFile = { file },
        )
        runBlocking {
            // The very first read already sees the migrated store, no explicit call needed.
            val prefs = store.data.first()
            assertEquals(3, prefs[version])
            assertEquals(true, prefs[proMode])
            assertEquals(
                SwipeRightVerb.MOVE,
                SettingsRepository.sourceSettingsOf(prefs, SourceKind.PHONE_LIBRARY).swipeRightVerb,
            )
            readScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `the application datastore is built with those migrations`() {
        // `Context.dataStore` is a process-wide singleton delegate; read the migrations it was
        // declared with, so removing the wiring fails here.
        val delegateField = Class.forName("com.phototok.data.repository.SettingsRepositoryKt")
            .getDeclaredField("dataStore\$delegate").apply { isAccessible = true }
        val delegate = delegateField.get(null)
        val producerField = delegate.javaClass.getDeclaredField("produceMigrations").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val produce = producerField.get(delegate) as (Context) -> List<*>
        val migrations = produce(RuntimeEnvironment.getApplication())

        assertTrue(migrations.contains(SettingsRepository.schemaMigration))
    }
}
