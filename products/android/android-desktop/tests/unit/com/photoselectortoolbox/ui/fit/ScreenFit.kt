package com.photoselectortoolbox.ui.fit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Frameduel's own font-scale x window-size fit checks (REQ-AND-INPUT.04, REQ-AND-LAYOUT.01/.02).
 *
 * A screen is hosted inside a [FitWindow] of exactly the cell's size, with the cell's font
 * scale on [LocalDensity] *outside* the theme (so `PhotoSelectorToolboxTheme`'s TV scale
 * multiplies it the way it multiplies the system's), and the measured semantics tree of that
 * window is checked by [collectFitViolations]:
 *
 * - `TEXT_OVERFLOW` — a text node's own layout overflowed (`TextLayoutResult.hasVisualOverflow`:
 *   ellipsised, clipped, or more lines than it may draw);
 * - `TEXT_CLIPPED` — a text node is cut by an ancestor's clip although no scroll container can
 *   bring the rest into view;
 * - `MID_WORD_BREAK` — a soft line break falls between two letters or digits ("Lesson/s");
 * - `SMALL_TARGET` — a clickable node's touch bounds are under 48 dp on an axis, in whole pixels;
 * - `OUTSIDE_WINDOW` — a clickable node not inside a scroll container reaches past the window;
 * - `CONTROLS_INTERSECT` — two clickable nodes, neither inside the other, share visible bounds.
 *
 * Only the window's own root is checked: popups and dialogs are their own windows.
 */
data class FitCell(val widthDp: Int, val heightDp: Int, val fontScale: Float) {
    val label: String get() = "${widthDp}x$heightDp@fs$fontScale"
    val compact: Boolean get() = widthDp < 600

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    val windowSizeClass: WindowSizeClass
        get() = WindowSizeClass.calculateFromSize(DpSize(widthDp.dp, heightDp.dp))
}

object FitMatrix {
    /**
     * The window sizes the device lab and the requirements name: the reference landscape window,
     * the Pixel Tablet, a DeX/Chromebook window, the lab's 720 x 560 Chromebook window, the
     * smallest Medium window, and a compact (< 600 dp, REQ-AND-LAYOUT.02) portrait window.
     */
    val sizes = listOf(1480 to 924, 1280 to 800, 1024 to 720, 720 to 560, 600 to 480, 360 to 720)
    val fontScales = listOf(1.0f, 1.3f, 2.0f)
    val cells: List<FitCell> = sizes.flatMap { (w, h) -> fontScales.map { FitCell(w, h, it) } }

    /** The Robolectric display every matrix test runs on: at least the largest cell. */
    const val QUALIFIERS = "w1480dp-h924dp-land-xhdpi"
}

const val FIT_WINDOW_TAG = "fit_window"

/** Hosts [content] in a window of exactly [cell]'s size and font scale, at the top-left. */
@Composable
fun FitWindow(cell: FitCell, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, cell.fontScale)) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .size(cell.widthDp.dp, cell.heightDp.dp)
                    .clipToBounds()
                    .testTag(FIT_WINDOW_TAG),
            ) { content() }
        }
    }
}

enum class FitCheck { TEXT_OVERFLOW, TEXT_CLIPPED, MID_WORD_BREAK, SMALL_TARGET, OUTSIDE_WINDOW, CONTROLS_INTERSECT }

data class FitViolation(
    val screen: String,
    val cell: FitCell,
    val check: FitCheck,
    /** The node, described by its tag, text or content description. */
    val node: String,
    val detail: String,
) {
    override fun toString() = "[$screen ${cell.label}] $check $node — $detail"
}

private fun SemanticsNode.unclippedBounds(): Rect {
    val p = positionInRoot
    return Rect(p.x, p.y, p.x + size.width, p.y + size.height)
}

/** True when no ancestor clips this node: what it draws is all of it. */
private fun SemanticsNode.drawnWhole(): Boolean {
    val u = unclippedBounds()
    val c = boundsInRoot
    return c.width > 0f && c.height > 0f &&
        kotlin.math.abs(u.left - c.left) <= 1f && kotlin.math.abs(u.top - c.top) <= 1f &&
        kotlin.math.abs(u.right - c.right) <= 1f && kotlin.math.abs(u.bottom - c.bottom) <= 1f
}

