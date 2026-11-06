package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.domain.FirstRunHint
import com.phototok.domain.UiText
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.FirstRunHintUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A first-run hint is drawn only over the screen it explains (REQ-TOK-GESTURE.05). Lab
 * 2026-11-06 (`2026-11-06-explore-tok`, step 26): the double-tap hint stayed in the state after
 * "Change folder" and covered the landing's Pro-mode and recent-folder rows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ScreenFirstRunHintTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val zoomHint = FirstRunHintUi(
        hint = FirstRunHint.DOUBLE_TAP_ZOOM,
        title = UiText.of(R.string.hint_title_double_tap_zoom),
    )

    private val mismatchHint = FirstRunHintUi(
        hint = FirstRunHint.FILTER_MISMATCH,
        title = UiText.of(R.string.hint_title_filter_raw),
        message = UiText.of(R.string.hint_body_filter_mostly_jpeg),
    )

    private fun hintCards() = rule.onAllNodes(hasTestTag("first_run_hint"), useUnmergedTree = true)

    @Test
    @Requirement("REQ-TOK-GESTURE.05")
    fun `the hint card leaves with the feed even while the hint is still in the state`() {
        var feedShown by mutableStateOf(true)
        rule.setContent {
            PhotoTokTheme {
                ScreenFirstRunHint(
                    hint = zoomHint,
                    feedShown = feedShown,
                    filterHidesWholeFolder = false,
                    onDismiss = {},
                )
            }
        }
        hintCards().assertCountEquals(1)

        feedShown = false // "Change folder": the landing replaces the feed
        rule.waitForIdle()

        hintCards().assertCountEquals(0)
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.05")
    fun `no hint card is composed on the landing`() {
        rule.setContent {
            PhotoTokTheme {
                ScreenFirstRunHint(
                    hint = mismatchHint,
                    feedShown = false,
                    filterHidesWholeFolder = false,
                    onDismiss = {},
                )
            }
        }

        hintCards().assertCountEquals(0)
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.05")
    fun `the filter-mismatch hint stays over a folder the filter hides entirely`() {
        rule.setContent {
            PhotoTokTheme {
                ScreenFirstRunHint(
                    hint = mismatchHint,
                    feedShown = false,
                    filterHidesWholeFolder = true,
                    onDismiss = {},
                )
            }
        }

        hintCards().assertCountEquals(1)
    }

    @Test
    fun `only the filter-mismatch hint explains an empty filtered folder`() {
        FirstRunHint.entries.forEach { hint ->
            assertTrue(hint.name, firstRunHintFitsScreen(hint, feedShown = true, filterHidesWholeFolder = false))
            assertFalse(hint.name, firstRunHintFitsScreen(hint, feedShown = false, filterHidesWholeFolder = false))
            assertTrue(
                hint.name,
                firstRunHintFitsScreen(hint, feedShown = false, filterHidesWholeFolder = true) ==
                    (hint == FirstRunHint.FILTER_MISMATCH),
            )
        }
        assertFalse(firstRunHintFitsScreen(null, feedShown = true, filterHidesWholeFolder = true))
    }
}
