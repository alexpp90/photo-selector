package com.photoselectortoolbox.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/**
 * The hand cursor every interactive control shows under a mouse or trackpad (DeX, Chromebook;
 * REQ-AND-INPUT.08). Put it on Material buttons, switches and checkboxes, which do not set it
 * themselves; `PointerCursorTest` fails a control that has neither this nor `pointerHoverIcon`.
 */
fun Modifier.handCursor(): Modifier = pointerHoverIcon(PointerIcon.Hand)
