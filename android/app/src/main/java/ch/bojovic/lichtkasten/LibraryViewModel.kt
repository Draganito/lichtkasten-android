package ch.bojovic.lichtkasten

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.bojovic.lichtkasten.media.MediaItem
import ch.bojovic.lichtkasten.media.MediaStoreRepository
import ch.bojovic.lichtkasten.ui.SelectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MediaAccess { Unknown, Denied, Partial, Full }

data class LibraryState(
    val access: MediaAccess = MediaAccess.Unknown,
    /** The user answered a permission request with "don't allow" at least once this session. */
    val permissionRefused: Boolean = false,
    val loading: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val selection: SelectionState = SelectionState(),
    /** Grid cell size in dp; pinch changes it, SharedPreferences keeps it. */
    val cellDp: Float = DEFAULT_CELL_DP,
    /** Index into [items] while the viewer is open, otherwise null. */
    val viewerIndex: Int? = null,
) {
    val selectedItems: List<MediaItem> get() = items.filter { it.id in selection.ids }

    companion object {
        const val MIN_CELL_DP = 64f
        const val MAX_CELL_DP = 240f
        const val DEFAULT_CELL_DP = 110f
    }
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MediaStoreRepository(app.contentResolver)
    private val prefs = app.getSharedPreferences("lichtkasten", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var observeJob: Job? = null

    init {
        viewModelScope.launch {
            val cell = withContext(Dispatchers.IO) { prefs.getFloat(KEY_CELL, LibraryState.DEFAULT_CELL_DP) }
            _state.update { it.copy(cellDp = cell.coerceIn(LibraryState.MIN_CELL_DP, LibraryState.MAX_CELL_DP)) }
        }
    }

    /**
     * Called by the Activity whenever the permission state is (re)checked.
     * [afterRequest] marks a check that follows the system dialog: a
     * Denied result there is a refusal and unlocks the settings hint.
     */
    fun onAccess(access: MediaAccess, afterRequest: Boolean = false) {
        val before = _state.value.access
        _state.update {
            it.copy(
                access = access,
                permissionRefused = it.permissionRefused || (afterRequest && access == MediaAccess.Denied),
            )
        }
        if (access == MediaAccess.Denied || access == MediaAccess.Unknown) {
            observeJob?.cancel()
            observeJob = null
            _state.update { it.copy(items = emptyList(), selection = SelectionState(), viewerIndex = null) }
            return
        }
        if (observeJob == null || before != access) startObserving()
    }

    private fun startObserving() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repo.changes().collect { reload() }
        }
    }

    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty()) }
            val items = repo.loadAll()
            _state.update { s ->
                val ids = items.mapTo(HashSet()) { it.id }
                val viewer = s.viewerIndex?.let { idx ->
                    // Keep the viewer on the same picture when the list shifts.
                    val current = s.items.getOrNull(idx)
                    current?.let { c -> items.indexOfFirst { it.id == c.id }.takeIf { it >= 0 } }
                        ?: idx.coerceIn(0, (items.size - 1).coerceAtLeast(0)).takeIf { items.isNotEmpty() }
                }
                s.copy(
                    loading = false,
                    items = items,
                    selection = s.selection.retain(ids),
                    viewerIndex = viewer,
                )
            }
        }
    }

    // ------------------------------------------------------------ grid size

    fun scaleCells(factor: Float) {
        if (factor == 1f) return
        _state.update { s ->
            s.copy(cellDp = (s.cellDp * factor).coerceIn(LibraryState.MIN_CELL_DP, LibraryState.MAX_CELL_DP))
        }
    }

    fun persistCellSize() {
        val cell = _state.value.cellDp
        viewModelScope.launch(Dispatchers.IO) { prefs.edit().putFloat(KEY_CELL, cell).apply() }
    }

    // ------------------------------------------------------------ selection

    fun enterSelection() = _state.update { it.copy(selection = it.selection.copy(active = true)) }

    fun exitSelection() = _state.update { it.copy(selection = SelectionState()) }

    fun toggle(item: MediaItem) = _state.update { it.copy(selection = it.selection.toggle(item.id)) }

    fun selectAll() = _state.update { s -> s.copy(selection = s.selection.selectAll(s.items.map { it.id })) }

    fun selectNone() = _state.update { it.copy(selection = it.selection.clear()) }

    // --------------------------------------------------------------- viewer

    fun openViewer(index: Int) = _state.update { it.copy(viewerIndex = index) }

    fun closeViewer() = _state.update { it.copy(viewerIndex = null) }

    fun onViewerPage(index: Int) = _state.update { if (it.viewerIndex == null) it else it.copy(viewerIndex = index) }

    private companion object {
        const val KEY_CELL = "cell_dp"
    }
}
