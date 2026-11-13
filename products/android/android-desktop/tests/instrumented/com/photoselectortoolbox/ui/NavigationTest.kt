package com.photoselectortoolbox.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
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
 * Instrumented tests for navigation between screens.
 * Verifies that tapping navigation items shows the correct screen content.
 *
 * These tests run on an emulator or real Android device.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    @Test
    fun selectorScreenIsDefaultDestination() {
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText(str(R.string.empty_select_folder), substring = false, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithText("Select photos folder", substring = false, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()
        val hasDesktopLabel = composeRule.onAllNodesWithText(str(R.string.empty_select_folder), substring = false, ignoreCase = true)
            .fetchSemanticsNodes().isNotEmpty()
        if (hasDesktopLabel) {
            composeRule.onNodeWithText(str(R.string.dialog_body_choose_shoot_folder), substring = true, ignoreCase = true)
                .assertIsDisplayed()
        } else {
            composeRule.onNodeWithText("Select photos folder", substring = false, ignoreCase = true)
                .assertIsDisplayed()
        }
    }

    @Test
    fun navigateToStatistics() {
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText(str(R.string.empty_select_folder), substring = false, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithText("Select photos folder", substring = false, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()
        val isDesktop = composeRule.onAllNodesWithText(str(R.string.empty_select_folder), substring = false, ignoreCase = true)
            .fetchSemanticsNodes().isNotEmpty()
        if (!isDesktop) {
            composeRule.onNodeWithText("Select photos folder", substring = false, ignoreCase = true)
                .assertIsDisplayed()
            return
        }

        // Tap on the Statistics nav item
        composeRule.onNodeWithTag("nav_statistics").performClick()

        composeRule.waitForIdle()

        // Statistics screen should show its empty state description
        composeRule.onNodeWithText("Select a folder containing photos to view EXIF statistics.", substring = true, ignoreCase = true)
            .assertIsDisplayed()
    }

    @Test
    fun navigateToDuplicates() {
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText(str(R.string.empty_select_folder), substring = false, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithText("Select photos folder", substring = false, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()
        val isDesktop = composeRule.onAllNodesWithText(str(R.string.empty_select_folder), substring = false, ignoreCase = true)
            .fetchSemanticsNodes().isNotEmpty()
        if (!isDesktop) {
            composeRule.onNodeWithText("Select photos folder", substring = false, ignoreCase = true)
                .assertIsDisplayed()
            return
        }

        // Dupes sits behind Tools ([USER 2026-11-12] decision #18).
        composeRule.onNodeWithTag("nav_tools").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_duplicates").performClick()

        composeRule.waitForIdle()

        // Duplicates screen should show its empty state description
        composeRule.onNodeWithText("Select a folder to scan for duplicate images.", substring = true, ignoreCase = true)
            .assertIsDisplayed()
    }

    @Test
    fun navigateToSettings() {
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText(str(R.string.empty_select_folder), substring = false, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithText("Select photos folder", substring = false, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()
        val isDesktop = composeRule.onAllNodesWithText(str(R.string.empty_select_folder), substring = false, ignoreCase = true)
            .fetchSemanticsNodes().isNotEmpty()
        if (!isDesktop) {
            composeRule.onNodeWithText("Select photos folder", substring = false, ignoreCase = true)
                .assertIsDisplayed()
            return
        }

        composeRule.onNodeWithTag("nav_settings").performClick()

        composeRule.waitForIdle()

        // Settings screen should show unique settings text
        composeRule.onNodeWithText(str(R.string.label_analysis_threads), substring = true, ignoreCase = true)
            .assertExists()
    }

    @Test
    fun navigateBackToSelector() {
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText(str(R.string.empty_select_folder), substring = false, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithText("Select photos folder", substring = false, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()
        val isDesktop = composeRule.onAllNodesWithText(str(R.string.empty_select_folder), substring = false, ignoreCase = true)
            .fetchSemanticsNodes().isNotEmpty()
        if (!isDesktop) {
            composeRule.onNodeWithText("Select photos folder", substring = false, ignoreCase = true)
                .assertIsDisplayed()
            return
        }

        // Navigate away first
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()

        // Navigate back to Selector
        composeRule.onNodeWithTag("nav_selector").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(str(R.string.dialog_body_choose_shoot_folder), substring = true, ignoreCase = true)
            .assertIsDisplayed()
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
