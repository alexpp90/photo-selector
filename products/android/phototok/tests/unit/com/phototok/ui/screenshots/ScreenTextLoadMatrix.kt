package com.phototok.ui.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.photoselector.core.Requirement
import com.photoselector.core.storage.SourceKind
import com.phototok.data.model.RecentPath
import com.phototok.domain.FirstRunHint
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb
import com.phototok.knownDefect
import com.phototok.ui.phonemode.FolderResumeDialog
import com.phototok.ui.phonemode.PhoneModeLanding
import com.phototok.ui.phonemode.PhoneScreenHarness
import com.phototok.ui.phonemode.SelectionFolderViewer
import com.phototok.viewmodel.ReloadPrompt
import com.phototok.viewmodel.SelectionViewerTab
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The text-load matrix (EN/DE × device night mode × font scale 1.0/2.0, [VARIANTS]) over the
 * screens [TextLoadScreenshotTest] does not render — the Selection viewer, the feed with the info
 * panel, the controls guide over the real bars, the landing under the top bar and with recent
 * folders, the resume dialog — and over overlays against the controls they must stay clear of
 * (snackbar, hint card). Each concrete subclass runs it at one window size, so every check holds on
 * a compact phone, a large phone and a short multi-window height (gap analysis 2026-11-09, ranks 6
 * and 7; REQ-TOK-UX.05, REQ-TOK-UX.02).
 *
 * Every check collects its failures over the whole matrix and reports them together. An open
 * finding is held by `knownDefect` over the whole matrix, so it turns red the day the last cell is
 * fixed; everything else is strict.
 */
abstract class ScreenTextLoadMatrix {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val host by lazy { MatrixHost(rule, VARIANTS.first()) }
    private val harness by lazy { PhoneScreenHarness(rule) }

