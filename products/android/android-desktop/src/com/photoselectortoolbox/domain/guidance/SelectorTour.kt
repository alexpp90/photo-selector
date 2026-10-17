package com.photoselectortoolbox.domain.guidance

import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.scoring.ScoreMetric
import com.photoselectortoolbox.domain.interaction.GestureRow
import com.photoselectortoolbox.domain.interaction.SelectorGestures
import com.photoselectortoolbox.domain.interaction.SelectorShortcut

/**
 * Where on the selector a callout is anchored.
 *
 * The regions are the *slack* of the layout — the sidebar on the left, the two
 * 388 dp flanks either side of the current frame, the filmstrip along the
 * bottom. There is deliberately no region over a frame: height is the binding
 * constraint on this screen and the frames are what the user is judging, so a
 * callout that covers one has explained a control by hiding the thing the
 * control acts on.
 */
enum class TourRegion(
    /**
     * Where this part of the screen is, in words. The guide is paged in the left
     * flank, so a card is no longer beside what it explains and has to say so.
     */
    val location: String,
) {
    /** Upper half of the 88 dp sidebar: Folder, Drive, Scan, Bursts, Legend. */
    SIDEBAR_SESSION("Left sidebar, top"),

    /** Lower half of the sidebar, under the rule: Cull, Stats, Dupes, Setup. */
    SIDEBAR_SCREENS("Left sidebar, bottom"),

    /** The readout block, left of the current frame. */
    READOUT("Left of the centre frame"),

    /** The three frames themselves — labelled from the flank, never covered. */
    FRAMES("The three frames"),

    /** The control block, right of the current frame. */
    CONTROLS("Right of the centre frame"),

    /** Keys that drive no on-screen control, so nothing can be pointed at. */
    KEYS("On the keyboard"),

    /** The vertical filmstrip, down the outer edge of the control flank. */
    FILMSTRIP("Far right edge, when shown"),
}

/**
 * One coach mark: what this part of the screen is, and the inputs it answers to.
 *
 * [rows] is always generated from a binding declaration — never written out —
 * so a row that does not correspond to something the selector actually binds
 * cannot be expressed.
 */
data class TourCallout(
    val region: TourRegion,
    val title: String,
    val body: String,
    val rows: List<GestureRow> = emptyList(),
    /**
     * Things on screen this callout *names*, as opposed to inputs it advertises.
     *
     * Kept apart from [rows] because they answer different questions — "what is
     * that icon" versus "what key does this" — and because the invariant test
     * over the bound input set reads [rows] alone.
     */
    val legend: List<LegendRow> = emptyList(),
)

/**
 * One line of the legend: something visible, named and explained.
 *
 * [metric] is set when the row describes a score, so the UI can draw that
 * metric's real glyph rather than a second one chosen here — the failure this
 * replaces was a legend teaching a spotlight and a lampshade for clipping while
 * the frames showed a sun and a moon.
 */
data class LegendRow(
    val label: String,
    val meaning: String,
    val metric: ScoreMetric? = null,
)

/**
 * The coach-mark guide, as data.
 *
 * Replaces a scrolling `ModalBottomSheet` of `input → effect` rows. The lesson
 * that killed that sheet (`ai/memory/palette.md`, 2026-07-31) is that *a list of
 * labels is the weakest possible way to explain a control the user can already
 * see* — and on this product every control that acts on a photograph already
 * carries a permanent word and a permanent mono key cap, so a list of those keys
 * was documenting what was already legible.
 *
 * What the user genuinely cannot work out by looking is **where things are and
 * why**: that the sidebar is session actions above and destinations below, that
 * the three frames are one comparison at one size, that the block right of the
 * centre frame acts on the centre frame only. That is spatial, so the guide is
 * spatial: each region of the layout gets one callout in the slack beside it.
 *
 * The keys are then split by whether anything on screen can be pointed at.
 * [SelectorShortcut.FILE_PRIMARY], `Del`, `F`, `←` and `→` all drive a labelled
 * control, so they belong to that control's callout; `1`/`2`/`3`, `Esc` and `?`
 * drive nothing visible, so they get a callout of their own. Every binding
 * appears exactly once across the whole guide, and a unit test asserts the
 * advertised set equals the bound set.
 *
 * Kept free of Compose and Android types so all of it is unit-testable.
 */
object SelectorTour {

