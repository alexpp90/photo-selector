package com.phototok.ui.phonemode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phototok.R
import com.phototok.domain.FirstRunHint
import com.phototok.domain.UiText
import com.phototok.plural
import com.phototok.str
import com.phototok.ui.components.ViewerBottomBar
import com.phototok.viewmodel.FirstRunHintUi
import com.phototok.viewmodel.ReloadPrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks

@RunWith(AndroidJUnit4::class)
class PhoneModeScreenDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun enableAccessibilityChecks() {
        composeTestRule.enableAccessibilityChecks()
    }

    @Test
    fun directDeleteDialog_showsConsequenceReasonAndDontAskChoice() {
        var confirmed = false
        var dismissed = false

        composeTestRule.setContent {
            DirectDeleteDialog(
                onConfirm = { confirmed = true },
                onDismiss = { dismissed = true },
            )
        }

        composeTestRule.onNodeWithText(str(R.string.dialog_title_direct_delete)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.dialog_body_no_trash)).assertIsDisplayed()
        // "Don't ask" replaces the old "Turn off this prompt in Settings." line.
        composeTestRule.onNodeWithTag("direct_delete_dont_ask").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.label_dont_ask)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.action_cancel)).assertIsDisplayed()

        composeTestRule.onNodeWithTag("direct_delete_confirm_button").performClick()
        assertTrue("Confirm button must trigger deletion", confirmed)
        assertFalse("Cancel must not have been triggered", dismissed)
    }

    @Test
    fun directDeleteDialog_dontAskIsReportedWithDelete() {
        var dontAsk: Boolean? = null

        composeTestRule.setContent {
            DirectDeleteDialog(onConfirm = { dontAsk = it }, onDismiss = {})
        }

        composeTestRule.onNodeWithTag("direct_delete_dont_ask").performClick()
        composeTestRule.onNodeWithTag("direct_delete_confirm_button").performClick()
        assertTrue("Delete with the choice ticked reports it", dontAsk == true)
    }

    @Test
    fun directDeleteDialog_cancelDismissesWithoutDeleting() {
        var confirmed = false
        var dismissed = false

        composeTestRule.setContent {
            DirectDeleteDialog(
                onConfirm = { confirmed = true },
                onDismiss = { dismissed = true },
            )
        }

        composeTestRule.onNodeWithTag("direct_delete_cancel_button").performClick()
        assertTrue("Dismiss button must trigger dismiss callback", dismissed)
        assertFalse("Confirm must not be triggered on cancel", confirmed)
    }

    @Test
    fun firstRunHintCard_displaysTitleAndMessageAndDismissesOnClick() {
        var dismissed = false
        val hintUi = FirstRunHintUi(
            hint = FirstRunHint.SWIPE_LEFT_DELETE,
            title = UiText.of(R.string.hint_title_swipe_left_delete),
            message = UiText.of(R.string.hint_body_raw_jpeg_pairs),
            id = 1,
        )

        composeTestRule.setContent {
            FirstRunHintCard(
                hint = hintUi,
                onDismiss = { dismissed = true },
            )
        }

        composeTestRule.onNodeWithTag("first_run_hint").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.hint_title_swipe_left_delete)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.hint_body_raw_jpeg_pairs)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("first_run_hint_dismiss").assertIsDisplayed()

        composeTestRule.onNodeWithTag("first_run_hint_dismiss").performClick()
        assertTrue("Dismiss button must invoke onDismiss callback", dismissed)
    }

    @Test
    fun firstRunHintCard_configurableHint_offersSettingsActionInsteadOfText() {
        var settingsOpened = false
        var dismissed = false
        val hintUi = FirstRunHintUi(
            hint = FirstRunHint.SWIPE_RIGHT,
            title = UiText.of(R.string.hint_title_swipe_right_copy, "Selection"),
            id = 3,
        )

        composeTestRule.setContent {
            FirstRunHintCard(
                hint = hintUi,
                onDismiss = { dismissed = true },
                onOpenSettings = { settingsOpened = true },
            )
        }

        composeTestRule.onNodeWithText(str(R.string.hint_title_swipe_right_copy, "Selection")).assertIsDisplayed()
        composeTestRule.onNodeWithTag("first_run_hint_action").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.action_settings)).assertIsDisplayed()

        composeTestRule.onNodeWithTag("first_run_hint_action").performClick()
        assertTrue("Settings action must open Settings", settingsOpened)
        assertTrue("Settings action must also dismiss the hint", dismissed)
    }

    @Test
    fun firstRunHintCard_withAction_displaysActionAndInvokesCallbacks() {
        var actionInvoked = false
        var dismissed = false
        val hintUi = FirstRunHintUi(
            hint = FirstRunHint.FILTER_MISMATCH,
            title = UiText.of(R.string.hint_title_filter_raw),
            message = UiText.of(R.string.hint_body_filter_mostly_jpeg),
            actionLabel = UiText.of(R.string.action_show_all),
            onAction = { actionInvoked = true },
            id = 2,
        )

        composeTestRule.setContent {
            FirstRunHintCard(
                hint = hintUi,
                onDismiss = { dismissed = true },
            )
        }

        composeTestRule.onNodeWithTag("first_run_hint").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.hint_title_filter_raw)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("first_run_hint_action").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.action_show_all)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("first_run_hint_dismiss").assertIsDisplayed()

        composeTestRule.onNodeWithTag("first_run_hint_action").performClick()
        assertTrue("Action button must invoke onAction callback", actionInvoked)
        assertTrue("Action button click must also dismiss the hint", dismissed)
    }

    @Test
    fun rawJpegSuggestionDialog_displaysAllChoicesClearly() {
        composeTestRule.setContent {
            RawJpegSuggestionDialog(
                visible = true,
                onFilterRaw = {},
                onFilterJpg = {},
                onEnableMoveRelatedFiles = {},
                onDismiss = {},
            )
        }

        composeTestRule.onAllNodesWithTag("raw_jpeg_suggestion_dialog").onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithText(str(R.string.dialog_title_raw_jpeg)).onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithText(str(R.string.action_merge_pairs)).onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("one_per_shot_button").onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithText(str(R.string.action_link_both)).onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithText(str(R.string.action_dont_change)).onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("apply_both_button").onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("filter_raw_button").onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("filter_jpg_button").onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("dont_change_settings_button").onFirst().assertIsDisplayed()
    }

    @Test
    fun rawJpegSuggestionDialog_dontChangeSettingsOption_invokesDismissCallback() {
        var dismissed = false
        var filterRaw = false
        var filterJpg = false
        var moveRelated = false

        composeTestRule.setContent {
            RawJpegSuggestionDialog(
                visible = true,
                onFilterRaw = { filterRaw = true },
                onFilterJpg = { filterJpg = true },
                onEnableMoveRelatedFiles = { moveRelated = true },
                onDismiss = { dismissed = true },
            )
        }

        composeTestRule.onAllNodesWithTag("dont_change_settings_button").onFirst().performClick()
        assertTrue("Don't change settings must invoke dismiss callback", dismissed)
        assertFalse("Filter RAW must not be invoked on dismiss", filterRaw)
        assertFalse("Filter JPG must not be invoked on dismiss", filterJpg)
        assertFalse("Move related files must not be invoked on dismiss", moveRelated)
    }

    @Test
    fun rawJpegSuggestionDialog_linkActionsOption_invokesCallback() {
        var moveRelated = false

        composeTestRule.setContent {
            RawJpegSuggestionDialog(
                visible = true,
                onFilterRaw = {},
                onFilterJpg = {},
                onEnableMoveRelatedFiles = { moveRelated = true },
                onDismiss = {},
            )
        }

        composeTestRule.onAllNodesWithTag("apply_both_button").onFirst().performClick()
        assertTrue("Link actions option must invoke onEnableMoveRelatedFiles callback", moveRelated)
    }

    @Test
    fun rawJpegSuggestionDialog_filterButtons_invokeCallbacks() {
        var filterRaw = false
        var filterJpg = false

        composeTestRule.setContent {
            RawJpegSuggestionDialog(
                visible = true,
                onFilterRaw = { filterRaw = true },
                onFilterJpg = { filterJpg = true },
                onEnableMoveRelatedFiles = {},
                onDismiss = {},
            )
        }

        composeTestRule.onAllNodesWithTag("filter_raw_button").onFirst().performClick()
        assertTrue("Filter RAW button must invoke onFilterRaw callback", filterRaw)

        composeTestRule.onAllNodesWithTag("filter_jpg_button").onFirst().performClick()
        assertTrue("Filter JPG button must invoke onFilterJpg callback", filterJpg)
    }

    @Test
    fun rawJpegSuggestionDialog_inLandscape_displaysOptionsAndIsClickable() {
        var clickedOption = false

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 640.dp, height = 360.dp)) {
                RawJpegSuggestionDialog(
                    visible = true,
                    onFilterRaw = {},
                    onFilterJpg = {},
                    onEnableMoveRelatedFiles = { clickedOption = true },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onAllNodesWithTag("raw_jpeg_suggestion_dialog").onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("apply_both_button").onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("apply_both_button").onFirst().performClick()
        assertTrue("Options must be clickable in landscape layout", clickedOption)
    }

    @Test
    fun rawJpegSuggestionDialog_withViewerBottomBar_notOccludedOrCovered() {
        var dismissed = false

        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                // Viewer bottom bar containing Sources (folder) and Selection (favorites/star)
                ViewerBottomBar(
                    canRevert = false,
                    onRevert = {},
                    onJumpToSelection = {},
                    onShare = {},
                    onGoToLanding = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // RAW + JPEG suggestion dialog
                RawJpegSuggestionDialog(
                    visible = true,
                    onFilterRaw = {},
                    onFilterJpg = {},
                    onEnableMoveRelatedFiles = {},
                    onDismiss = { dismissed = true },
                )
            }
        }

        // Verify the dialog is displayed
        composeTestRule.onAllNodesWithTag("raw_jpeg_suggestion_dialog").onFirst().assertIsDisplayed()

        // Verify bottom options of the dialog are displayed and interactable (not occluded by bottom bar)
        composeTestRule.onAllNodesWithTag("dont_change_settings_button").onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("dont_change_settings_button").onFirst().performClick()
        assertTrue("Don't change settings button must be clickable when bottom bar is present", dismissed)

        // Verify bottom bar also exists in background
        composeTestRule.onAllNodesWithTag("viewer_bottom_bar").onFirst().assertIsDisplayed()
    }

    @Test
    fun folderResumeDialog_withNewPhotos_showsCountOnlyAndTriggersCallbacks() {
        var startedWithNewest = false
        var continuedWhereLeftOff = false

        composeTestRule.setContent {
            FolderResumeDialog(
                prompt = ReloadPrompt(newCount = 5, firstNewIndex = 0, resumeIndex = 3, totalCount = 20),
                onStartWithNewest = { startedWithNewest = true },
                onContinueWhereLeftOff = { continuedWhereLeftOff = true },
            )
        }

        composeTestRule.onAllNodesWithTag("reload_jump_dialog").onFirst().assertIsDisplayed()
        composeTestRule.onNodeWithText(plural(R.plurals.dialog_title_new_photos, 5)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.action_show_new)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.action_stay)).assertIsDisplayed()

        composeTestRule.onAllNodesWithTag("reload_jump_confirm_button").onFirst().performClick()
        assertTrue("Show new button must trigger callback", startedWithNewest)
        assertFalse("Stay button must not be triggered", continuedWhereLeftOff)
    }

    @Test
    fun folderResumeDialog_singleNewPhoto_usesSingularAndStays() {
        var startedWithNewest = false
        var continuedWhereLeftOff = false

        composeTestRule.setContent {
            FolderResumeDialog(
                prompt = ReloadPrompt(newCount = 1, firstNewIndex = 0, resumeIndex = 7, totalCount = 15),
                onStartWithNewest = { startedWithNewest = true },
                onContinueWhereLeftOff = { continuedWhereLeftOff = true },
            )
        }

        composeTestRule.onNodeWithText(plural(R.plurals.dialog_title_new_photos, 1)).assertIsDisplayed()

        composeTestRule.onAllNodesWithTag("reload_jump_stay_button").onFirst().performClick()
        assertTrue("Stay button must trigger callback", continuedWhereLeftOff)
        assertFalse("Show new button must not be triggered", startedWithNewest)
    }

    @Test
    fun phoneModeLanding_proModeRow_togglesAndOpensHelp() {
        var pro = false

        composeTestRule.setContent {
            var on by remember { mutableStateOf(pro) }
            PhoneModeLanding(
                sourceFolderUri = null,
                sourceFolderName = "",
                collectionFolderName = "Selection",
                isLoading = false,
                onSelectSource = {},
                onSelectCollection = {},
                onStart = {},
                proMode = on,
                onProModeChange = { on = it; pro = it },
            )
        }

        composeTestRule.onNodeWithTag("landing_pro_mode_toggle").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("landing_pro_mode_toggle").performClick()
        assertTrue("The Pro mode row switches Pro mode on", pro)

        composeTestRule.onNodeWithTag("landing_pro_mode_help").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("pro_mode_help_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pro_mode_help_got_it").performClick()
        composeTestRule.onAllNodesWithTag("pro_mode_help_dialog").assertCountEquals(0)
    }

    @Test
    fun phoneModeLanding_missingGrantPrompt_displaysCardAndTriggersReselect() {
        var pickFolderClicked = false

        composeTestRule.setContent {
            PhoneModeLanding(
                sourceFolderUri = null,
                sourceFolderName = "Not selected",
                collectionFolderName = "Selection",
                isLoading = false,
                missingGrantPrompt = true,
                onReselectFolder = { pickFolderClicked = true },
                onSelectSource = {},
                onSelectCollection = {},
                onStart = {},
            )
        }

        // The landing content is a vertically scrolling column; the card sits below the hero
        // and mock cards, so on small emulators it is composed but off-screen until scrolled to.
        composeTestRule.onNodeWithTag("missing_grant_prompt_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.title_folder_access_lost)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("reselect_folder_button").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("reselect_folder_button").performClick()
        assertTrue("Re-select Folder button must trigger folder picker", pickFolderClicked)
    }
}

