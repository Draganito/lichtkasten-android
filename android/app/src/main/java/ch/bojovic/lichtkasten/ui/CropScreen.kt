package ch.bojovic.lichtkasten.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ch.bojovic.lichtkasten.R
import ch.bojovic.lichtkasten.media.CropRect
import ch.bojovic.lichtkasten.media.ExportResult
import ch.bojovic.lichtkasten.media.ImageExport
import ch.bojovic.lichtkasten.media.ImageGeometry
import ch.bojovic.lichtkasten.media.ImageGeometry.Corner
import ch.bojovic.lichtkasten.media.ImageGeometry.Edge
import ch.bojovic.lichtkasten.media.MediaItem
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch

private val ASPECTS: List<Pair<String, Float?>> = listOf(
    "free" to null, "1:1" to 1f, "4:3" to 4f / 3f, "3:2" to 3f / 2f, "16:9" to 16f / 9f,
)
private val SIZES = listOf(0, 2048, 1024, 512)

/**
 * Crop and shrink one picture into a new file. The picture on screen is
 * the viewer's own decode; the crop rectangle is kept normalized, so what
 * the user frames here is exactly what [ImageExport] cuts from the full
 * file.
 */
@Composable
fun CropScreen(
    item: MediaItem,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    onSaved: (ExportResult.Saved) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val painter = rememberAsyncImagePainter(rememberViewerModel(item))
    val painterState by painter.state.collectAsState()
    val imageSize = (painterState as? AsyncImagePainter.State.Success)?.let { painter.intrinsicSize }
        ?.takeIf { it.width > 0f && it.height > 0f }
    val imageAspect = imageSize?.let { it.width / it.height }

    var crop by remember(item.id) { mutableStateOf(CropRect.FULL) }
    var aspectIndex by remember(item.id) { mutableStateOf(0) }
    var swapped by remember(item.id) { mutableStateOf(false) }
    var sizeIndex by remember(item.id) { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    val aspect: Float? = ASPECTS[aspectIndex].second?.let { if (swapped) 1f / it else it }
    val ratio: Float? = if (aspect != null && imageAspect != null) ImageGeometry.normalizedRatio(aspect, imageAspect) else null

    fun chooseAspect(index: Int, swap: Boolean) {
        aspectIndex = index
        swapped = swap
        val a = ASPECTS[index].second?.let { if (swap) 1f / it else it }
        if (imageAspect != null) crop = ImageGeometry.centeredCrop(a, imageAspect)
    }

    val failedText = stringResource(R.string.export_failed)
    val tooLargeText = stringResource(R.string.export_too_large)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black,
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White,
                actionIconContentColor = Color.White,
            ),
            navigationIcon = {
                IconButton(onClick = onClose, enabled = !busy) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.crop_title)) },
            actions = {
                TextButton(
                    enabled = imageSize != null && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            when (val r = ImageExport.export(context, item, crop, SIZES[sizeIndex])) {
                                is ExportResult.Saved -> onSaved(r)
                                is ExportResult.Failed -> {
                                    busy = false
                                    snackbar.showSnackbar(
                                        if (r.reason == ExportResult.Reason.TooLarge) tooLargeText else failedText,
                                    )
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.crop_save), color = MaterialTheme.colorScheme.primary) }
            },
        )

        Box(Modifier.fillMaxWidth().weight(1f)) {
            Image(
                painter = painter,
                contentDescription = stringResource(R.string.image_description, item.displayName),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
            if (imageSize != null) {
                CropOverlay(
                    imageSize = imageSize,
                    crop = crop,
                    ratio = ratio,
                    enabled = !busy,
                    onChange = { crop = it },
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            }
            if (busy) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ASPECTS.forEachIndexed { i, (label, value) ->
                    val text = when {
                        value == null -> stringResource(R.string.crop_free)
                        swapped -> label.substringAfter(':') + ":" + label.substringBefore(':')
                        else -> label
                    }
                    Chip(text = text, selected = aspectIndex == i, enabled = imageSize != null && !busy) {
                        chooseAspect(i, swapped)
                    }
                }
                IconButton(
                    onClick = { chooseAspect(aspectIndex, !swapped) },
                    enabled = aspect != null && aspect != 1f && !busy,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.crop_swap), tint = Color.White)
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SIZES.forEachIndexed { i, px ->
                    val text = if (px == 0) stringResource(R.string.size_original) else stringResource(R.string.size_px, px)
                    Chip(text = text, selected = sizeIndex == i, enabled = !busy) { sizeIndex = i }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    outputLabel(item, crop, SIZES[sizeIndex], imageSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { chooseAspect(0, false); crop = CropRect.FULL }, enabled = !busy) {
                    Text(stringResource(R.string.crop_reset))
                }
            }
            val note = when {
                item.needsRust -> stringResource(R.string.crop_raw_note, ImageExport.RUST_MAX_EDGE)
                ImageExport.isLosslessCandidate(item, SIZES[sizeIndex]) -> stringResource(R.string.crop_lossless_note)
                else -> null
            }
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Color.White,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = Color.Black,
        ),
    )
}

