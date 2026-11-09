package ch.bojovic.lichtkasten.ui

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import ch.bojovic.lichtkasten.LibraryState
import ch.bojovic.lichtkasten.LibraryViewModel
import ch.bojovic.lichtkasten.R
import ch.bojovic.lichtkasten.media.MediaItem
import ch.bojovic.lichtkasten.media.RustRequest
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import kotlin.math.max

@Composable
fun ViewerScreen(
    state: LibraryState,
    vm: LibraryViewModel,
    actions: ScreenActions,
    snackbar: SnackbarHostState,
) {
    val items = state.items
    val start = (state.viewerIndex ?: 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { vm.closeViewer() }
        return
    }
    val pagerState = rememberPagerState(initialPage = start) { items.size }
    var barsVisible by remember { mutableStateOf(true) }
    var infoFor by remember { mutableStateOf<MediaItem?>(null) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { vm.onViewerPage(it) }
    }
    // The list changed under us (delete, rescan): follow the ViewModel.
    LaunchedEffect(state.viewerIndex, items.size) {
        val target = state.viewerIndex ?: return@LaunchedEffect
        if (target != pagerState.currentPage && target in items.indices && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(target)
        }
    }

    val current = items.getOrNull(pagerState.currentPage) ?: items[start]

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            key = { items.getOrNull(it)?.id ?: it.toLong() },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            if (item.isVideo) {
                VideoPage(item = item, active = pagerState.settledPage == page, onTap = { barsVisible = !barsVisible })
            } else {
                PhotoPage(item = item, onTap = { barsVisible = !barsVisible })
            }
        }

        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                navigationIcon = {
                    IconButton(onClick = { vm.closeViewer() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(current.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (!current.isVideo) {
                        IconButton(onClick = { actions.crop(current) }) {
                            Icon(painterResource(R.drawable.ic_crop), contentDescription = stringResource(R.string.crop))
                        }
                    }
                    IconButton(onClick = { actions.share(listOf(current)) }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
                    }
                    IconButton(onClick = { infoFor = current }) {
                        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.info))
                    }
                    IconButton(onClick = { actions.delete(listOf(current)) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                    }
                },
            )
        }

        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }

    infoFor?.let { item ->
        InfoSheet(item = item, onDismiss = { infoFor = null })
    }
}

/**
 * The Coil model the viewer shows: twice the window's long edge gives
 * headroom for the pinch zoom without decoding a 50 MP file at full size.
 * The crop screen uses the same model, so it opens on the cached picture.
 */
@Composable
internal fun rememberViewerModel(item: MediaItem): Any {
    val context = LocalContext.current
    val container = LocalWindowInfo.current.containerSize
    val edgePx = remember(container) {
        (max(container.width, container.height) * 2).coerceIn(1024, 4096)
    }
    return remember(item.cacheKey, edgePx) {
        if (item.needsRust) {
            RustRequest(item, edgePx)
        } else {
            ImageRequest.Builder(context)
                .data(item.uri)
                .size(edgePx)
                .precision(Precision.INEXACT)
                .memoryCacheKey("view:${item.cacheKey}:$edgePx")
                .build()
        }
    }
}

@Composable
private fun PhotoPage(item: MediaItem, onTap: () -> Unit) {
    val model = rememberViewerModel(item)
    ZoomableBox(onTap = onTap) {
        AsyncImage(
            model = model,
            contentDescription = stringResource(R.string.image_description, item.displayName),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * One ExoPlayer, only for the page the user is actually on; it is released
 * as soon as the page is swiped away or the composable leaves.
 */
@Composable
private fun VideoPage(item: MediaItem, active: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    if (!active) {
        // A still while it is a neighbour page.
        PhotoPage(item = item, onTap = onTap)
        return
    }
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(Media3Item.fromUri(item.uri))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = true
                controllerAutoShow = true
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
    )
}
