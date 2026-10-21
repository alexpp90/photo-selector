package com.photoselectortoolbox.ui.selector

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import com.photoselectortoolbox.domain.guidance.CoachPaging
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoselectortoolbox.domain.guidance.SelectorHint
import com.photoselectortoolbox.domain.guidance.LegendRow
import com.photoselectortoolbox.ui.navigation.Screen
import com.photoselectortoolbox.domain.guidance.SelectorHintText
import com.photoselectortoolbox.domain.guidance.SelectorTour
import com.photoselectortoolbox.domain.guidance.TourCallout
import com.photoselectortoolbox.domain.guidance.TourRegion
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.interaction.GestureRow
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.BrandAccentText
import com.photoselectortoolbox.ui.theme.StudioBorder
import com.photoselectortoolbox.ui.theme.StudioCanvas
import com.photoselectortoolbox.ui.theme.StudioCard
import com.photoselectortoolbox.ui.theme.StudioPanel
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc50

/**
 * The guide: the selector, labelled where it actually is.
 *
 * This replaces a scrolling `ModalBottomSheet` of `input → effect` rows, for the
 * reason recorded in `ai/memory/palette.md` (2026-07-31): *a list of labels is
 * the weakest possible way to explain a control the user can already see*. Every
 * control on this screen that acts on a photograph already carries a permanent
 * word and a permanent mono key cap, so the sheet was reciting what was already
 * legible while answering none of the questions a new user actually has — what
 * is the sidebar for, which frame am I deciding on, what do the two below it
 * mean, why is there a block of numbers beside the picture.
 *
 * Those are spatial questions, so this is a spatial answer:
 *
 * - **The screen is dimmed, not replaced.** The real chrome stays visible
 *   through the scrim, because the point is to label *it*, not a diagram of it.
 * - **The real geometry is reserved.** The sidebar column, the two flanks and
 *   the frame footprints are laid out here at exactly the sizes the layout
 *   resolved — through the same [FrameGeometry.threeUpLayout] the layout itself
 *   calls, with the same `detailsVisible` and `filmstripVisible` inputs — so
 *   every callout lands in the horizontal slack and no callout can cover a
 *   frame. An instrumented test asserts the bounds do not intersect, because
 *   that is a regression otherwise only caught by eye.
 * - **It is paged, and a page never scrolls.** All the cards used to be stacked
 *   in the control flank and overflowed it — truncated cards, the title over
 *   Copy and Move, "Got it" over controls (AD-3). The cards now live in the
 *   *left* flank only, over the readout block (non-interactive text), and are
 *   packed greedily by *measured* height into as many pages as that space needs
 *   ([CoachPaging]). Skip, a page indicator and Next / **Got it** form a footer
 *   at the foot of the same column. The invariant is that nothing the guide
 *   draws — card, title, footer — may touch `control_block`, `selector_sidebar`
 *   or a frame (badges included); keeping to the left flank guarantees it by
 *   construction rather than by measurement. Because a card is no longer beside
 *   what it explains, each names where it is ([TourRegion.location]). A guide
 *   that scrolls is documenting rather than teaching, so there is no scrolling:
 *   a card taller than the space gets a page to itself.
 * - **Nothing is written out.** Every row comes from [SelectorTour], which
 *   generates them from the same declarations the bindings read, and the title
 *   comes from [SelectorHintText]. The sheet this replaces claimed the maximise
 *   control was a `⛱` badge — a glyph this product has never rendered.
 *
 * Reachable by `?`, by the keyboard glyph in the control block and from the
 * overflow menu, and shown once at first launch. `Esc` closes it; shortcuts stay
 * suppressed while it is open (REQUIREMENTS §2).
 */
