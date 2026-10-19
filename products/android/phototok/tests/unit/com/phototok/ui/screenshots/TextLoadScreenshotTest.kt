package com.phototok.ui.screenshots

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.phototok.R
import com.phototok.data.model.PhoneSettings
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.domain.CollectionAction
import com.phototok.domain.FirstRunHint
import com.phototok.domain.SwipeAction
import com.phototok.domain.UiText
import com.phototok.ui.components.ViewerBottomBar
import com.phototok.ui.phonemode.DirectDeleteDialog
import com.phototok.ui.phonemode.FirstRunHintCard
import com.phototok.ui.phonemode.FolderResumeDialog
import com.phototok.ui.phonemode.GestureTutorialOverlay
import com.phototok.ui.phonemode.LandscapeLeftPanel
import com.phototok.ui.phonemode.LandscapeRightButtons
import com.phototok.ui.phonemode.PhoneModeLanding
import com.phototok.ui.phonemode.PhoneModeTopBar
import com.phototok.ui.phonemode.RawJpegSuggestionDialog
import com.phototok.ui.settings.SettingsScreen
import com.phototok.viewmodel.FirstRunHintUi
import com.phototok.viewmodel.ReloadPrompt
import com.phototok.viewmodel.SettingsViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * REQ-TOK-UX.02/.03/.05: every text-bearing PhotoTok surface is rendered in English and German,
 * on a dark device and on a light one (the app is dark either way), at font scale 1.0 and 2.0 —
 * 8 renderings per surface, written as PNGs to `build/outputs/screenshots/` — and checked for the
 * defects text causes on a phone: visible text on an icon-only bar, a target under 48 dp, text
 * that is truncated, and anything that leaves the screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-port-xxhdpi")
class TextLoadScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    private val host by lazy { MatrixHost(rule, VARIANTS.first()) }

    private val density get() = RuntimeEnvironment.getApplication().resources.displayMetrics.density
    private val screen
        get() = RuntimeEnvironment.getApplication().resources.displayMetrics.let {
            Rect(0f, 0f, it.widthPixels.toFloat(), it.heightPixels.toFloat())
        }

    /** Renders every variant of the surface set up by [content] and runs [checks] on each. */
    private fun everyVariant(
        surface: String,
        content: @androidx.compose.runtime.Composable () -> Unit,
        checks: TextLoadChecks.(Variant) -> Unit,
    ) {
        rule.mainClock.autoAdvance = false
        host.setContent(content)
        val verifier = TextLoadChecks(rule, screen, density)
        for (variant in VARIANTS) {
            host.show(variant)
            rule.saveScreenshot(surface, variant)
            verifier.checks(variant)
        }
    }

    @Test
    fun viewerBottomBar_isIconOnly() = everyVariant(
        surface = "viewer_bottom_bar",
        content = {
            Box(Modifier.fillMaxSize()) {
                ViewerBottomBar(
                    canRevert = true,
                    onRevert = {},
                    onJumpToSelection = {},
                    onGoToLanding = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
        checks = { variant ->
            assertHasClickableControls("viewer_bottom_bar", 3)
            assertIconOnly(variant, "viewer_bottom_bar")
            assertTouchTargets(variant, "viewer_bottom_bar")
            assertInsideScreen(variant, "viewer_bottom_bar")
        },
    )

    @Test
    fun topBar_isIconOnly() = everyVariant(
        surface = "top_bar",
        content = {
            Box(Modifier.fillMaxSize()) {
                PhoneModeTopBar(
                    showControls = true,
                    showDoneWithCard = true,
                    guideOpen = false,
                    exifOverlayOn = false,
                    onToggleExif = {},
                    onDoneWithCard = {},
                    onReload = {},
                    onHelp = {},
                    onSettings = {},
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        },
        checks = { variant ->
            assertHasClickableControls("top_bar", 6)
            assertIconOnly(variant, "top_bar")
            assertTouchTargets(variant, "top_bar")
            assertInsideScreen(variant, "top_bar")
        },
    )

    @Test
    fun landscapeSidePanel_isIconOnly() = everyVariant(
        surface = "landscape_side_panel",
        content = {
            Row(Modifier.fillMaxSize()) {
                LandscapeLeftPanel(
                    canRevert = true,
                    helpOpen = false,
                    anchors = null,
                    onToggleExif = {},
                    onSources = {},
                    onSelection = {},
                    onRevert = {},
                    onHelp = {},
                )
                Box(Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.width(125.dp).fillMaxHeight().padding(12.dp)) {
                    LandscapeRightButtons(
                        exifOn = false,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        anchors = null,
                        onReload = {},
                        onInfo = {},
                        onSettings = {},
                    )
                }
            }
        },
        checks = { variant ->
            assertHasClickableControls("landscape_side_panel", 8)
            assertIconOnly(variant, "landscape_side_panel")
            assertTouchTargets(variant, "landscape_side_panel")
            assertInsideScreen(variant, "landscape_side_panel")
        },
    )

    @Test
    fun directDeleteDialog_fits() = everyVariant(
        surface = "direct_delete_dialog",
        content = { DirectDeleteDialog(onConfirm = {}, onDismiss = {}) },
        checks = { variant ->
            assertHasClickableControls("direct_delete_dialog", 2)
            assertTouchTargets(variant, "direct_delete_dialog")
            assertNoTextOverflow(variant, "direct_delete_dialog")
            assertInsideScreen(variant, "direct_delete_dialog")
        },
    )

    @Test
    fun newPhotosDialog_fits() = everyVariant(
        surface = "new_photos_dialog",
        content = {
            FolderResumeDialog(
                prompt = ReloadPrompt(newCount = 12, firstNewIndex = 0, resumeIndex = 3, totalCount = 40),
                onStartWithNewest = {},
                onContinueWhereLeftOff = {},
            )
        },
        checks = { variant ->
            assertHasClickableControls("new_photos_dialog", 2)
            assertTouchTargets(variant, "new_photos_dialog")
            assertNoTextOverflow(variant, "new_photos_dialog")
            assertInsideScreen(variant, "new_photos_dialog")
        },
    )

    @Test
    fun rawJpegDialog_fits() = everyVariant(
        surface = "raw_jpeg_dialog",
        content = {
            RawJpegSuggestionDialog(
                visible = true,
                onFilterRaw = {},
                onFilterJpg = {},
                onEnableMoveRelatedFiles = {},
                onDismiss = {},
                onShowOnePerShot = {},
            )
        },
        checks = { variant ->
            assertHasClickableControls("raw_jpeg_dialog", 5)
            assertTouchTargets(variant, "raw_jpeg_dialog")
            assertNoTextOverflow(variant, "raw_jpeg_dialog")
            // The dialog scrolls at large font scales; only sideways overflow is a defect.
            assertInsideScreen(variant, "raw_jpeg_dialog", vertical = false)
        },
    )

    @Test
    fun hintCard_fits() = everyVariant(
        surface = "hint_card",
        content = {
            Box(Modifier.fillMaxSize()) {
                FirstRunHintCard(
                    hint = FirstRunHintUi(
                        hint = FirstRun.hint,
                        title = UiText.of(R.string.hint_title_swipe_right_copy, "Selection"),
                        message = UiText.of(R.string.hint_body_swipe_left_delete),
                    ),
                    onDismiss = {},
                    onOpenSettings = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
        checks = { variant ->
            assertHasClickableControls("hint_card", 2)
            assertTouchTargets(variant, "hint_card")
            assertNoTextOverflow(variant, "hint_card")
            assertInsideScreen(variant, "hint_card")
        },
    )

    @Test
    fun controlsGuide_fits() = everyVariant(
        surface = "controls_guide",
        content = {
            GestureTutorialOverlay(
                visible = true,
                onDismiss = {},
                collectionAction = CollectionAction.MOVE,
                collectionFolderName = "Keepers",
                leftSwipeAction = SwipeAction.COPY,
                leftSwipeFolderName = "Maybes",
            )
        },
        checks = { variant ->
            assertHasClickableControls("controls_guide", 1)
            assertNoWordBroken(variant, "controls_guide")
            assertTouchTargets(variant, "controls_guide")
            assertInsideScreen(variant, "controls_guide", vertical = false)
        },
    )

    @Test
    fun settingsFirstScreen_fits() {
        val settingsRepository: SettingsRepository = mockk(relaxed = true)
        every { settingsRepository.phoneSettings } returns MutableStateFlow(PhoneSettings())
        every { settingsRepository.selectionFolderName } returns MutableStateFlow("Selection")
        every { settingsRepository.sortingEnabled } returns MutableStateFlow(true)
        every { settingsRepository.phoneCollectionUri } returns MutableStateFlow<String?>(null)
        every { settingsRepository.phoneLeftSwipeUri } returns MutableStateFlow<String?>(null)
        every { settingsRepository.lastFolderUri } returns MutableStateFlow<String?>(null)
        val imageRepository: ImageRepository = mockk(relaxed = true)
        val viewModel = SettingsViewModel(settingsRepository, imageRepository)

        everyVariant(
            surface = "settings",
            content = {
                SettingsScreen(
                    viewModel = viewModel,
                    onChangeSourceFolder = {},
                    onReloadFolder = {},
                )
            },
            checks = { variant ->
                assertHasClickableControls("settings", 3)
                assertNoTextOverflow(variant, "settings")
                // A long list: it scrolls, so only sideways overflow is a defect.
                assertInsideScreen(variant, "settings", vertical = false)
            },
        )
    }

    @Test
    fun landing_fits() = everyVariant(
        surface = "landing",
        content = {
            PhoneModeLanding(
                sourceFolderUri = "content://tree/PSTLab",
                sourceFolderName = "PSTLab",
                collectionFolderName = "Selection",
                isLoading = false,
                onSelectSource = {},
                onSelectCollection = {},
                onStart = {},
            )
        },
        checks = { variant ->
            assertHasClickableControls("landing", 3)
            assertNoTextOverflow(variant, "landing")
            // The landing screen scrolls; only sideways overflow is a defect.
            assertInsideScreen(variant, "landing", vertical = false)
        },
    )

    private object FirstRun {
        val hint = FirstRunHint.SWIPE_RIGHT
    }
}
