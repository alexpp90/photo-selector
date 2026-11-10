package com.phototok.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.photoselector.core.Requirement
import com.photoselector.core.storage.SourceKind
import com.phototok.R
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.data.repository.dataStore
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.SettingsViewModel
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * A custom destination reads as its path, in German, on a 360 dp phone at font scale 2.0: the
 * Settings row keeps it on one line (middle ellipsis, so the volume and the folder stay), clear of
 * its Reset button, and the folder dialog shows it whole inside the dialog.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w360dp-h640dp-xxhdpi", fontScale = 2.0f)
class SettingsFolderPathTextLoadTest {

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

    private fun host() {
        val viewModel = SettingsViewModel(repository, mockk<ImageRepository>(relaxed = true))
        rule.setContent { PhotoTokTheme { SettingsScreen(viewModel = viewModel, sourceKind = SourceKind.PHONE_LIBRARY) } }
        rule.waitForIdle()
    }

    /** DataStore writes land on an IO thread; idle the main looper between checks (see SettingsFolderAndSourceTabTest). */
    private fun awaitStored(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            rule.waitForIdle()
            if (condition()) return
            assertTrue("settings write not seen within 5 s", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }

    private fun inRow(text: String) =
        hasText(text) and hasAnyAncestor(hasTestTag("settings_row_selection_location"))

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.20")
    fun aLongCustomPath_staysOneLineInTheRow_andWholeInTheDialog() {
        // A deep folder on the SD card: far wider than the row at font scale 2.0.
        val uri = "content://com.android.externalstorage.documents/tree/" +
            "1A2B-3C4D%3ADCIM%2FUrlaub%202026%2FBerge%20und%20Seen%2FAusgew%C3%A4hlte%20Bilder"
        runBlocking { repository.setPhoneCollectionUri(uri) }
        awaitStored { runBlocking { repository.phoneCollectionUri.first() } == uri }
        host()

        val path = "SD / USB › DCIM › Urlaub 2026 › Berge und Seen › Ausgewählte Bilder"
        val summary = rule.onAllNodes(inRow(path), useUnmergedTree = true).onFirst()
        summary.performScrollTo()
        rule.waitForIdle()
        val node = summary.fetchSemanticsNode()
        val layout = layoutOf(node)
        assertEquals("the path is one line in the row", 1, layout.lineCount)
        assertTrue("so long a path is ellipsized, not clipped", layout.isLineEllipsized(0))
        assertTrue("'Custom' is gone", rule.onAllNodes(inRow(context.getString(R.string.setting_summary_custom)), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        val row = rule.onAllNodes(hasTestTag("settings_row_selection_location"), useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
        val reset = rule.onAllNodes(inRow(context.getString(R.string.action_reset)), useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
        val box = node.boundsInRoot
        assertTrue("the path stays inside its row", box.left >= row.left && box.right <= row.right && box.bottom <= row.bottom)
        assertTrue("the path ends before Reset (${box.right} > ${reset.left})", box.right <= reset.left)

        // The dialog shows the whole path, wrapped, inside the dialog.
        // Tap the path, not the row's centre, which a wide "Zurücksetzen" covers at this font scale.
        summary.performClick()
        rule.waitForIdle()
        val dialog = rule.onAllNodes(hasTestTag("folder_target_dialog"), useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
        val detailNode = rule.onAllNodes(hasText(path) and hasAnyAncestor(hasTestTag("folder_target_choose")), useUnmergedTree = true)
            .onFirst().fetchSemanticsNode()
        assertTrue("the whole path is drawn", !layoutOf(detailNode).isLineEllipsized(layoutOf(detailNode).lineCount - 1))
        val choose = rule.onAllNodes(hasTestTag("folder_target_choose"), useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
        assertTrue("Choose folder ≥ 48 dp tall", with(rule.density) { choose.height.toDp() } >= 48.dp)
        assertTrue("Choose folder inside the dialog", choose.left >= dialog.left && choose.right <= dialog.right)
    }

    @Test
    @Requirement("REQ-TOK-UX.05")
    fun theInternalStorageRoot_isNamedInGerman() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FKeepers"
        runBlocking { repository.setPhoneCollectionUri(uri) }
        awaitStored { runBlocking { repository.phoneCollectionUri.first() } == uri }
        host()

        val path = "Telefon › Pictures › Keepers"
        assertEquals(1, rule.onAllNodes(inRow(path), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(
            "no English volume name in a German UI",
            rule.onAllNodes(hasText("Internal storage", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }
}
