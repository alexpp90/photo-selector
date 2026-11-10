package com.photoselectortoolbox.domain.guidance

import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.interaction.FrameGesture
import com.photoselectortoolbox.domain.interaction.SelectorShortcut
import com.photoselectortoolbox.domain.scoring.ScoreMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide is generated from the bindings, and cannot advertise anything else.
 *
 * The sheet this replaces was mostly generated already — and the four rows that
 * were *not* included one claiming the maximise control is a `⛱` badge, a glyph
 * this product has never rendered. That is the 2026-07-31 lesson exactly: a
 * guide is where a plausible-sounding lie survives review indefinitely.
 */
class SelectorTourTest {

    private val everyConfiguration = FilingAction.entries

    @Test
    fun `the advertised set equals the bound set`() {
        // The invariant that makes the whole class of bug impossible: every key
        // and gesture the selector binds appears in the guide, and nothing
        // appears that is not bound. Not "today's strings look right".
        val bound = SelectorShortcut.entries.map { it.input }.toSet() +
            FrameGesture.entries.map { it.input }.toSet()

        everyConfiguration.forEach { action ->
            assertEquals(bound, SelectorTour.advertisedInputs(action))
        }
    }

    @Test
    fun `every bound shortcut is advertised exactly once`() {
        // Twice would be a guide that has grown a second, drifting copy of a
        // key — the shape of the original defect.
        val inputs = SelectorTour
            .callouts(FilingAction.COPY, detailsVisible = true, filmstripVisible = true)
            .flatMap { it.rows }
            .map { it.input }

        assertEquals(inputs.size, inputs.toSet().size)
    }

    @Test
    fun `the frame gestures advertised are the frame gestures bound`() {
        val bound = FrameGesture.entries.map { it.input }.toSet()
        val advertised = SelectorTour
            .callouts(FilingAction.COPY, detailsVisible = true, filmstripVisible = true)
            .single { it.region == TourRegion.FRAMES }
            .rows
            .map { it.input }
            .toSet()

        assertEquals(bound, advertised)
    }

    @Test
    fun `no advertised gesture is destructive`() {
        // The product rule as data: a destructive action is never a bare
        // gesture. Binding delete to a tap or a swipe means setting this flag,
        // and this is where that is refused.
        val destructive = FrameGesture.entries.filter { it.destructive }

        assertTrue("these frame gestures are destructive: $destructive", destructive.isEmpty())
    }

    @Test
    fun `no frame gesture claims to file or delete a photograph`() {
        val forbidden = listOf("delete", "remove", "trash", "keep", "selection")

        SelectorTour
            .callouts(FilingAction.MOVE, detailsVisible = true, filmstripVisible = true)
            .single { it.region == TourRegion.FRAMES }
            .rows
            .forEach { row ->
                forbidden.forEach { word ->
                    assertFalse(
                        "gesture '${row.input}' claims to '${row.effect}'",
                        row.effect.lowercase().contains(word),
                    )
                }
            }
    }

    @Test
    fun `no callout names a glyph`() {
        // The retired sheet said "tap the ⛱ badge". `MaximiseBadge` draws an
        // open-in-full arrow pair and always has. Naming a glyph in prose is an
        // assertion about a drawable that nothing keeps true.
        val glyphs = listOf("⛱", "⛶", "◎", "◍", "☀", "☾", "★", "▤", "▭", "👁", "⌨")

        everyConfiguration.forEach { action ->
            SelectorTour.callouts(action, detailsVisible = true, filmstripVisible = true)
                .forEach { callout ->
                    val text = callout.title + callout.body +
                        callout.rows.joinToString(" ") { it.input + it.effect }
                    glyphs.forEach { glyph ->
                        assertFalse("${callout.region} contains $glyph", text.contains(glyph))
                    }
                }
        }
    }

    @Test
    fun `keys that drive a control are separated from keys that do not`() {
        val callouts = SelectorTour
            .callouts(FilingAction.COPY, detailsVisible = true, filmstripVisible = true)
            .associateBy { it.region }

        val keysWithoutControls = callouts.getValue(TourRegion.KEYS).rows.map { it.input }.toSet()

        // 1/2/3, Esc and ? have nothing on screen to point at, which is the entire
        // reason that callout exists. G and S drive the locator row's two chips (Go and
        // Sort), so they are explained in the controls callout, beside the chips.
        assertEquals(
            setOf(
                SelectorShortcut.MAXIMISE.input,
                SelectorShortcut.ESCAPE.input,
                SelectorShortcut.SHORTCUTS.input,
            ),
            keysWithoutControls,
        )
        val controls = callouts.getValue(TourRegion.CONTROLS).rows.map { it.input }
        assertTrue(controls.toString(), SelectorShortcut.GO_TO.input in controls)
        assertTrue(controls.toString(), SelectorShortcut.SORT.input in controls)
    }

