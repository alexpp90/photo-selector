package com.photoselectortoolbox.ui.selector

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.photoselectortoolbox.ui.components.TouchTarget
import com.photoselectortoolbox.ui.components.handCursor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoselectortoolbox.domain.format.ActionLabel
import com.photoselectortoolbox.domain.format.SelectionActionLabels
import com.photoselectortoolbox.domain.format.SelectorLabels
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.ui.components.StarRatingBar
import com.photoselectortoolbox.ui.theme.HintText
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.BrandAccentNav
import com.photoselectortoolbox.ui.theme.BrandAccentText
import com.photoselectortoolbox.ui.theme.BrandAccentTonal
import com.photoselectortoolbox.ui.theme.ScoreBad
import com.photoselectortoolbox.ui.theme.ScoreGood
import com.photoselectortoolbox.ui.theme.StudioBorder
import com.photoselectortoolbox.ui.theme.HoverFill
import com.photoselectortoolbox.ui.theme.StudioCard
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc50
import com.photoselectortoolbox.ui.theme.Zinc600
import com.photoselectortoolbox.ui.theme.Zinc700
import androidx.compose.ui.res.stringResource
import com.photoselectortoolbox.R
import com.photoselectortoolbox.ui.text.asString
import com.photoselectortoolbox.domain.interaction.SelectorShortcut

/**
 * Everything that acts on the current frame, in the letterbox column to its
 * right.
 *
 * A 2 × 3 grid of large worded controls rather than the narrow icon rail this
 * replaces. The width is available — the layout is height-bound, so these 388 dp
 * cost the photographs nothing — and 190 dp per cell is enough to carry the
 * word, which is the point: **a control that changes a file is never
 * icon-only.** The previous rail was 48 dp glyphs with hover-revealed key
 * hints, on a tablet, which has no hover.
 *
 * ## Four rungs, chosen by measurement (AD-1)
 *
 * The block is 362 dp tall at font scale 1.0 and the frames do not give way for
 * it — row 1 is exactly one frame tall, ~376 dp at 1280 × 800 but ~210 dp at
 * 600 × 480 and ~336 dp at 1024 × 720. [ControlBlockFit] compares the height the
 * row actually hands over with what each variant needs (font scale included) and
 * picks the first that fits:
 *
 * 1. **FULL** — the 80 dp two-line buttons above, the toggle row, and the **locator
 *    row** (the Go chip holding the position counter, the Sort chip).
 * 2. **COMPACT + locator** (252 dp) — 48 dp one-line buttons (icon · word · key cap,
 *    the key cap never ellipsized: the word gives way first), four 48 dp toggles,
 *    the locator row.
 * 3. **COMPACT** (~201–265 dp) — as 2 without the row: the position counter sits beside
 *    the toggles or leads the block. The counter is still the Go control, and `G` and `S`
 *    still work; the row needs [ControlBlockFit.LocatorMinInnerWidth] of width.
 * 4. **SCROLLING** — only when even COMPACT does not fit: the block scrolls with
 *    the counter *first*, so the one fact that locates the user is the one that
 *    cannot be scrolled out of sight. It is never silently clipped.
 *
 * FULL without the locator row no longer exists (`[USER 2026-11-04]`: Sort and Go to
 * must be findable). The row is a fixed 48 dp, so the frames never pay for it: it is
 * priced inside the block, which sits on the non-binding axis of row 1.
 *
 * The state is exposed as a marker tag on a wrapper (`control_block_full`,
 * `control_block_compact`, `control_block_scrolling`) so a test can tell which
 * variant it is looking at, and `locator_row` exists exactly where the row is drawn;
 * `control_block` stays on the block itself.
 *
 * The two filing verbs come from [SelectionActionLabels], so they say Copy and
 * Move according to the user's setting and can never drift into a euphemism.
 */