    /** Shortcuts the user can be shown by pointing at the control they drive. */
    private val SHORTCUTS_WITH_A_CONTROL = setOf(
        SelectorShortcut.FILE_PRIMARY,
        SelectorShortcut.DELETE,
        SelectorShortcut.FULLSCREEN,
        SelectorShortcut.PREVIOUS,
        SelectorShortcut.NEXT,
    )

    /** Shortcuts with nothing on screen to point at, so the guide must list them. */
    private val SHORTCUTS_WITHOUT_A_CONTROL =
        SelectorShortcut.entries.filterNot { it in SHORTCUTS_WITH_A_CONTROL }.toSet()

    /**
     * Every callout, in the order they are read: sidebar, values, frames,
     * controls, keys, filmstrip.
     *
     * [filmstripVisible] and [detailsVisible] drop the callouts for chrome that
     * is currently switched off — labelling a panel the user has hidden is the
     * same defect as inventing a control that does not exist.
     */
    fun callouts(
        filingAction: FilingAction,
        detailsVisible: Boolean,
        filmstripVisible: Boolean,
        /**
         * The destinations in the lower half of the sidebar, named.
         *
         * Passed in rather than declared here because a `Screen` carries a
         * Compose `ImageVector` and this object stays free of Compose types, so
         * `Screen.all` cannot be read from it.
         */
        screenLegend: List<LegendRow> = emptyList(),
    ): List<TourCallout> = buildList {
        add(
            TourCallout(
                region = TourRegion.SIDEBAR_SESSION,
                title = "The shoot",
                body = "These act on every photograph, not on the one in front of you.",
                legend = SidebarAction.entries.map { action ->
                    LegendRow(label = action.label, meaning = action.meaning)
                },
            )
        )
        add(
            TourCallout(
                region = TourRegion.SIDEBAR_SCREENS,
                title = "The screens",
                body = "Your place in the folder is kept when you come back.",
                legend = screenLegend,
            )
        )
        if (detailsVisible) {
            add(
                TourCallout(
                    region = TourRegion.READOUT,
                    title = "This frame's values",
                    body = "Filename, exposure, then one row per metric. The bar is the " +
                        "score: longer is always better, whichever way the raw number runs.",
                    legend = metricLegend(),
                )
            )
        }
        add(
            TourCallout(
                region = TourRegion.FRAMES,
                title = "The comparison",
                body = "Centre frame is the one you are deciding on; the two below are " +
                    "the frames either side of it. All three are the same size on purpose " +
                    "— a smaller or dimmed neighbour cannot be judged.",
                rows = SelectorGestures.frameGestureRows(),
            )
        )
        add(
            TourCallout(
                region = TourRegion.CONTROLS,
                title = "Acts on the centre frame",
                body = "Every one of these carries its word and its key. " +
                    "Nothing here is a gesture.",
                rows = SelectorGestures.selectorShortcutRows(
                    filingAction,
                    SHORTCUTS_WITH_A_CONTROL,
                ),
            )
        )
        add(
            TourCallout(
                region = TourRegion.KEYS,
                title = "Keys with no button",
                body = "Nothing on screen shows these, so here they are.",
                rows = SelectorGestures.selectorShortcutRows(
                    filingAction,
                    SHORTCUTS_WITHOUT_A_CONTROL,
                ),
            )
        )
        if (filmstripVisible) {
            add(
                TourCallout(
                    region = TourRegion.FILMSTRIP,
                    title = "The whole folder",
                    body = "Every frame in order, top to bottom. The dot is its " +
                        "sharpness; bursts are marked when Bursts is on.",
                )
            )
        }
    }

    /**
     * One row per score, in the order the readout draws them.
     *
     * The direction is stated rather than left to be inferred: half these
     * metrics are better low, and a photographer reading a bare number has no
     * way to know which half they are looking at.
     */
    fun metricLegend(): List<LegendRow> = ScoreMetric.entries.map { metric ->
        LegendRow(
            label = metric.displayName,
            meaning = "${metric.direction.hint} — ${metric.description}",
            metric = metric,
        )
    }

    /**
     * Every input the guide advertises — keys and frame gestures alike.
     *
     * Exposed so the invariant test can compare it against the bound set rather
     * than against today's strings: the union of [SelectorShortcut] and
     * [com.photoselectortoolbox.domain.interaction.FrameGesture] is exactly what
     * this must equal, no more and no less.
     */
    fun advertisedInputs(filingAction: FilingAction): Set<String> =
        callouts(filingAction, detailsVisible = true, filmstripVisible = true)
            .flatMap { it.rows }
            .map { it.input }
            .toSet()
}
