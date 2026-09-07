package xyz.photocleaner.ui

import android.view.ViewGroup
import android.webkit.CookieManager
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import xyz.photocleaner.api.MediaItem
import xyz.photocleaner.session.GPhotosSession

/**
 * Inline video playback for the review deck.
 *
 * Judging a video from a single still frame is guesswork, so videos play in place.
 *
 * Two things make this work where a plain player would not:
 *  - Google's video URLs are private to the account, so the request carries the
 *    session cookie from the WebView jar, exactly as image loading does.
 *  - The right URL form varies by video, so [MediaItem.videoUrls] is tried in order
 *    and a playback failure advances to the next rather than giving up.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    item: MediaItem,
    authUser: Int,
    modifier: Modifier = Modifier,
    onFailed: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val urls = remember(item.dedupKey, authUser) { item.videoUrls(authUser) }

    // Which URL form we are currently attempting.
    var attempt by remember(item.dedupKey) { mutableStateOf(0) }

    val player = remember(item.dedupKey, attempt) {
        val url = urls[attempt.coerceIn(urls.indices)]

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(GPhotosSession.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                buildMap {
                    val cookie = runCatching {
                        CookieManager.getInstance().getCookie(url)
                    }.getOrNull()
                    if (!cookie.isNullOrEmpty()) put("Cookie", cookie)
                    put("Referer", "${GPhotosSession.ORIGIN}/")
                },
            )

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
