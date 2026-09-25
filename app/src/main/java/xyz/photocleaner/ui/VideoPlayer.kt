package xyz.photocleaner.ui

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import xyz.photocleaner.Graph
import xyz.photocleaner.api.MediaItem

/**
 * Inline video playback for the review deck.
 *
 * Judging a video from a single still frame is guesswork, so videos play in place.
 *
 * Two things make this work where a plain player would not:
 *  - Google's video URLs are private to the account, so playback goes through the
 *    same HTTP client as image loading, which carries the session cookie.
 *  - The right URL form varies by video, so [MediaItem.videoUrls] is tried in order
 *    and a playback failure advances to the next rather than giving up.
 */
// media3's UnstableApi is a Java opt-in marker, so this needs androidx's OptIn with
// markerClass — Kotlin's `@OptIn(UnstableApi::class)` compiles but does not satisfy
// the lint check, which is what the API is actually gated behind.
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun VideoPlayer(
    item: MediaItem,
    authUser: Int,
    modifier: Modifier = Modifier,
    /** Bumping this rebuilds the player from the first URL form, for a manual retry. */
    retryKey: Int = 0,
    onFailed: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val urls = remember(item.dedupKey, authUser) { item.videoUrls(authUser) }

    // Which URL form we are currently attempting.
    var attempt by remember(item.dedupKey, retryKey) { mutableStateOf(0) }

    val player = remember(item.dedupKey, attempt, retryKey) {
        val url = urls[attempt.coerceIn(urls.indices)]

        // The shared client attaches the session cookie per hop, to Google hosts only,
        // so a redirect can never carry it anywhere else.
        val http = OkHttpDataSource.Factory(Graph.httpClient)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
            .apply {
                setMediaItem(Media3MediaItem.fromUri(url))
                // Short clips are the norm here; looping avoids a dead frame at the end.
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (attempt < urls.lastIndex) {
                    // Try the next URL form; remember() rebuilds the player.
                    attempt += 1
                } else {
                    onFailed(error.errorCodeName)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // Must release, or each swiped card leaks a decoder and audio session.
            player.release()
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    // SurfaceView (PlayerView's default) renders correctly under
                    // FLAG_SECURE; a TextureView would come out black.
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 1_500
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view -> view.player = player },
            onRelease = { view -> view.player = null },
        )
    }
}
