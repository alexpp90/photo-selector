package com.phototok.ui.phonemode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.phototok.ui.phonemode.ControlsGuideSolver.Box as GBox
import com.phototok.ui.phonemode.ControlsGuideSolver.Size as GSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the guide's callouts point, in the overlay's own coordinates and dp.
 * [landscape] selects side-panel callouts; otherwise top/bottom-bar callouts.
 */
internal data class GuideGeometry(
    val landscape: Boolean,
    val anchors: Map<GuideAnchor, GBox>,
)

/** Converts measured (or, lacking those, nominal) chrome positions into [GuideGeometry]. */
internal fun guideGeometry(
    anchors: ControlsGuideAnchors?,
    origin: Offset,
    density: Float,
    width: Float,
    height: Float,
    landscape: Boolean,
    topInset: Float,
    bottomInset: Float,
): GuideGeometry {
    val measured = anchors?.bounds?.toMap().orEmpty()
    val hasChrome = GuideAnchor.TOP_BAR in measured || GuideAnchor.LEFT_PANEL in measured
    if (hasChrome) {
        val local = measured.mapValues { (_, r) ->
            GBox(
                (r.left - origin.x) / density,
                (r.top - origin.y) / density,
                (r.right - origin.x) / density,
                (r.bottom - origin.y) / density,
            )
        }
        return GuideGeometry(landscape = GuideAnchor.LEFT_PANEL in local, anchors = local)
    }
    val nominal = if (landscape) {
        ControlsGuideSolver.nominalLandscape(width, height, nominalMetrics())
    } else {
        ControlsGuideSolver.nominalPortrait(
            width, height, topInset, bottomInset, hasDoneWithCard = false, m = nominalMetrics(),
        )
    }
    return GuideGeometry(landscape, nominal)
}

private fun nominalMetrics() = with(PhoneModeChromeMetrics) {
    ControlsGuideSolver.Metrics(
        topBarHPadding = TOP_BAR_HORIZONTAL_PADDING.value,
        topBarVPadding = TOP_BAR_VERTICAL_PADDING.value,
        topBarLogo = TOP_BAR_LOGO_SIZE.value,
        topBarButton = TOP_BAR_BUTTON_SIZE.value,
        bottomHPadding = BOTTOM_BAR_HORIZONTAL_PADDING.value,
        bottomVPadding = BOTTOM_BAR_VERTICAL_PADDING.value,
        bottomButtonW = BOTTOM_BAR_BUTTON_WIDTH.value,
        bottomButtonH = BOTTOM_BAR_BUTTON_HEIGHT.value,
        leftPanelW = LEFT_PANEL_WIDTH.value,
        rightPanelW = RIGHT_PANEL_WIDTH.value,
        sideButtonW = SIDE_PANEL_BUTTON_WIDTH.value,
        sideButtonH = SIDE_PANEL_BUTTON_HEIGHT.value,
        sideSpacing = SIDE_PANEL_BUTTON_SPACING.value,
        leftLogo = LEFT_PANEL_LOGO_SIZE.value,
        leftVPadding = LEFT_PANEL_VERTICAL_PADDING.value,
        rightPadding = RIGHT_PANEL_PADDING.value,
    )
}

/**
 * The dimmed scrim hosting the guide: resolves the [GuideGeometry] and lays the callouts out
 * around [content] (title, gesture stage, dismiss button), which receives the space between them.
 */
