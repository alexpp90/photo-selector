package com.photoselectortoolbox.viewmodel

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import com.photoselector.saf.FolderAccessManager
import com.photoselector.saf.FolderAccessPurpose
import com.photoselector.saf.SafGrantBackend
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.HeldDeleteCommitter
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.data.repository.dataStore
import com.photoselectortoolbox.domain.usecase.FindDuplicatesUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A folder Dupes or Stats opened keeps its grant while the screen works on it (REQ-AND-PLATFORM.06,
 * backlog 2026-11-12): each screen's folder is a durable grant holder, so neither the startup
 * reconcile nor the cull switching folders releases it — which made the next Dupes delete fail with
 * a permission error. The only other holder here is none: the cull has another folder open.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolFolderGrantTest {

    /** The OS's persisted grants, as `ContentResolver` keeps them. */
    private class OsGrants : SafGrantBackend {
        val persisted = LinkedHashSet<String>()
        val released = mutableListOf<String>()
        override fun takePersistableGrant(uri: Uri, modeFlags: Int) {
            persisted += uri.toString()
        }
        override fun releasePersistableGrant(uri: Uri, modeFlags: Int) {
            released += uri.toString()
            persisted -= uri.toString()
        }
        override fun persistedGrantUris(): Set<String> = persisted.toSet()
    }

    private val context: Context = RuntimeEnvironment.getApplication()
    // A real single thread for Main: the screens' work runs as in the app, and each test waits
    // for the state it needs (an unconfined test dispatcher resumed DataStore's write lock inline
    // and left the store locked for the next edit).
    private val main = Dispatchers.Default.limitedParallelism(1)
    private val appScope = CoroutineScope(SupervisorJob() + main)
    private val os = OsGrants()
    private val access = FolderAccessManager(os)
    private lateinit var settings: SettingsRepository

    private val dupesFolder = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FOld"
    private val cullFolder = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FTrip"
    private val nextCullFolder = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FNext"

    /** The repository's `openFolder`, as `ImageRepositoryImpl` does it: the grant goes through the manager. */
    private val repository = mockk<ImageRepository>(relaxed = true).also { repo ->
        coEvery { repo.openFolder(any(), any()) } answers {
            access.takeGrant(secondArg(), FolderAccessPurpose.SOURCE)
            "Old"
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        // Process-wide DataStore singleton (agent rule, 2026-10-17).
        runBlocking { context.dataStore.edit { it.clear() } }
        settings = SettingsRepository(context, access)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        runBlocking { context.dataStore.edit { it.clear() } }
    }

    private fun dupes() = DuplicatesViewModel(
        mockk<FindDuplicatesUseCase>(relaxed = true),
        repository,
        HeldDeleteCommitter(repository, mockk<CacheRepository>(relaxed = true), settings, context),
        appScope,
        context,
        settings,
    )

    private fun stats() = StatisticsViewModel(repository, null, context, settings)

    /** Open [folder] in Dupes and wait until the screen shows it (its holder is written first). */
    private suspend fun DuplicatesViewModel.open(folder: String) {
        selectFolder(Uri.parse(folder))
        withTimeout(10_000) { while (uiState.value.folderUri != folder) delay(10) }
    }

    private suspend fun StatisticsViewModel.open(folder: String) {
        selectFolder(Uri.parse(folder))
        withTimeout(10_000) { while (uiState.value.folderUri != folder) delay(10) }
    }

    @Test
    fun `a folder open in Dupes keeps its grant through the startup reconcile and a cull folder switch`() = runBlocking {
        settings.setLastFolderUri(cullFolder)
        dupes().open(dupesFolder)
        assertTrue(dupesFolder in os.persisted)

        // The cull opens another folder (its old one is released), and the app starts again.
        settings.setLastFolderUri(nextCullFolder)
        settings.reconcilePersistedGrants()

        assertTrue("the Dupes folder's grant was released", dupesFolder in os.persisted)
        assertEquals(listOf(cullFolder), os.released)
    }

    @Test
    fun `a folder open in Stats keeps its grant through the startup reconcile and a cull folder switch`() = runBlocking {
        settings.setLastFolderUri(cullFolder)
        stats().open(dupesFolder)
        assertTrue(dupesFolder in os.persisted)

        settings.setLastFolderUri(nextCullFolder)
        settings.reconcilePersistedGrants()

        assertTrue("the Stats folder's grant was released", dupesFolder in os.persisted)
    }

    @Test
    fun `Dupes opening another folder releases the one it had, unless the cull has it open`() = runBlocking {
        settings.setLastFolderUri(cullFolder)
        val dupes = dupes()
        dupes.open(cullFolder)
        dupes.open(dupesFolder)
        assertTrue("the cull still has it open", cullFolder in os.persisted)

        dupes.open(nextCullFolder)
        assertTrue("left by Dupes, held by no one", dupesFolder !in os.persisted)
        assertTrue(cullFolder in os.persisted)
    }
}
