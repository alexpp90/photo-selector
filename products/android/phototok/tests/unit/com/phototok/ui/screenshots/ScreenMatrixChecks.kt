package com.phototok.ui.screenshots

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult

/**
 * Checks for whole screens (REQ-TOK-UX.05, gap analysis 2026-11-09 ranks 6 and 7) that report
 * defects as a list instead of throwing, so one run over the variant matrix can name every cell
 * that fails, and an open finding can be held by `knownDefect` over the whole matrix.
 */

private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

/** The node itself or one of its ancestors carries [tag]. */
fun SemanticsNode.isUnder(tag: String): Boolean {
    var node: SemanticsNode? = this
    while (node != null) {
        if (node.tag() == tag) return true
        node = node.parent
    }
    return false
}

/** Unmerged nodes in or under the node tagged [tag], in every window. */
fun ComposeContentTestRule.nodesUnder(tag: String): List<SemanticsNode> = allNodes().filter { it.isUnder(tag) }

/** The bounds of the first node tagged [tag], or null when it is not composed. */
fun ComposeContentTestRule.boundsOf(tag: String): Rect? =
    allNodes().firstOrNull { it.tag() == tag }?.boundsInWindow

/** Texts of [nodes] that are ellipsized, wider than their space, or cut in height. */
fun clippedTexts(nodes: List<SemanticsNode>): List<String> = nodes.mapNotNull { node ->
    val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@mapNotNull null
    val results = ArrayList<TextLayoutResult>()
    if (!action(results) || results.isEmpty()) return@mapNotNull null
    val r = results.first()
    val lines = 0 until r.lineCount
    val ellipsized = lines.any { r.isLineEllipsized(it) }
    val tooWide = lines.any { r.getLineRight(it) - r.getLineLeft(it) > r.layoutInput.constraints.maxWidth + 1f }
    if (ellipsized || tooWide || r.didOverflowHeight) "'${node.label()}' (lines=${r.lineCount}, ellipsized=$ellipsized)" else null
}

/** The nodes that carry a click. */
fun List<SemanticsNode>.clickables(): List<SemanticsNode> =
    filter { it.config.contains(SemanticsActions.OnClick) }

/** The nodes a user can see or press: those with text or a click, and a non-empty visible size. */
fun List<SemanticsNode>.visibleContent(): List<SemanticsNode> = filter {
    (it.visibleText() != null || it.config.contains(SemanticsActions.OnClick)) &&
        it.boundsInWindow.width > 0.5f && it.boundsInWindow.height > 0.5f
}

/** Nodes with text or a click and a laid-out size, on screen or not. */
fun List<SemanticsNode>.laidOutContent(): List<SemanticsNode> = filter {
    (it.visibleText() != null || it.config.contains(SemanticsActions.OnClick)) && it.size.width > 0 && it.size.height > 0
}

/** True when the two rectangles share more than a one-pixel seam. */
fun Rect.overlapsBeyondSeam(other: Rect, seam: Float = 1f): Boolean {
    val i = intersect(other)
    return i.width > seam && i.height > seam
}

/** Every pair (a in [overlay], b in [controls]) whose bounds overlap. */
fun overlaps(overlay: List<SemanticsNode>, controls: List<SemanticsNode>): List<String> =
    overlay.flatMap { a ->
        controls.filter { b -> b.id != a.id && a.boundsInWindow.overlapsBeyondSeam(b.boundsInWindow) }
            .map { b -> "'${a.label()}' ${a.boundsInWindow} over '${b.label()}' ${b.boundsInWindow}" }
    }

/** Clickable nodes on screen whose touch target is under 48 dp. */
fun smallTargets(nodes: List<SemanticsNode>, density: Float): List<String> = nodes.clickables().mapNotNull {
    // A control scrolled out of its viewport has empty visible bounds: it is not on screen to tap.
    if (it.boundsInWindow.width <= 0.5f || it.boundsInWindow.height <= 0.5f) return@mapNotNull null
    val b = it.touchBoundsInRoot
    val min = 48f * density - 1f
    if (b.width >= min && b.height >= min) null else "'${it.label()}' ${b.width / density} x ${b.height / density} dp"
}

/** The node's full laid-out rectangle in the window; `boundsInWindow` is clipped to the window. */
fun SemanticsNode.unclippedBounds(): Rect = Rect(positionInWindow, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))

/**
 * Content nodes on screen that reach past [screen] (horizontally, and vertically when
 * [vertical]). Their full rectangle is compared, because `boundsInWindow` is already clipped to the
 * window and would hide exactly this defect; nodes wholly off screen (a pager's neighbour pages)
 * are not content the user sees.
 */
fun outside(
    nodes: List<SemanticsNode>,
    screen: Rect,
    vertical: Boolean = true,
    /** True for layers that have no off-screen pages: a control pushed wholly off screen is a defect there. */
    includeWhollyOffscreen: Boolean = false,
): List<String> =
    (if (includeWhollyOffscreen) nodes.laidOutContent() else nodes.visibleContent()).mapNotNull {
        val b = it.unclippedBounds()
        val h = b.left < screen.left - 1f || b.right > screen.right + 1f
        val v = vertical && (b.top < screen.top - 1f || b.bottom > screen.bottom + 1f)
        if (h || v) "'${it.label()}' $b" else null
    }

/**
 * Texts that wrap inside a word ("verbunde-n"): every line after the first must start after a
 * space, a hyphen or a slash. [TextLoadChecks.assertNoWordBroken] only sees one-word texts.
 */
fun brokenWords(nodes: List<SemanticsNode>): List<String> = nodes.mapNotNull { node ->
    val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@mapNotNull null
    val results = ArrayList<TextLayoutResult>()
    if (!action(results) || results.isEmpty()) return@mapNotNull null
    val r = results.first()
    val text = r.layoutInput.text.text
    val breaks = (1 until r.lineCount).map { r.getLineStart(it) }.filter { it in 1 until text.length }
    val inWord = breaks.filter { i -> !text[i - 1].isWhitespace() && !text[i].isWhitespace() && text[i - 1] !in "-‐/–" }
    if (inWord.isEmpty()) null else "'$text' wraps inside a word at ${inWord.map { text.substring(0, it).takeLast(8) + "|" + text.substring(it).take(4) }}"
}

/** Only the nodes that are at least partly inside [screen] (a pager composes its neighbours too). */
fun List<SemanticsNode>.onScreen(screen: Rect): List<SemanticsNode> = filter { it.boundsInWindow.overlapsBeyondSeam(screen) }
