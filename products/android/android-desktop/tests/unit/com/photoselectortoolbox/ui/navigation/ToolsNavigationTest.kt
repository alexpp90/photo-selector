package com.photoselectortoolbox.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationRail
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.ui.inspect.handleInspectKey
import com.photoselectortoolbox.ui.selector.SelectorActions
import com.photoselectortoolbox.ui.selector.SelectorSidebar
import com.photoselectortoolbox.ui.selector.SidebarWidth
import com.photoselectortoolbox.ui.selector.handleSelectorKey
import com.photoselectortoolbox.ui.selector.screenLegend
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SelectorFrame
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.photoselectortoolbox.testing.en

/**
 * One culling desk with tools beside it ([USER 2026-11-12] decision #18): the sidebar, the rail
 * and the bottom bar offer Cull, Stats, Tools and Setup, and Tools opens a menu with Learn and
 * Dupes. Learn carries no Beta label since Compare shows both frames and its progress is stored
 * (the label's exit condition, decision #18). Hosted at the reference geometry (1480 x 924 dp).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1480dp-h924dp-xhdpi")
@Requirement("REQ-AND-LAYOUT.10")
class ToolsNavigationTest {

    @get:Rule
    val rule = createComposeRule()

    private val navigated = mutableListOf<Screen>()

    private fun host(content: @Composable () -> Unit) {
        rule.setContent { PhotoSelectorToolboxTheme { content() } }
        rule.waitForIdle()
    }

    private fun sidebar(currentRoute: String? = Screen.PhotoSelector.route) = host {
        Box(Modifier.width(SidebarWidth).height(924.dp)) {
            SelectorSidebar(
                currentRoute = currentRoute,
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
                onNavigate = { navigated += it },
            )
        }
    }

    private fun bounds(tag: String): DpRect =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()

    private fun count(tag: String): Int =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun spoken(tag: String): String =
        rule.onAllNodes(hasTestTag(tag)).onFirst().fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    private fun click(tag: String) {
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().performClick()
        rule.waitForIdle()
    }

    private fun Dp.px(): Int = (value * rule.density.density).roundToInt()

    @Test
    fun `the cells are Cull, Stats, Tools and Setup, in that order`() {
        assertEquals(listOf("Cull", "Stats", "Tools", "Setup"), NavEntry.all.map { it.label.en() })
        assertEquals(
            listOf("nav_selector", "nav_statistics", "nav_tools", "nav_settings"),
            NavEntry.all.map { it.testTag },
        )
        // Every screen is still reachable: a cell of its own, or a row of the Tools menu.
        val offered = NavEntry.all.filterIsInstance<NavEntry.Destination>().map { it.screen } + NavEntry.Tools.screens
        assertEquals(Screen.all.toSet(), offered.toSet())
    }

    @Test
    fun `the sidebar draws the four cells top to bottom and no Learn or Dupes cell`() {
        sidebar()

        val tops = NavEntry.all.map { bounds(it.testTag).top }
        assertEquals("cells out of order: $tops", tops.sorted(), tops)
        assertEquals("Learn has a cell of its own", 0, count("nav_inspect"))
        assertEquals("Dupes has a cell of its own", 0, count("nav_duplicates"))
    }

    @Test
    fun `Tools opens a menu with Learn and Dupes, neither labelled Beta`() {
        sidebar()
        click("nav_tools")

        assertEquals(1, count("tools_menu"))
        val learn = bounds("nav_inspect")
        val dupes = bounds("nav_duplicates")
        assertTrue("Learn comes first", learn.top < dupes.top)
        assertEquals("no row carries a Beta chip", 0, count("beta_chip"))
        assertTrue(
            "no row says Beta",
            rule.onAllNodes(hasText("Beta", substring = true, ignoreCase = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        assertEquals("Learn", spoken("nav_inspect"))
        assertEquals("Dupes", spoken("nav_duplicates"))
        // Right of the cell, never over it: the sidebar stays readable while the menu is open.
        // The menu is its own window, so both are compared on screen.
        val sidebarRight = rule.onAllNodes(hasTestTag("selector_sidebar")).onFirst().fetchSemanticsNode()
            .let { it.positionOnScreen.x + it.size.width }
        val menuLeft = rule.onAllNodes(hasTestTag("nav_inspect")).onFirst().fetchSemanticsNode().positionOnScreen.x
        assertTrue("menu at $menuLeft px overlaps the sidebar ending at $sidebarRight px", menuLeft >= sidebarRight - 1)
    }

    @Test
    fun `a Tools row opens its screen and closes the menu`() {
        sidebar()
        click("nav_tools")
        click("nav_inspect")
        assertEquals(listOf(Screen.Inspect), navigated)
        assertEquals("the menu stayed open", 0, count("tools_menu"))

        click("nav_tools")
        click("nav_duplicates")
        assertEquals(listOf(Screen.Inspect, Screen.DuplicateFinder), navigated)
    }

    @Test
    fun `Tools is spoken with what it opens, and as selected on Learn and Dupes`() {
        assertEquals("Tools. Opens Learn and Dupes.", NavEntry.Tools.spoken(Screen.PhotoSelector.route).en())
        assertEquals("Tools, Learn selected", NavEntry.Tools.spoken(Screen.Inspect.route).en())
        assertEquals("Tools, Dupes selected", NavEntry.Tools.spoken(Screen.DuplicateFinder.route).en())
        assertTrue(NavEntry.Tools.isSelected(Screen.Inspect.route))
        assertTrue(NavEntry.Tools.isSelected(Screen.DuplicateFinder.route))
        assertTrue(!NavEntry.Tools.isSelected(Screen.Statistics.route))

        sidebar(currentRoute = Screen.PhotoSelector.route)
        assertEquals("Tools. Opens Learn and Dupes.", spoken("nav_tools"))
    }

    @Test
    fun `on Learn the rail shows Tools selected and says so`() {
        host {
            NavigationRail {
                NavEntry.all.forEach { RailCell(entry = it, currentRoute = Screen.Inspect.route, onNavigate = { s -> navigated += s }) }
            }
        }
        val tools = rule.onAllNodes(hasTestTag("nav_tools")).onFirst().fetchSemanticsNode().config
        assertEquals(true, tools.getOrNull(SemanticsProperties.Selected))
        assertTrue(spoken("nav_tools").contains("Tools, Learn selected"))
        val cull = rule.onAllNodes(hasTestTag("nav_selector")).onFirst().fetchSemanticsNode().config
        assertEquals(false, cull.getOrNull(SemanticsProperties.Selected))

        click("nav_tools")
        val learnRow = rule.onAllNodes(hasTestTag("nav_inspect")).onFirst().fetchSemanticsNode().config
        assertEquals(true, learnRow.getOrNull(SemanticsProperties.Selected))
        click("nav_duplicates")
        assertEquals(listOf(Screen.DuplicateFinder), navigated)
    }

    @Test
    fun `the bottom bar offers the same four cells and the same menu`() {
        host {
            NavigationBar {
                NavEntry.all.forEach { BarCell(entry = it, currentRoute = Screen.Statistics.route, onNavigate = { s -> navigated += s }) }
            }
        }
        NavEntry.all.forEach { assertEquals(it.testTag, 1, count(it.testTag)) }
        assertEquals(0, count("nav_inspect"))
        click("nav_tools")
        click("nav_inspect")
        assertEquals(listOf(Screen.Inspect), navigated)
    }

    @Test
    @Requirement("REQ-AND-INPUT.04")
    fun `cells and menu rows are at least 48 dp and the labels keep to one line`() {
        sidebar()
        NavEntry.all.forEach { entry ->
            val cell = bounds(entry.testTag)
            assertTrue("${entry.testTag} ${cell.right - cell.left} wide", (cell.right - cell.left).px() >= 48.dp.px())
            assertTrue("${entry.testTag} ${cell.bottom - cell.top} tall", (cell.bottom - cell.top).px() >= 48.dp.px())
            val word = rule.onAllNodes(hasText(entry.label.en()) and hasAnyAncestor(hasTestTag(entry.testTag)), useUnmergedTree = true)
                .onFirst().fetchSemanticsNode()
            val lines = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            word.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(lines)
            assertEquals("'${entry.label.en()}' wraps", 1, lines.single().lineCount)
            val layout = lines.single()
            // The paragraph of a centred word is laid out at the full constraint, so its line
            // metrics say nothing about the cell: the word's own bounds must lie inside the cell.
            assertTrue("'${entry.label.en()}' is ellipsized", !layout.isLineEllipsized(0))
            val wordBounds = rule.onAllNodes(hasText(entry.label.en()) and hasAnyAncestor(hasTestTag(entry.testTag)), useUnmergedTree = true)
                .onFirst().getUnclippedBoundsInRoot()
            assertTrue(
                "'${entry.label.en()}' $wordBounds leaves its cell $cell",
                wordBounds.left.px() >= cell.left.px() - 1 && wordBounds.right.px() <= cell.right.px() + 1,
            )
            assertTrue("'${entry.label.en()}' is clipped", kotlin.math.ceil(layout.multiParagraph.height).toInt() <= layout.size.height)
        }
        click("nav_tools")
        listOf("nav_inspect", "nav_duplicates").forEach { tag ->
            val row = bounds(tag)
            assertTrue("$tag ${row.bottom - row.top} tall", (row.bottom - row.top).px() >= 48.dp.px())
            assertTrue("$tag ${row.right - row.left} wide", (row.right - row.left).px() >= 48.dp.px())
        }
    }

    @Test
    fun `the guide lists the cells in sidebar order, Cull first, Learn and Dupes under Tools`() {
        val legend = screenLegend()
        assertEquals(listOf("Cull", "Stats", "Tools", "Setup"), legend.map { it.label.en() })
        assertTrue(legend[2].meaning.en().contains("Learn") && legend[2].meaning.en().contains("Dupes"))
        assertFalse(legend[2].meaning.en().contains("beta", ignoreCase = true))
    }

    @Test
    fun `the digit keys keep their meaning per screen`() {
        // Cull: 1 / 2 / 3 enlarge the frame in that place.
        val maximised = mutableListOf<SelectorFrame>()
        val navigatedByKey = mutableListOf<Screen>()
        listOf(Key.One, Key.Two, Key.Three, Key.L).forEach { key ->
            handleSelectorKey(
                key = key,
                sheetOpen = false,
                contextMenuOpen = false,
                fullscreenOpen = false,
                maximised = false,
                actions = SelectorActions({}, {}, {}, {}, {}, {}, {}, {}),
                onPrevious = {},
                onNext = {},
                onMaximise = { maximised += it },
                onShowShortcuts = {},
                onCloseOverlays = {},
                onNavigate = { navigatedByKey += it },
            )
        }
        assertEquals(listOf(SelectorFrame.PREVIOUS, SelectorFrame.CURRENT, SelectorFrame.NEXT), maximised)
        assertEquals("L still opens Learn directly", listOf(Screen.Inspect), navigatedByKey)

        // Learn: 1 to 5 rate.
        val ratings = mutableListOf<Int>()
        listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five).forEach { key ->
            handleInspectKey(key, onNavigateToSelector = {}, onPrevious = {}, onNext = {}, onSetRating = { ratings += it })
        }
        assertEquals(listOf(1, 2, 3, 4, 5), ratings)
    }
}
