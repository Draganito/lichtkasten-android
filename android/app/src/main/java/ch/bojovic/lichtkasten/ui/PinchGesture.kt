package ch.bojovic.lichtkasten.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Two-finger pinch on a scrolling container. Seen in the Initial pass so
 * the grid underneath never gets the second finger as a scroll; a single
 * finger passes through untouched.
 */
fun Modifier.pinchToScale(onScale: (Float) -> Unit, onEnd: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pinching = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    pinching = true
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) onScale(zoom)
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (pinching) onEnd()
        }
    }
