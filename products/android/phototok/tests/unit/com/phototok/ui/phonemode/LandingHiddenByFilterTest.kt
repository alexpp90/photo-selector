package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.UiText
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F-TOK-086: a folder whose every photo the format filter hides says how many, names the filter
 * and offers Show all on the landing — a real ≥ 48 dp button — at 360 dp and font scale 2.0, in
 * English and German. A truly empty folder keeps "«folder»: no photos" and no Show all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-xhdpi")
class LandingHiddenByFilterTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val res get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private var showAll = 0

    private fun landing(message: UiText, hiddenBy: FileTypeFilter?) {
        rule.setContent {
            PhotoTokTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                    PhoneModeLanding(
                        sourceFolderUri = "content://tree/photos",
                        sourceFolderName = "DCIM",
                        collectionFolderName = "Selection",
                        isLoading = false,
                        emptyFolderMessage = message,
                        onSelectSource = {},
                        onStart = {},
                        hiddenByFilter = hiddenBy,
                        onShowAll = { showAll++ },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun card() = hasAnyAncestor(hasTestTag("empty_folder_warning_card"))

    private fun assertHiddenByFilterCard() {
        landing(UiText.of(R.string.empty_hidden_by_filter, 12), FileTypeFilter.RAW)
        rule.onAllNodes(hasText(res.getString(R.string.empty_hidden_by_filter, 12)) and card(), useUnmergedTree = true)
            .onFirst().performScrollTo()
        rule.onAllNodes(hasText(res.getString(R.string.chip_raw_only)) and card(), useUnmergedTree = true)
            .fetchSemanticsNodes().let { assertTrue("the active filter is named", it.isNotEmpty()) }
        val button = rule.onAllNodes(hasTestTag("empty_folder_show_all"), useUnmergedTree = true).onFirst()
        button.performScrollTo()
        val node = button.fetchSemanticsNode()
        val density = node.layoutInfo.density.density
        assertTrue("Show all is ≥ 48 dp wide: ${node.size}", node.size.width / density >= 47.5f)
        assertTrue("Show all is ≥ 48 dp high: ${node.size}", node.size.height / density >= 47.5f)
        assertEquals(androidx.compose.ui.semantics.Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        button.performClick()
        rule.waitForIdle()
        assertEquals("Show all lifts the filter", 1, showAll)
        val card = rule.onAllNodes(hasTestTag("empty_folder_warning_card"), useUnmergedTree = true).fetchSemanticsNodes().single()
        assertEquals(
            "TalkBack reads the card when it appears",
            androidx.compose.ui.semantics.LiveRegionMode.Polite,
            card.config.getOrNull(SemanticsProperties.LiveRegion),
        )
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.04", "REQ-TOK-UX.05")
    fun english_fontScale2() {
        assertEquals("12 hidden by filter", res.getString(R.string.empty_hidden_by_filter, 12))
        assertHiddenByFilterCard()
    }

    @Test
    @Config(qualifiers = "de-w360dp-h640dp-xhdpi")
    @Requirement("REQ-TOK-FEATURE.04", "REQ-TOK-UX.05")
    fun german_fontScale2() {
        assertEquals("12 durch Filter ausgeblendet", res.getString(R.string.empty_hidden_by_filter, 12))
        assertHiddenByFilterCard()
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.04")
    fun aTrulyEmptyFolderHasNoShowAll() {
        landing(UiText.of(R.string.empty_folder, "DCIM"), hiddenBy = null)
        assertTrue(
            rule.onAllNodes(hasText(res.getString(R.string.empty_folder, "DCIM")), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(rule.onAllNodes(hasTestTag("empty_folder_show_all"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
