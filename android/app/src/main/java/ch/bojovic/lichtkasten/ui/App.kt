package ch.bojovic.lichtkasten.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ch.bojovic.lichtkasten.LibraryState
import ch.bojovic.lichtkasten.LibraryViewModel
import ch.bojovic.lichtkasten.MediaAccess
import ch.bojovic.lichtkasten.R
import ch.bojovic.lichtkasten.media.MediaItem
import kotlinx.coroutines.launch

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

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val n = state.selection.count.takeIf { it > 0 } ?: 1
            vm.exitSelection()
            vm.reload()
            scope.launch { snackbar.showSnackbar(context.getString(R.string.delete_done, n)) }
        }
    }

    var shareCandidates by remember { mutableStateOf<List<MediaItem>?>(null) }

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
                } else if (!MediaActions.requestDelete(context.contentResolver, items, deleteLauncher)) {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.delete_failed)) }
                }
            },
            requestPermissions = { permissionLauncher.launch(mediaPermissions) },
        )
    }

    when {
        state.access == MediaAccess.Denied || state.access == MediaAccess.Unknown -> {
            PermissionScreen(denied = state.access == MediaAccess.Denied, onRequest = actions.requestPermissions)
        }
        state.viewerIndex != null -> {
            BackHandler { vm.closeViewer() }
            ViewerScreen(state = state, vm = vm, actions = actions, snackbar = snackbar)
        }
        else -> {
            BackHandler(enabled = state.selection.active) { vm.exitSelection() }
            GridScreen(state = state, vm = vm, actions = actions, snackbar = snackbar)
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

/** What a screen can trigger without knowing how it is done. */
class ScreenActions(
    val share: (List<MediaItem>) -> Unit,
    val delete: (List<MediaItem>) -> Unit,
    val requestPermissions: () -> Unit,
)
