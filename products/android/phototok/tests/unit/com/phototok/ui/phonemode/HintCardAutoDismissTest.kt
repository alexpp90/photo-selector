package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.domain.FirstRunHint
import com.phototok.domain.UiText
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.FirstRunHintUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F-TOK-031: tip and hint cards covered the photo and each needed a GOT IT tap. A hint card leaves
 * by itself (REQ-TOK-GESTURE.05: after 7 s, 12 s when it carries an action), so no tap is needed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class HintCardAutoDismissTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun dismissesAfter(hint: FirstRunHintUi, onOpenSettings: (() -> Unit)?, stillUpAt: Long, goneBy: Long) {
        var shown by mutableStateOf<FirstRunHintUi?>(hint)
        var dismissed = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PhotoTokTheme {
                FirstRunHintCard(
                    hint = shown,
                    onDismiss = { dismissed++; shown = null },
                    onOpenSettings = onOpenSettings,
                )
            }
        }
        rule.mainClock.advanceTimeBy(stillUpAt)
        rule.waitForIdle()
        assertEquals("the card is still up after $stillUpAt ms", 0, dismissed)
        rule.mainClock.advanceTimeBy(goneBy - stillUpAt)
        rule.waitForIdle()
        assertEquals("the card left by itself by $goneBy ms, with no tap", 1, dismissed)
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.05")
    fun aPlainHintLeavesBy7Seconds() = dismissesAfter(
        FirstRunHintUi(hint = FirstRunHint.DOUBLE_TAP_ZOOM, title = UiText.of(R.string.hint_title_double_tap_zoom)),
        onOpenSettings = null,
        stillUpAt = 5_000,
        goneBy = 7_500,
    )

    @Test
    @Requirement("REQ-TOK-GESTURE.05")
    fun aHintWithAnActionLeavesBy12Seconds() = dismissesAfter(
        FirstRunHintUi(hint = FirstRunHint.SWIPE_RIGHT, title = UiText.of(R.string.hint_title_swipe_right_copy, "Selection")),
        onOpenSettings = {},
        stillUpAt = 9_000,
        goneBy = 12_500,
    )
}
