package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.domain.guidance.SelectorHint
import com.photoselectortoolbox.domain.guidance.SelectorHintText
import com.photoselectortoolbox.domain.guidance.SidebarAction
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.navigation.NavEntry
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SelectorHintUi
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.photoselectortoolbox.testing.en

/**
 * Three selector surfaces at the largest system font (usability panel 2026-11-10,
 * Pixel Tablet at font scale 2.0; `docs/shared/UI_TEXT.md`: "Layouts are checked at
 * font scale 2.0", and text wraps or scrolls, never truncates):
 *
 * - the first-run pill's **Got it** read "Got i" — a fixed 56 dp box cut the word;
 * - the after-delete explanation card was cut mid-word and its Got it was off the card;
 * - each sidebar glyph sat nearer the word of the item above than its own, because a
 *   fixed 72 dp item let icon and word overflow it both ways.
 *
 * The scale is provided the way the app's theme applies it (`Density(density, fontScale)`,
 * outside [PhotoSelectorToolboxTheme], which keeps it), so the sizes are the app's.
 * NATIVE graphics, because under LEGACY text does not grow with the scale.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class LargeFontHintAndSidebarTest {

    @get:Rule
    val rule = createComposeRule()

    private fun host(fontScale: Float, content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PhotoSelectorToolboxTheme { content() }
            }
        }
        rule.waitForIdle()
    }

    private fun Dp.px(): Int = (value * rule.density.density).roundToInt()

    private val DpRect.w: Dp get() = right - left
    private val DpRect.h: Dp get() = bottom - top

    private fun boundsOf(matcher: SemanticsMatcher): DpRect =
        rule.onAllNodes(matcher, useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()

    private fun layoutOf(matcher: SemanticsMatcher): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.runOnUiThread {
            rule.onAllNodes(matcher, useUnmergedTree = true).onFirst().fetchSemanticsNode()
                .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        }
        return results.first()
    }

    /** [inner] lies inside [outer], compared in whole pixels (offsets drift below the dp value). */
    private fun assertInside(what: String, inner: DpRect, outer: DpRect) {
        assertTrue(
            "$what $inner is not inside $outer",
            inner.left.px() >= outer.left.px() && inner.top.px() >= outer.top.px() &&
                inner.right.px() <= outer.right.px() && inner.bottom.px() <= outer.bottom.px(),
        )
    }

    // ── 1e: first-run pill ────────────────────────────────────────────────────

    private val pillWord = hasText("Got it") and hasAnyAncestor(hasTestTag("first_run_hint_dismiss"))

    private fun pill(fontScale: Float) = host(fontScale) {
        Box(Modifier.width(1200.dp).height(200.dp)) {
            FirstRunNavigationHint(visible = true, onDismiss = {})
        }
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.17")
    fun `at font scale 2 the pill's Got it is whole and inside its target`() {
        pill(fontScale = 2f)

        // Not `hasVisualOverflow`: for one unwrapped line it reports an overflow
        // against the paragraph's layout width even when the word is drawn whole.
        // The word's own unclipped width against the width it was given is the test.
        val word = layoutOf(pillWord)
        val needed = ceil(word.multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
        assertTrue("'Got it' needs $needed px and got ${word.size.width}", needed <= word.size.width)
        val target = boundsOf(hasTestTag("first_run_hint_dismiss"))
        assertInside("'Got it'", boundsOf(pillWord), target)
        assertTrue("target ${target.w} x ${target.h}", target.w.px() >= 48.dp.px() && target.h.px() >= 48.dp.px())
        rule.onAllNodes(hasTestTag("first_run_hint_dismiss"), useUnmergedTree = true).onFirst().assertHasClickAction()
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.17")
    fun `at font scale 1 the pill's Got it target is still 56 x 48 dp`() {
        pill(fontScale = 1f)

        val target = boundsOf(hasTestTag("first_run_hint_dismiss"))
        assertEquals(56.dp.px(), target.w.px())
        assertEquals(48.dp.px(), target.h.px())
    }

    // ── 1f: explanation card after a delete ───────────────────────────────────

    private val deleteHint = SelectorHintUi(
        hint = SelectorHint.DELETE_UNDO,
        title = SelectorHintText.title(SelectorHint.DELETE_UNDO, FilingAction.MOVE),
        message = SelectorHintText.message(
            hint = SelectorHint.DELETE_UNDO,
            filingAction = FilingAction.MOVE,
            selectionFolderName = FilingAction.DEFAULT_SELECTION_FOLDER,
            sortingEnabled = true,
        ),
    )

    /** A flank-sized slot: 300 dp wide (the panel's flank at 2560 x 1600), [height] tall. */
    private fun card(fontScale: Float, height: Dp) = host(fontScale) {
        Box(Modifier.width(300.dp).height(height)) {
            SelectorHintCard(hint = deleteHint, onDismiss = {})
        }
    }

    private fun scrollRange(): Float = rule.onAllNodes(hasTestTag("selector_hint_message"), useUnmergedTree = true)
        .onFirst().fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f

    @Test
    fun `at font scale 2 a card taller than its slot keeps Got it on the card and scrolls the message`() {
        card(fontScale = 2f, height = 320.dp)

        val card = boundsOf(hasTestTag("selector_hint_card"))
        val dismiss = boundsOf(hasTestTag("selector_hint_dismiss"))
        assertInside("Got it", dismiss, card)
        assertTrue("card ${card.bottom} overflows its 320 dp slot", card.bottom.px() <= 320.dp.px())
        assertTrue("Got it ${dismiss.w} x ${dismiss.h}", dismiss.w.px() >= 48.dp.px() && dismiss.h.px() >= 48.dp.px())
        assertTrue("the message does not scroll, so its end is unreachable", scrollRange() > 0f)
        rule.onAllNodes(hasTestTag("selector_hint_dismiss"), useUnmergedTree = true).onFirst().assertHasClickAction()
    }

    @Test
    fun `at font scale 1 with room to spare the card is its own height and nothing scrolls`() {
        card(fontScale = 1f, height = 600.dp)

        val card = boundsOf(hasTestTag("selector_hint_card"))
        assertTrue("card ${card.h} filled the slot", card.bottom.px() < 600.dp.px())
        assertEquals(0f, scrollRange(), 0f)
        assertInside("Got it", boundsOf(hasTestTag("selector_hint_dismiss")), card)
    }

    // ── 1h: sidebar items ─────────────────────────────────────────────────────

    private val sidebarItems: List<Pair<String, String>> = listOf(
        "sidebar_open_folder" to SidebarAction.FOLDER.label.en(),
        "scan_button" to SidebarAction.SCAN.label.en(),
        "grouping_toggle" to SidebarAction.BURSTS.label.en(),
        "score_legend_button" to SidebarAction.LEGEND.label.en(),
    ) + NavEntry.all.map { it.testTag to it.label.en() }

    private fun sidebar(fontScale: Float) = host(fontScale) {
        // Tall enough that nothing scrolls: the test is about each item, not the column.
        Box(Modifier.width(SidebarWidth).height(2000.dp)) {
            SelectorSidebar(
                currentRoute = null,
                groupingEnabled = false,
                hasImages = true,
                isScanning = false,
                scanStatusText = "",
                onOpenFolder = {},
                onScan = {},
                onCancelScan = {},
                onToggleGrouping = {},
                onShowLegend = {},
                onShowMenu = {},
                onNavigate = {},
            )
        }
    }

    @Test
    @Requirement("REQ-AND-INPUT.04")
    fun `at font scale 2 every sidebar word lies inside its own item`() {
        sidebar(fontScale = 2f)

        sidebarItems.forEach { (tag, label) ->
            val item = boundsOf(hasTestTag(tag))
            val word = hasText(label) and hasAnyAncestor(hasTestTag(tag))
            // In a fixed 72 dp item the word was squeezed to what the glyph left over
            // (42 of 52 dp at xhdpi): drawn past its box onto the next item.
            val layout = layoutOf(word)
            val needed = ceil(layout.multiParagraph.height).toInt()
            assertTrue("'$label' needs $needed px and got ${layout.size.height}", needed <= layout.size.height)
            assertInside("'$label'", boundsOf(word), item)
            assertTrue("$tag is ${item.h} tall", item.h.px() >= 68.dp.px())
        }
    }

    @Test
    @Requirement("REQ-AND-INPUT.04")
    fun `at font scale 1 every sidebar item is as tall as before`() {
        sidebar(fontScale = 1f)

        // The tagged node is measured inside the item's 2 dp vertical padding: 72 - 4 dp,
        // the same 136 px at xhdpi that the fixed height gave.
        sidebarItems.forEach { (tag, _) ->
            assertEquals(tag, 68.dp.px(), boundsOf(hasTestTag(tag)).h.px())
        }
    }
}