@Composable
fun SelectorControlBlock(
    actions: SelectorActions,
    filingAction: FilingAction,
    position: Int,
    total: Int,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    detailsVisible: Boolean,
    filmstripVisible: Boolean,
    overlayValuesVisible: Boolean,
    onNavigatePrevious: () -> Unit,
    onNavigateNext: () -> Unit,
    modifier: Modifier = Modifier,
    stillEnumerating: Boolean = false,
    ratingFilter: RatingFilter = RatingFilter.All,
    onRatingFilterSelected: (RatingFilter) -> Unit = {},
    currentRating: Int = 0,
    onSetRating: (Int) -> Unit = {},
    /** The Sort chip and its menu; the menu is also what the `S` key opens. */
    sort: SortControl = SortControl(),
) {
    val fontScale = LocalDensity.current.fontScale
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier = modifier) {
        val isNarrow = maxWidth < 240.dp
        val horizontalPadding = if (isNarrow) 4.dp else 10.dp
        val innerWidth = maxWidth - horizontalPadding * 2
        val fit = ControlBlockFit.fit(maxHeight, fontScale, density, innerWidth)
        val mode = fit.mode
        // The locator row (Go chip with the counter, Sort chip) is the last child
        // wherever the ladder grants it; elsewhere the counter stands alone as before.
        val locatorRow = fit.locatorRow
        val compact = mode != ControlBlockFit.Mode.FULL
        val scrolling = mode == ControlBlockFit.Mode.SCROLLING
        // The counter leads on its own line when scrolling (so it cannot be
        // scrolled away) and when the compact toggles have no room beside it.
        val counterOnTop = scrolling || (compact && !ControlBlockFit.counterInline(innerWidth))
        // null in the FULL block (80 dp two-line buttons); in the compact block the
        // shape the cell width affords, so the word and key cap are never cut.
        val buttonStyle = if (compact) ControlBlockFit.buttonStyle(innerWidth) else null
        val gap = if (compact) ControlBlockFit.CompactGap else ControlBlockFit.FullGap

        val counterText = SelectorLabels.position(position, total, stillEnumerating)
        val counterDescription = stringResource(
            if (stillEnumerating) R.string.cd_image_position_loading else R.string.cd_image_position,
            position,
            total,
        )
        val goToLabel = SelectorLabels.GO_TO_PHOTO.asString()
        val counter: @Composable (Modifier) -> Unit = { counterModifier ->
            // `127 / 842+` while the folder is still enumerating. Discovery
            // streams, so the total is a running total for the first seconds of
            // a large shoot; printing it bare would state a folder size nobody
            // has counted. An explicit line height keeps the block's height a
            // function of font scale that [ControlBlockFit] can price.
            Text(
                text = counterText,
                fontSize = if (compact) 11.sp else 12.sp,
                lineHeight = (
                    if (compact) ControlBlockFit.CompactCounterLineHeightSp
                    else ControlBlockFit.CounterLineHeightSp
                    ).sp,
                fontFamily = FontFamily.Monospace,
                color = Zinc400,
                maxLines = 1,
                softWrap = false,
                textAlign = if (compact) TextAlign.End else TextAlign.Start,
                // Opens "Go to photo". The layout size is the priced one (a 48 dp slot
                // would change [ControlBlockFit]'s heights and, in the compact block,
                // overlap the 48 dp toggles beside it), so the 48 dp target is the
                // framework's minimum touch-target expansion around this node.
                modifier = counterModifier
                    .clickable(
                        onClickLabel = goToLabel,
                        role = Role.Button,
                        onClick = actions.onGoToPhoto,
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
                    // Beside the toggles the row is already 48 dp tall, so the counter
                    // there is a real 48 dp slot with its text centred in it.
                    .wrapContentHeight(Alignment.CenterVertically)
                    .semantics { contentDescription = counterDescription }
                    .testTag("position_counter"),
            )
        }

        // The marker wraps the block, so it has the block's exact bounds.
        Box(
            modifier = Modifier
                .then(if (scrolling) Modifier.heightIn(max = maxHeight) else Modifier)
                .testTag("control_block_${mode.name.lowercase()}"),
        ) {
            // Where the locator row is not drawn the `S` key still opens the menu, over the block.
            if (!locatorRow) SortMenu(sort)
            Column(
                modifier = Modifier
                    .padding(horizontal = horizontalPadding)
                    .then(
                        if (scrolling) Modifier.verticalScroll(rememberScrollState()) else Modifier
                    )
                    .testTag("control_block"),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                val labels = SelectionActionLabels.both(filingAction)
                val deleteWord = stringResource(R.string.action_delete)
                val fullWord = stringResource(R.string.action_full)
                val prevWord = stringResource(R.string.action_prev)
                val nextWord = stringResource(R.string.action_next)
                val verbs = labels.map { it.verb.asString() }

                // The counter leads, on its own line, so it is the one thing a
                // scroll position can never hide.
                if (counterOnTop && !locatorRow) {
                    counter(Modifier.fillMaxWidth())
                }

                // Where the block scrolls anyway and a half-width cell cannot hold the
                // longest word at its smallest step (600 x 480 at font scale 2.0), the
                // buttons stand one per row: the scroll pays for it, and no word is cut.
                val single = scrolling && !wordsFitHalfCells(
                    words = verbs + listOf(deleteWord, fullWord, prevWord, nextWord),
                    cellWidth = ControlBlockFit.cellWidth(innerWidth) - 6.dp,
                )
                val style = if (single) ControlBlockFit.buttonStyle(innerWidth * 2 + gap) else buttonStyle
                val (first, second) = labels
                ButtonPair(
                    single = single,
                    gap = gap,
                    first = { m -> FilingTagged(first, style, actions, m) },
                    second = { m -> FilingTagged(second, style, actions, m) },
                )

                // Delete is diagonally opposite the configured filing button, not
                // beside it. The two most-used controls on this screen are "file
                // this" and "destroy this", and they should not be neighbours.
                ButtonPair(
                    single = single,
                    gap = gap,
                    first = { m ->
                        WordedButton(
                            icon = Icons.Default.Delete,
                            word = deleteWord,
                            keyHint = SelectorShortcut.DELETE.input.asString(),
                            description = stringResource(R.string.cd_delete_button),
                            style = style,
                            contentColor = ScoreBad,
                            outlined = true,
                            onClick = actions.onDelete,
                            modifier = m.testTag("delete_button_expanded"),
                        )
                    },
                    second = { m ->
                        WordedButton(
                            icon = Icons.Default.Fullscreen,
                            word = fullWord,
                            keyHint = "F",
                            description = stringResource(R.string.cd_fullscreen_button),
                            style = style,
                            onClick = actions.onFullscreen,
                            modifier = m.testTag("fullscreen_button"),
                        )
                    },
                )

                ButtonPair(
                    single = single,
                    gap = gap,
                    first = { m ->
                        WordedButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            word = prevWord,
                            keyHint = "←",
                            description = stringResource(R.string.cd_previous_button),
                            style = style,
                            enabled = canGoPrevious,
                            onClick = onNavigatePrevious,
                            modifier = m.testTag("rail_previous"),
                        )
                    },
                    second = { m ->
                        WordedButton(
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            word = nextWord,
                            keyHint = "→",
                            description = stringResource(R.string.cd_next_button),
                            style = style,
                            enabled = canGoNext,
                            onClick = onNavigateNext,
                            modifier = m.testTag("rail_next"),
                        )
                    },
                )

                // View controls: none of these four changes a file, so a mis-tap costs a
                // glance, not a photo. Each carries a one-word label where the slot holds it.
                val toggleWords = SelectorLabels.VIEW_TOGGLE_LABELS.map { it.asString() }
                val showToggleLabels = toggleLabelsFit(toggleWords)
                fun toggleLabel(index: Int) = toggleWords[index].takeIf { showToggleLabels }
                val toggles: List<@Composable () -> Unit> = listOf(
                    {
                        ViewToggle(
                            icon = Icons.Outlined.Info,
                            description = stringResource(R.string.cd_photo_details),
                            active = detailsVisible,
                            label = toggleLabel(0),
                            onClick = actions.onToggleDetails,
                            modifier = Modifier.testTag("details_toggle"),
                        )
                    },
                    {
                        ViewToggle(
                            icon = Icons.Default.ViewCarousel,
                            description = stringResource(R.string.cd_filmstrip),
                            active = filmstripVisible,
                            label = toggleLabel(1),
                            onClick = actions.onToggleFilmstrip,
                            modifier = Modifier.testTag("filmstrip_toggle"),
                        )
                    },
                    {
                        ViewToggle(
                            icon = if (overlayValuesVisible) {
                                Icons.Default.Visibility
                            } else {
                                Icons.Default.VisibilityOff
                            },
                            description = stringResource(R.string.cd_overlay_values),
                            active = overlayValuesVisible,
                            label = toggleLabel(2),
                            onClick = actions.onToggleOverlayValues,
                            modifier = Modifier.testTag("overlay_values_toggle"),
                        )
                    },
                    {
                        ViewToggle(
                            icon = Icons.Default.Keyboard,
                            description = stringResource(R.string.cd_shortcuts_guide),
                            label = toggleLabel(3),
                            onClick = actions.onShowShortcuts,
                            modifier = Modifier.testTag("shortcuts_button"),
                        )
                    },
                )

                if (compact) {
                    // Every toggle is a 48 x 48 dp slot (AD-17). Four in a row where
                    // the width allows, otherwise a 2 x 2 grid; the slots abut and
                    // never overlap, and the counter sits beside them or leads.
                    val perRow = ControlBlockFit.togglesPerRow(innerWidth)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            toggles.chunked(perRow).forEach { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    rowItems.forEach { it() }
                                }
                            }
                        }
                        if (!counterOnTop && !locatorRow) {
                            counter(
                                Modifier.padding(start = ControlBlockFit.CounterGap)
                                    .widthIn(min = ControlBlockFit.CounterWidth)
                                    .heightIn(min = ControlBlockFit.ToggleHitSize)
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(
                            ControlBlockFit.FullGap,
                            Alignment.CenterHorizontally,
                        ),
                    ) {
                        toggles.forEach { it() }
                    }
                }

                if (locatorRow) {
                    LocatorRow(
                        counterText = counterText,
                        goDescription = SelectorLabels.goToChipDescription(position, total, stillEnumerating).asString(),
                        onGoToPhoto = actions.onGoToPhoto,
                        sort = sort,
                        style = ControlBlockFit.locatorStyle(innerWidth),
                    )
                }
            }
        }
    }
}

