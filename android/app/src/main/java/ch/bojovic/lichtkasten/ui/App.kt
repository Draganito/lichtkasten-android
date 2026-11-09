package ch.bojovic.lichtkasten.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ch.bojovic.lichtkasten.LibraryState
import ch.bojovic.lichtkasten.LibraryViewModel
import ch.bojovic.lichtkasten.MediaAccess
import ch.bojovic.lichtkasten.R
import ch.bojovic.lichtkasten.media.MediaItem
import ch.bojovic.lichtkasten.media.Sections
import ch.bojovic.lichtkasten.media.ThumbCache
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Three screens, no navigation library: permission, grid, viewer. The
 * share dialog and the delete launcher live here because both screens use
 * them.
 */
@Composable
fun App(
    state: LibraryState,
    vm: LibraryViewModel,
    mediaPermissions: Array<String>,
    onPermissionsResult: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onPermissionsResult() }

    // What the system delete dialog is currently asking about; needed when
    // the result comes back, to drop their cached renditions as well.
    var pendingDelete by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val deleted = pendingDelete
        pendingDelete = emptyList()
        if (result.resultCode == Activity.RESULT_OK) {
            vm.exitSelection()
            vm.reload()
            scope.launch {
                withContext(Dispatchers.IO) { ThumbCache.evict(context, deleted) }
                SingletonImageLoader.get(context).memoryCache?.clear()
                snackbar.showSnackbar(context.getString(R.string.delete_done, deleted.size.coerceAtLeast(1)))
            }
        }
    }

    var shareCandidates by remember { mutableStateOf<List<MediaItem>?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    var cropFor by remember { mutableStateOf<MediaItem?>(null) }

    val actions = remember(state.items, state.selection) {
        ScreenActions(
            share = { items ->
                if (items.isEmpty()) {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.select_first)) }
                } else {
                    shareCandidates = items
                }
            },
            delete = { items ->
                if (items.isEmpty()) {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.select_first)) }
                } else {
                    pendingDelete = items
                    if (!MediaActions.requestDelete(context.contentResolver, items, deleteLauncher)) {
                        pendingDelete = emptyList()
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.delete_failed)) }
                    }
                }
            },
            requestPermissions = { permissionLauncher.launch(mediaPermissions) },
            licenses = { showLicenses = true },
            crop = { item -> if (!item.isVideo) cropFor = item },
        )
    }

    // The grid's scroll state lives here, not in GridScreen: the grid leaves
    // the composition while the viewer or the licenses are shown, and must
    // come back where it was. Saveable, so it also survives process death.
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    ReturnToViewedItem(state.viewerIndex, state.sections, gridState)

    when {
        showLicenses -> {
            BackHandler { showLicenses = false }
            LicensesScreen(onBack = { showLicenses = false })
        }
        cropFor != null -> {
            val item = cropFor!!
            BackHandler { cropFor = null }
            CropScreen(
                item = item,
                snackbar = snackbar,
                onClose = { cropFor = null },
                onSaved = { saved ->
                    cropFor = null
                    // MediaStore's observer reloads the grid; the new file sorts next to its source.
                    scope.launch {
                        val text = if (saved.lossless) R.string.export_done_lossless else R.string.export_done
                        snackbar.showSnackbar(context.getString(text, saved.width, saved.height))
                    }
                },
            )
        }
        state.access == MediaAccess.Denied || state.access == MediaAccess.Unknown -> {
            PermissionScreen(denied = state.permissionRefused, onRequest = actions.requestPermissions)
        }
        state.viewerIndex != null -> {
            BackHandler { vm.closeViewer() }
            ViewerScreen(state = state, vm = vm, actions = actions, snackbar = snackbar)
        }
        else -> {
            BackHandler(enabled = state.selection.active) { vm.exitSelection() }
            GridScreen(state = state, vm = vm, actions = actions, snackbar = snackbar, gridState = gridState)
        }
    }

    shareCandidates?.let { items ->
        AlertDialog(
            onDismissRequest = { shareCandidates = null },
            title = {
                Text(
                    if (items.size == 1) stringResource(R.string.share_question_one)
                    else stringResource(R.string.share_question, items.size),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    shareCandidates = null
                    MediaActions.share(context, items)
                }) { Text(stringResource(R.string.share_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { shareCandidates = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/**
 * Keeps the grid in step with the viewer. When the viewer opens, remember
 * which cells were on screen; when it closes, leave the grid alone if the
 * last viewed picture is among them, otherwise scroll it into view (the
 * user swiped on in the viewer and expects to land there).
 */
@Composable
private fun ReturnToViewedItem(viewerIndex: Int?, sections: Sections, gridState: LazyGridState) {
    // All in grid row indices (headers count), not item indices.
    var firstVisibleOnOpen by rememberSaveable { mutableStateOf(-1) }
    var lastVisibleOnOpen by rememberSaveable { mutableStateOf(-1) }
    var lastViewed by rememberSaveable { mutableStateOf(-1) }
    LaunchedEffect(viewerIndex) {
        if (viewerIndex != null) {
            if (lastViewed < 0) {
                val info = gridState.layoutInfo.visibleItemsInfo
                firstVisibleOnOpen = info.firstOrNull()?.index ?: -1
                lastVisibleOnOpen = info.lastOrNull()?.index ?: -1
            }
            lastViewed = viewerIndex
        } else if (lastViewed >= 0) {
            val targetRow = sections.rowForItem(lastViewed)
            lastViewed = -1
            if (targetRow !in firstVisibleOnOpen..lastVisibleOnOpen) gridState.scrollToItem(targetRow)
        }
    }
}

/** What a screen can trigger without knowing how it is done. */
class ScreenActions(
    val share: (List<MediaItem>) -> Unit,
    val delete: (List<MediaItem>) -> Unit,
    val requestPermissions: () -> Unit,
    val licenses: () -> Unit,
    val crop: (MediaItem) -> Unit,
)