private fun SemanticsNode.ancestors(): Sequence<SemanticsNode> = generateSequence(parent) { it.parent }

private fun SemanticsNode.isScrollContainer(): Boolean =
    SemanticsProperties.VerticalScrollAxisRange in config ||
        SemanticsProperties.HorizontalScrollAxisRange in config ||
        SemanticsActions.ScrollBy in config

private fun SemanticsNode.insideScrollContainer(): Boolean = ancestors().any { it.isScrollContainer() }

private fun SemanticsNode.isAncestorOf(other: SemanticsNode): Boolean = other.ancestors().any { it.id == id }

/** A readable name: test tag, else own text, else content description, else the merged text below. */
fun SemanticsNode.describe(): String {
    config.getOrNull(SemanticsProperties.TestTag)?.let { return "#$it" }
    config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")?.takeIf { it.isNotBlank() }?.let { return "\"$it\"" }
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.let { return "cd=\"$it\"" }
    val below = mutableListOf<String>()
    fun walk(n: SemanticsNode) {
        n.config.getOrNull(SemanticsProperties.Text)?.let { below += it.joinToString(" ") }
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { below += it.joinToString(" ") }
        n.children.forEach(::walk)
    }
    children.forEach(::walk)
    return if (below.isEmpty()) "node${id}" else "<${below.joinToString(" / ").take(60)}>"
}

private fun SemanticsNode.allDescendants(): List<SemanticsNode> {
    val out = mutableListOf<SemanticsNode>()
    fun walk(n: SemanticsNode) {
        out += n
        n.children.forEach(::walk)
    }
    walk(this)
    return out
}

private fun SemanticsNode.textLayouts(): List<TextLayoutResult> {
    val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return emptyList()
    val results = mutableListOf<TextLayoutResult>()
    action(results)
    return results
}

/**
 * Why [layout] does not show its whole text, or null.
 *
 * - Ellipsis and exceeded `maxLines` are read directly, and are allowed only for user data
 *   ([isData]: a file, folder or lens name can be any length); a word of the app's own chrome
 *   is never cut.
 * - Width is the widest line's glyph extent against the node, never `didOverflowWidth`: the
 *   result the semantics action re-creates lays the paragraph out at the incoming maximum width
 *   while the node shrank to the text's intrinsic width, so `didOverflowWidth` is true for every
 *   short label that fits ("Folder": paragraph 152 px, node 64 px, text 64 px).
 * - Height counts a line as lost when its baseline lies below the node: a line box taller than
 *   its node by the leading alone still shows its glyphs.
 *
 * Overflows count beyond one pixel.
 */
fun textOverflow(
    layout: TextLayoutResult,
    isData: (String) -> Boolean = { false },
    autoSizeMin: TextUnit? = null,
): String? {
    val text = layout.layoutInput.text.text
    val ellipsised = (0 until layout.lineCount).filter { layout.isLineEllipsized(it) }
    if (autoSizeMin != null && ellipsised.isNotEmpty() && fitsAt(layout, autoSizeMin)) return null
    val mp = layout.multiParagraph
    val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
    val overW = widest - layout.size.width
    val lost = (0 until layout.lineCount).firstOrNull { layout.getLineBaseline(it) > layout.size.height + 1f }
    val why = when {
        ellipsised.isNotEmpty() && !isData(text) -> "ellipsised on line ${ellipsised.first() + 1}"
        mp.didExceedMaxLines && ellipsised.isEmpty() && !isData(text) ->
            "cut after ${layout.layoutInput.maxLines} line(s) with no ellipsis"
        overW > 1f -> "${overW.roundToInt()} px wider than its node"
        lost != null -> "line ${lost + 1} of ${layout.lineCount} has its baseline below the node"
        else -> return null
    }
    return "\"${text.take(60)}\" $why (lines=${layout.lineCount}, node ${layout.size}, " +
        "text ${widest.roundToInt()}x${mp.height.roundToInt()} at ${layout.layoutInput.style.fontSize})"
}