/**
 * Filter chips allowing rapid selection of rating criteria in the control flank.
 */
@Composable
fun RatingFilterSelector(
    currentFilter: RatingFilter,
    onFilterSelected: (RatingFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .testTag("rating_filter_selector"),
        horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RatingFilter.PRESETS.forEach { filter ->
            val isSelected = filter == currentFilter
            val tag = "filter_${filter.label.replace("★", "star").replace("+", "plus").trim()}"
            // The whole 48 dp slot is the control; the drawn chip sits inside it.
            TouchTarget(
                onClick = { onFilterSelected(filter) },
                testTag = tag,
                shape = RoundedCornerShape(4.dp),
                role = Role.RadioButton,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) BrandAccentNav else StudioCard)
                        .border(1.dp, if (isSelected) BrandAccent else StudioBorder, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = filter.text.asString(),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isSelected) BrandAccentText else Zinc400,
                    )
                }
            }
        }
    }
}

/** Two controls side by side, or one per row when [single]; [content] gets the modifier that sizes it. */
@Composable
private fun ButtonPair(
    single: Boolean,
    gap: Dp,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    if (single) {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            first(Modifier.fillMaxWidth())
            second(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}

/** Whether every word fits a half-width cell at its smallest step and the font scale in force. */
@Composable
private fun wordsFitHalfCells(words: List<String>, cellWidth: Dp): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val limit = with(density) { cellWidth.roundToPx() }
    return words.all { word ->
        measurer.measure(word, TextStyle(fontSize = WordMinSize), maxLines = 1, softWrap = false).size.width + TextSlackPx <= limit
    }
}

/** A [FilingButton] with the tag of its action. */
@Composable
private fun FilingTagged(
    label: ActionLabel,
    style: ControlBlockFit.ButtonStyle?,
    actions: SelectorActions,
    modifier: Modifier,
) = FilingButton(
    label = label,
    style = style,
    onClick = if (label.action == FilingAction.MOVE) actions.onMove else actions.onCopy,
    modifier = modifier.testTag(if (label.action == FilingAction.MOVE) "move_button_expanded" else "copy_button_expanded"),
)

/** A filing control, worded from the setting and tinted by whether it is primary. */
@Composable
private fun FilingButton(
    label: ActionLabel,
    style: ControlBlockFit.ButtonStyle?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = WordedButton(
    style = style,
    icon = if (label.action == FilingAction.MOVE) {
        Icons.AutoMirrored.Filled.DriveFileMove
    } else {
        Icons.Default.ContentCopy
    },
    word = label.verb.asString(),
    keyHint = label.shortcut,
    description = label.accessibilityLabel.asString(),
    contentColor = if (label.isPrimary) ScoreGood else Zinc50,
    filled = label.isPrimary,
    outlined = !label.isPrimary,
    onClick = onClick,
    modifier = modifier,
)

/**
 * The one button shape on this screen: glyph, word, permanent key cap.
 *
 * The key cap is not hover-revealed. A tablet has no hover, and the lesson
 * recorded on the desktop app (2024-05-18, `ai/memory/palette.md`) is that a
 * shortcut nobody can see is a shortcut nobody uses.
 *
 * A null [style] is the FULL 80 dp two-line button. Otherwise the compact button is
 * 48 dp tall and takes the shape the cell width affords ([ControlBlockFit.buttonStyle]):
 * one line when wide, word over key cap when narrow, and without the icon when the
 * cell is too narrow even for that. The word steps down in size before it would
 * ellipsize and the key cap is never shortened, so a file-changing control always
 * reads its full word and shortcut (AD-16: it read `C… [C]` at 720 x 560).
 */
@Composable
private fun WordedButton(
    icon: ImageVector,
    word: String,
    keyHint: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ControlBlockFit.ButtonStyle? = null,
    contentColor: Color = Zinc50,
    filled: Boolean = false,
    outlined: Boolean = false,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val resolvedContent = if (enabled) contentColor else Zinc600
    val background by animateColorAsState(
        targetValue = when {
            !enabled -> Color.Transparent
            filled -> BrandAccentTonal
            hovered -> HoverFill
            else -> StudioCard
        },
        animationSpec = tween(150),
        label = "buttonBackground",
    )

    val compact = style != null
    val container = modifier
        .height(if (compact) ControlBlockFit.CompactButtonHeight else ControlBlockFit.FullButtonHeight)
        .clip(RoundedCornerShape(8.dp))
        .background(background)
        .then(
            if (outlined) Modifier.border(1.dp, StudioBorder, RoundedCornerShape(8.dp))
            else Modifier
        )
        .hoverable(interactionSource, enabled = enabled)
        .then(
            if (enabled) {
                Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onClick, role = Role.Button)
            } else {
                Modifier
            }
        )
        .semantics { contentDescription = description }
        .padding(
            when (style) {
                null -> 6.dp
                ControlBlockFit.ButtonStyle.INLINE -> 4.dp
                else -> 3.dp
            }
        )
    val formattedKey = if (keyHint.startsWith("[")) keyHint else "[$keyHint]"
    val keyColor = if (enabled) HintText else Zinc700
    val wordStyle = TextStyle(fontSize = 13.sp, color = resolvedContent)
    val keyStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = keyColor)
    val measurer = rememberTextMeasurer()

    // The word shrinks (13 sp down to 9 sp) before it ever ellipsizes: at 960 x 600
    // the 13 sp word read "Dele…", and a destructive control must say what it does. The size
    // is the largest step whose line fits both the width and the height the word is left,
    // measured below: an auto-size that stepped by width alone let a 13 sp word overflow a
    // 48 dp button's height at font scale 2.0.
    val wordText: @Composable (Modifier, TextUnit) -> Unit = { m, size ->
        BasicText(
            text = word,
            style = wordStyle.copy(fontSize = size),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = m.testTag("action_word"),
        )
    }
    val keyText: @Composable () -> Unit = {
        Text(
            text = formattedKey,
            style = keyStyle,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag("action_key"),
        )
    }
    val glyph: @Composable (Dp) -> Unit = { size ->
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(size),
            tint = resolvedContent,
        )
    }

    BoxWithConstraints(modifier = container, contentAlignment = Alignment.Center) {
        // Which parts fit is measured, not assumed from the cell width: at font scale 2.0 the
        // arrangement priced at 1.0 cut "Delete" and pushed its key cap out of the button.
        val glyphSize = when (style) {
            null -> 22.dp
            ControlBlockFit.ButtonStyle.INLINE -> 20.dp
            else -> 18.dp
        }
        val gap = if (style == null) 5.dp else 4.dp
        val density = LocalDensity.current
        val wordMin = measurer.measure(word, wordStyle.copy(fontSize = WordMinSize), maxLines = 1, softWrap = false)
        val key = measurer.measure(formattedKey, keyStyle, maxLines = 1, softWrap = false)
        val iconPx = with(density) { glyphSize.roundToPx() }
        val gapPx = with(density) { gap.roundToPx() }
        val content = ControlBlockFit.buttonContent(
            preferred = style,
            m = ControlBlockFit.ButtonMeasure(
                width = constraints.maxWidth,
                height = constraints.maxHeight,
                icon = iconPx,
                gap = gapPx,
                // A pixel of slack per text: layout places each part on fractional
                // positions, and a word measured exactly as wide as its slot ellipsised.
                wordWidth = wordMin.size.width + TextSlackPx,
                wordHeight = wordMin.size.height,
                keyWidth = key.size.width + TextSlackPx,
                keyHeight = key.size.height,
            ),
        )
        // The box the word's line is left once the glyph (beside it in COMPACT, above it in FULL)
        // and the key cap (beside or under it) are paid; the word takes the largest step that fits it.
        val besideKey = content.key == ControlBlockFit.KeyPlacement.BESIDE
        val belowKey = content.key == ControlBlockFit.KeyPlacement.BELOW
        val wordWidthPx = constraints.maxWidth -
            (if (style != null && content.icon) iconPx + gapPx else 0) -
            (if (besideKey) gapPx + key.size.width else 0)
        val wordHeightPx = constraints.maxHeight -
            (if (style == null && content.icon) iconPx + gapPx else 0) -
            (if (belowKey) key.size.height else 0)
        val wordSize = WordSizeSteps.firstOrNull { size ->
            val line = measurer.measure(word, wordStyle.copy(fontSize = size), maxLines = 1, softWrap = false).size
            line.width + TextSlackPx <= wordWidthPx && line.height <= wordHeightPx
        } ?: WordMinSize
        val words: @Composable () -> Unit = {
            when (content.key) {
                ControlBlockFit.KeyPlacement.BESIDE -> Row(
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    wordText(Modifier.weight(1f, fill = false), wordSize)
                    keyText()
                }
                ControlBlockFit.KeyPlacement.BELOW -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    wordText(Modifier, wordSize)
                    keyText()
                }
                ControlBlockFit.KeyPlacement.NONE -> wordText(Modifier, wordSize)
            }
        }
        if (style == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
            ) {
                if (content.icon) glyph(glyphSize)
                words()
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (content.icon) glyph(glyphSize)
                words()
            }
        }
    }
}

