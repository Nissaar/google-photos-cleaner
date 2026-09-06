package xyz.photocleaner.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.photocleaner.Graph
import xyz.photocleaner.data.ApplyResult
import xyz.photocleaner.data.Decision

data class ApplyProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val result: ApplyResult? = null,
) {
    val fraction: Float get() = if (total == 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

class ReviewViewModel : ViewModel() {

    private val repo = Graph.repository

    val pending: StateFlow<List<Decision>> =
        repo.pendingDeletes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Already trashed, and therefore restorable while inside Google's 60-day window. */
    val applied: StateFlow<List<Decision>> =
        repo.appliedDeletes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _progress = MutableStateFlow(ApplyProgress())
    val progress: StateFlow<ApplyProgress> = _progress.asStateFlow()

    /** Sends every pending DELETE verdict to Google. Requires explicit confirmation upstream. */
    fun apply() {
        if (_progress.value.running) return
        viewModelScope.launch {
            _progress.value = ApplyProgress(running = true, total = pending.value.size)
            val result = repo.applyPending { done, total ->
                _progress.value = _progress.value.copy(done = done, total = total)
            }
            _progress.value = _progress.value.copy(running = false, result = result)
        }
    }

    /** Pulls items back out of the Google Photos trash. */
    fun restore(decisions: List<Decision>) {
        if (_progress.value.running || decisions.isEmpty()) return
        viewModelScope.launch {
            _progress.value = ApplyProgress(running = true, total = decisions.size)
            runCatching {
                repo.restore(decisions) { done, total ->
                    _progress.value = _progress.value.copy(done = done, total = total)
                }
            }
            _progress.value = ApplyProgress(running = false)
        }
    }

    /** Take a single photo off the delete list without touching Google. */
    fun removeFromPending(decision: Decision) {
        viewModelScope.launch { repo.undo(decision.dedupKey) }
    }

    fun dismissResult() {
        _progress.value = _progress.value.copy(result = null)
    }
}