/**
 * True when [layout]'s text fits its node on one line at [fontSize]. A step-based auto-sized
 * text reports its *maximum* size through the semantics action, so an ellipsis read there may
 * be one the auto-size step-down avoids; this measures the smallest step the way it is drawn.
 */
private fun fitsAt(layout: TextLayoutResult, fontSize: TextUnit): Boolean {
    val input = layout.layoutInput
    val measured = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection).measure(
        text = input.text,
        style = input.style.copy(fontSize = fontSize),
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = layout.size.width),
    )
    return !measured.isLineEllipsized(0) && !measured.didOverflowWidth
}

/** The soft line breaks of [layout] that fall between two letters or digits. */
fun midWordBreaks(layout: TextLayoutResult): List<String> {
    val text = layout.layoutInput.text.text
    return (0 until layout.lineCount - 1).mapNotNull { line ->
        val end = layout.getLineEnd(line, visibleEnd = false)
        if (end <= 0 || end >= text.length) return@mapNotNull null
        val before = text[end - 1]
        val after = text[end]
        if (before.isLetterOrDigit() && after.isLetterOrDigit()) {
            text.substring(0, end).takeLastWhile { it.isLetterOrDigit() } + "/" +
                text.substring(end).takeWhile { it.isLetterOrDigit() }
        } else {
            null
        }
    }
}

/**
 * Every fit violation under [window] (the [FIT_WINDOW_TAG] node, unmerged tree). [px48] is
 * `48.dp.roundToPx()` at the test density.
 */
fun collectFitViolations(
    screen: String,
    cell: FitCell,
    window: SemanticsNode,
    px48: Int,
    policy: FitPolicy = FitPolicy(),
): List<FitViolation> {
    val out = mutableListOf<FitViolation>()
    val win = window.unclippedBounds()
    val nodes = window.allDescendants().drop(1)
    fun add(check: FitCheck, node: SemanticsNode, detail: String) {
        out += FitViolation(screen, cell, check, node.describe(), detail)
    }

    for (node in nodes) {
        for (layout in node.textLayouts()) {
            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            textOverflow(layout, policy::isData, policy.autoSizeMin[tag])?.let { add(FitCheck.TEXT_OVERFLOW, node, it) }
            midWordBreaks(layout).forEach { add(FitCheck.MID_WORD_BREAK, node, "breaks \"$it\"") }
        }
        if (SemanticsProperties.Text in node.config && !node.insideScrollContainer()) {
            val unclipped = node.unclippedBounds()
            val clipped = node.boundsInRoot
            val visible = clipped.width > 0f && clipped.height > 0f
            if (visible && (unclipped.width - clipped.width > 1f || unclipped.height - clipped.height > 1f)) {
                add(FitCheck.TEXT_CLIPPED, node, "drawn $clipped of $unclipped")
            }
        }
    }

    val controls = nodes.filter { SemanticsActions.OnClick in it.config && it.size.width > 0 && it.size.height > 0 }
    for (c in controls) {
        // The hit area accessibility services and ATF are given: the node's touch bounds, which
        // Material's minimumInteractiveComponentSize extends to 48 dp around a 40 dp Button.
        // They are clipped like boundsInRoot, so only a node drawn whole is measured; a matrix
        // reaches the rest of a scrolling screen through a variant scrolled to its end.
        val touch = c.touchBoundsInRoot
        val tw = touch.width.roundToInt()
        val th = touch.height.roundToInt()
        if (c.drawnWhole() && (tw < px48 || th < px48)) {
            add(FitCheck.SMALL_TARGET, c, "touch ${tw}x$th px (node ${c.size.width}x${c.size.height}) < $px48")
        }
        if (!c.insideScrollContainer()) {
            val b = c.unclippedBounds()
            if (b.left < win.left - 1 || b.top < win.top - 1 || b.right > win.right + 1 || b.bottom > win.bottom + 1) {
                add(FitCheck.OUTSIDE_WINDOW, c, "bounds $b, window $win")
            }
        }
    }
    val visibleControls = controls.filter { it.boundsInRoot.width > 1f && it.boundsInRoot.height > 1f }
    for (i in visibleControls.indices) for (j in i + 1 until visibleControls.size) {
        val a = visibleControls[i]
        val b = visibleControls[j]
        if (a.isAncestorOf(b) || b.isAncestorOf(a)) continue
        if (policy.mayShareBounds(a.describe(), b.describe())) continue
        val overlap = a.boundsInRoot.intersect(b.boundsInRoot)
        if (overlap.width > 1f && overlap.height > 1f) {
            out += FitViolation(
                screen, cell, FitCheck.CONTROLS_INTERSECT, "${a.describe()} x ${b.describe()}",
                "overlap ${overlap.width.roundToInt()}x${overlap.height.roundToInt()} px",
            )
        }
    }
    return out
}

