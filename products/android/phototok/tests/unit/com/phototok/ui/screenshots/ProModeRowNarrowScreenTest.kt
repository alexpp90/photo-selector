package com.phototok.ui.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.phototok.ui.components.ProModeRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Pro mode row on the narrowest supported phone (360 dp): English and German, font scale 1.0
 * and 2.0, switched off and on. Its label wraps instead of clipping, never breaks inside the word
 * "Profi-Modus", and both targets stay at least 48 dp (REQ-TOK-UX.05).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-port-xxhdpi")
class ProModeRowNarrowScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val host by lazy { MatrixHost(rule, VARIANTS.first()) }
    private val density get() = RuntimeEnvironment.getApplication().resources.displayMetrics.density
    private val screen
        get() = RuntimeEnvironment.getApplication().resources.displayMetrics.let {
            Rect(0f, 0f, it.widthPixels.toFloat(), it.heightPixels.toFloat())
        }

    private fun everyVariant(pro: Boolean) {
        rule.mainClock.autoAdvance = false
        host.setContent {
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                ProModeRow(
                    proMode = pro,
                    onProModeChange = {},
                    onHelp = {},
                    toggleTag = "pro_row_toggle",
                    helpTag = "pro_row_help",
                )
            }
        }
        val verifier = TextLoadChecks(rule, screen, density)
        for (variant in VARIANTS) {
            host.show(variant)
            verifier.assertHasClickableControls("pro_mode_row", 2)
            verifier.assertTouchTargets(variant, "pro_mode_row")
            verifier.assertNoTextOverflow(variant, "pro_mode_row")
            verifier.assertInsideScreen(variant, "pro_mode_row")
        }
    }

    @Test fun off_fitsEveryVariant() = everyVariant(pro = false)

    @Test fun on_fitsEveryVariant() = everyVariant(pro = true)
}