@Composable
internal fun GuideBox(
    anchors: ControlsGuideAnchors?,
    isLandscape: Boolean,
    content: @Composable (geometry: GuideGeometry, compact: Boolean) -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // A scrim rather than an opaque sheet: the controls being explained
            // stay faintly visible underneath, which is the whole point.
            .background(Color(0xFF09090B).copy(alpha = 0.70f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { /* absorb taps */ }
            .onGloballyPositioned { origin = it.positionInRoot() }
            .testTag("controls_guide"),
    ) {
        val compact = isLandscape || maxHeight < 600.dp || maxWidth < 360.dp
        val density = LocalDensity.current
        // Window insets only apply to the part of the overlay that actually reaches the window
        // edge (it may be hosted in a smaller box).
        val rootHeightPx = LocalView.current.rootView.height
        val topInset = with(density) {
            (WindowInsets.statusBars.getTop(density) - origin.y.roundToInt()).coerceAtLeast(0).toDp()
        }
        val bottomInset = with(density) {
            val gap = rootHeightPx - (origin.y.roundToInt() + constraints.maxHeight)
            (WindowInsets.navigationBars.getBottom(density) - gap).coerceAtLeast(0).toDp()
        }
        val geometry = guideGeometry(
            anchors = anchors,
            origin = origin,
            density = density.density,
            width = maxWidth.value,
            height = maxHeight.value,
            landscape = isLandscape,
            topInset = topInset.value,
            bottomInset = bottomInset.value,
        )
        GuideLayout(geometry = geometry, compact = compact, density = density.density) {
            content(geometry, compact)
        }
    }
}

private data class CalloutKey(val id: GuideAnchor)
private data class LeaderKey(val id: GuideAnchor)
private object MiddleKey

private val TOP_ICONS = listOf(
    GuideAnchor.DONE_WITH_CARD,
    GuideAnchor.RELOAD,
    GuideAnchor.HELP,
    GuideAnchor.INFO,
    GuideAnchor.SETTINGS,
)
private val BOTTOM_BUTTONS = listOf(GuideAnchor.SOURCES, GuideAnchor.SELECTION, GuideAnchor.REVERT)
private val LEFT_PANEL_BUTTONS = listOf(
    GuideAnchor.LOGO,
    GuideAnchor.SOURCES,
    GuideAnchor.SELECTION,
    GuideAnchor.REVERT,
    GuideAnchor.HELP,
)
private val RIGHT_PANEL_BUTTONS = listOf(GuideAnchor.RELOAD, GuideAnchor.INFO, GuideAnchor.SETTINGS)

private enum class Align { CENTER, START, END }

/**
 * Custom layout: one callout plus leader line per labelled control, positioned by
 * [ControlsGuideSolver], and a single [content] block that fills whatever space is left.
 */
@Composable
private fun GuideLayout(
    geometry: GuideGeometry,
    compact: Boolean,
    density: Float,
    content: @Composable () -> Unit,
) {
    val a = geometry.anchors
    val landscape = geometry.landscape

    val topIcons = if (landscape) emptyList() else TOP_ICONS.filter { it in a }
    val logoTop = !landscape && GuideAnchor.LOGO in a
    val bottoms = if (landscape) emptyList() else BOTTOM_BUTTONS.filter { it in a }
    val leftSide = if (landscape) LEFT_PANEL_BUTTONS.filter { it in a } else emptyList()
    val rightSide = if (landscape) RIGHT_PANEL_BUTTONS.filter { it in a } else emptyList()

    val labelled: List<GuideAnchor> =
        topIcons + (if (logoTop) listOf(GuideAnchor.LOGO) else emptyList()) + bottoms + leftSide + rightSide
    val rightSet = rightSide.toSet()

    Layout(
        content = {
            labelled.forEach { id ->
                CoachMark(
                    id = id,
                    compact = compact,
                    align = when {
                        landscape && id in rightSet -> Align.END
                        landscape || id == GuideAnchor.LOGO -> Align.START
                        else -> Align.CENTER
                    },
                    descriptionLines = when {
                        id == GuideAnchor.LOGO -> if (compact) 1 else 3
                        landscape -> if (id in rightSet) 1 else 2
                        compact && (id in topIcons || id in bottoms) -> 0
                        else -> if (compact) 2 else 3
                    },
                    modifier = Modifier.layoutId(CalloutKey(id)),
                )
                Box(
                    modifier = Modifier
                        .layoutId(LeaderKey(id))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                )
            }
            Column(modifier = Modifier.layoutId(MiddleKey)) { content() }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val wDp = w / density
        val hDp = h / density
        fun px(dp: Float) = (dp * density).roundToInt()
        fun sizeOf(p: Placeable) = GSize(p.width / density, p.height / density)

        val callouts = measurables.filter { it.layoutId is CalloutKey }
            .associateBy { (it.layoutId as CalloutKey).id }
        val leaders = measurables.filter { it.layoutId is LeaderKey }
            .associateBy { (it.layoutId as LeaderKey).id }
        val middle = measurables.first { it.layoutId == MiddleKey }

        val placeables = HashMap<GuideAnchor, Placeable>()
        fun measureCallout(id: GuideAnchor, maxWidthDp: Float): GSize {
            val mw = max(px(maxWidthDp), 1)
            val p = callouts.getValue(id).measure(Constraints(0, mw, 0, Constraints.Infinity))
            placeables[id] = p
            return sizeOf(p)
        }

        val placed = mutableListOf<ControlsGuideSolver.Placed>()
        var middleX = 0
        var middleY = 0
        var middleW = w
        var middleH = h

        if (!landscape) {
            // ── Top bar ──────────────────────────────────────────────
            val iconBoxes = topIcons.associateWith { a.getValue(it) }
            val logoBox = a[GuideAnchor.LOGO]
            val barBottom = a[GuideAnchor.TOP_BAR]?.bottom
                ?: (iconBoxes.values.map { it.bottom } + listOfNotNull(logoBox?.bottom)).maxOrNull()
            var topOccupied = 0f
            if (barBottom != null) {
                topOccupied = barBottom
                val plans = ControlsGuideSolver.planTop(wDp, iconBoxes)
                val sizes = plans.associate { it.id to measureCallout(it.id, it.maxWidth) }
                var logoPlan: ControlsGuideSolver.LogoPlan? = null
                var logoSize: GSize? = null
                if (logoBox != null) {
                    logoPlan = ControlsGuideSolver.planLogo(wDp, logoBox, plans, sizes)
                    logoSize = measureCallout(GuideAnchor.LOGO, logoPlan.maxWidth)
                }
                val top = ControlsGuideSolver.placeTop(barBottom, plans, sizes, logoBox, logoPlan, logoSize)
                placed += top
                topOccupied = max(barBottom, top.maxOfOrNull { it.callout.bottom } ?: barBottom)
                if (top.isNotEmpty()) topOccupied += ControlsGuideSolver.ROW_GAP
            }

            // ── Bottom bar ───────────────────────────────────────────
            var bottomOccupied = hDp
            val barTop = a[GuideAnchor.BOTTOM_BAR]?.top
                ?: bottoms.map { a.getValue(it).top }.minOrNull()
            if (barTop != null) {
                bottomOccupied = barTop
                val plans = ControlsGuideSolver.planBottom(wDp, bottoms.associateWith { a.getValue(it) })
                val sizes = plans.associate { it.id to measureCallout(it.id, it.maxWidth) }
                val bottom = ControlsGuideSolver.placeBottom(barTop, plans, sizes)
                placed += bottom
                bottomOccupied = min(barTop, bottom.minOfOrNull { it.callout.top } ?: barTop) -
                    ControlsGuideSolver.ROW_GAP
            }
            middleY = px(topOccupied)
            middleH = max(px(bottomOccupied) - middleY, 0)
        } else {
            // ── Side panels ──────────────────────────────────────────
            val leftBoxes = leftSide.associateWith { a.getValue(it) }
            val rightBoxes = rightSide.associateWith { a.getValue(it) }
            val leftEdge = a[GuideAnchor.LEFT_PANEL]?.right ?: leftBoxes.values.maxOfOrNull { it.right } ?: 0f
            val rightEdge = a[GuideAnchor.RIGHT_PANEL]?.left ?: rightBoxes.values.minOfOrNull { it.left } ?: wDp
            val budget = ((rightEdge - leftEdge) * 0.27f).coerceIn(90f, 160f)
            val leftSizes = leftSide.associateWith { measureCallout(it, budget) }
            val rightSizes = rightSide.associateWith { measureCallout(it, budget) }
            val l = ControlsGuideSolver.placeSide(leftEdge, true, leftBoxes, leftSizes, 4f, hDp - 4f)
            val r = ControlsGuideSolver.placeSide(rightEdge, false, rightBoxes, rightSizes, 4f, hDp - 4f)
            placed += l
            placed += r
            val leftUsed = l.maxOfOrNull { it.callout.right } ?: leftEdge
            val rightUsed = r.minOfOrNull { it.callout.left } ?: rightEdge
            middleX = px(leftUsed + ControlsGuideSolver.GAP)
            middleW = max(px(rightUsed - ControlsGuideSolver.GAP) - middleX, 0)
        }

        val middleP = middle.measure(Constraints.fixed(middleW, middleH))
        val leaderPlaceables = placed.associate { p ->
            val lw = max(px(p.leader.width), 1)
            val lh = max(px(p.leader.height), 1)
            p.id to leaders.getValue(p.id).measure(Constraints.fixed(lw, lh))
        }
        // Callouts that were measured but not placed (should not happen) stay hidden.
        layout(w, h) {
            middleP.place(middleX, middleY)
            placed.forEach { p ->
                placeables.getValue(p.id).place(px(p.callout.left), px(p.callout.top))
                leaderPlaceables.getValue(p.id).place(px(p.leader.left), px(p.leader.top))
            }
        }
    }
}

/** One callout: optional glyph, a title and a short description under it. */
@Composable
private fun CoachMark(
    id: GuideAnchor,
    compact: Boolean,
    align: Align,
    descriptionLines: Int,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spec = coachMarkSpec(id, compact)
    val textAlign = when (align) {
        Align.CENTER -> TextAlign.Center
        Align.START -> TextAlign.Start
        Align.END -> TextAlign.End
    }
    Column(
        modifier = modifier.testTag("guide_callout_${id.name}"),
        horizontalAlignment = when (align) {
            Align.CENTER -> Alignment.CenterHorizontally
            Align.START -> Alignment.Start
            Align.END -> Alignment.End
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (spec.icon != null) {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(if (compact) 12.dp else 14.dp),
                )
            }
            Text(
                text = spec.label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (descriptionLines > 0) {
            Text(
                text = spec.description,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                textAlign = textAlign,
                maxLines = descriptionLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class CoachMarkSpec(val label: String, val description: String, val icon: ImageVector? = null)

private fun coachMarkSpec(id: GuideAnchor, compact: Boolean = false): CoachMarkSpec = when (id) {
    GuideAnchor.LOGO -> CoachMarkSpec(
        "Tap the logo",
        // Short enough for one line, so the row stays slim on small screens.
        if (compact) "Show or hide camera info" else "Show or hide the camera info on each photo",
    )
    GuideAnchor.DONE_WITH_CARD -> CoachMarkSpec("Card done", "Finish this card")
    GuideAnchor.RELOAD -> CoachMarkSpec("Reload", "Rescan folder")
    GuideAnchor.HELP -> CoachMarkSpec("Help", "This guide")
    GuideAnchor.INFO -> CoachMarkSpec("Info", "EXIF overlay")
    GuideAnchor.SETTINGS -> CoachMarkSpec("Settings", "Actions & filters")
    GuideAnchor.SOURCES -> CoachMarkSpec("Sources", "Pick another folder", Icons.Default.FolderOpen)
    GuideAnchor.SELECTION ->
        CoachMarkSpec("Selection", "Everything you filed away", Icons.Default.CollectionsBookmark)
    GuideAnchor.REVERT ->
        CoachMarkSpec("Revert", "Restore last deleted photo", Icons.Default.RestoreFromTrash)
    else -> CoachMarkSpec("", "")
}
