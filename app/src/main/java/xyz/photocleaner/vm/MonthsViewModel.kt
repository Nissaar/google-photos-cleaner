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

/** One row of the month grid: how many photos it holds and how many you have judged. */
data class MonthEntry(
    val month: YearMonth,
    val total: Int,
    val reviewed: Int,
) {
    val remaining: Int get() = (total - reviewed).coerceAtLeast(0)
    val fullyReviewed: Boolean get() = total > 0 && reviewed >= total
    val started: Boolean get() = reviewed > 0
}

/**
 * Which months the grid shows.
 *
 * Defaults to [TO_REVIEW]: with a couple of hundred months, a badge on finished tiles
 * still leaves you scanning the whole grid to find what is left to do.
 */
enum class MonthFilter(val label: String) {
    TO_REVIEW("To review"),
    DONE("Done"),
    ALL("All"),
}

data class MonthsState(
    val counts: Map<YearMonth, Int> = emptyMap(),
    val decided: Map<YearMonth, Int> = emptyMap(),
    val filter: MonthFilter = MonthFilter.TO_REVIEW,
    val scanning: Boolean = false,
    /** True while the very first scan runs, when there is nothing cached to show. */
    val initialScan: Boolean = false,
    val newFound: Int = 0,
    val error: String? = null,
) {
    /** Months newest first, which is how people tend to clean up. */
    val months: List<MonthEntry>
        get() = counts.entries
            .sortedByDescending { it.key }
            .map { MonthEntry(it.key, it.value, decided[it.key] ?: 0) }

    val visibleMonths: List<MonthEntry>
        get() = when (filter) {
            MonthFilter.TO_REVIEW -> months.filterNot { it.fullyReviewed }
            MonthFilter.DONE -> months.filter { it.fullyReviewed }
            MonthFilter.ALL -> months
        }

    val toReviewCount: Int get() = months.count { !it.fullyReviewed }
    val doneCount: Int get() = months.count { it.fullyReviewed }

    /** Photos still awaiting a verdict, across every month. */
    val photosLeft: Int get() = months.sumOf { it.remaining }

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
        viewModelScope.launch {
            repo.decidedByMonth().collect { decided ->
                _state.value = _state.value.copy(decided = decided)
            }
        }
    }

    fun setFilter(filter: MonthFilter) {
        _state.value = _state.value.copy(filter = filter)
    }

    /**
     * Forgets the verdicts for one month so it can be gone through again.
     * Local only — nothing in Google Photos changes.
     */
    fun resetMonth(month: YearMonth) {
        viewModelScope.launch { repo.resetMonth(month) }
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
