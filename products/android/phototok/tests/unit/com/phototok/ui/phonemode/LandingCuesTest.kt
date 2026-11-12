package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.data.repository.SettingsRepository
import com.phototok.domain.LandingStatsLine
import com.phototok.domain.PhotoFolders
import com.phototok.domain.StatFigures
import com.phototok.domain.SwipeRightOutcome
import com.phototok.domain.UiText
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The landing says, before the first swipe, what swipe right does per source (★ on the phone, a
 * copy to the phone from a card; REQ-TOK-FEATURE.20/.21), shows the stats line only when there are
 * stats (REQ-TOK-FEATURE.19), and an empty folder with videos says they are hidden
 * (REQ-TOK-FEATURE.04). EN and DE, spoken names from the resources.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
class LandingCuesTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val res get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    /** A fresh install's real effective settings (Casual). */
    private val casualOutcomes = SwipeRightOutcome.perKind(SettingsRepository.phoneSettingsOf(emptyPreferences()))

    private fun landing(stats: LandingStatsLine? = null, empty: UiText? = null, videos: Int = 0) {
        rule.setContent {
            PhotoTokTheme {
                PhoneModeLanding(
                    sourceFolderUri = if (empty != null) "content://tree/photos" else null,
                    sourceFolderName = "DCIM",
                    collectionFolderName = PhotoFolders.SELECTION,
                    isLoading = false,
                    emptyFolderMessage = empty,
                    onSelectSource = {},
                    onStart = {},
                    stats = stats,
                    sourceOutcomes = casualOutcomes,
                    hiddenVideoCount = videos,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun spoken(tag: String): String? =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
            ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()

    private fun assertCardCues() {
        landing()
        val phone = spoken("landing_phone_camera_outcome")
        val card = spoken("landing_sd_card_outcome")
        assertEquals(res.getString(R.string.cd_swipe_right_outcome_favourite), phone)
        assertEquals(res.getString(R.string.cd_swipe_right_outcome_phone), card)
        assertNotEquals("the SD card's cue differs from the phone's", phone, card)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.20", "REQ-TOK-FEATURE.21")
    fun cardCopiesToThePhone_phoneStars_english() {
        assertEquals("Swipe right: copy to phone", res.getString(R.string.cd_swipe_right_outcome_phone))
        assertCardCues()
    }

    @Test
    @Config(qualifiers = "de-w393dp-h851dp-xxhdpi")
    @Requirement("REQ-TOK-FEATURE.20", "REQ-TOK-FEATURE.21", "REQ-TOK-UX.05")
    fun cardCopiesToThePhone_phoneStars_german() {
        assertEquals("Rechts wischen: aufs Handy kopieren", res.getString(R.string.cd_swipe_right_outcome_phone))
        assertCardCues()
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.19")
    fun theStatsLineIsShownWhenThereAreStats_andNotOnAFreshInstall() {
        landing(stats = LandingStatsLine(isSession = true, figures = StatFigures(84, 6, 312_000_000)))
        val line = spoken("landing_stats_session")
        assertTrue("session line: $line", line?.startsWith("This session: 84 reviewed, 6 selected") == true)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.19")
    fun aFreshInstallHasNoStatsLine() {
        landing(stats = null)
        assertTrue(rule.onAllNodes(hasTestTag("landing_stats_total"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasTestTag("landing_stats_session"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.04")
    fun anEmptyFolderWithVideosSaysTheyAreHidden() {
        landing(empty = UiText.of(R.string.empty_folder, "DCIM"), videos = 2)
        val note = rule.onAllNodes(hasTestTag("empty_folder_videos_hidden"), useUnmergedTree = true).onFirst()
        note.performScrollTo()
        val text = note.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        assertEquals(res.getQuantityString(R.plurals.empty_videos_hidden, 2, 2), text)
        assertEquals("2 videos hidden", text)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.20")
    fun theProHelpSaysCardKeepersStayOnTheCard() {
        landing()
        rule.onAllNodes(hasTestTag("landing_pro_mode_help"), useUnmergedTree = true).onFirst().performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(res.getString(R.string.cd_pro_card_selection), spoken("pro_mode_help_card_selection"))
    }
}
