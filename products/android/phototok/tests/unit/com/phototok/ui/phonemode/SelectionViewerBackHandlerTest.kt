package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** System Back in the Selection viewer closes it and leaves the feed (and its photo) untouched. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class SelectionViewerBackHandlerTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun systemBackClosesTheViewer() {
        var viewerOpen by mutableStateOf(true)
        composeTestRule.setContent {
            SelectionViewerBackHandler(enabled = viewerOpen, onClose = { viewerOpen = false })
        }

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertEquals(false, viewerOpen)
    }

    @Test
    fun systemBackIsNotInterceptedWhileTheViewerIsClosed() {
        var closes = 0
        composeTestRule.setContent {
            SelectionViewerBackHandler(enabled = false, onClose = { closes++ })
        }

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        assertEquals(0, closes)
    }
}
