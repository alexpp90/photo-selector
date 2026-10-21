package com.photoselectortoolbox.ui.selector

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoselectortoolbox.domain.format.ActionLabel
import com.photoselectortoolbox.domain.format.SelectionActionLabels
import com.photoselectortoolbox.domain.format.SelectorLabels
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.ui.components.StarRatingBar
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.BrandAccentNav
import com.photoselectortoolbox.ui.theme.BrandAccentText
import com.photoselectortoolbox.ui.theme.BrandAccentTonal
import com.photoselectortoolbox.ui.theme.ScoreBad
import com.photoselectortoolbox.ui.theme.ScoreGood
import com.photoselectortoolbox.ui.theme.StudioBorder
import com.photoselectortoolbox.ui.theme.StudioBorderActive
import com.photoselectortoolbox.ui.theme.StudioCard
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc50
import com.photoselectortoolbox.ui.theme.Zinc600
import com.photoselectortoolbox.ui.theme.Zinc700

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
 * ## Three heights, chosen by measurement (AD-1)
 *
 * The block is 332 dp tall at font scale 1.0 and the frames do not give way for
 * it — row 1 is exactly one frame tall, ~376 dp at 1280 × 800 but ~210 dp at
 * 600 × 480 and ~336 dp at 1024 × 720. [ControlBlockFit] compares the height the
 * row actually hands over with what each variant needs (font scale included) and
 * picks the first that fits:
 *
 * 1. **FULL** — the 80 dp two-line buttons above, the toggle row, the counter.
 * 2. **COMPACT** (~196 dp) — 48 dp one-line buttons (icon · word · key cap, the
 *    key cap never ellipsized: the word gives way first), four 36 dp toggles with
 *    the position counter right-aligned in the same row. Nothing is hidden.
 * 3. **SCROLLING** — only when even COMPACT does not fit: the block scrolls with
 *    the counter *first*, so the one fact that locates the user is the one that
 *    cannot be scrolled out of sight. It is never silently clipped.
 *
 * The state is exposed as a marker tag on a wrapper (`control_block_full`,
 * `control_block_compact`, `control_block_scrolling`) so a test can tell which
 * variant it is looking at; `control_block` stays on the block itself.
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
) {
    val fontScale = LocalDensity.current.fontScale
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier = modifier) {
        val isNarrow = maxWidth < 240.dp
        val horizontalPadding = if (isNarrow) 4.dp else 10.dp
        val innerWidth = maxWidth - horizontalPadding * 2
        val mode = ControlBlockFit.mode(maxHeight, fontScale, density, innerWidth)
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
        val counterDescription = if (stillEnumerating) {
            "Image $position of $total so far, still loading"
        } else {
            "Image $position of $total"
        }
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
                modifier = counterModifier
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

                // The counter leads, on its own line, so it is the one thing a
                // scroll position can never hide.
                if (counterOnTop) {
                    counter(Modifier.fillMaxWidth())
                }

                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    labels.forEach { label ->
                        FilingButton(
                            label = label,
                            style = buttonStyle,
                            onClick = if (label.action == FilingAction.MOVE) {
                                actions.onMove
                            } else {
                                actions.onCopy
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(
                                    if (label.action == FilingAction.MOVE) {
                                        "move_button_expanded"
                                    } else {
                                        "copy_button_expanded"
                                    }
                                ),
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    // Delete is diagonally opposite the configured filing button, not
                    // beside it. The two most-used controls on this screen are "file
                    // this" and "destroy this", and they should not be neighbours.
                    WordedButton(
                        icon = Icons.Default.Delete,
                        word = "Delete",
                        keyHint = "Del",
                        description = "Delete, shortcut Delete or Backspace",
                        style = buttonStyle,
                        contentColor = ScoreBad,
                        outlined = true,
                        onClick = actions.onDelete,
                        modifier = Modifier.weight(1f).testTag("delete_button_expanded"),
                    )
                    WordedButton(
                        icon = Icons.Default.Fullscreen,
                        word = "Full",
                        keyHint = "F",
                        description = "Open fullscreen, shortcut F",
                        style = buttonStyle,
                        onClick = actions.onFullscreen,
                        modifier = Modifier.weight(1f).testTag("fullscreen_button"),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    WordedButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        word = "Prev",
                        keyHint = "←",
                        description = "Previous image, shortcut left arrow",
                        style = buttonStyle,
                        enabled = canGoPrevious,
                        onClick = onNavigatePrevious,
                        modifier = Modifier.weight(1f).testTag("rail_previous"),
                    )
                    WordedButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        word = "Next",
                        keyHint = "→",
                        description = "Next image, shortcut right arrow",
                        style = buttonStyle,
                        enabled = canGoNext,
                        onClick = onNavigateNext,
                        modifier = Modifier.weight(1f).testTag("rail_next"),
                    )
                }

                // View controls. Icon-only is acceptable here and only here: none of
                // these four changes a file, so a mis-tap costs a glance, not a photo.
                val toggles: List<@Composable () -> Unit> = listOf(
                    {
                        ViewToggle(
                            icon = Icons.Outlined.Info,
                            description = if (detailsVisible) "Hide photo details" else "Show photo details",
                            active = detailsVisible,
                            onClick = actions.onToggleDetails,
                            modifier = Modifier.testTag("details_toggle"),
                        )
                    },
                    {
                        ViewToggle(
                            icon = Icons.Default.ViewCarousel,
                            description = if (filmstripVisible) "Hide the filmstrip" else "Show the filmstrip",
                            active = filmstripVisible,
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
                            description = if (overlayValuesVisible) {
                                "Hide the values on Previous and Next"
                            } else {
                                "Show the values on Previous and Next"
                            },
                            active = overlayValuesVisible,
                            onClick = actions.onToggleOverlayValues,
                            modifier = Modifier.testTag("overlay_values_toggle"),
                        )
                    },
                    {
                        ViewToggle(
                            icon = Icons.Default.Keyboard,
                            description = "Shortcuts and layout guide",
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
                        if (!counterOnTop) {
                            counter(
                                Modifier.padding(start = ControlBlockFit.CounterGap)
                                    .widthIn(min = ControlBlockFit.CounterWidth)
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
                    counter(Modifier.fillMaxWidth().padding(top = 2.dp))
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
                        text = filter.label,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isSelected) BrandAccentText else Zinc400,
                    )
                }
            }
        }
    }
}

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
    word = label.verb,
    keyHint = label.shortcut,
    description = label.accessibilityLabel,
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
            hovered -> StudioBorderActive
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
                    .clickable(onClick = onClick)
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
    val keyColor = if (enabled) Zinc600 else Zinc700

    // The word shrinks (13 sp down to 9 sp) before it ever ellipsizes: at 960 x 600
    // the 13 sp word read "Dele…", and a destructive control must say what it does.
    val wordText: @Composable (Modifier) -> Unit = { m ->
        BasicText(
            text = word,
            style = TextStyle(fontSize = 13.sp, color = resolvedContent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 13.sp),
            modifier = m.testTag("action_word"),
        )
    }
    val keyText: @Composable () -> Unit = {
        Text(
            text = formattedKey,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = keyColor,
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

    when (style) {
        ControlBlockFit.ButtonStyle.INLINE -> Row(
            modifier = container,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            glyph(20.dp)
            wordText(Modifier.weight(1f, fill = false))
            keyText()
        }
        ControlBlockFit.ButtonStyle.STACKED_WITH_ICON -> Row(
            modifier = container,
            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            glyph(18.dp)
            Column(
                modifier = Modifier.weight(1f, fill = false),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                wordText(Modifier)
                keyText()
            }
        }
        ControlBlockFit.ButtonStyle.STACKED -> Column(
            modifier = container,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            wordText(Modifier)
            keyText()
        }
        null -> Column(
            modifier = container,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            glyph(22.dp)
            Row(
                modifier = Modifier.padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = word, fontSize = 13.sp, color = resolvedContent, modifier = Modifier.testTag("action_word"))
                keyText()
            }
        }
    }
}

/**
 * A view control — no file changes hands, so the glyph carries it.
 *
 * The control is always a 48 x 48 dp square (AD-17), and the node that carries the
 * caller's test tag is that square, so a measurement of the tag is a measurement of
 * the hit target. The 36 dp hover chip and 20 dp glyph inside it are only visual.
 */
@Composable
private fun ViewToggle(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = modifier
            .size(ControlBlockFit.ToggleHitSize)
            .clip(RoundedCornerShape(8.dp))
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(ControlBlockFit.ToggleVisualSize)
                .clip(RoundedCornerShape(8.dp))
                .background(if (hovered) StudioBorderActive else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (active) BrandAccent else Zinc400,
            )
        }
    }
}
