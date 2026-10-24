package com.phototok.ui.phonemode

import com.phototok.ui.phonemode.ControlsGuideSolver.Box
import com.phototok.ui.phonemode.ControlsGuideSolver.Placed
import com.phototok.ui.phonemode.ControlsGuideSolver.Size
import kotlin.math.abs
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsGuideSolverTest {

    private val topIcons = listOf(
        GuideAnchor.DONE_WITH_CARD, GuideAnchor.RELOAD, GuideAnchor.HELP, GuideAnchor.INFO, GuideAnchor.SETTINGS,
    )
    private val bottomButtons =
        listOf(GuideAnchor.SOURCES, GuideAnchor.SELECTION, GuideAnchor.SHARE, GuideAnchor.REVERT)

    /** Stand-in for text measurement: as wide as allowed up to [natural], a fixed height. */
    private fun sizeFor(maxWidth: Float, natural: Float = 120f, height: Float = 40f) =
        Size(min(maxWidth, natural), height)

    private class Portrait(
        val width: Float,
        val anchors: Map<GuideAnchor, Box>,
        val placed: List<Placed>,
    )

    private fun portrait(width: Float, sd: Boolean, naturalWidth: Float = 120f): Portrait {
        val a = ControlsGuideSolver.nominalPortrait(width, 800f, 24f, 24f, sd)
        val icons = topIcons.filter { it in a }.associateWith { a.getValue(it) }
        val plans = ControlsGuideSolver.planTop(width, icons)
        val sizes = plans.associate { it.id to sizeFor(it.maxWidth, naturalWidth) }
        val logo = a.getValue(GuideAnchor.LOGO)
        val logoPlan = ControlsGuideSolver.planLogo(width, logo, plans, sizes)
        val logoSize = Size(min(logoPlan.maxWidth, 200f), 54f)
        val top = ControlsGuideSolver.placeTop(
            a.getValue(GuideAnchor.TOP_BAR).bottom, plans, sizes, logo, logoPlan, logoSize,
        )
        val bPlans = ControlsGuideSolver.planBottom(
            width, bottomButtons.associateWith { a.getValue(it) }, bottomButtons.associateWith { naturalWidth },
        )
        val bSizes = bPlans.associate { it.id to sizeFor(it.maxWidth, naturalWidth, 52f) }
        val bottom = ControlsGuideSolver.placeBottom(a.getValue(GuideAnchor.BOTTOM_BAR).top, bPlans, bSizes)
        return Portrait(width, a, top + bottom)
    }

    private fun assertNoOverlapsAndLeadersClear(placed: List<Placed>, width: Float) {
        for (p in placed) {
            assertTrue("${p.id} left of screen", p.callout.left >= 0f)
            assertTrue("${p.id} right of screen", p.callout.right <= width + 0.01f)
        }
        for (i in placed.indices) for (j in placed.indices) {
            if (i < j) assertFalse(
                "callouts ${placed[i].id} and ${placed[j].id} overlap",
                placed[i].callout.intersects(placed[j].callout),
            )
            if (i != j) assertFalse(
                "leader of ${placed[i].id} crosses callout ${placed[j].id}",
                placed[i].leader.intersects(placed[j].callout),
            )
        }
    }

    @Test
    fun top_and_bottom_callouts_obey_the_placement_rules_on_393_and_320() {
        for (width in listOf(393f, 320f, 360f, 411f)) for (sd in listOf(false, true)) {
            for (natural in listOf(60f, 120f, 400f)) {
                val r = portrait(width, sd, natural)
                val tag = "w=$width sd=$sd natural=$natural"
                assertNoOverlapsAndLeadersClear(r.placed, width)
                val barBottom = r.anchors.getValue(GuideAnchor.TOP_BAR).bottom
                val barTop = r.anchors.getValue(GuideAnchor.BOTTOM_BAR).top
                for (p in r.placed) {
                    when (p.id) {
                        in bottomButtons -> {
                            assertTrue("$tag ${p.id} below bottom bar top", p.callout.bottom <= barTop)
                            // Centred, or slid sideways where neighbours / the screen edge leave no
                            // room; either way it covers its button's x so the leader attaches.
                            val cx = r.anchors.getValue(p.id).centerX
                            assertTrue("$tag ${p.id} covers its button", cx >= p.callout.left && cx <= p.callout.right)
                            if (natural <= 60f) assertEquals("$tag ${p.id} centred", cx, p.callout.centerX, 1f)
                        }
                        GuideAnchor.LOGO -> {
                            assertTrue("$tag logo below bar", p.callout.top >= barBottom)
                            assertEquals(r.anchors.getValue(GuideAnchor.LOGO).left, p.callout.left, 0.01f)
                        }
                        else -> {
                            assertTrue("$tag ${p.id} below bar", p.callout.top >= barBottom)
                            assertEquals(
                                "$tag ${p.id} centred", r.anchors.getValue(p.id).centerX, p.callout.centerX, 1f,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun adjacent_icons_alternate_levels_with_the_rightmost_on_level_zero() {
        val r = portrait(393f, sd = true)
        val levels = topIcons.map { id -> id to r.placed.first { it.id == id }.level }
        assertEquals(listOf(0, 1, 0, 1, 0), levels.reversed().map { it.second })
    }

    @Test
    fun a_card_done_callout_exists_only_when_the_anchor_does() {
        assertTrue(portrait(393f, sd = true).placed.any { it.id == GuideAnchor.DONE_WITH_CARD })
        assertFalse(portrait(393f, sd = false).placed.any { it.id == GuideAnchor.DONE_WITH_CARD })
    }

    @Test
    fun logo_on_a_393dp_phone_gets_room_for_a_three_line_description_on_level_zero_or_one() {
        val r = portrait(393f, sd = false)
        val logo = r.placed.first { it.id == GuideAnchor.LOGO }
        assertTrue("logo callout wide enough (${logo.callout.width})", logo.callout.width >= 120f)
        assertTrue(logo.level <= 1)
    }

    @Test
    fun logo_drops_below_the_icon_callouts_when_the_bar_is_full_width() {
        val r = portrait(320f, sd = true)
        val logo = r.placed.first { it.id == GuideAnchor.LOGO }
        assertEquals(2, logo.level)
        assertNoOverlapsAndLeadersClear(r.placed, 320f)
    }

    @Test
    fun landscape_side_callouts_sit_beside_the_panels_and_do_not_overlap() {
        for (height in listOf(360f, 411f, 392f)) {
            val width = 800f
            val a = ControlsGuideSolver.nominalLandscape(width, height)
            val leftIds = listOf(
                GuideAnchor.LOGO, GuideAnchor.SOURCES, GuideAnchor.SELECTION, GuideAnchor.SHARE,
                GuideAnchor.REVERT, GuideAnchor.HELP,
            )
            val rightIds = listOf(GuideAnchor.RELOAD, GuideAnchor.INFO, GuideAnchor.SETTINGS)
            val leftEdge = a.getValue(GuideAnchor.LEFT_PANEL).right
            val rightEdge = a.getValue(GuideAnchor.RIGHT_PANEL).left
            val l = ControlsGuideSolver.placeSide(
                leftEdge, true, leftIds.associateWith { a.getValue(it) },
                leftIds.associateWith { Size(140f, 40f) }, 4f, height - 4f,
            )
            val r = ControlsGuideSolver.placeSide(
                rightEdge, false, rightIds.associateWith { a.getValue(it) },
                rightIds.associateWith { Size(140f, 28f) }, 4f, height - 4f,
            )
            val all = l + r
            assertNoOverlapsAndLeadersClear(all, width)
            l.forEach { assertTrue("${it.id} right of left panel", it.callout.left >= leftEdge) }
            r.forEach { assertTrue("${it.id} left of right panel", it.callout.right <= rightEdge) }
            // Vertically centred on the button wherever nothing had to be pushed aside.
            r.forEach {
                assertEquals("${it.id} centred", a.getValue(it.id).centerY, it.callout.centerY, 1f)
            }
            assertTrue(all.none { abs(it.leader.height) > 2f })
        }
    }

    @Test
    fun nominal_portrait_matches_the_real_bar_geometry() {
        // 48dp buttons, 16dp side padding: Settings ends 16dp from the edge, icons are 48dp apart.
        val a = ControlsGuideSolver.nominalPortrait(393f, 851f, 24f, 24f, true)
        assertEquals(393f - 16f, a.getValue(GuideAnchor.SETTINGS).right, 0.01f)
        assertEquals(48f, a.getValue(GuideAnchor.SETTINGS).centerX - a.getValue(GuideAnchor.INFO).centerX, 0.01f)
        assertEquals(24f + 64f, a.getValue(GuideAnchor.TOP_BAR).bottom, 0.01f)
        assertEquals(851f - 24f - 60f, a.getValue(GuideAnchor.BOTTOM_BAR).top, 0.01f)
        // Four 72dp buttons in the bottom bar, in the order Sources, Selection, Share, Revert.
        val xs = bottomButtons.map { a.getValue(it).centerX }
        assertEquals(xs.sorted(), xs)
        assertTrue(bottomButtons.all { a.getValue(it).width == 72f })
        // Five buttons and the logo fit a 320dp phone.
        val narrow = ControlsGuideSolver.nominalPortrait(320f, 480f, 0f, 0f, true)
        assertTrue(narrow.getValue(GuideAnchor.DONE_WITH_CARD).left > narrow.getValue(GuideAnchor.LOGO).right)
    }

    @Test
    fun casual_portrait_has_no_logo_or_info_anchor_and_the_rest_keeps_its_place() {
        val pro = ControlsGuideSolver.nominalPortrait(393f, 851f, 24f, 24f, true)
        val casual = ControlsGuideSolver.nominalPortrait(393f, 851f, 24f, 24f, true, hasCameraInfo = false)
        assertFalse(GuideAnchor.LOGO in casual)
        assertFalse(GuideAnchor.INFO in casual)
        // Right-aligned: Settings and Help stay where they are, Reload moves one button right.
        assertEquals(pro.getValue(GuideAnchor.SETTINGS), casual.getValue(GuideAnchor.SETTINGS))
        assertEquals(48f, casual.getValue(GuideAnchor.SETTINGS).centerX - casual.getValue(GuideAnchor.HELP).centerX, 0.01f)
        val plans = ControlsGuideSolver.planTop(393f, topIcons.filter { it in casual }.associateWith { casual.getValue(it) })
        assertTrue(plans.none { it.id == GuideAnchor.INFO || it.id == GuideAnchor.LOGO })
    }

    @Test
    fun casual_landscape_has_no_logo_or_info_anchor_but_keeps_the_other_buttons() {
        val pro = ControlsGuideSolver.nominalLandscape(800f, 360f)
        val casual = ControlsGuideSolver.nominalLandscape(800f, 360f, hasCameraInfo = false)
        assertFalse(GuideAnchor.LOGO in casual)
        assertFalse(GuideAnchor.INFO in casual)
        for (id in listOf(GuideAnchor.SOURCES, GuideAnchor.SELECTION, GuideAnchor.SHARE, GuideAnchor.REVERT, GuideAnchor.HELP)) {
            assertEquals("$id does not move", pro.getValue(id), casual.getValue(id))
        }
        assertTrue(GuideAnchor.RELOAD in casual && GuideAnchor.SETTINGS in casual)
    }

    private fun bottomRow(width: Float, naturalWidth: Float, height: Float = 52f): List<Placed> {
        val a = ControlsGuideSolver.nominalPortrait(width, 800f, 24f, 24f, false)
        val plans = ControlsGuideSolver.planBottom(
            width, bottomButtons.associateWith { a.getValue(it) }, bottomButtons.associateWith { naturalWidth },
        )
        val sizes = plans.associate { it.id to sizeFor(it.maxWidth, naturalWidth, height) }
        return ControlsGuideSolver.placeBottom(a.getValue(GuideAnchor.BOTTOM_BAR).top, plans, sizes)
    }

    @Test
    fun four_bottom_buttons_fit_the_bar_on_320_and_360dp_phones() {
        for (width in listOf(320f, 360f, 393f)) {
            val a = ControlsGuideSolver.nominalPortrait(width, 800f, 24f, 24f, false)
            val boxes = bottomButtons.map { a.getValue(it) }
            assertTrue("w=$width first button inside the bar", boxes.first().left >= 0f)
            assertTrue("w=$width last button inside the bar", boxes.last().right <= width)
            for (i in 0 until boxes.size - 1) {
                assertTrue("w=$width buttons ${i} and ${i + 1} do not overlap", boxes[i].right <= boxes[i + 1].left)
            }
        }
    }

    @Test
    fun four_short_bottom_callouts_stay_on_one_level_when_they_fit() {
        val placed = bottomRow(393f, naturalWidth = 50f)
        assertTrue(placed.all { it.level == 0 })
        assertNoOverlapsAndLeadersClear(placed, 393f)
    }

    @Test
    fun four_wide_bottom_callouts_at_360dp_alternate_two_levels_and_never_overlap() {
        // 120 dp is a long label at large font scale (labelMedium at 2.0 is about 24 sp).
        for (natural in listOf(90f, 120f, 200f, 400f)) {
            val placed = bottomRow(360f, naturalWidth = natural, height = 60f)
            assertEquals("every bottom button gets a callout", 4, placed.size)
            assertNoOverlapsAndLeadersClear(placed, 360f)
            val byX = placed.sortedBy { it.callout.centerX }
            assertEquals("adjacent callouts alternate", listOf(0, 1, 0, 1), byX.map { it.level })
            // Level 1 sits above level 0; every leader still reaches the bar.
            val level0Top = byX.filter { it.level == 0 }.minOf { it.callout.top }
            byX.filter { it.level == 1 }.forEach { assertTrue("natural=$natural", it.callout.bottom <= level0Top) }
            val barTop = ControlsGuideSolver.nominalPortrait(360f, 800f, 24f, 24f, false)
                .getValue(GuideAnchor.BOTTOM_BAR).top
            placed.forEach { assertEquals("leader reaches the bar", barTop, it.leader.bottom, 0.01f) }
            // The leader of every callout stays attached to it.
            placed.forEach {
                assertTrue("${it.id} leader under its callout", it.leader.centerX >= it.callout.left && it.leader.centerX <= it.callout.right)
            }
        }
    }

    @Test
    fun staggered_bottom_callouts_get_about_two_button_pitches() {
        val a = ControlsGuideSolver.nominalPortrait(360f, 800f, 24f, 24f, false)
        val plans = ControlsGuideSolver.planBottom(
            360f, bottomButtons.associateWith { a.getValue(it) }, bottomButtons.associateWith { 120f },
        )
        val pitch = a.getValue(GuideAnchor.SELECTION).centerX - a.getValue(GuideAnchor.SOURCES).centerX
        // The two inner buttons are not limited by the screen edge.
        plans.filter { it.id == GuideAnchor.SELECTION || it.id == GuideAnchor.SHARE }
            .forEach { assertTrue("${it.id} maxWidth ${it.maxWidth} vs pitch $pitch", it.maxWidth > pitch) }
    }

    @Test
    fun bottom_callouts_without_measured_widths_stay_on_one_level() {
        val a = ControlsGuideSolver.nominalPortrait(360f, 800f, 24f, 24f, false)
        val plans = ControlsGuideSolver.planBottom(360f, bottomButtons.associateWith { a.getValue(it) })
        assertTrue(plans.all { it.level == 0 })
    }
}