/**
 * What the checks allow, each by a requirement: user data that may be ellipsised, and pairs of
 * controls that are specified to lie one inside the other's bounds.
 */
data class FitPolicy(
    /** Strings the fixture put on screen as user data (file, folder, lens, camera names). */
    val dataStrings: Set<String> = emptySet(),
    /** Pairs (by [describe] regex) that may share bounds, each with the requirement that says so. */
    val sharedBounds: List<SharedBounds> = emptyList(),
    /** Test tags of step-based auto-sized texts and the smallest step each may take. */
    val autoSizeMin: Map<String?, TextUnit> = emptyMap(),
) {
    fun isData(text: String) = dataStrings.any { it in text }
    fun mayShareBounds(a: String, b: String) = sharedBounds.any { it.matches(a, b) }
}

data class SharedBounds(val first: Regex, val second: Regex, val requirement: String) {
    fun matches(a: String, b: String) =
        (first.containsMatchIn(a) && second.containsMatchIn(b)) || (first.containsMatchIn(b) && second.containsMatchIn(a))
}

/**
 * A ledgered defect the fit matrix reproduces: [matches] picks out its violations. A matrix test
 * fails on every violation no entry matches, and through [knownDefect] on every entry for its
 * screen that no longer reproduces.
 */
data class KnownFitDefect(
    val id: String,
    val screen: Regex,
    val summary: String,
    /** Where the cause is, as `file:line` in product code. */
    val cause: String,
    val matches: (FitViolation) -> Boolean,
)

/**
 * Asserts [violations] hold nothing but the [known] defects, and that each known defect for
 * [screen] still reproduces (strict: a fixed defect fails here until its marker is removed).
 */
fun assertFitsExceptKnown(screen: String, violations: List<FitViolation>, known: List<KnownFitDefect>) {
    val unexplained = violations.filter { v -> known.none { it.matches(v) } }
    if (unexplained.isNotEmpty()) {
        val grouped = unexplained.groupBy { it.check to "${it.screen} ${it.node}" }.entries.joinToString("\n") { (k, list) ->
            "  ${k.first} ${k.second} at ${list.map { it.cell.label }.distinct().joinToString(",")}: ${list.first().detail}"
        }
        throw AssertionError("${unexplained.size} fit violation(s) on $screen no known defect explains:\n$grouped")
    }
    for (defect in known.filter { it.screen.containsMatchIn(screen) }) {
        knownDefect(defect.id) {
            val still = violations.filter(defect.matches)
            if (still.isNotEmpty()) throw AssertionError("${defect.id}: ${defect.summary} (${still.size} violations)")
        }
    }
}

/**
 * A known, ledgered defect: [block] asserts the *correct* behaviour and must fail today. Passes
 * only when [block] throws [AssertionError]; once the defect is fixed it fails, so the marker
 * cannot outlive the defect.
 */
fun knownDefect(id: String, block: () -> Unit) {
    try {
        block()
    } catch (expected: AssertionError) {
        return
    }
    throw AssertionError("$id is fixed: remove the knownDefect marker and keep the assertion")
}
