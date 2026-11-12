package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.photoselector.core.Requirement
import com.phototok.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The "Card done" sheet of the real screen says how many photos of the card were copied off it
 * (REQ-TOK-FEATURE.15): two copies to the phone read "2 copied", a card nothing left reads
 * "0 copied", in English and German, and TalkBack reads the whole row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-port-xxhdpi")
class CardDoneSheetCountTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val screen = PhoneScreenHarness(rule)
    private val res get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun sheetAfterCopies(copies: Int) {
        // Swipe right copies the card's photos to a folder on the phone.
        screen.sourceSettings = screen.sourceSettings.copy(collectionUri = screen.phoneTree)
        screen.launch()
        screen.openFolder(PhoneScreenHarness.PHOTOS, folder = screen.sdTree)
        repeat(copies) {
            rule.runOnUiThread { screen.viewModel.addToCollection() }
            rule.waitForIdle()
        }
        rule.runOnUiThread { screen.viewModel.openCardDoneSheet() }
        rule.waitForIdle()
    }

    private fun assertCount(count: Int) {
        val row = rule.onAllNodes(hasTestTag("card_copied_count"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("one copied row on the sheet", 1, row.size)
        assertEquals(
            listOf(res.getString(R.string.cd_card_copied, count)),
            row.single().config.getOrNull(SemanticsProperties.ContentDescription),
        )
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText(res.getString(R.string.label_card_copied, count)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.15")
    fun twoCopiedToThePhone_english() {
        assertEquals("2 copied", res.getString(R.string.label_card_copied, 2))
        sheetAfterCopies(2)
        assertCount(2)
    }

    @Test
    @Config(qualifiers = "de-w393dp-h851dp-port-xxhdpi")
    @Requirement("REQ-TOK-FEATURE.15", "REQ-TOK-UX.05")
    fun twoCopiedToThePhone_german() {
        assertEquals("2 kopiert", res.getString(R.string.label_card_copied, 2))
        sheetAfterCopies(2)
        assertCount(2)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.15")
    fun nothingCopied_saysZero() {
        sheetAfterCopies(0)
        assertCount(0)
        assertTrue(screen.viewModel.uiState.value.showCardDoneSheet)
    }
}
