package ch.bojovic.lichtkasten.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Pinch, pan and double-tap around [content] (an image that fills the
 * box). Lives inside a pager: while the image is not zoomed, or when a
 * horizontal pan runs into the edge, the gesture is left to the pager.
 */
@Composable
fun ZoomableBox(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (size.width * (s - 1f)) / 2f
        val maxY = (size.height * (s - 1f)) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        val target = if (scale > 1.01f) 1f else DOUBLE_TAP_SCALE
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val targetOffset = if (target == 1f) Offset.Zero else clamp((center - tap) * (target - 1f), target)
                        scope.launch {
                            val anim = Animatable(0f)
                            val fromScale = scale
                            val fromOffset = offset
                            anim.animateTo(1f) {
                                scale = fromScale + (target - fromScale) * value
                                offset = fromOffset + (targetOffset - fromOffset) * value
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val zoomChange = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (pressed >= 2) {
                            val centroid = event.calculateCentroid()
                            val newScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            // Keep the point under the fingers where it is.
                            val focal = centroid - center
                            val newOffset = (offset - focal) * (newScale / scale) + focal + pan
                            scale = newScale
                            offset = clamp(newOffset, newScale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (pressed == 1 && scale > 1.01f && pan != Offset.Zero) {
                            val wanted = offset + pan
                            val clamped = clamp(wanted, scale)
                            val stuckHorizontally = abs(clamped.x - offset.x) < 0.5f && abs(pan.x) > abs(pan.y)
                            if (!stuckHorizontally) {
                                offset = clamped
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale <= 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            content()
        }
    }
}