@Composable
fun SelectorCoachOverlay(
    visible: Boolean,
    filingAction: FilingAction,
    detailsVisible: Boolean,
    filmstripVisible: Boolean,
    aspect: Float,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        // Fades. Nothing on this screen slides: the frames underneath are what
        // the user is judging, and movement lies about sharpness.
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(150)),
        modifier = modifier,
    ) {
        val callouts = remember(filingAction, detailsVisible, filmstripVisible) {
            SelectorTour.callouts(
                filingAction = filingAction,
                detailsVisible = detailsVisible,
                filmstripVisible = filmstripVisible,
                screenLegend = Screen.all.map { screen ->
                    LegendRow(label = screen.label, meaning = screen.meaning)
                },
            )
        }
        val items = callouts

        Box(
            modifier = Modifier
                .fillMaxSize()
                // A scrim, not an opaque sheet: the controls being explained stay
                // visible underneath, which is the whole point of a coach mark.
                .background(StudioCanvas.copy(alpha = 0.85f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                )
                .testTag("selector_coach_overlay"),
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // ── The sidebar column, at its real width ────────────────
                // Reserved and left empty, exactly like the frame footprints.
                // 88 dp cannot hold a sentence, and covering the glyphs with
                // their own explanation is the one thing a coach mark must not
                // do — so the sidebar's callouts sit in the flank beside it and
                // the real icons stay legible through the scrim.
                Spacer(modifier = Modifier.width(SidebarWidth).fillMaxHeight())

                // ── The image region, at its real bounds ─────────────────
                //
                // No strip beneath it any more: the filmstrip is a vertical
                // column at the outer edge of the control flank, so its callout
                // goes beside it. An overlay that still reserved a bottom strip
                // would push every callout up by 76 dp and start labelling the
                // wrong chrome.
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    ImageRegionCallouts(
                        items = items,
                        aspect = aspect,
                        detailsVisible = detailsVisible,
                        filmstripVisible = filmstripVisible,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

/**
 * The three-up region: two flanks carrying callouts, and frame footprints
 * carrying nothing.
 *
 * The footprints are laid out and left empty on purpose. Reserving them is what
 * pushes every callout into the slack; drawing anything in them would be the
 * defect this overlay exists to avoid.
 */
@Composable
private fun ImageRegionCallouts(
    items: List<TourCallout>,
    aspect: Float,
    detailsVisible: Boolean,
    filmstripVisible: Boolean,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(FrameGeometry.OuterPadding),
    ) {
        val layout = FrameGeometry.threeUpLayout(
            regionWidth = maxWidth,
            regionHeight = maxHeight,
            aspect = aspect,
            detailsVisible = detailsVisible,
            filmstripVisible = filmstripVisible,
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(FrameGeometry.Gap),
        ) {
            // ── Row 1: readout flank · CURRENT footprint · control flank ─
            // Centred exactly as the layout centres it, or the reserved
            // footprint drifts off the frame it is reserving.
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The pager owns the whole left flank (see the class comment).
                CoachPager(
                    items = items,
                    onDismiss = onDismiss,
                    modifier = Modifier.width(layout.flankWidth).fillMaxHeight(),
                )

                Spacer(modifier = Modifier.width(FrameGeometry.Gap))
                Spacer(modifier = Modifier.size(layout.frame))
                Spacer(modifier = Modifier.width(FrameGeometry.Gap))

                // The control flank, reserved and left EMPTY: the real control
                // block (and the filmstrip beside it) is what the guide is
                // explaining, and drawing over it is the defect AD-3 recorded.
                Spacer(modifier = Modifier.width(layout.flankWidth).fillMaxHeight())
            }

            // ── Row 2: the two neighbour footprints, reserved and empty ──
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    FrameGeometry.Gap,
                    Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.size(layout.frame))
                Spacer(modifier = Modifier.size(layout.frame))
            }
        }
    }
}

/** Height of the footer row; also its touch target. */
private val CoachFooterHeight = 48.dp

/** Space between cards on a page. */
private val CoachCardGap = 10.dp

/**
 * The paged left flank: cards packed by measured height, footer at the foot.
 *
 * A `SubcomposeLayout` because the packing depends on how tall each card turns
 * out at this width and font scale. Cards are measured in throw-away slots with
 * no test tag and no semantics, so the only `coach_callout` nodes that exist are
 * the ones on the visible page.
 */
@Composable
private fun CoachPager(
    items: List<TourCallout>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var requested by remember { mutableIntStateOf(0) }
    SubcomposeLayout(modifier = modifier.clipToBounds()) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = CoachCardGap.roundToPx()
        val footer = CoachFooterHeight.roundToPx()

        // The heading leads every page rather than being packed like a card: packed,
        // it took page 1 alone whenever the first card (the sidebar legend) did not
        // fit beside it, and a page that explains nothing is a page to skip.
        val header = subcompose("measureTitle") { Title() }
            .first()
            .measure(Constraints(maxWidth = width))
            .height
        val heights = items.mapIndexed { index, item ->
            subcompose("measure$index") { Callout(item, tagged = false) }
                .first()
                .measure(Constraints(maxWidth = width))
                .height
        }
        val pages = CoachPaging.pack(heights, available = height - header - footer - 2 * gap, gap = gap)
        val page = requested.coerceIn(0, pages.lastIndex)

        val placeable = subcompose("page$page") {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(CoachCardGap),
                    horizontalAlignment = Alignment.End,
                ) {
                    Title()
                    pages[page].forEach { Callout(items[it]) }
                }
                CoachFooter(
                    page = page,
                    pageCount = pages.size,
                    onNext = { requested = page + 1 },
                    onClose = onDismiss,
                )
            }
        }.first().measure(Constraints.fixed(width, height))

        layout(width, height) { placeable.place(0, 0) }
    }
}

/**
 * Skip · `2 / 3` · Next, or just **Got it** when there is one page.
 *
 * On the last page the closing control keeps the `coach_overlay_dismiss` tag it
 * always had; Next has its own. Every target is 48 dp tall.
 */
