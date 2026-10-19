package com.phototok.ui.phonemode

import kotlin.math.max
import kotlin.math.min

/**
 * Pure placement of the controls-guide callouts. No Compose types: everything is plain floats in
 * one consistent unit (dp in production, anything in tests) so the rules can be unit-tested on
 * the JVM. The overlay measures the callouts and feeds the sizes in; this decides where they go.
 *
 * Rules (REQ-TOK-GESTURE.07):
 *  - a top callout is centred horizontally on its icon and starts below the top bar;
 *  - icons are ~one pitch apart, so callouts of adjacent icons alternate between two levels
 *    (rightmost = level 0). A callout's width is limited so same-level neighbours never touch and
 *    a level-1 leader passes through the gap between two level-0 callouts;
 *  - the logo callout is left-aligned at the logo, on the first level where it has room;
 *  - bottom callouts are centred on their button and end above the bottom bar;
 *  - landscape side-panel callouts sit beside the panel, vertically centred on their button.
 */
object ControlsGuideSolver {
    const val GAP = 8f
    const val MARGIN = 8f
    const val LEADER = 8f
    const val ROW_GAP = 4f
    const val LEADER_WIDTH = 1f
    const val MIN_LOGO_WIDTH = 120f
    const val MAX_LOGO_WIDTH = 220f

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        val centerX get() = (left + right) / 2f
        val centerY get() = (top + bottom) / 2f

        /** True when the interiors overlap (touching edges do not count). */
        fun intersects(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    }

    data class Size(val width: Float, val height: Float)

    /** A callout, its leader line and the control it labels. */
    data class Placed(val id: GuideAnchor, val callout: Box, val leader: Box, val level: Int = 0)

    // ── Top bar ──────────────────────────────────────────────────────────

    data class TopPlan(val id: GuideAnchor, val level: Int, val centerX: Float, val maxWidth: Float)

    /** Levels and maximum widths for the icon callouts; [icons] may be in any order. */
    fun planTop(screenWidth: Float, icons: Map<GuideAnchor, Box>): List<TopPlan> {
        val sorted = icons.entries.sortedByDescending { it.value.centerX }
        val levels = sorted.mapIndexed { i, _ -> i % 2 }
        return sorted.mapIndexed { i, (id, box) ->
            val cx = box.centerX
            var maxW = 2f * min(cx, screenWidth - cx) - MARGIN
            // Same-level neighbours (two steps away): share the distance between centres.
            for (j in listOf(i - 2, i + 2)) {
                if (j in sorted.indices) maxW = min(maxW, abs(cx - sorted[j].value.centerX) - GAP)
            }
            // Level-1 leaders cross the level-0 row: stay clear of them.
            if (levels[i] == 0) {
                sorted.forEachIndexed { j, other ->
                    if (levels[j] == 1) {
                        maxW = min(maxW, 2f * (abs(cx - other.value.centerX) - GAP / 2f))
                    }
                }
            }
            TopPlan(id, levels[i], cx, max(maxW, 0f))
        }
    }

    data class LogoPlan(val level: Int, val maxWidth: Float)

    /**
     * Picks the first level on which the logo callout has [MIN_LOGO_WIDTH], else level 2 (below
     * everything). [iconSizes] are the measured sizes of the icon callouts.
     */
    fun planLogo(
        screenWidth: Float,
        logo: Box,
        plans: List<TopPlan>,
        iconSizes: Map<GuideAnchor, Size>,
    ): LogoPlan {
        val leaderX = logo.centerX
        for (level in 0..2) {
            var right = screenWidth - MARGIN
            var valid = true
            for (p in plans) {
                val w = iconSizes[p.id]?.width ?: p.maxWidth
                val left = p.centerX - w / 2f
                val r = p.centerX + w / 2f
                when {
                    p.level == level -> if (r > logo.left) right = min(right, left - GAP)
                    p.level > level -> if (p.centerX > logo.left) right = min(right, p.centerX - GAP)
                    else -> if (leaderX > left - GAP / 2f && leaderX < r + GAP / 2f) valid = false
                }
            }
            val width = min(right - logo.left, MAX_LOGO_WIDTH)
            if (valid && (width >= MIN_LOGO_WIDTH || level == 2)) return LogoPlan(level, max(width, 0f))
        }
        return LogoPlan(2, min(screenWidth - MARGIN - logo.left, MAX_LOGO_WIDTH))
    }

