package com.phototok.ui.settings

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.datastore.preferences.core.edit
import com.photoselector.core.Requirement
import com.photoselector.core.storage.SourceKind
import com.phototok.R
import com.phototok.data.model.FolderLookup
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.data.repository.dataStore
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.SettingsViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * REQ-TOK-FEATURE.23 (`[USER 2026-11-04]`, "I do not understand where the pictures go"): the
 * Selection and swipe-left folder rows show where photos land as a readable path — the custom
 * folder's, or "In source folder · …" with the open folder's — and say when a custom folder's
 * access was lost, with Re-select. 360 dp, font scale 2.0, English and German.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-xhdpi")
class SettingsDestinationPathTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var repository: SettingsRepository
    private val imageRepository: ImageRepository = mockk(relaxed = true)

    private val camera = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera"
    private val picks = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FPicks"

    @Before
    fun setUp() {
        runBlocking { context.dataStore.edit { it.clear() } }
        repository = SettingsRepository(context)
        runBlocking {
            repository.setProMode(true)
            repository.setLastFolderUri(camera)
        }
        coEvery { imageRepository.describeFolder(any()) } returns FolderLookup()
    }

    private fun host() {
        val viewModel = SettingsViewModel(repository, imageRepository)
        rule.setContent {
            PhotoTokTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                    SettingsScreen(viewModel = viewModel, sourceKind = SourceKind.PHONE_LIBRARY)
                }
            }
        }
        rule.waitForIdle()
    }

    /** Waits (idling the main looper the ViewModel resumes on) until [text] shows in row [rowId]. */
    private fun awaitRowText(rowId: String, text: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            rule.waitForIdle()
            val nodes = rule.onAllNodes(
                hasText(text) and hasAnyAncestor(hasTestTag("settings_row_$rowId")),
                useUnmergedTree = true,
            )
            if (nodes.fetchSemanticsNodes().isNotEmpty()) {
                nodes.onFirst().performScrollTo()
                return
            }
            assertTrue("'$text' not shown in $rowId within 5 s", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }

    private fun internal() = context.getString(R.string.label_internal_storage)

    private fun checkDefaultAndCustom() {
        host()
        val sep = com.photoselector.core.storage.SafTreeDisplayPath.SEPARATOR
        // Default: the open folder's path and the Selection subfolder inside it.
        awaitRowText(
            "selection_location",
            context.getString(R.string.setting_summary_in_source_path, listOf(internal(), "DCIM", "Camera", "Selection").joinToString(sep)),
        )
        // Custom: the chosen folder's full path, with the subfolder the files go into.
        runBlocking { repository.setPhoneCollectionUri(picks) }
        awaitRowText("selection_location", listOf(internal(), "Pictures", "Picks", "Selection").joinToString(sep))
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.23")
    fun english_defaultAndCustomPaths() {
        checkDefaultAndCustom()
    }

    @Test
    @Config(qualifiers = "de-w360dp-h640dp-xhdpi")
    @Requirement("REQ-TOK-FEATURE.23")
    fun german_defaultAndCustomPaths() {
        assertTrue(internal() == "Interner Speicher")
        checkDefaultAndCustom()
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.23")
    fun aLostCustomFolderSaysSoAndOffersReselect() {
        coEvery { imageRepository.describeFolder(Uri.parse(picks)) } returns FolderLookup(lost = true)
        runBlocking { repository.setPhoneCollectionUri(picks) }
        host()
        awaitRowText("selection_location", context.getString(R.string.setting_summary_folder_lost))
        val reselect = rule.onAllNodes(hasTestTag("settings_selection_reselect"), useUnmergedTree = true)
            .fetchSemanticsNodes().single()
        val density = reselect.layoutInfo.density.density
        assertTrue("Re-select ≥ 48 dp high: ${reselect.size}", reselect.size.height / density >= 47.5f)
        assertTrue("Re-select ≥ 48 dp wide: ${reselect.size}", reselect.size.width / density >= 47.5f)
    }
}
