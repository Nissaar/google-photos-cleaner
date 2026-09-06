package xyz.photocleaner.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How a DELETE verdict is carried out. */
enum class CleanupMode {
    /**
     * Move to the Google Photos trash. Genuinely removes the photo from the library
     * and frees storage after 60 days, and is reversible until then.
     */
    TRASH,

    /**
     * Non-destructive: collect items into an album instead, so you can review them
     * in the real Google Photos app before deleting anything yourself.
     */
    ALBUM,
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class Settings(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("cleanup_mode")
        val ALBUM_NAME = stringPreferencesKey("album_name")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val SKIP_DECIDED = booleanPreferencesKey("skip_decided")
        val INCLUDE_ARCHIVED = booleanPreferencesKey("include_archived")
        val WAS_SIGNED_IN = booleanPreferencesKey("was_signed_in")
    }

    companion object {
        const val DEFAULT_ALBUM_NAME = "To Be Deleted"
    }

    val mode: Flow<CleanupMode> = context.dataStore.data.map { prefs ->
        runCatching { CleanupMode.valueOf(prefs[Keys.MODE] ?: CleanupMode.TRASH.name) }
            .getOrDefault(CleanupMode.TRASH)
    }

    val albumName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.ALBUM_NAME]?.takeIf { it.isNotBlank() } ?: DEFAULT_ALBUM_NAME
    }

    /** Require biometric / device credential each time the app is opened. */
    val appLock: Flow<Boolean> = context.dataStore.data.map { it[Keys.APP_LOCK] ?: false }

    /** Hide photos you have already judged when re-opening a month. */
    val skipDecided: Flow<Boolean> = context.dataStore.data.map { it[Keys.SKIP_DECIDED] ?: true }

    val includeArchived: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.INCLUDE_ARCHIVED] ?: false }

    /**
     * Whether a Google session was live last time the app ran.
     *
     * Lets the app open straight onto the library instead of blocking on a full
     * photos.google.com page load just to discover we are already signed in.
     */
    val wasSignedIn: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.WAS_SIGNED_IN] ?: false }

    suspend fun setWasSignedIn(value: Boolean) = put(Keys.WAS_SIGNED_IN, value)

    suspend fun setMode(mode: CleanupMode) = put(Keys.MODE, mode.name)
    suspend fun setAlbumName(name: String) = put(Keys.ALBUM_NAME, name)
    suspend fun setAppLock(enabled: Boolean) = put(Keys.APP_LOCK, enabled)
    suspend fun setSkipDecided(enabled: Boolean) = put(Keys.SKIP_DECIDED, enabled)
    suspend fun setIncludeArchived(enabled: Boolean) = put(Keys.INCLUDE_ARCHIVED, enabled)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
