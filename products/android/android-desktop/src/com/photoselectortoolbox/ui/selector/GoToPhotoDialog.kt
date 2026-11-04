package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.format.SelectorLabels
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.domain.session.JumpPreview
import com.photoselectortoolbox.domain.session.JumpResolution
import com.photoselectortoolbox.domain.session.PhotoDates
import com.photoselectortoolbox.ui.theme.StudioCard
import java.time.LocalDate
import kotlinx.coroutines.flow.filterNotNull

/**
 * "Go to photo": one field that takes the number the position counter shows, part of a file
 * name, or a date — and a calendar button that swaps a date picker into the same dialog.
 *
 * The dialog counts as an open overlay in [SelectorScreen]: while it is up every
 * selector shortcut but Esc is suppressed, so typing `1` into the field can never
 * maximise a frame. The field is focused on open; closing it hands focus back to
 * the selector root through the screen's overlay re-claim (AD-11).
 *
 * Resolution is `PhotoJump` — this composable only shows what it says. The line under the
 * field is, by what is typed:
 * - nothing: the days the folder spans, `3 Oct 2026 – 2 Nov 2026`;
 * - a position, a name or a date that lands: the file it would open and when it was taken;
 * - a day without photos: the same, plus "No photos on … — showing …" in the neutral colour
 *   (Go jumps to the nearest day; it is not an error).
 * An out-of-range number is reported only after Go has been pressed, and a date still being
 * typed (`2026-10-`) is not scolded as "No match", so the field does not shout at someone who
 * has not finished typing.
 *
 * The picker ([DatePicker]) is limited to days that have photos ([PhotoDates]) and opens on
 * [pickerStartDay]; picking a day jumps at once ([onJumpToDate]) and closes the dialog. It
 * scrolls where the window is too short to show it whole (a 600 x 480 window).
 *
 * [onGo] performs the jump and returns what the input resolved to; the dialog
 * closes itself only when that was a photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoToPhotoDialog(
    images: List<ImageItem>,
    ratingFilter: RatingFilter,
    dates: PhotoDates,
    pickerStartDay: LocalDate?,
    preview: (String) -> JumpPreview,
    onGo: (String) -> JumpResolution,
    onJumpToDate: (LocalDate) -> JumpResolution.Resolved?,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val jump = remember(text, images, ratingFilter) { preview(text) }
    val resolution = jump.resolution
    val total = images.size

    // Back from the picker, the field is the thing to type into again.
    LaunchedEffect(picking) { if (!picking) focusRequester.requestFocus() }

    val submit: () -> Unit = {
        attempted = true
        if (onGo(text) is JumpResolution.Resolved) onDismiss()
    }

    val dateInProgress = isDateInProgress(text)
    val supporting: String = when {
        text.isBlank() ->
            if (attempted) {
                SelectorLabels.goToPhotoRange(total)
            } else if (dates.first != null && dates.last != null) {
                SelectorLabels.goToPhotoDateSpan(dates.first!!, dates.last!!)
            } else {
                ""
            }
        resolution is JumpResolution.Resolved -> SelectorLabels.goToPhotoPreview(
            fileName = jump.fileName ?: images.getOrNull(resolution.index)?.fileName.orEmpty(),
            takenAt = jump.takenAt,
        )
        resolution is JumpResolution.NoNameMatch ->
            if (dateInProgress && !attempted) "" else SelectorLabels.GO_TO_PHOTO_NO_MATCH
        else -> if (attempted) SelectorLabels.goToPhotoRange(total) else ""
    }
    val nearestDay: String? = (resolution as? JumpResolution.Resolved)?.date?.let(SelectorLabels::goToPhotoNearestDay)
    val isError = resolution !is JumpResolution.Resolved &&
        (attempted || (resolution is JumpResolution.NoNameMatch && !dateInProgress))

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("go_to_photo_dialog"),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(SelectorLabels.GO_TO_PHOTO) },
        text = {
            if (picking) {
                DayPicker(
                    dates = dates,
                    startDay = pickerStartDay,
                    onJumpToDate = onJumpToDate,
                    onDismiss = onDismiss,
                )
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        attempted = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        // Esc is decided before the soft keyboard: with an on-screen keyboard
                        // up, the IME would otherwise take Esc to hide itself and the dialog
                        // would need a second press (measured on the reference AVD).
                        .onInterceptKeyBeforeSoftKeyboard { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                onDismiss()
                                true
                            } else {
                                false
                            }
                        }
                        // Esc and Enter are decided here, before the field's own handling:
                        // the dialog is a window of its own, so the selector's key handler
                        // never sees them.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Escape -> { onDismiss(); true }
                                Key.Enter, Key.NumPadEnter -> { submit(); true }
                                else -> false
                            }
                        }
                        .testTag("go_to_photo_field"),
                    singleLine = true,
                    label = { Text(SelectorLabels.GO_TO_PHOTO_FIELD) },
                    placeholder = { Text(SelectorLabels.goToPhotoPlaceholder(total)) },
                    trailingIcon = {
                        // 48 dp (IconButton's own size), so the target is not the 24 dp glyph.
                        IconButton(
                            onClick = { picking = true },
                            enabled = !dates.isEmpty,
                            modifier = Modifier
                                // IconButton is 40 dp and only *expands* its touch target; this
                                // is a real 48 dp node, so a measurement of it is the target.
                                .size(48.dp)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .testTag("go_to_photo_calendar"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = SelectorLabels.GO_TO_PHOTO_PICK_DATE,
                            )
                        }
                    },
                    supportingText = {
                        Column {
                            Text(
                                text = supporting.ifEmpty { " " },
                                modifier = Modifier.testTag("go_to_photo_support"),
                                color = if (isError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            // Neutral on purpose: Go works, it just lands on another day.
                            if (nearestDay != null) {
                                Text(
                                    text = nearestDay,
                                    modifier = Modifier.testTag("go_to_photo_nearest_day"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    isError = isError,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
            }
        },
        confirmButton = {
            if (picking) {
                TextButton(onClick = { picking = false }, modifier = Modifier.testTag("go_to_photo_back")) {
                    Text(SelectorLabels.GO_TO_PHOTO_BACK)
                }
            } else {
                TextButton(onClick = submit, modifier = Modifier.testTag("go_to_photo_go")) {
                    Text(SelectorLabels.GO_TO_PHOTO_GO)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("go_to_photo_cancel")) {
                Text(SelectorLabels.GO_TO_PHOTO_CANCEL)
            }
        },
        containerColor = StudioCard,
    )
}

/**
 * The Material 3 date picker, limited to the days that have photos. Picking a day is the
 * jump: there is no confirm step. It scrolls, so a 600 x 480 window can still reach every day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(
    dates: PhotoDates,
    startDay: LocalDate?,
    onJumpToDate: (LocalDate) -> JumpResolution.Resolved?,
    onDismiss: () -> Unit,
) {
    val selectable = remember(dates) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = dates.isSelectableUtcMillis(utcTimeMillis)
            override fun isSelectableYear(year: Int): Boolean = dates.isSelectableYear(year)
        }
    }
    // No initial selection: a selection is the jump, so a preselected day would jump on open.
    val state = rememberDatePickerState(
        initialDisplayedMonthMillis = (startDay ?: dates.last)?.let(PhotoDates::pickerMillisOf),
        selectableDates = selectable,
    )
    val jump by rememberUpdatedState(onJumpToDate)
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(state) {
        snapshotFlow { state.selectedDateMillis }.filterNotNull().collect { millis ->
            if (jump(PhotoDates.dateOfPickerMillis(millis)) != null) dismiss()
        }
    }
    DatePicker(
        state = state,
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .testTag("go_to_photo_picker"),
        title = null,
        headline = null,
        showModeToggle = false,
        colors = DatePickerDefaults.colors(containerColor = StudioCard),
    )
}

/**
 * Whether [text] is only digits and date separators — a date (or a number) that is still
 * being typed, `2026-10-` or `12.10`. Such text is not reported "No match" until Go.
 */
internal fun isDateInProgress(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.isNotEmpty() && trimmed.any { it.isDigit() } && trimmed.all { it.isDigit() || it in ".-/ " }
}
