package xyz.photocleaner.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.photocleaner.Graph
import xyz.photocleaner.api.MediaItem
import xyz.photocleaner.data.Verdict
import java.time.YearMonth

data class SwipeState(
    val month: YearMonth? = null,
    val items: List<MediaItem> = emptyList(),
    val index: Int = 0,
    val loading: Boolean = false,
    val loadedCount: Int = 0,
    val error: String? = null,
    val keptThisSession: Int = 0,
    val deletedThisSession: Int = 0,
    /** Verdicts given this session, newest last — backs the undo button. */
    val history: List<Pair<MediaItem, Verdict>> = emptyList(),
) {
    val current: MediaItem? get() = items.getOrNull(index)
    val next: MediaItem? get() = items.getOrNull(index + 1)
    val remaining: Int get() = (items.size - index).coerceAtLeast(0)
    val finished: Boolean get() = !loading && items.isNotEmpty() && index >= items.size
    val isEmpty: Boolean get() = !loading && items.isEmpty()
    val progress: Float
        get() = if (items.isEmpty()) 0f else (index.toFloat() / items.size).coerceIn(0f, 1f)
}

/**
 * Drives the one-photo-at-a-time review deck for a single month.
 *
 * Verdicts are written to the local database immediately but are *not* sent to
 * Google here — nothing leaves the device until the review screen is confirmed.
 * That keeps swiping fast and, more importantly, undoable.
 */
class SwipeViewModel : ViewModel() {

    private val repo = Graph.repository

    private val _state = MutableStateFlow(SwipeState())
    val state: StateFlow<SwipeState> = _state.asStateFlow()

    private var job: Job? = null

    fun load(month: YearMonth) {
        if (_state.value.month == month && _state.value.items.isNotEmpty()) return
        startLoad(month)
    }

    /**
     * Forgets this month's verdicts and deals the deck again.
     *
     * Photos already sent to the trash are left alone — they are out of the library
     * and will not come back — so this brings back what you kept or had marked but
     * not yet committed.
     */
    fun reviewAgain() {
        val month = _state.value.month ?: return
        startLoad(month, resetFirst = true)
    }

    private fun startLoad(month: YearMonth, resetFirst: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = SwipeState(month = month, loading = true)
            try {
                if (resetFirst) repo.resetMonth(month)
                val items = repo.loadMonth(month) { count ->
                    _state.value = _state.value.copy(loadedCount = count)
                }
                _state.value = _state.value.copy(items = items, loading = false, index = 0)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not load this month",
                )
            }
        }
    }

    fun keep() = decide(Verdict.KEEP)

    fun delete() = decide(Verdict.DELETE)

    private fun decide(verdict: Verdict) {
        val s = _state.value
        val item = s.current ?: return

        // Advance the UI immediately; persistence follows. The deck must never feel
        // like it is waiting on the database.
        _state.value = s.copy(
            index = s.index + 1,
            keptThisSession = s.keptThisSession + if (verdict == Verdict.KEEP) 1 else 0,
            deletedThisSession = s.deletedThisSession + if (verdict == Verdict.DELETE) 1 else 0,
            history = s.history + (item to verdict),
        )
        viewModelScope.launch { repo.record(item, verdict) }
    }

    /** Steps back one photo and forgets the verdict that was given. */
    fun undo() {
        val s = _state.value
        val last = s.history.lastOrNull() ?: return
        val (item, verdict) = last
        _state.value = s.copy(
            index = (s.index - 1).coerceAtLeast(0),
            keptThisSession = (s.keptThisSession - if (verdict == Verdict.KEEP) 1 else 0)
                .coerceAtLeast(0),
            deletedThisSession = (s.deletedThisSession - if (verdict == Verdict.DELETE) 1 else 0)
                .coerceAtLeast(0),
            history = s.history.dropLast(1),
        )
        viewModelScope.launch { repo.undo(item.dedupKey) }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
