package xyz.photocleaner.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.photocleaner.Graph
import xyz.photocleaner.data.CleanupMode
import xyz.photocleaner.session.GPhotosSession

class AppViewModel : ViewModel() {

    private val session = Graph.session
    private val settings = Graph.settings
    private val repo = Graph.repository

    val sessionState: StateFlow<GPhotosSession.State> = session.state

    /**
     * Which screen to open on: null while still resolving, then true for the library
     * or false for sign-in.
     *
     * Resolved from a local flag rather than by probing Google, so a returning user
     * sees their library immediately instead of waiting on a full page load.
     */
    private val _startSignedIn = MutableStateFlow<Boolean?>(null)
    val startSignedIn: StateFlow<Boolean?> = _startSignedIn.asStateFlow()

    val mode: StateFlow<CleanupMode> =
        settings.mode.stateIn(viewModelScope, SharingStarted.Eagerly, CleanupMode.TRASH)

    val albumName: StateFlow<String> =
        settings.albumName.stateIn(viewModelScope, SharingStarted.Eagerly, "To Be Deleted")

    val appLock: StateFlow<Boolean> =
        settings.appLock.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val skipDecided: StateFlow<Boolean> =
        settings.skipDecided.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val includeArchived: StateFlow<Boolean> =
        settings.includeArchived.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val pendingCount: StateFlow<Int> =
        repo.pendingDeleteCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val keptCount: StateFlow<Int> =
        repo.keptCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        viewModelScope.launch {
            // Fast local read — no network — so the first frame is not gated on Google.
            _startSignedIn.value = settings.wasSignedIn.first()
            // Warm the worker session in the background; screens that need it will wait.
            warmSession()
        }
    }

    private suspend fun warmSession() {
        val ready = session.ensureReady()
        settings.setWasSignedIn(ready)
    }

    fun refreshSession() {
        viewModelScope.launch { warmSession() }
    }

    /** Called when the login WebView reports a signed-in Photos page. */
    fun onLoginCompleted() {
        viewModelScope.launch { settings.setWasSignedIn(true) }
    }

    fun setMode(mode: CleanupMode) = viewModelScope.launch { settings.setMode(mode) }
    fun setAlbumName(name: String) = viewModelScope.launch { settings.setAlbumName(name) }
    fun setAppLock(enabled: Boolean) = viewModelScope.launch { settings.setAppLock(enabled) }
    fun setSkipDecided(enabled: Boolean) = viewModelScope.launch { settings.setSkipDecided(enabled) }
    fun setIncludeArchived(enabled: Boolean) =
        viewModelScope.launch { settings.setIncludeArchived(enabled) }

    fun clearDecisions() = viewModelScope.launch { repo.clearAllDecisions() }

    fun signOutAndWipe(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            Graph.wipeEverything()
            onDone()
        }
    }
}