/** "3024 × 2016 px" for the current crop and size choice, from MediaStore's dimensions. */
private fun outputLabel(item: MediaItem, crop: CropRect, targetEdge: Int, preview: Size?): String {
    var w = item.width
    var h = item.height
    if (w <= 0 || h <= 0) {
        if (preview == null) return ""
        // Unknown in MediaStore: the preview decode is the best we know.
        w = preview.width.toInt()
        h = preview.height.toInt()
    } else if (item.orientation == 90 || item.orientation == 270) {
        w = item.height
        h = item.width
    }
    if (item.needsRust && max(w, h) > ImageExport.RUST_MAX_EDGE) {
        val s = ImageExport.RUST_MAX_EDGE.toFloat() / max(w, h)
        val (sw, sh) = ImageGeometry.scaled(w, h, s)
        w = sw
        h = sh
    }
    val box = crop.toPixels(w, h)
    val scale = ImageGeometry.exportScale(box.width, box.height, targetEdge)
    val (ow, oh) = ImageGeometry.scaled(box.width, box.height, scale)
    return "$ow × $oh px"
}

private sealed interface DragMode {
    data class AtCorner(val corner: Corner) : DragMode
    data class AtEdge(val edge: Edge) : DragMode
    data object Move : DragMode
}

/**
 * Dim outside, thin frame, thirds, L-shaped corner handles. Corners
 * resize (along the diagonal when the aspect is locked), edges resize when
 * the aspect is free, the inside moves. Everything works in normalized
 * coordinates of the displayed picture rectangle.
 */
