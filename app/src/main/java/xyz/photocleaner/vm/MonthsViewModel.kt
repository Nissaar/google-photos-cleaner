package xyz.photocleaner.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.photocleaner.Graph
import java.time.YearMonth

data class MonthsState(
    val counts: Map<YearMonth, Int> = emptyMap(),
    val scanning: Boolean = false,
    /** True while the very first scan runs, when there is nothing cached to show. */
    val initialScan: Boolean = false,
    val newFound: Int = 0,
    val error: String? = null,
) {
    /** Months newest first, which is how people tend to clean up. */
    val months: List<Pair<YearMonth, Int>>
        get() = counts.entries.sortedByDescending { it.key }.map { it.key to it.value }

    val totalPhotos: Int get() = counts.values.sum()
}

/**
 * Backs the month picker.
 *
 * The month tallies are persisted, so launching the app renders them immediately from
 * the local database with no network call. A background sync then reads only the
 * photos added since the last scan, rather than walking the whole library again.
 */
class MonthsViewModel : ViewModel() {

    private val repo = Graph.repository

    private val _state = MutableStateFlow(MonthsState())
    val state: StateFlow<MonthsState> = _state.asStateFlow()

    private var job: Job? = null
    private var syncedThisSession = false

    init {
        viewModelScope.launch {
            repo.observeMonthCounts().collect { counts ->
                _state.value = _state.value.copy(counts = counts)
            }
        }
    }

    /**
     * Updates the index. Incremental by default; [full] re-counts everything, which is
     * how the user corrects drift after deleting photos elsewhere.
     */
    fun sync(full: Boolean = false) {
        if (_state.value.scanning) return
        if (syncedThisSession && !full) return
        syncedThisSession = true

        job?.cancel()
        job = viewModelScope.launch {
            // A resumed first pass is still a first pass as far as the user is concerned.
            val first = full || repo.needsInitialScan() || repo.scanIncomplete()
            _state.value = _state.value.copy(scanning = true, initialScan = first, error = null)
            try {
                repo.refreshMonthCounts(full = full) { found ->
                    _state.value = _state.value.copy(newFound = found)
                }
                _state.value = _state.value.copy(scanning = false, initialScan = false, newFound = 0)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    scanning = false,
                    initialScan = false,
                    error = e.message ?: "Could not read your library",
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(scanning = false, initialScan = false)
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