    /**
     * Places the top callouts below [barBottom]. [logoPlan]/[logoBox]/[logoSize] are optional.
     */
    fun placeTop(
        barBottom: Float,
        plans: List<TopPlan>,
        sizes: Map<GuideAnchor, Size>,
        logoBox: Box? = null,
        logoPlan: LogoPlan? = null,
        logoSize: Size? = null,
    ): List<Placed> {
        val levelCount = 3
        val rowHeight = FloatArray(levelCount)
        plans.forEach { p ->
            sizes[p.id]?.let { rowHeight[p.level] = max(rowHeight[p.level], it.height) }
        }
        if (logoPlan != null && logoSize != null) {
            rowHeight[logoPlan.level] = max(rowHeight[logoPlan.level], logoSize.height)
        }
        val rowTop = FloatArray(levelCount)
        var y = barBottom + LEADER
        for (k in 0 until levelCount) {
            rowTop[k] = y
            if (rowHeight[k] > 0f) y += rowHeight[k] + ROW_GAP
        }
        val out = mutableListOf<Placed>()
        plans.forEach { p ->
            val s = sizes[p.id] ?: return@forEach
            val top = rowTop[p.level]
            val callout = Box(p.centerX - s.width / 2f, top, p.centerX + s.width / 2f, top + s.height)
            out += Placed(p.id, callout, verticalLeader(p.centerX, barBottom, top), p.level)
        }
        if (logoBox != null && logoPlan != null && logoSize != null) {
            val top = rowTop[logoPlan.level]
            val callout = Box(logoBox.left, top, logoBox.left + logoSize.width, top + logoSize.height)
            out += Placed(GuideAnchor.LOGO, callout, verticalLeader(logoBox.centerX, barBottom, top), logoPlan.level)
        }
        return out
    }

    private fun verticalLeader(x: Float, from: Float, to: Float) =
        Box(x - LEADER_WIDTH / 2f, from, x + LEADER_WIDTH / 2f, max(to, from))

    // ── Bottom bar ───────────────────────────────────────────────────────

    data class BottomPlan(val id: GuideAnchor, val centerX: Float, val maxWidth: Float)

    fun planBottom(screenWidth: Float, buttons: Map<GuideAnchor, Box>): List<BottomPlan> {
        val sorted = buttons.entries.sortedBy { it.value.centerX }
        return sorted.mapIndexed { i, (id, box) ->
            val cx = box.centerX
            var maxW = 2f * min(cx, screenWidth - cx) - MARGIN
            for (j in listOf(i - 1, i + 1)) {
                if (j in sorted.indices) maxW = min(maxW, abs(cx - sorted[j].value.centerX) - GAP)
            }
            BottomPlan(id, cx, max(maxW, 0f))
        }
    }

    fun placeBottom(barTop: Float, plans: List<BottomPlan>, sizes: Map<GuideAnchor, Size>): List<Placed> =
        plans.mapNotNull { p ->
            val s = sizes[p.id] ?: return@mapNotNull null
            val bottom = barTop - LEADER
            val callout = Box(p.centerX - s.width / 2f, bottom - s.height, p.centerX + s.width / 2f, bottom)
            Placed(p.id, callout, verticalLeader(p.centerX, bottom, barTop))
        }

    // ── Landscape side panels ────────────────────────────────────────────

    /**
     * Callouts beside a side panel. [panelEdgeX] is the panel's inner edge (its right edge for
     * the left panel, its left edge for the right panel); callouts grow away from the panel.
     * They are centred vertically on their button, then pushed apart where they would collide.
     */
    fun placeSide(
        panelEdgeX: Float,
        growsRight: Boolean,
        buttons: Map<GuideAnchor, Box>,
        sizes: Map<GuideAnchor, Size>,
        minY: Float,
        maxY: Float,
    ): List<Placed> {
        val items = buttons.entries.filter { sizes.containsKey(it.key) }.sortedBy { it.value.centerY }
        val tops = FloatArray(items.size)
        items.forEachIndexed { i, (id, b) ->
            tops[i] = b.centerY - sizes.getValue(id).height / 2f
        }
        // Sweep down to remove overlaps, then back up if the last one left the allowed band.
        for (i in items.indices) {
            val h = sizes.getValue(items[i].key).height
            if (i > 0) {
                val prevBottom = tops[i - 1] + sizes.getValue(items[i - 1].key).height + 2f
                tops[i] = max(tops[i], prevBottom)
            }
            tops[i] = max(tops[i], minY)
            if (tops[i] + h > maxY) tops[i] = maxY - h
        }
        for (i in items.indices.reversed()) {
            val h = sizes.getValue(items[i].key).height
            if (i < items.size - 1) tops[i] = min(tops[i], tops[i + 1] - 2f - h)
        }
        return items.mapIndexed { i, (id, b) ->
            val s = sizes.getValue(id)
            val left = if (growsRight) panelEdgeX + LEADER else panelEdgeX - LEADER - s.width
            val callout = Box(left, tops[i], left + s.width, tops[i] + s.height)
            val y = b.centerY.coerceIn(callout.top, callout.bottom)
            val leader = if (growsRight) {
                Box(panelEdgeX, y - LEADER_WIDTH / 2f, callout.left, y + LEADER_WIDTH / 2f)
            } else {
                Box(callout.right, y - LEADER_WIDTH / 2f, panelEdgeX, y + LEADER_WIDTH / 2f)
            }
            Placed(id, callout, leader)
        }
    }

