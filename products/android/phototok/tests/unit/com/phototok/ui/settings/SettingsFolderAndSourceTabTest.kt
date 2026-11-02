package com.phototok.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.edit
import com.photoselector.core.storage.SourceKind
import com.phototok.R
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.data.repository.dataStore
import com.phototok.domain.PhotoFolders
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.SettingsViewModel
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pro-mode Settings against the real settings store:
 * - Settings opens on the tab of the folder being browsed, so "Move" chosen while browsing an SD
 *   card acts on the SD card (it used to land on the phone's tab and the card kept Copy).
 * - A folder row's "Default" is explained when tapped: the dialog names the subfolder inside the
 *   source folder and offers the picker.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
class SettingsFolderAndSourceTabTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        runBlocking { context.dataStore.edit { it.clear() } }
        repository = SettingsRepository(context)
        runBlocking { repository.setProMode(true) }
    }

    private fun host(sourceKind: SourceKind?) {
        val viewModel = SettingsViewModel(repository, mockk<ImageRepository>(relaxed = true))
        rule.setContent {
            PhotoTokTheme {
                SettingsScreen(viewModel = viewModel, sourceKind = sourceKind)
            }
        }
        rule.waitForIdle()
    }

    private fun clickOption(rowId: String, label: Int) {
        rule.onNode(
            hasText(context.getString(label)) and hasAnyAncestor(hasTestTag("settings_row_$rowId")),
            useUnmergedTree = true,
        ).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun stored(kind: SourceKind) = runBlocking { repository.sourceSettingsFlow(kind).first() }

    private fun count(tag: String) =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun browsingAnSdCard_moveActsOnTheCard_notOnThePhone() {
        host(SourceKind.REMOVABLE)

        clickOption("swipe_right", R.string.chip_move)
        clickOption("swipe_left", R.string.chip_move)

        assertEquals(SwipeRightVerb.MOVE, stored(SourceKind.REMOVABLE).swipeRightVerb)
        assertEquals(SwipeAction.MOVE, stored(SourceKind.REMOVABLE).swipeLeftVerb)
        assertEquals("the phone keeps its own choice", SwipeRightVerb.FAVOURITE, stored(SourceKind.PHONE_LIBRARY).swipeRightVerb)
    }

    @Test
    fun browsingACamera_opensOnTheCameraTab() {
        host(SourceKind.CAMERA_MTP)
        clickOption("swipe_right", R.string.chip_move)
        assertEquals(SwipeRightVerb.MOVE, stored(SourceKind.CAMERA_MTP).swipeRightVerb)
        assertEquals(SwipeRightVerb.COPY, stored(SourceKind.REMOVABLE).swipeRightVerb)
    }

    @Test
    fun browsingTheCloud_opensOnThePhoneTab_whoseSettingsItFollows() {
        host(SourceKind.CLOUD_OTHER)
        clickOption("swipe_right", R.string.chip_copy)
        assertEquals(SwipeRightVerb.COPY, stored(SourceKind.PHONE_LIBRARY).swipeRightVerb)
    }

    @Test
    fun selectionLocation_explainsDefault_andCanGoBackToIt() {
        host(SourceKind.PHONE_LIBRARY)
        runBlocking { repository.setPhoneCollectionUri("content://com.android.externalstorage.documents/tree/primary%3APicked") }
        rule.waitForIdle()

        rule.onNodeWithTag("settings_row_selection_location").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, count("folder_target_dialog"))
        val explanation = context.getString(R.string.setting_summary_in_source_folder, PhotoFolders.SELECTION)
        assertEquals(1, rule.onAllNodes(hasText(explanation), useUnmergedTree = true).fetchSemanticsNodes().size)

        rule.onNodeWithTag("folder_target_default").performClick()
        rule.waitForIdle()
        assertEquals(0, count("folder_target_dialog"))
        assertNull(runBlocking { repository.phoneCollectionUri.first() })
    }

    @Test
    fun leftSwipeFolder_explainsItsOwnDefault_andCancelChangesNothing() {
        host(SourceKind.PHONE_LIBRARY)
        clickOption("swipe_left", R.string.chip_copy)

        rule.onNodeWithTag("settings_row_left_folder").performScrollTo().performClick()
        rule.waitForIdle()
        val explanation = context.getString(R.string.setting_summary_in_source_folder, PhotoFolders.LEFT_SWIPE)
        assertEquals(1, rule.onAllNodes(hasText(explanation), useUnmergedTree = true).fetchSemanticsNodes().size)

        rule.onNodeWithTag("folder_target_cancel").performClick()
        rule.waitForIdle()
        assertEquals(0, count("folder_target_dialog"))
        assertNull(runBlocking { repository.phoneLeftSwipeUri.first() })
    }
}
