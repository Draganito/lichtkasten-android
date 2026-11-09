package ch.bojovic.lichtkasten.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.bojovic.lichtkasten.LibraryState
import ch.bojovic.lichtkasten.LibraryViewModel
import ch.bojovic.lichtkasten.MediaAccess
import ch.bojovic.lichtkasten.R
import ch.bojovic.lichtkasten.media.GridRow
import ch.bojovic.lichtkasten.media.MediaItem
import ch.bojovic.lichtkasten.media.RustRequest
import ch.bojovic.lichtkasten.media.Sections
import ch.bojovic.lichtkasten.media.ThumbRequest
import coil3.compose.AsyncImage
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Thumbnail size tiers. Requests are rounded up to one of these so a pinch
 * through intermediate cell sizes does not reload every cell; the top tier
 * is the Rust source size, so nothing is ever upscaled.
 */
internal fun thumbTier(cellPx: Int): Int = when {
    cellPx <= 256 -> 256
    cellPx <= 512 -> 512
    else -> RustRequest.THUMB_SOURCE
}

/** Width of one adaptive cell in px: the screen divided into as many `minPx` columns as fit. */
internal fun adaptiveCellPx(screenWidthPx: Int, minPx: Int): Int {
    if (minPx <= 0 || screenWidthPx <= 0) return screenWidthPx.coerceAtLeast(0)
    val columns = (screenWidthPx / minPx).coerceAtLeast(1)
    return screenWidthPx / columns
}

@Composable
private fun OverflowMenu(onLicenses: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.licenses)) },
                onClick = {
                    open = false
                    onLicenses()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridScreen(
    state: LibraryState,
    vm: LibraryViewModel,
    actions: ScreenActions,
    snackbar: SnackbarHostState,
    gridState: LazyGridState,
) {
    val selection = state.selection

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    if (selection.active) {
                        IconButton(onClick = { vm.exitSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel_selection))
                        }
                    }
                },
                title = {
                    Text(
                        if (selection.active) stringResource(R.string.selected_count, selection.count)
                        else stringResource(R.string.app_name),
                    )
                },
                actions = {
                    if (selection.active) {
                        val all = selection.allSelected(state.items.size)
                        TextButton(onClick = { if (all) vm.selectNone() else vm.selectAll() }) {
                            Text(stringResource(if (all) R.string.select_none else R.string.select_all))
                        }
                        IconButton(onClick = { actions.delete(state.selectedItems) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    } else {
                        TextButton(onClick = { vm.enterSelection() }, enabled = state.items.isNotEmpty()) {
                            Text(stringResource(R.string.select))
                        }
                    }
                    IconButton(onClick = { actions.share(state.selectedItems) }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
                    }
                    if (!selection.active) {
                        OverflowMenu(onLicenses = actions.licenses)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.access == MediaAccess.Partial) {
                PartialAccessBanner(onMore = actions.requestPermissions)
            }
            when {
                state.loading && state.items.isEmpty() -> Centered {
                    CircularProgressIndicator()
                }
                state.items.isEmpty() -> Centered {
                    Text(stringResource(R.string.grid_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    val cellDp = state.cellDp
                    val density = LocalDensity.current
                    val screenWidthPx = LocalWindowInfo.current.containerSize.width
                    val thumbPx = remember(cellDp, screenWidthPx, density) {
                        thumbTier(adaptiveCellPx(screenWidthPx, with(density) { cellDp.dp.roundToPx() }))
                    }
                    val rows = state.sections.rows
                    Box(Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = cellDp.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .pinchToScale(onScale = vm::scaleCells, onEnd = vm::persistCellSize),
                        ) {
                            itemsIndexed(
                                rows,
                                key = { _, row -> rowKey(row) },
                                span = { _, row -> if (row is GridRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                                contentType = { _, row ->
                                    when (row) {
                                        is GridRow.Header -> 2
                                        is GridRow.Cell -> if (row.item.isVideo) 1 else 0
                                    }
                                },
                            ) { rowIndex, row ->
                                when (row) {
                                    is GridRow.Header -> MonthHeader(row, first = rowIndex == 0)
                                    is GridRow.Cell -> {
                                        val item = row.item
                                        GridCell(
                                            item = item,
                                            thumbPx = thumbPx,
                                            selected = item.id in selection.ids,
                                            selecting = selection.active,
                                            onClick = { if (selection.active) vm.toggle(item) else vm.openViewer(row.index) },
                                            onLongClick = { vm.toggle(item) },
                                        )
                                    }
                                }
                            }
                        }
                        MonthOverlay(sections = state.sections, gridState = gridState, modifier = Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }
    }
}

private fun rowKey(row: GridRow): Any = when (row) {
    is GridRow.Header -> "h:${row.year}-${row.month}"
    is GridRow.Cell -> row.item.id
}

/** "November" large and light, "2026" small and quiet. Nothing else. */
@Composable
private fun MonthHeader(header: GridRow.Header, first: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = if (first) 6.dp else 26.dp, bottom = 10.dp),
    ) {
        MonthLabel(header)
    }
}

@Composable
private fun MonthLabel(header: GridRow.Header, tint: Color = MaterialTheme.colorScheme.onBackground) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val month = remember(header, locale) {
        if (header.month == 0) null else Month.of(header.month).getDisplayName(TextStyle.FULL_STANDALONE, locale)
    }
    Text(
        month ?: stringResource(R.string.month_undated),
        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Light),
        color = tint,
        maxLines = 1,
    )
    if (header.month != 0) {
        Text(
            header.year.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The month of the first visible picture, floating under the app bar on a
 * short black gradient. Shown while the grid moves and for a moment after,
 * and only when that month's own header is not already at the top. Reads
 * layout info only; decodes nothing.
 */
@Composable
private fun MonthOverlay(sections: Sections, gridState: LazyGridState, modifier: Modifier = Modifier) {
    val header by remember(sections) {
        derivedStateOf {
            val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull() ?: return@derivedStateOf null
            val row = sections.rows.getOrNull(first.index) ?: return@derivedStateOf null
            if (row is GridRow.Header) null else sections.headerForRow(first.index)
        }
    }
    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) {
            recentlyScrolled = true
        } else {
            delay(1200)
            recentlyScrolled = false
        }
    }
    AnimatedVisibility(
        visible = header != null && recentlyScrolled,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val shown = header ?: return@AnimatedVisibility
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)))
                .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 28.dp),
        ) {
            MonthLabel(shown, tint = Color.White)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun PartialAccessBanner(onMore: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(R.string.perm_partial),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onMore) { Text(stringResource(R.string.perm_partial_more)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    item: MediaItem,
    thumbPx: Int,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val model = remember(item.cacheKey, thumbPx) {
        if (item.needsRust) RustRequest(item, RustRequest.THUMB_SOURCE, thumbPx) else ThumbRequest(item, ThumbRequest.SOURCE, thumbPx)
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.video_badge), tint = Color.White, modifier = Modifier.size(14.dp))
                if (item.durationMillis > 0) {
                    Text(formatDuration(item.durationMillis), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (selecting) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                }
            }
            if (selected) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)))
            }
        }
    }
}

fun formatDuration(millis: Long): String {
    val total = millis / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
