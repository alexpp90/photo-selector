package com.photoselectortoolbox.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselectortoolbox.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import com.photoselectortoolbox.str
import com.photoselectortoolbox.R

/**
 * Smoke test: verifies the app launches without crashing and
 * shows expected UI elements. Runs on emulator or real device.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    @Test
    fun appLaunchesWithoutCrash() {
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithText(str(R.string.empty_select_folder), substring = false, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithText("Select photos folder", substring = false, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val hasDesktopLabel = composeRule.onAllNodesWithText(str(R.string.empty_select_folder), substring = false, ignoreCase = true)
            .fetchSemanticsNodes().isNotEmpty()
        dismissGestureTutorialIfShown()
        if (hasDesktopLabel) {
            composeRule.onNodeWithText(str(R.string.empty_select_folder), substring = false, ignoreCase = true)
                .assertIsDisplayed()
        } else {
            composeRule.onNodeWithText("Select photos folder", substring = false, ignoreCase = true)
                .assertIsDisplayed()
        }
    }

    @Test
    fun systemBars_navigationBarsAreConfiguredTransient() {
        composeRule.waitForIdle()
        val activity = composeRule.activity
        val insetsController = androidx.core.view.WindowCompat.getInsetsController(
            activity.window,
            activity.window.decorView,
        )
        org.junit.Assert.assertEquals(
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            insetsController.systemBarsBehavior,
        )
    }

    private fun dismissGestureTutorialIfShown() {
        if (composeRule.onAllNodes(hasText(str(R.string.action_got_it), ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onAllNodes(hasText(str(R.string.action_got_it), ignoreCase = true), useUnmergedTree = true).onFirst().performClick()
            composeRule.waitUntil(timeoutMillis = 15000) {
                composeRule.onAllNodes(hasText(str(R.string.action_got_it), ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
        }
        if (composeRule.onAllNodes(hasText(str(R.string.title_gestures), ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onAllNodes(hasText(str(R.string.title_gestures), ignoreCase = true), useUnmergedTree = true).onFirst().performClick()
        }
    }
}