    private val metrics get() = RuntimeEnvironment.getApplication().resources.displayMetrics
    private val density get() = metrics.density
    private val screen get() = Rect(0f, 0f, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
    private val size get() = "${(metrics.widthPixels / density).toInt()}x${(metrics.heightPixels / density).toInt()}"

    /** A compact phone width (360 dp class). */
    private val isNarrow get() = metrics.widthPixels / density < 380f

    /** A short window (multi-window, landscape-like height): a long screen may scroll there. */
    private val isShort get() = metrics.heightPixels / density < 600f

    /** Failures of one check, by matrix cell. */
    private class Findings(val what: String) {
        val list = mutableListOf<String>()
        fun add(cell: String, items: List<String>) {
            items.forEach { list += "[$cell] $it" }
        }
        fun assertNone() = assertTrue("$what:\n" + list.joinToString("\n"), list.isEmpty())
    }

    private fun walk(onEach: (cell: String) -> Unit) {
        rule.mainClock.autoAdvance = false
        for (variant in VARIANTS) {
            host.show(variant)
            onEach("$size $variant")
        }
    }

    private fun static(surface: String, content: @Composable () -> Unit, onEach: (cell: String) -> Unit) {
        host.setContent(content)
        walk { cell ->
            rule.saveScreenshot("${surface}_$size", host.current)
            onEach(cell)
        }
    }

    private fun feed(proMode: Boolean = true, infoPanel: Boolean = false) {
        harness.phoneSettings = harness.phoneSettings.copy(proMode = proMode, showExifOverlay = infoPanel)
        harness.sourceSettings = harness.sourceSettings.copy(
            swipeRightVerb = SwipeRightVerb.COPY,
            swipeLeftVerb = SwipeAction.DELETE,
            directDeleteConfirmEnabled = false,
        )
        harness.prepare()
        host.setContent { harness.Screen() }
        harness.openFolder(PhoneScreenHarness.PHOTOS)
    }

    private fun barControls() =
        (rule.nodesUnder("viewer_bottom_bar") + rule.nodesUnder("phone_top_bar")).clickables()

    // ── Selection viewer ─────────────────────────────────────────────────────

    @Test
    @Requirement("REQ-TOK-UX.05", "REQ-TOK-FEATURE.17")
    fun selectionViewer_fits() {
        val targets = Findings("Selection viewer: targets under 48 dp")
        val clipped = Findings("Selection viewer: clipped text")
        val off = Findings("Selection viewer: content off screen")
        val crowded = Findings("Selection viewer: bar controls overlap each other")
        val broken = Findings("Selection viewer: a word wraps inside itself")
        static("selection_viewer", {
            SelectionFolderViewer(
                images = PhoneScreenHarness.PHOTOS,
                currentIndex = 0,
                folderName = "Selection",
                activeTab = SelectionViewerTab.FAVOURITES,
                showTabs = true,
                favouriteCount = 3,
                selectionCount = 12,
                showExifOverlay = false,
                onSelectTab = {},
                onShareBatch = {},
                onCopyFavouritesToFolder = {},
                onNavigate = {},
                onClose = {},
            )
        }) { cell ->
            val bar = rule.nodesUnder("selection_viewer_bar")
            assertTrue("[$cell] Back, two tabs, Copy and Share", bar.clickables().size >= 5)
            targets.add(cell, smallTargets(bar, density))
            clipped.add(cell, clippedTexts(rule.allNodes()))
            off.add(cell, outside(rule.allNodes(), screen))
            val controls = bar.clickables()
            crowded.add(cell, controls.flatMapIndexed { i, a -> overlaps(listOf(a), controls.drop(i + 1)) })
            broken.add(cell, brokenWords(rule.allNodes()))
        }
        listOf(targets, clipped, off, crowded, broken).forEach { it.assertNone() }
    }

    // ── Feed with the info panel open ───────────────────────────────────────

    @Test
    @Requirement("REQ-TOK-UX.05", "REQ-TOK-FEATURE.07")
    fun feedWithInfoPanel_fits() {
        feed(infoPanel = true)
        val targets = Findings("feed: targets under 48 dp")
        val off = Findings("feed: bar or info content off screen")
        val covered = Findings("feed: the info panel covers a control or the counter")
        val truncated = Findings("F-TOK-052: info panel text truncated")
        val noDate = Findings("F-TOK-028: info panel shows no capture date")
        val broken = Findings("feed: a word wraps inside itself")
        walk { cell ->
            rule.saveScreenshot("feed_info_$size", host.current)
            // The pager composes the neighbouring pages too: only the panel on screen counts.
            val panel = rule.nodesUnder("viewer.info").onScreen(screen)
            assertTrue("[$cell] the info panel is shown", panel.isNotEmpty())
            // The file name is a value, not words: it wraps wherever it must, never ellipsised
            // (REQ-TOK-UX.05); `truncated` below still checks it is shown whole.
            val words = panel.filterNot { it.isUnder("viewer.info.file_name") }
            broken.add(cell, brokenWords(words + rule.nodesUnder("viewer_bottom_bar") + rule.nodesUnder("phone_top_bar")))
            targets.add(cell, smallTargets(barControls(), density))
            off.add(cell, outside(barControls(), screen, includeWhollyOffscreen = true))
            off.add(cell, outside(panel, screen))
            covered.add(cell, overlaps(panel.visibleContent(), barControls() + rule.nodesUnder("viewer.counter")))
            truncated.add(cell, clippedTexts(panel))
            val fileNames = PhoneScreenHarness.PHOTOS.map { it.fileName }.toSet()
            val dated = panel.mapNotNull { it.visibleText() }.any { it !in fileNames && it.contains("2026") }
            if (!dated) noDate.add(cell, listOf(panel.mapNotNull { it.visibleText() }.toString()))
        }
        listOf(targets, off, covered, broken, truncated, noDate).forEach { it.assertNone() }
    }

    // ── Controls guide over the real bars ───────────────────────────────────

    @Test
    @Requirement("REQ-TOK-UX.05", "REQ-TOK-GESTURE.07")
    fun controlsGuideOnTheFeed_fits() = guideFits(firstRun = false)

    /** F-TOK-002/-012: the first-run tutorial (the same overlay with its GOT IT button) at every size. */
    @Test
    @Requirement("REQ-TOK-UX.05", "REQ-TOK-GESTURE.08")
    fun firstRunTutorialOnTheFeed_fits() = guideFits(firstRun = true)

    private fun guideFits(firstRun: Boolean) {
        if (firstRun) harness.gestureTutorialTs = 0L
        feed()
        if (firstRun) {
            rule.waitUntil(5_000) { harness.viewModel.uiState.value.showGestureTutorial }
        } else {
            rule.runOnUiThread { harness.viewModel.showControlsGuide() }
        }
        rule.waitForIdle()
        // F-TOK-002/-012: the guide does not scroll, so a callout cut off at a small window is lost.
        val off = Findings("controls guide (F-TOK-002): callout off screen")
        val unreadable = Findings("controls guide (F-TOK-029): callouts truncated or overlapping")
        walk { cell ->
            rule.saveScreenshot((if (firstRun) "first_run_tutorial_" else "controls_guide_feed_") + size, host.current)
            if (firstRun) {
                val gotIt = rule.nodesUnder("gesture_tutorial_got_it")
                assertTrue("[$cell] GOT IT is shown", gotIt.isNotEmpty())
                off.add(cell, outside(gotIt, screen, includeWhollyOffscreen = true))
                off.add(cell, smallTargets(gotIt, density))
            }
            val callouts = rule.allNodes().filter { node ->
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
                    ?.startsWith("guide_callout_") == true
            }
            if (firstRun) {
                // Step 1 teaches the swipes; of the bars it labels only Help, which opens the rest.
                val names = callouts.map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }
                assertTrue("[$cell] first run labels only Help among the bars: $names", names == listOf("guide_callout_HELP"))
            } else {
                assertTrue("[$cell] the guide shows callouts", callouts.size >= 6)
            }
            off.add(cell, outside(callouts.flatMap { c -> rule.allNodes().filter { it.isUnder(c.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]) } } + rule.nodesUnder("controls_guide").clickables(), screen, includeWhollyOffscreen = true))
            unreadable.add(cell, callouts.flatMap { c -> clippedTexts(rule.nodesUnder(c.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag])) })
            unreadable.add(cell, callouts.flatMapIndexed { i, a -> overlaps(listOf(a), callouts.drop(i + 1)) })
            unreadable.add(cell, overlaps(callouts, barControls()))
            unreadable.add(cell, brokenWords(rule.nodesUnder("controls_guide")))
        }
        off.assertNone()
        unreadable.assertNone()
    }

    // ── Overlays clear of the bars ──────────────────────────────────────────

    @Test
    @Requirement("REQ-TOK-UX.02", "REQ-TOK-FEATURE.18")
    fun feedSnackbar_clearOfTheBars() {
        feed()
        rule.runOnUiThread { harness.viewModel.onSwipeLeft() } // a delete: "Photo deleted · Undo"
        rule.waitForIdle()
        val covered = Findings("feed: the snackbar covers a bar control")
        val off = Findings("feed: the snackbar leaves the screen")
        val clipped = Findings("feed: snackbar text clipped")
        walk { cell ->
            rule.saveScreenshot("feed_snackbar_$size", host.current)
            val snackbar = rule.nodesUnder("feed_snackbar_host").visibleContent()
            assertTrue("[$cell] the snackbar is shown", snackbar.isNotEmpty())
            covered.add(cell, overlaps(snackbar, barControls()))
            off.add(cell, outside(rule.nodesUnder("feed_snackbar_host"), screen, includeWhollyOffscreen = true))
            clipped.add(cell, clippedTexts(rule.nodesUnder("feed_snackbar_host")))
            clipped.add(cell, brokenWords(rule.nodesUnder("feed_snackbar_host")))
        }
        listOf(covered, off, clipped).forEach { it.assertNone() }
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.05", "REQ-TOK-UX.05")
    fun feedHintCard_clearOfTheBars() {
        feed()
        rule.runOnUiThread { harness.viewModel.maybeShowFirstRunHint(FirstRunHint.DOUBLE_TAP_ZOOM) }
        rule.waitForIdle()
        val covered = Findings("feed: the hint card covers a bar control")
        val off = Findings("feed: the hint card leaves the screen")
        val clipped = Findings("feed: hint card text clipped")
        val targets = Findings("feed: hint card targets under 48 dp")
        walk { cell ->
            rule.saveScreenshot("feed_hint_$size", host.current)
            val card = rule.nodesUnder("first_run_hint")
            assertTrue("[$cell] the hint card is shown", card.isNotEmpty())
            covered.add(cell, overlaps(card.visibleContent(), barControls()))
            off.add(cell, outside(card, screen, includeWhollyOffscreen = true))
            clipped.add(cell, clippedTexts(card))
            clipped.add(cell, brokenWords(card))
            targets.add(cell, smallTargets(card, density))
        }
        listOf(covered, off, clipped, targets).forEach { it.assertNone() }
    }

    // ── Landing ─────────────────────────────────────────────────────────────

    /** F-TOK-048 (fixed): the landing scrolled under the top bar at font scale 2.0. */
    @Test
    @Requirement("REQ-TOK-UX.05", "REQ-TOK-FEATURE.03")
    fun landingUnderTheTopBar() {
        // A returning user: three recent folders and a folder to resume make the landing long.
        harness.phoneSettings = harness.phoneSettings.copy(recentPaths = RECENTS, recentPathsCount = 3)
        harness.prepare()
        host.setContent { harness.Screen() }
        harness.openFolder(PhoneScreenHarness.PHOTOS)
        rule.runOnUiThread { harness.viewModel.goBackToLanding() }
        rule.waitForIdle()
        assertTrue("the landing offers Resume", rule.boundsOf("landing_start_browsing_button") != null)
        val under = Findings("F-TOK-048: landing content under the top bar")
        val targets = Findings("landing: targets under 48 dp")
        walk { cell ->
            val bar = rule.boundsOf("phone_top_bar")
            assertTrue("[$cell] the top bar is shown", bar != null)
            fun landingContent() = rule.allNodes().filter { !it.isUnder("phone_top_bar") }.visibleContent()
            under.add("$cell top", landingContent().filter { it.boundsInWindow.overlapsBeyondSeam(bar!!) }.map { "'${it.label()}' ${it.boundsInWindow}" })
            targets.add(cell, smallTargets(rule.allNodes(), density))
            // Scroll the landing to its end, as a user does to reach the last card.
            rule.runOnUiThread {
                rule.allNodes().firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }
                    ?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f, 100_000f)
            }
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(400)
            rule.waitForIdle()
            rule.saveScreenshot("landing_scrolled_$size", host.current)
            under.add("$cell scrolled", landingContent().filter { it.boundsInWindow.overlapsBeyondSeam(bar!!) }.map { "'${it.label()}' ${it.boundsInWindow}" })
        }
        targets.assertNone()
        under.assertNone()
    }

    @Test
    @Requirement("REQ-TOK-UX.05", "REQ-TOK-FEATURE.12")
    fun landingWithRecentFolders_fits() {
        val recents = RECENTS
        val targets = Findings("landing: targets under 48 dp")
        val clipped = Findings("landing: clipped text")
        val off = Findings("landing: content off screen sideways")
        val resumeBelowFold = Findings("F-TOK-052: Resume is below the fold at font scale 2.0")
        val broken = Findings("landing: a word wraps inside itself")
        static("landing_recent", {
            PhoneModeLanding(
                sourceFolderUri = "content://tree/a",
                sourceFolderName = "DCIM",
                collectionFolderName = "Selection",
                isLoading = false,
                onSelectSource = {},
                onSelectCollection = {},
                onStart = {},
                recentPaths = recents,
                recentPathsCount = 3,
                // DCIM with its seen progress, the SD card not connected, the cloud folder plain.
                folderSeenProgress = mapOf("content://tree/a" to (4 to 15), "content://tree/c" to (120 to 1_204)),
                unmountedRecentUris = setOf("content://tree/b"),
                proMode = true,
            )
        }) { cell ->
            assertTrue("[$cell] three recent rows", rule.allNodes().count { it.isUnder("landing_recent_row") && it.config.contains(SemanticsActions.OnClick) } >= 3)
            targets.add(cell, smallTargets(rule.allNodes(), density))
            clipped.add(cell, clippedTexts(rule.allNodes()))
            off.add(cell, outside(rule.allNodes(), screen, vertical = false))
            broken.add(cell, brokenWords(rule.allNodes()))
            val resume = rule.boundsOf("landing_start_browsing_button")
            if (!isShort && (resume == null || resume.bottom > screen.bottom + 1f)) {
                resumeBelowFold.add(cell, listOf("Resume at $resume, screen $screen"))
            }
        }
        listOf(targets, clipped, off, resumeBelowFold).forEach { it.assertNone() }
        if (isNarrow) {
            // At 360 dp and font scale 2.0 the badge squeezes the name column: "verbunde|n".
            knownDefect("F-TOK-087 (new)", "'Not connected' wraps inside a word on a 360 dp phone at font scale 2.0") {
                broken.assertNone()
            }
        } else {
            broken.assertNone()
        }
    }

    private companion object {
        val RECENTS = listOf(
            RecentPath("content://tree/a", "DCIM", kind = SourceKind.PHONE_LIBRARY),
            RecentPath("content://tree/b", "Camera", kind = SourceKind.REMOVABLE),
            RecentPath("content://tree/c", "Pictures", kind = SourceKind.CLOUD_OTHER),
        )
    }

    // ── Resume dialog ───────────────────────────────────────────────────────

    @Test
    @Requirement("REQ-TOK-UX.05", "REQ-TOK-FEATURE.09")
    fun resumeDialog_fits() {
        val targets = Findings("resume dialog: targets under 48 dp")
        val clipped = Findings("resume dialog: clipped text")
        val off = Findings("resume dialog: content off screen")
        static("resume_dialog", {
            FolderResumeDialog(
                prompt = ReloadPrompt(newCount = 12, firstNewIndex = 0, resumeIndex = 3, totalCount = 40),
                onStartWithNewest = {},
                onContinueWhereLeftOff = {},
            )
        }) { cell ->
            assertTrue("[$cell] Show new and Stay", rule.clickableNodes().size >= 2)
            targets.add(cell, smallTargets(rule.allNodes(), density))
            clipped.add(cell, clippedTexts(rule.allNodes()))
            clipped.add(cell, brokenWords(rule.allNodes()))
            off.add(cell, outside(rule.allNodes(), screen, includeWhollyOffscreen = true))
        }
        listOf(targets, clipped, off).forEach { it.assertNone() }
    }
}

/** A large phone (Pixel 7a class), as [TextLoadScreenshotTest]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w393dp-h851dp-port-xxhdpi")
class ScreenTextLoadPhone393x851Test : ScreenTextLoadMatrix()

/** A compact phone (5.0", 360 x 640 dp). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h640dp-port-xhdpi")
class ScreenTextLoadCompact360x640Test : ScreenTextLoadMatrix()

/** A short window: split screen or a multi-window freeform at 393 x 480 dp. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w393dp-h480dp-port-xxhdpi")
class ScreenTextLoadShort393x480Test : ScreenTextLoadMatrix()
