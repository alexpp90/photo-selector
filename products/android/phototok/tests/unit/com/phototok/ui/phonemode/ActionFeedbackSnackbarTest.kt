package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.domain.UiText
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.ActionFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * After a delete the feed snackbar says so and offers Undo, which restores the photo
 * ([USER 2026-10-24], review JP-UX-4). Other feedback has no action.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ActionFeedbackSnackbarTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val res get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun host(initial: ActionFeedback, onUndoProOff: () -> Unit = {}, onUndo: () -> Unit): () -> Int {
        var shown = 0
        rule.setContent {
            PhotoTokTheme {
                val hostState = remember { SnackbarHostState() }
                var feedback by remember { mutableStateOf<ActionFeedback?>(initial) }
                ActionFeedbackSnackbar(
                    feedback = feedback,
                    hostState = hostState,
                    onShown = { shown++; feedback = null },
                    onUndoDelete = onUndo,
                    onUndoProOff = onUndoProOff,
                )
                // The feed's own host, as the screen draws it.
                FeedSnackbarHost(hostState = hostState, bottomBarHeight = 0.dp)
            }
        }
        return { shown }
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.18")
    fun undoOnTheDeleteSnackbarRestores() {
        var undone = 0
        host(ActionFeedback(UiText.of(R.string.snackbar_photo_deleted), offersUndoDelete = true)) { undone++ }
        val undo = res.getString(R.string.snackbar_action_undo)
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(undo), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(
            rule.onAllNodes(hasText(res.getString(R.string.snackbar_photo_deleted)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        rule.onAllNodes(hasText(undo)).onFirst().performClick()
        rule.waitUntil(5_000) { undone == 1 }
        assertEquals(1, undone)
    }

    /** Undo stays a 48 dp target with its whole word, German at font scale 2.0 (REQ-TOK-UX.02). */
    @Test
    @Config(qualifiers = "de-w360dp-h640dp-xhdpi", fontScale = 2.0f)
    @Requirement("REQ-TOK-FEATURE.18", "REQ-TOK-UX.02")
    fun undoIsA48dpTarget_germanFontScale2() {
        host(ActionFeedback(UiText.of(R.string.snackbar_photo_deleted), offersUndoDelete = true)) {}
        val undo = res.getString(R.string.snackbar_action_undo)
        assertEquals("Rückgängig", undo)
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(undo), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val button = rule.onAllNodes(hasTestTag("feed_snackbar_action")).fetchSemanticsNodes().single()
        assertTrue("the tagged node is the clickable one", button.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
        val density = button.layoutInfo.density.density
        assertTrue("Undo is ${button.size.width / density} dp wide", button.size.width / density >= 47.5f)
        assertTrue("Undo is ${button.size.height / density} dp tall", button.size.height / density >= 47.5f)
    }

    @Test
    fun otherFeedbackOffersNoUndo() {
        val shown = host(ActionFeedback(UiText.of(R.string.snackbar_not_copied))) { error("no undo here") }
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText(res.getString(R.string.snackbar_not_copied)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            rule.onAllNodes(hasText(res.getString(R.string.snackbar_action_undo)), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        rule.mainClock.advanceTimeBy(10_000)
        rule.waitUntil(5_000) { shown() == 1 }
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.20")
    fun undoOnTheProOffSnackbarTurnsProBackOn_notADeleteRestore() {
        var proBackOn = 0
        host(
            ActionFeedback(UiText.of(R.string.snackbar_pro_off_left_delete), offersUndoProOff = true),
            onUndoProOff = { proBackOn++ },
        ) { error("not a delete") }
        val undo = res.getString(R.string.snackbar_action_undo)
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(undo), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(
            rule.onAllNodes(hasText(res.getString(R.string.snackbar_pro_off_left_delete)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        rule.onAllNodes(hasText(undo)).onFirst().performClick()
        rule.waitUntil(5_000) { proBackOn == 1 }
    }
}
