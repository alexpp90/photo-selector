package com.photoselectortoolbox.ui.settings

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.FolderDescription
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SettingsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The user path through Settings → "Where photos go" with the real ViewModel and the real
 * screen: tap a radio row, the picker opens (a fake registry stands in for DocumentsUI and
 * returns a folder or cancels), and what comes back is what the screen shows. A folder lost
 * while the app was away shows up when Settings resumes.
 */
@Requirement("REQ-AND-WORKFLOW.02", "REQ-AND-PLATFORM.06")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h1200dp-xhdpi")
class SettingsScreenDestinationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val trip = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FTrip"
    private val keepers = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FKeepers"

    private val customFlow = MutableStateFlow<String?>(null)
    private val lastFolderFlow = MutableStateFlow<String?>(trip)

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { selectionFolderName } returns MutableStateFlow(SettingsRepository.DEFAULT_SELECTION_FOLDER_NAME)
        every { secondarySelectionFolderName } returns MutableStateFlow(SettingsRepository.DEFAULT_SECONDARY_SELECTION_FOLDER_NAME)
        every { sortingEnabled } returns MutableStateFlow(SettingsRepository.DEFAULT_SORTING_ENABLED)
        every { customSelectionDestinationUri } returns customFlow
        every { lastFolderUri } returns lastFolderFlow
        every { groupingEnabled } returns MutableStateFlow(false)
        every { groupingLevel } returns MutableStateFlow(GroupingLevel.TIME_FILENAME)
        every { analysisThreadCount } returns MutableStateFlow(4)
        every { fullscreenButtonsEnabled } returns MutableStateFlow(true)
        every { filingAction } returns MutableStateFlow(FilingAction.DEFAULT)
        every { tvDisplayScaleEnabled } returns MutableStateFlow(false)
        coEvery { setCustomSelectionDestinationUri(any()) } answers { customFlow.value = firstArg() }
    }

    private fun described(uri: String, path: List<String>, grant: Boolean = true) = FolderDescription(
        uri = uri, displayPath = path, name = path.last(),
        hasPersistedGrant = grant, exists = true, canWrite = true,
    )

    /** What the storage layer reports now; a test edits it to model a revoked permission. */
    private val folders = mutableMapOf(
        trip to described(trip, listOf("Internal storage", "DCIM", "Trip")),
        keepers to described(keepers, listOf("Internal storage", "Pictures", "Keepers")),
    )

    private val imageRepository: ImageRepository = mockk(relaxed = true) {
        coEvery { describeFolder(any(), any()) } answers { folders.getValue(secondArg<Uri>().toString()) }
    }

    // The picker: a fake registry. `nextPick` null is the user backing out of DocumentsUI.
    private var nextPick: Uri? = null
    private var pickerLaunches = 0

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I : Any?, O : Any?> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                pickerLaunches++
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, nextPick as O)
            }
        }
    }

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = SettingsViewModel(
            settingsRepository = settingsRepository,
            cacheRepository = mockk<CacheRepository>(relaxed = true),
            imageRepository = imageRepository,
            context = ApplicationProvider.getApplicationContext(),
        )
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                PhotoSelectorToolboxTheme { SettingsScreen(viewModel = viewModel) }
            }
        }
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun selected(tag: String): Boolean? =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected)

    private fun text(tag: String): String? = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(what: String, condition: () -> Boolean) {
        rule.waitUntil(5_000) { condition() }
        assertTrue(what, condition())
    }

    @Test
    fun `settings opens on the working folder and names the folder that is open now`() {
        waitFor("path") { exists("destination_path") && text("destination_path")?.startsWith("Internal storage") == true }

        assertEquals(true, selected("destination_working"))
        assertEquals("Internal storage › DCIM › Trip › Selection › RAW · JPEG", text("destination_path"))
    }

    @Test
    fun `tapping Custom opens the picker, and backing out of it keeps the working folder`() {
        waitFor("path") { exists("destination_path") }
        nextPick = null

        rule.onNodeWithTag("destination_custom").performClick()
        rule.waitForIdle()

        assertEquals(1, pickerLaunches)
        assertEquals(true, selected("destination_working"))
        assertEquals(false, selected("destination_custom"))
        coVerify(exactly = 0) { settingsRepository.setCustomSelectionDestinationUri(any()) }
        assertEquals("Choose", rule.onNodeWithTag("destination_choose").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
    }

    @Test
    fun `choosing a folder makes it the destination and the screen shows its path`() {
        waitFor("path") { exists("destination_path") }
        nextPick = Uri.parse(keepers)

        rule.onNodeWithTag("destination_custom").performClick()

        waitFor("custom selected") { selected("destination_custom") == true }
        coVerify { settingsRepository.setCustomSelectionDestinationUri(keepers) }
        waitFor("custom path") {
            text("destination_path") == "Internal storage › Pictures › Keepers › Selection › RAW · JPEG"
        }
        assertEquals(false, selected("destination_working"))
        assertEquals("Change", rule.onNodeWithTag("destination_choose").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
    }

    @Test
    fun `Working folder clears the custom folder and shows the open folder again`() {
        customFlow.value = keepers
        waitFor("custom") { selected("destination_custom") == true }

        rule.onNodeWithTag("destination_working").performClick()

        waitFor("working again") { selected("destination_working") == true }
        coVerify { settingsRepository.setCustomSelectionDestinationUri(null) }
        waitFor("working path") { text("destination_path") == "Internal storage › DCIM › Trip › Selection › RAW · JPEG" }
    }

    @Test
    fun `a folder whose permission is lost while the app is away warns when Settings resumes`() {
        customFlow.value = keepers
        waitFor("custom") { selected("destination_custom") == true }
        assertTrue("reachable at first", !exists("destination_warning"))

        // The permission goes while Settings is in the background...
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        folders[keepers] = described(keepers, listOf("Internal storage", "Pictures", "Keepers"), grant = false)
        // ...and nothing re-describes it until the user comes back.
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        waitFor("warning") { exists("destination_warning") }
        assertEquals("Folder unavailable · No permission — choose the folder again", text("destination_problem"))
    }
}