@Composable
private fun CoachFooter(
    page: Int,
    pageCount: Int,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val last = page >= pageCount - 1
    Row(
        modifier = Modifier.fillMaxWidth().height(CoachFooterHeight).testTag("coach_footer"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pageCount > 1) {
            FooterButton("Skip", onClose, "coach_overlay_skip", primary = false)
            Text(
                text = "${page + 1} / $pageCount",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Zinc400,
                modifier = Modifier.testTag("coach_page_indicator"),
            )
        } else {
            Spacer(modifier = Modifier.width(1.dp))
        }
        if (last) {
            FooterButton("Got it", onClose, "coach_overlay_dismiss", primary = true)
        } else {
            FooterButton("Next", onNext, "coach_overlay_next", primary = true)
        }
    }
}

@Composable
private fun FooterButton(label: String, onClick: () -> Unit, tag: String, primary: Boolean) {
    Box(
        modifier = Modifier
            .height(CoachFooterHeight)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) StudioCard else Color.Transparent)
            .then(
                if (primary) Modifier.border(1.dp, StudioBorder, RoundedCornerShape(8.dp)) else Modifier
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) BrandAccentText else Zinc400,
        )
    }
}

/** The guide's own heading, worded by the same object as every hint. */
@Composable
private fun Title() {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = SelectorHintText.title(SelectorHint.TOUR, FilingAction.DEFAULT),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Zinc50,
        )
        Text(
            // The message names no verb, so the filing action does not reach it;
            // it is still read from the one text object rather than typed here.
            text = SelectorHintText.message(
                hint = SelectorHint.TOUR,
                filingAction = FilingAction.DEFAULT,
                selectionFolderName = "",
                sortingEnabled = false,
            ),
            fontSize = 12.sp,
            color = Zinc400,
        )
    }
}

/**
 * One coach mark: a leader line, a title, one line of body, and the inputs.
 *
 * [compact] is the sidebar and filmstrip variant: the sidebar's has 88 dp to
 * work in, and the filmstrip's is the last of three callouts stacked in the
 * control flank, so both drop to the title and a short body.
 */
@Composable
private fun Callout(callout: TourCallout, compact: Boolean = false, tagged: Boolean = true) {
    Column(
        modifier = Modifier
            .then(if (compact) Modifier.fillMaxWidth() else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(StudioPanel)
            .border(1.dp, StudioBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .then(
                if (tagged) {
                    Modifier
                        .semantics {
                            contentDescription = "${callout.region.location}. ${callout.title}. ${callout.body}"
                        }
                        .testTag("coach_callout")
                } else {
                    Modifier
                }
            ),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Where it is: the card no longer sits beside what it explains.
        Text(
            text = callout.region.location.uppercase(),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = BrandAccentText,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Leader()
            Text(
                text = callout.title,
                fontSize = if (compact) 11.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                color = Zinc50,
            )
        }
        Text(
            text = callout.body,
            fontSize = if (compact) 9.sp else 11.sp,
            color = Zinc400,
        )
        callout.legend.forEach { LegendLine(it, compact = compact) }
        callout.rows.forEach { KeyRow(it, compact = compact) }
    }
}

/**
 * One legend line: the thing's own glyph where it has one, its name, and what
 * it means.
 *
 * The glyph is [ScoreMetricIcon.vector] — the same mapping the readouts and the
 * chips draw from — so the legend cannot teach a symbol this product does not
 * render. It did exactly that until the two mappings were merged: a spotlight
 * for highlight clipping against the sun the frames actually showed.
 */
@Composable
private fun LegendLine(row: LegendRow, compact: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (row.metric != null) {
            Icon(
                imageVector = row.metric.icon.vector(),
                contentDescription = null,
                tint = Zinc50,
                modifier = Modifier.size(if (compact) 11.dp else 14.dp),
            )
        }
        Column {
            Text(
                text = row.label,
                fontSize = if (compact) 9.sp else 11.sp,
                fontWeight = FontWeight.Medium,
                color = Zinc50,
            )
            Text(
                text = row.meaning,
                fontSize = if (compact) 8.sp else 10.sp,
                color = Zinc400,
            )
        }
    }
}

/** The short rule that ties a callout to the region it labels. */
@Composable
private fun Leader() {
    Box(
        modifier = Modifier
            .width(10.dp)
            .height(1.dp)
            .background(BrandAccent),
    )
}

/** One `input → effect` line, generated — never written out. */
@Composable
private fun KeyRow(row: GestureRow, compact: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.input,
            fontSize = if (compact) 9.sp else 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Zinc50,
            modifier = Modifier.width(if (compact) 62.dp else 92.dp),
        )
        Text(
            text = row.effect,
            fontSize = if (compact) 9.sp else 11.sp,
            color = Zinc400,
        )
    }
}