    @Test
    fun `the controls callout claims nothing a narrow chip does not show and names G and S`() {
        val controls = SelectorTour.callouts(FilingAction.COPY, detailsVisible = true, filmstripVisible = true)
            .single { it.region == TourRegion.CONTROLS }
        // Below 160 dp per cell the Go and Sort chips drop their key caps, and at large font scales
        // the action buttons may too: the body must not say every control carries its key.
        assertEquals("Keys below", controls.body)
        assertFalse(controls.body, controls.body.contains("carries"))
        val words = Regex("\\p{L}+").findAll(controls.body).count()
        assertTrue("guide_ budget is 2 words, 14 chars: \"${controls.body}\"", words <= 2 && controls.body.length <= 14)
        val inputs = controls.rows.map { it.input }
        assertTrue(inputs.toString(), "G" in inputs && "S" in inputs)
    }

    @Test
    fun `the filing keys are worded from the configured action`() {
        everyConfiguration.forEach { action ->
            val row = SelectorTour
                .callouts(action, detailsVisible = true, filmstripVisible = true)
                .single { it.region == TourRegion.CONTROLS }
                .rows
                .single { it.input == SelectorShortcut.FILE_PRIMARY.input }

            assertTrue(row.effect, row.effect.contains("Copy to Selection"))
            assertTrue(row.effect, row.effect.contains("Move to Selection"))
        }
    }

    @Test
    fun `chrome that is switched off is not labelled`() {
        val hidden = SelectorTour
            .callouts(FilingAction.COPY, detailsVisible = false, filmstripVisible = false)
            .map { it.region }
            .toSet()

        assertFalse(TourRegion.READOUT in hidden)
        assertFalse(TourRegion.FILMSTRIP in hidden)
        // The keys still have to go somewhere, so those callouts stay.
        assertTrue(TourRegion.CONTROLS in hidden)
        assertTrue(TourRegion.KEYS in hidden)
    }

    @Test
    fun `no callout is a region without words`() {
        everyConfiguration.forEach { action ->
            SelectorTour.callouts(action, detailsVisible = true, filmstripVisible = true)
                .forEach { callout ->
                    assertTrue("${callout.region} has no title", callout.title.isNotBlank())
                    assertTrue("${callout.region} has no body", callout.body.isNotBlank())
                }
        }
    }

    @Test
    fun `the guide fits on one screen`() {
        // A guide that scrolls is documenting, not teaching. Seven callouts is
        // the ceiling this layout's slack can carry without one; the assertion
        // is here so adding an eighth is a decision rather than an accident.
        val callouts = SelectorTour
            .callouts(FilingAction.COPY, detailsVisible = true, filmstripVisible = true)

        assertEquals(TourRegion.entries.size, callouts.size)
        assertTrue(callouts.size <= 7)
    }

    // ── The legend half: what the icons on screen mean ───────────────────

    @Test
    fun `every score has a legend row, carrying its own metric`() {
        // The row must name which metric it is for, so the UI draws that
        // metric's real glyph instead of a second one chosen in the guide.
        val legend = SelectorTour.metricLegend()

        assertEquals(ScoreMetric.entries.size, legend.size)
        assertEquals(ScoreMetric.entries, legend.map { it.metric })
        legend.forEach { row ->
            assertTrue("${row.label} has no meaning", row.meaning.isNotBlank())
        }
    }

    @Test
    fun `a legend row says which way its number runs`() {
        // Half these metrics are better low. A photographer reading a bare
        // number has no way to tell which half they are looking at.
        SelectorTour.metricLegend().forEach { row ->
            assertTrue(
                "${row.label} does not say which way is better: ${row.meaning}",
                row.meaning.contains(row.metric!!.direction.hint),
            )
        }
    }

    @Test
    fun `every sidebar action is named exactly once in the legend`() {
        val session = SelectorTour
            .callouts(FilingAction.COPY, detailsVisible = true, filmstripVisible = true)
            .single { it.region == TourRegion.SIDEBAR_SESSION }

        assertEquals(
            SidebarAction.entries.map { it.label },
            session.legend.map { it.label },
        )
    }

    @Test
    fun `the destinations the caller passes in are the ones explained`() {
        val screens = listOf(
            LegendRow(label = "Statistics", meaning = "How the folder scored"),
        )
        val callouts = SelectorTour.callouts(
            filingAction = FilingAction.COPY,
            detailsVisible = true,
            filmstripVisible = true,
            screenLegend = screens,
        )

        assertEquals(
            screens,
            callouts.single { it.region == TourRegion.SIDEBAR_SCREENS }.legend,
        )
    }

    @Test
    fun `the readouts callout carries the scores, and loses them when hidden`() {
        val shown = SelectorTour
            .callouts(FilingAction.COPY, detailsVisible = true, filmstripVisible = false)
            .singleOrNull { it.region == TourRegion.READOUT }
        assertEquals(ScoreMetric.entries.size, shown?.legend?.size)

        // Labelling a panel the photographer has switched off is the same
        // defect as inventing a control that does not exist.
        val hidden = SelectorTour
            .callouts(FilingAction.COPY, detailsVisible = false, filmstripVisible = false)
            .singleOrNull { it.region == TourRegion.READOUT }
        assertEquals(null, hidden)
    }

    @Test
    fun `legend rows are not advertised as inputs`() {
        // The two lists answer different questions, and the invariant test over
        // the bound input set reads only `rows`.
        val inputs = SelectorTour.advertisedInputs(FilingAction.COPY)

        SelectorTour.metricLegend().forEach { row ->
            assertFalse(row.label in inputs)
        }
    }
}
