package com.photoselectortoolbox.ui.selector

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoselectortoolbox.domain.format.SelectorLabels
import com.photoselectortoolbox.domain.interaction.SelectorShortcut
import com.photoselectortoolbox.domain.session.SelectorSortOrder
import com.photoselectortoolbox.ui.components.handCursor
import com.photoselectortoolbox.ui.theme.HintText
import com.photoselectortoolbox.ui.theme.StudioBorder
import com.photoselectortoolbox.ui.theme.HoverFill
import com.photoselectortoolbox.ui.theme.StudioCard
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc50
import com.photoselectortoolbox.R
import com.photoselectortoolbox.ui.text.asString
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.text

/**
 * The four orders the selector can be in, as one radio group: the three plain orders
 * and Blurriest first (worst-first triage, `U-34`), which is a session view over scores.
 * [SortChoice.of] is the one place the pair (order, worst-first) becomes a single choice.
 */
enum class SortChoice(val menuLabel: UiText, val chipWord: UiText, val tag: String) {
    NEWEST_FIRST(SelectorLabels.SORT_NEWEST_FIRST, SelectorLabels.SORT_CHIP_NEWEST, "sort_newest_first"),
    OLDEST_FIRST(SelectorLabels.SORT_OLDEST_FIRST, SelectorLabels.SORT_CHIP_OLDEST, "sort_oldest_first"),
    FILE_NAME(SelectorLabels.SORT_FILE_NAME, SelectorLabels.SORT_CHIP_FILE_NAME, "sort_file_name"),
    BLURRIEST_FIRST(SelectorLabels.SORT_BLURRIEST_FIRST, SelectorLabels.SORT_CHIP_BLURRIEST, "sort_blurriest_first");

    companion object {
        fun of(order: SelectorSortOrder, worstFirst: Boolean): SortChoice = when {
            worstFirst -> BLURRIEST_FIRST
            order == SelectorSortOrder.NEWEST_FIRST -> NEWEST_FIRST
            order == SelectorSortOrder.OLDEST_FIRST -> OLDEST_FIRST
            else -> FILE_NAME
        }
    }
}

/**
 * What the Sort chip and its menu need: the order now in force, whether Blurriest first is
 * on offer (it needs scores), and whether the menu is open.
 *
 * The open state is the screen's, not the chip's, so the `S` key and a tap open the same menu
 * and the menu counts as an open overlay (shortcuts are suppressed while it is up).
 */
data class SortControl(
    val selected: SortChoice = SortChoice.NEWEST_FIRST,
    val blurriestEnabled: Boolean = false,
    val menuOpen: Boolean = false,
    val onMenuOpenChange: (Boolean) -> Unit = {},
    val onSelect: (SortChoice) -> Unit = {},
)

/** The radio group, in the order it is shown; the active entry leads with a check mark. */
@Composable
internal fun SortMenuItems(
    selected: SortChoice,
    blurriestEnabled: Boolean,
    onSelect: (SortChoice) -> Unit,
) {
    SortChoice.entries.forEach { choice ->
        val enabled = choice != SortChoice.BLURRIEST_FIRST || blurriestEnabled
        SortMenuItem(
            label = choice.menuLabel.asString(),
            selected = choice == selected,
            tag = choice.tag,
            enabled = enabled,
            // Blurriest first needs sharpness scores; greyed out, it says so instead of nothing.
            reason = SelectorLabels.SORT_NEEDS_SCAN.asString().takeIf { !enabled },
            onClick = { onSelect(choice) },
        )
    }
}

/**
 * One entry of the sort radio group.
 *
 * The active entry leads with a check mark, not a colour change alone, and says
 * `selected` to a screen reader.
 */
@Composable
internal fun SortMenuItem(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    /** Why the entry is disabled, shown at its end; null when it is enabled. */
    reason: String? = null,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = reason?.let {
            {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    color = HintText,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag("${tag}_reason"),
                )
            }
        },
        leadingIcon = {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null)
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .testTag(tag).handCursor(),
    )
}

/**
 * The Sort menu: the same radio group as the overflow menu, anchored to its parent — the
 * Sort chip where the locator row is drawn, the control block where it is not (the `S` key
 * still opens it there).
 */