    // ── Fallback geometry (no anchors registered) ────────────────────────

    /** Metrics the nominal geometry needs; defaults mirror [PhoneModeChromeMetrics] in dp. */
    data class Metrics(
        val topBarHPadding: Float = 16f,
        val topBarVPadding: Float = 8f,
        val topBarLogo: Float = 36f,
        val topBarButton: Float = 48f,
        val bottomHPadding: Float = 24f,
        val bottomVPadding: Float = 6f,
        val bottomButtonW: Float = 72f,
        val bottomButtonH: Float = 48f,
        val leftPanelW: Float = 72f,
        val rightPanelW: Float = 125f,
        val sideButtonW: Float = 64f,
        val sideButtonH: Float = 48f,
        val sideSpacing: Float = 16f,
        val leftLogo: Float = 32f,
        val leftVPadding: Float = 16f,
        val rightPadding: Float = 12f,
    )

    /** Anchors the real portrait chrome would produce on a [width] x [height] screen. */
    fun nominalPortrait(
        width: Float,
        height: Float,
        topInset: Float,
        bottomInset: Float,
        hasDoneWithCard: Boolean,
        m: Metrics = Metrics(),
    ): Map<GuideAnchor, Box> {
        val out = LinkedHashMap<GuideAnchor, Box>()
        val barH = m.topBarButton + 2 * m.topBarVPadding
        out[GuideAnchor.TOP_BAR] = Box(0f, topInset, width, topInset + barH)
        val cy = topInset + barH / 2f
        out[GuideAnchor.LOGO] = Box(
            m.topBarHPadding, cy - m.topBarLogo / 2f, m.topBarHPadding + m.topBarLogo, cy + m.topBarLogo / 2f,
        )
        val order = buildList {
            if (hasDoneWithCard) add(GuideAnchor.DONE_WITH_CARD)
            add(GuideAnchor.RELOAD); add(GuideAnchor.HELP); add(GuideAnchor.INFO); add(GuideAnchor.SETTINGS)
        }
        var right = width - m.topBarHPadding
        for (id in order.reversed()) {
            out[id] = Box(right - m.topBarButton, cy - m.topBarButton / 2f, right, cy + m.topBarButton / 2f)
            right -= m.topBarButton
        }
        val barTop = height - bottomInset - (m.bottomButtonH + 2 * m.bottomVPadding)
        out[GuideAnchor.BOTTOM_BAR] = Box(0f, barTop, width, height - bottomInset)
        val inner = width - 2 * m.bottomHPadding
        val free = inner - 3 * m.bottomButtonW
        val by = barTop + m.bottomVPadding
        listOf(GuideAnchor.SOURCES, GuideAnchor.SELECTION, GuideAnchor.REVERT).forEachIndexed { i, id ->
            val left = m.bottomHPadding + free / 6f + i * (m.bottomButtonW + free / 3f)
            out[id] = Box(left, by, left + m.bottomButtonW, by + m.bottomButtonH)
        }
        return out
    }

    /** Anchors the real landscape side panels would produce. */
    fun nominalLandscape(
        width: Float,
        height: Float,
        m: Metrics = Metrics(),
    ): Map<GuideAnchor, Box> {
        val out = LinkedHashMap<GuideAnchor, Box>()
        out[GuideAnchor.LEFT_PANEL] = Box(0f, 0f, m.leftPanelW, height)
        out[GuideAnchor.RIGHT_PANEL] = Box(width - m.rightPanelW, 0f, width, height)
        val lcx = m.leftPanelW / 2f
        val inner = height - 2 * m.leftVPadding
        val buttonsH = 4 * m.sideButtonH + 3 * m.sideSpacing
        val spacer = 24f
        val free = max(inner - m.leftLogo - buttonsH - spacer, 0f)
        val logoTop = m.leftVPadding
        out[GuideAnchor.LOGO] = Box(lcx - m.leftLogo / 2f, logoTop, lcx + m.leftLogo / 2f, logoTop + m.leftLogo)
        var y = logoTop + m.leftLogo + free / 2f
        listOf(GuideAnchor.SOURCES, GuideAnchor.SELECTION, GuideAnchor.REVERT, GuideAnchor.HELP).forEach { id ->
            out[id] = Box(lcx - m.sideButtonW / 2f, y, lcx + m.sideButtonW / 2f, y + m.sideButtonH)
            y += m.sideButtonH + m.sideSpacing
        }
        val rcx = width - m.rightPanelW / 2f
        var ry = m.rightPadding
        listOf(GuideAnchor.RELOAD, GuideAnchor.INFO, GuideAnchor.SETTINGS).forEach { id ->
            out[id] = Box(rcx - m.topBarButton / 2f, ry, rcx + m.topBarButton / 2f, ry + m.topBarButton)
            ry += m.topBarButton
        }
        return out
    }

    private fun abs(v: Float) = if (v < 0f) -v else v
}
