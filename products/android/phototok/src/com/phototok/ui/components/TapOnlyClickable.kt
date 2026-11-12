package com.phototok.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

/**
 * A click that only a real tap triggers: the press starts on the node and lifts on it without
 * moving more than the touch slop. `clickable` also fires after a horizontal drag that ends on
 * the node (no vertical scroll consumes it), which made a sideways swipe over Settings reset every
 * folder's seen history ("Reset all seen", lab 2026-11-05 step 10). The ripple shows while pressed
 * and is cancelled once the finger moves away; TalkBack and keyboard users get the same [onClick]
 * through the semantics action.
 */
fun Modifier.tapOnlyClickable(role: Role = Role.Button, onClick: () -> Unit): Modifier = composed {
    val currentOnClick by rememberUpdatedState(onClick)
    val interactions = remember { MutableInteractionSource() }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val scope = rememberCoroutineScope()
    val indication = LocalIndication.current
    this
        // Merged like `clickable`: the row's title is the click target's text.
        .semantics(mergeDescendants = true) {
            this.role = role
            onClick { currentOnClick(); true }
        }
        .indication(interactions, indication)
        // Keyboard and D-pad: Enter or the centre key, as `clickable` did.
        .onKeyEvent { event ->
            val activates = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter
            if (activates && event.type == KeyEventType.KeyUp) currentOnClick()
            activates
        }
        .focusable(interactionSource = interactions)
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val press = PressInteraction.Press(down.position)
                scope.launch { interactions.emit(press) }
                var tapped = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > touchSlop) break
                    if (change.changedToUp()) {
                        val inside = change.position.x in 0f..size.width.toFloat() &&
                            change.position.y in 0f..size.height.toFloat()
                        tapped = inside && !change.isConsumed
                        if (tapped) change.consume()
                        break
                    }
                }
                scope.launch {
                    interactions.emit(if (tapped) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                }
                if (tapped) currentOnClick()
            }
        }
}