/** The smallest step the action word takes before it may ellipsise (REQ-AND-LAYOUT.07). */
private val WordMinSize = 9.sp

/** Pixels of slack per measured text in [ControlBlockFit.buttonContent] and the word's size. */
private const val TextSlackPx = 2

/** The word's sizes, largest first, from which the one whose line fits the button is taken. */
private val WordSizeSteps = listOf(13f, 12.5f, 12f, 11.5f, 11f, 10.5f, 10f, 9.5f, 9f).map { it.sp }

/**
 * A view control — no file changes hands, so the glyph carries it, with a one-word label under
 * it where the slot holds the word at the font scale in force (DESIGN.md § Control block: the
 * one exception to icon-only bar items; the usability panel of 2026-10-24 could not tell what
 * the four bare glyphs toggled).
 *
 * The control is always a 48 x 48 dp square (AD-17), and the node that carries the caller's
 * test tag is that square, so a measurement of the tag is a measurement of the hit target.
 *
 * A toggle ([active] non-null) says its state three ways: filled with the accent glyph when on,
 * outlined with a grey glyph when off (never by colour alone), and to a screen reader as a
 * switch that is on or off. [active] null is a plain button (the guide).
 */
@Composable
private fun ViewToggle(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean? = null,
    label: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val on = active == true
    val shape = RoundedCornerShape(8.dp)

    val interaction = if (active != null) {
        Modifier.toggleable(value = active, role = Role.Switch, onValueChange = { onClick() })
    } else {
        Modifier.clickable(onClick = onClick, role = Role.Button)
    }
    Box(
        modifier = modifier
            .size(ControlBlockFit.ToggleHitSize)
            .clip(shape)
            .hoverable(interactionSource)
            .handCursor()
            .then(interaction)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .size(
                    width = ControlBlockFit.ToggleVisualSize + 8.dp,
                    height = if (label != null) ControlBlockFit.ToggleHitSize - 4.dp else ControlBlockFit.ToggleVisualSize,
                )
                .clip(shape)
                .background(
                    when {
                        on -> BrandAccentTonal
                        hovered -> HoverFill
                        else -> Color.Transparent
                    }
                )
                .then(
                    if (active != null) Modifier.border(1.dp, if (on) BrandAccent else StudioBorder, shape) else Modifier
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (on) BrandAccent else Zinc400,
            )
            if (label != null) {
                Text(
                    text = label,
                    style = ToggleLabelStyle,
                    color = if (on) BrandAccentText else Zinc400,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag("toggle_label"),
                )
            }
        }
    }
}

/** The view toggles' one-word labels: small, because the slot is a 48 dp square. */
private val ToggleLabelStyle = TextStyle(fontSize = 9.sp, lineHeight = 11.sp)

/**
 * Whether every view toggle's label fits under its glyph in the 48 x 48 dp slot at the font
 * scale in force; otherwise all four are glyph-only (the spoken label still names each).
 */
@Composable
private fun toggleLabelsFit(labels: List<String>): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return with(density) {
        val maxWidth = (ControlBlockFit.ToggleVisualSize + 8.dp - 4.dp).roundToPx()
        val maxHeight = (ControlBlockFit.ToggleHitSize - 4.dp - 20.dp).roundToPx()
        labels.all { label ->
            val size = measurer.measure(label, ToggleLabelStyle, maxLines = 1, softWrap = false).size
            size.width <= maxWidth && size.height <= maxHeight
        }
    }
}
