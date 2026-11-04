package com.photoselectortoolbox.ui.selector

import com.photoselectortoolbox.ui.components.handCursor
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.format.SelectorLabels
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.domain.session.JumpResolution
import com.photoselectortoolbox.domain.session.PhotoJump
import com.photoselectortoolbox.ui.theme.StudioCard

/**
 * "Go to photo": one field that takes the number the position counter shows, or
 * part of a file name.
 *
 * The dialog counts as an open overlay in [SelectorScreen]: while it is up every
 * selector shortcut but Esc is suppressed, so typing `1` into the field can never
 * maximise a frame. The field is focused on open; closing it hands focus back to
 * the selector root through the screen's overlay re-claim (AD-11).
 *
 * Resolution is [PhotoJump] — this composable only shows what it says. The live
 * line under the field is the file name the input would land on; an out-of-range
 * or empty number is reported only after Go has been pressed, so the field does
 * not shout at someone who has not finished typing.
 *
 * [onGo] performs the jump and returns what the input resolved to; the dialog
 * closes itself only when that was a photo.
 */
@Composable
internal fun GoToPhotoDialog(
    images: List<ImageItem>,
    ratingFilter: RatingFilter,
    onGo: (String) -> JumpResolution,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val resolution = remember(text, images, ratingFilter) {
        PhotoJump.resolve(text, images, ratingFilter)
    }
    val total = images.size

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit: () -> Unit = {
        attempted = true
        if (onGo(text) is JumpResolution.Resolved) onDismiss()
    }

    val supporting: String = when (resolution) {
        is JumpResolution.Resolved -> images.getOrNull(resolution.index)?.fileName.orEmpty()
        is JumpResolution.NoNameMatch -> SelectorLabels.GO_TO_PHOTO_NO_MATCH
        is JumpResolution.OutOfRange, JumpResolution.Empty ->
            if (attempted) SelectorLabels.goToPhotoRange(total) else ""
    }
    val isError = resolution !is JumpResolution.Resolved && (attempted || resolution is JumpResolution.NoNameMatch)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("go_to_photo_dialog"),
        title = { Text(SelectorLabels.GO_TO_PHOTO) },
        text = {
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
                supportingText = {
                    Text(
                        text = supporting.ifEmpty { " " },
                        modifier = Modifier.testTag("go_to_photo_support"),
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                isError = isError,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
        },
        confirmButton = {
            TextButton(onClick = submit, modifier = Modifier.testTag("go_to_photo_go").handCursor()) {
                Text(SelectorLabels.GO_TO_PHOTO_GO)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("go_to_photo_cancel").handCursor()) {
                Text(SelectorLabels.GO_TO_PHOTO_CANCEL)
            }
        },
        containerColor = StudioCard,
    )
}
