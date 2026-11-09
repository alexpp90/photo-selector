package com.photoselectortoolbox.ui.fit

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselector.saf.FileOperationResult
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.guidance.SelectorHint
import com.photoselectortoolbox.ui.selector.SelectorScreen
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SelectorViewModel
import io.mockk.coEvery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every transient surface of the selector clears every control, at every expanded and medium
 * window of the fit matrix and font scale 1.0 / 1.3 / 2.0: the action snackbar with its UNDO
 * (REQ-AND-LAYOUT.21), the first-run "Tap either side to browse" pill (REQ-AND-LAYOUT.17) and the
 * one-time filing hint card (REQ-AND-LAYOUT.19), all up at once after the first move of a first
 * launch — the state the device lab met on 2026-10-24 (F-FD-047).
 *
 * The snackbar is centred over the photographs by specification, so it may cover a frame; it may
 * not cover a control. The hint card is guidance and may cover neither; the pill may lie in a
 * frame but must clear its maximise badges.
 */
@Requirement("REQ-AND-LAYOUT.09", "REQ-AND-LAYOUT.17", "REQ-AND-LAYOUT.19", "REQ-AND-LAYOUT.21")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = FitMatrix.QUALIFIERS)
class SelectorOverlayClearanceTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private lateinit var viewModel: SelectorViewModel

    private val cells = FitMatrix.cells.filter { !it.compact }
    private val frames = setOf("column_current", "column_previous", "column_next")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        if (this::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private data class Seen(val tag: String?, val label: String, val bounds: Rect, val clickable: Boolean, val node: SemanticsNode)

    private fun SemanticsNode.flatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flatten() }

    private fun SemanticsNode.isUnder(tag: String): Boolean =
        generateSequence(this) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun window(): List<Seen> =
        rule.onNodeWithTag(FIT_WINDOW_TAG, useUnmergedTree = true).fetchSemanticsNode().flatten()
            .filter { it.boundsInRoot.width > 1f && it.boundsInRoot.height > 1f }
            .map {
                Seen(
                    it.config.getOrNull(SemanticsProperties.TestTag), it.describe(), it.boundsInRoot,
                    SemanticsActions.OnClick in it.config, it,
                )
            }

    private fun Rect.hits(other: Rect): Boolean {
        val o = intersect(other)
        return o.width > 1f && o.height > 1f
    }

    /** The overlay's bounds against every control outside it, minus [except] tags. */
    private fun clashes(nodes: List<Seen>, overlayTag: String, except: Set<String> = emptySet()): List<String> {
        val overlay = nodes.firstOrNull { it.tag == overlayTag } ?: return emptyList()
        return nodes.filter { it.clickable && !it.node.isUnder(overlayTag) && it.tag !in except }
            .filter { overlay.bounds.hits(it.bounds) }
            .map { "$overlayTag covers ${it.label}" }
    }

    /** The cells at which the filing hint card was drawn. */
    private val cardsSeen = mutableListOf<FitCell>()

    private fun photos(n: Int): List<ImageItem> = (1..n).map { fitPhoto("P$it.JPG", 10_000L - it) }

    /**
     * First launch (pill and filing hint not yet seen, guide already seen), one move made, so the
     * snackbar with UNDO, the pill and the hint card are all up; then every cell of [cells].
     * Returns, per cell, the overlay findings.
     */
    private fun sweep(): Map<FitCell, List<String>> {
        val settings = FitSelectorSettings(
            seen = SelectorHint.entries.toSet() - SelectorHint.FILING,
            navHintSeen = false,
        )
        val repo = fitImageRepository().also { r ->
            coEvery { r.moveBundle(any(), any(), any(), any()) } answers {
                FileOperationResult.success(secondArg<ImageItem>().uri, "content://test/sel/x")
            }
        }
        viewModel = fitSelectorViewModel(
            ApplicationProvider.getApplicationContext(), appScope, settings, photos(6), imageRepository = repo,
        )
        var cell by mutableStateOf(cells.first())
        rule.setContent {
            FitWindow(cell) {
                PhotoSelectorToolboxTheme {
                    key(cell) { SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = viewModel) }
                }
            }
        }
        rule.waitUntil(5_000) { rule.tagExists("column_current") }
        rule.mainClock.autoAdvance = false
        val findings = linkedMapOf<FitCell, List<String>>()
        for (c in cells) {
            cell = c
            // A fresh composition per cell restarts the card's own timer; the move is made once.
            if (c == cells.first()) viewModel.moveToSelection()
            rule.mainClock.advanceTimeBy(600)
            rule.waitForIdle()
            val nodes = window()
            val tags = nodes.mapNotNull { it.tag }.toSet()
            assertTrue("${c.label}: the move's snackbar is up", "selector_snackbar" in tags)
            assertTrue("${c.label}: the first-run pill is up", "first_run_hint" in tags)
            // The card is drawn only where SelectorGuidance.slotFor finds room; where it does, it
            // is checked. It must be up at the reference window at least (checked after the sweep).
            if ("selector_hint_card" in tags) cardsSeen += c
            val f = mutableListOf<String>()
            // The snackbar may lie over a photograph, never over a control.
            f += clashes(nodes, "selector_snackbar", except = frames)
            // Guidance clears every control and every frame (REQ-AND-LAYOUT.17/.19).
            f += clashes(nodes, "selector_hint_card")
            nodes.firstOrNull { it.tag == "selector_hint_card" }?.let { card ->
                nodes.filter { it.tag in frames && card.bounds.hits(it.bounds) }
                    .forEach { f += "selector_hint_card covers ${it.label}" }
            }
            // The pill may lie in a frame but clears its badges and every other control.
            f += clashes(nodes, "first_run_hint", except = frames)
            // And no two of them share bounds.
            val byTag = nodes.filter { it.tag in setOf("selector_snackbar", "first_run_hint", "selector_hint_card") }
            for (i in byTag.indices) for (j in i + 1 until byTag.size) {
                if (byTag[i].bounds.hits(byTag[j].bounds)) f += "${byTag[i].tag} overlaps ${byTag[j].tag}"
            }
            findings[c] = f.distinct()
        }
        return findings
    }

    private fun report(findings: Map<FitCell, List<String>>, keep: (String) -> Boolean): String =
        findings.mapNotNull { (cell, f) -> f.filter(keep).takeIf { it.isNotEmpty() }?.let { "${cell.label}: $it" } }
            .joinToString("\n")

    /** F-FD-047: the snackbar and the browse pill on top of each other. */
    private fun isPillSnackbar(f: String) = f in setOf(
        "selector_snackbar overlaps first_run_hint", "first_run_hint overlaps selector_snackbar",
        "selector_snackbar covers #first_run_hint_dismiss", "first_run_hint covers #snackbar_undo",
    )

    /** NEW-FD-8: an overlay on a frame's maximise badge. */
    private fun coversBadge(f: String) = f.endsWith("covers #maximise_badge")

    @Test
    fun `the snackbar, the first-run pill and the filing hint clear every control at every size and font scale`() {
        val findings = sweep()
        assertTrue("the filing hint card is drawn at 1480 x 924", cardsSeen.any { it.widthDp == 1480 })
        val others = report(findings) { !isPillSnackbar(it) && !coversBadge(it) }
        assertTrue("overlays cover controls:\n$others", others.isEmpty())
        // F-FD-047: the undo snackbar is drawn on top of the browse pill (lab 2026-10-24, Pixel
        // Tablet); here at every size and font scale. Cause: SelectorActions.kt:408 (the card a
        // fixed 96 dp above the bottom) and FrameGeometry.kt:160 (the pill lifted only past the
        // badge band), neither aware of the other.
        knownDefect("F-FD-047") {
            val pill = report(findings, ::isPillSnackbar)
            assertTrue("the snackbar sits on the first-run pill:\n$pill", pill.isEmpty())
        }
        // NEW-FD-8: at 1024 x 720 at font scale 2.0 and 720 x 560 at 1.0 the snackbar and the pill
        // cover the Next frame's maximise badge (REQ-AND-LAYOUT.17: the pill clears every badge).
        knownDefect("NEW-FD-8") {
            val badge = report(findings, ::coversBadge)
            assertTrue("an overlay covers a maximise badge:\n$badge", badge.isEmpty())
        }
    }
}