@Composable
internal fun SortMenu(sort: SortControl) {
    DropdownMenu(
        expanded = sort.menuOpen,
        onDismissRequest = { sort.onMenuOpenChange(false) },
        modifier = Modifier.testTag("sort_menu"),
    ) {
        SortMenuItems(sort.selected, sort.blurriestEnabled) { choice ->
            sort.onMenuOpenChange(false)
            sort.onSelect(choice)
        }
    }
}

/**
 * The locator row (`[USER 2026-11-04]`): two chips that say where you are and let you move
 * or reorder — the **Go** chip (the position counter, opens "Go to photo") and the **Sort**
 * chip (opens the order menu).
 *
 * Each chip is a 48 dp-tall slot, half the row wide, and that slot is the clickable and the
 * node that carries the tag; the chip itself is drawn 40 dp tall inside it with a 1 dp outline
 * (no shadow, REQ-AND-LAYOUT), so the targets abut and never overlap. The ripple is drawn on
 * the chip, not on the slot. Content follows the cell ([ControlBlockFit.locatorStyle]): icon,
 * phrase and key cap when wide; icon and one word; the counter or the word alone.
 *
 * The counter node keeps the tag `position_counter`, so what the user reads and what the
 * tests and flows read is the same node it always was.
 */
@Composable
internal fun LocatorRow(
    counterText: String,
    goDescription: String,
    onGoToPhoto: () -> Unit,
    sort: SortControl,
    style: ControlBlockFit.LocatorStyle,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ControlBlockFit.LocatorRowHeight)
            .testTag("locator_row"),
    ) {
        LocatorChip(
            description = goDescription,
            onClickLabel = SelectorLabels.GO_TO_PHOTO.asString(),
            onClick = onGoToPhoto,
            tag = "go_chip",
            modifier = Modifier.weight(1f),
        ) {
            if (style != ControlBlockFit.LocatorStyle.NARROW) ChipGlyph(Icons.Default.Search)
            Text(
                text = counterText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Zinc50,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag("position_counter"),
            )
            if (style == ControlBlockFit.LocatorStyle.WIDE) ChipKeyCap(SelectorShortcut.GO_TO.input.asString(), "go_chip_key")
        }
        Box(modifier = Modifier.weight(1f)) {
            LocatorChip(
                description = SelectorLabels.sortChipDescription(sort.selected.menuLabel).asString(),
                onClickLabel = null,
                onClick = { sort.onMenuOpenChange(true) },
                tag = "sort_chip",
                modifier = Modifier.fillMaxSize(),
            ) {
                if (style != ControlBlockFit.LocatorStyle.NARROW) ChipGlyph(Icons.Default.SwapVert)
                Text(
                    text = if (style == ControlBlockFit.LocatorStyle.WIDE) {
                        sort.selected.menuLabel.asString()
                    } else {
                        sort.selected.chipWord.asString()
                    },
                    fontSize = 12.sp,
                    color = Zinc50,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag("sort_chip_word"),
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Zinc400,
                )
                if (style == ControlBlockFit.LocatorStyle.WIDE) ChipKeyCap(SelectorShortcut.SORT.input.asString(), "sort_chip_key")
            }
            SortMenu(sort)
        }
    }
}

/** A 48 dp-tall slot with a 40 dp chip drawn inside it; the slot is the one clickable. */
@Composable
private fun LocatorChip(
    description: String,
    onClickLabel: String?,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = if (hovered) HoverFill else StudioCard,
        animationSpec = tween(150),
        label = "locatorChipBackground",
    )
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description }
            .testTag(tag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = (ControlBlockFit.LocatorRowHeight - ControlBlockFit.LocatorChipHeight) / 2)
                .clip(shape)
                .background(background)
                .border(1.dp, StudioBorder, shape)
                .indication(interactionSource, LocalIndication.current)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
private fun ChipGlyph(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = Zinc400,
    )
}

@Composable
private fun ChipKeyCap(key: String, tag: String) {
    Text(
        text = "[$key]",
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        color = HintText, // a key hint: 4.5:1 on the card, as every other key cap
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.testTag(tag),
    )
}