@Composable
private fun CropOverlay(
    imageSize: Size,
    crop: CropRect,
    ratio: Float?,
    enabled: Boolean,
    onChange: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cornerHit = with(density) { 28.dp.toPx() }
    val edgeHit = with(density) { 18.dp.toPx() }
    val handleLen = with(density) { 22.dp.toPx() }
    val handleStroke = with(density) { 3.dp.toPx() }
    val frameStroke = with(density) { 1.5.dp.toPx() }
    val thirdStroke = with(density) { 0.75.dp.toPx() }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val currentCrop by rememberUpdatedState(crop)
    val currentRatio by rememberUpdatedState(ratio)
    var mode by remember { mutableStateOf<DragMode?>(null) }

    Canvas(
        modifier
            .onSizeChanged { boxSize = it }
            .pointerInput(enabled, imageSize) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { pos ->
                        val disp = displayedRect(boxSize, imageSize)
                        mode = hitTest(pos, cropPx(currentCrop, disp), cornerHit, edgeHit, currentRatio == null)
                    },
                    onDragEnd = { mode = null },
                    onDragCancel = { mode = null },
                ) { change, drag ->
                    val m = mode ?: return@detectDragGestures
                    val disp = displayedRect(boxSize, imageSize)
                    if (disp.width <= 0f || disp.height <= 0f) return@detectDragGestures
                    change.consume()
                    val nx = (change.position.x - disp.left) / disp.width
                    val ny = (change.position.y - disp.top) / disp.height
                    val next = when (m) {
                        is DragMode.AtCorner -> ImageGeometry.dragCorner(currentCrop, m.corner, nx, ny, currentRatio)
                        is DragMode.AtEdge -> ImageGeometry.dragEdge(
                            currentCrop, m.edge,
                            if (m.edge == Edge.LEFT || m.edge == Edge.RIGHT) nx else ny,
                        )
                        DragMode.Move -> ImageGeometry.move(currentCrop, drag.x / disp.width, drag.y / disp.height)
                    }
                    onChange(next)
                }
            },
    ) {
        val disp = displayedRect(IntSize(size.width.toInt(), size.height.toInt()), imageSize)
        val r = cropPx(crop, disp)
        val dim = Color.Black.copy(alpha = 0.6f)
        // Four rectangles around the crop, inside the picture only.
        drawRect(dim, Offset(disp.left, disp.top), Size(disp.width, r.top - disp.top))
        drawRect(dim, Offset(disp.left, r.bottom), Size(disp.width, disp.bottom - r.bottom))
        drawRect(dim, Offset(disp.left, r.top), Size(r.left - disp.left, r.height))
        drawRect(dim, Offset(r.right, r.top), Size(disp.right - r.right, r.height))

        val line = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            val x = r.left + r.width * i / 3f
            val y = r.top + r.height * i / 3f
            drawLine(line, Offset(x, r.top), Offset(x, r.bottom), thirdStroke)
            drawLine(line, Offset(r.left, y), Offset(r.right, y), thirdStroke)
        }
        drawRect(Color.White.copy(alpha = 0.9f), r.topLeft, r.size, style = Stroke(frameStroke))

        val len = min(handleLen, min(r.width, r.height) / 3f)
        val white = Color.White
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(white, Offset(x, y), Offset(x + dx * len, y), handleStroke, StrokeCap.Square)
            drawLine(white, Offset(x, y), Offset(x, y + dy * len), handleStroke, StrokeCap.Square)
        }
        corner(r.left, r.top, 1f, 1f)
        corner(r.right, r.top, -1f, 1f)
        corner(r.left, r.bottom, 1f, -1f)
        corner(r.right, r.bottom, -1f, -1f)
    }
}

/** Where a Fit-scaled picture of [image] lands inside a box of [box] pixels. */
private fun displayedRect(box: IntSize, image: Size): Rect {
    if (box.width <= 0 || box.height <= 0) return Rect.Zero
    val scale = min(box.width / image.width, box.height / image.height)
    val w = image.width * scale
    val h = image.height * scale
    val l = (box.width - w) / 2f
    val t = (box.height - h) / 2f
    return Rect(l, t, l + w, t + h)
}

private fun cropPx(crop: CropRect, disp: Rect): Rect = Rect(
    disp.left + crop.left * disp.width,
    disp.top + crop.top * disp.height,
    disp.left + crop.right * disp.width,
    disp.top + crop.bottom * disp.height,
)

private fun hitTest(pos: Offset, r: Rect, cornerHit: Float, edgeHit: Float, edgesEnabled: Boolean): DragMode? {
    fun near(x: Float, y: Float) = abs(pos.x - x) <= cornerHit && abs(pos.y - y) <= cornerHit
    when {
        near(r.left, r.top) -> return DragMode.AtCorner(Corner.TOP_LEFT)
        near(r.right, r.top) -> return DragMode.AtCorner(Corner.TOP_RIGHT)
        near(r.left, r.bottom) -> return DragMode.AtCorner(Corner.BOTTOM_LEFT)
        near(r.right, r.bottom) -> return DragMode.AtCorner(Corner.BOTTOM_RIGHT)
    }
    val insideX = pos.x in (r.left - edgeHit)..(r.right + edgeHit)
    val insideY = pos.y in (r.top - edgeHit)..(r.bottom + edgeHit)
    if (edgesEnabled && insideX && insideY) {
        when {
            abs(pos.x - r.left) <= edgeHit -> return DragMode.AtEdge(Edge.LEFT)
            abs(pos.x - r.right) <= edgeHit -> return DragMode.AtEdge(Edge.RIGHT)
            abs(pos.y - r.top) <= edgeHit -> return DragMode.AtEdge(Edge.TOP)
            abs(pos.y - r.bottom) <= edgeHit -> return DragMode.AtEdge(Edge.BOTTOM)
        }
    }
    return if (r.contains(pos)) DragMode.Move else null
}
