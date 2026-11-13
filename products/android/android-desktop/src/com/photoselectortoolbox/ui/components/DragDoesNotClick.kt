package com.photoselectortoolbox.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A drag that ends on the row does not count as a tap.
 *
 * `clickable`, `toggleable` and `selectable` fire on the release whenever the pointer is still
 * inside the control and no scroller took the gesture. A Settings column scrolls vertically only,
 * so a sideways drag that ended on "Reset Guidance" reset it (backlog 2026-11-12). Put this
 * **before** the click modifier: it watches the gesture in the initial pass and, once the pointer
 * has moved past the touch slop, consumes the release, which the click modifier then treats as a
 * cancel. A tap, a keyboard Enter and an accessibility click are unchanged.
 */
fun Modifier.dragDoesNotClick(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        var dragged = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > slop) dragged = true
            if (!change.pressed) {
                if (dragged) change.consume()
                break
            }
        }
    }
}
