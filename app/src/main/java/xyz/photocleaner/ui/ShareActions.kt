package xyz.photocleaner.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.core.content.FileProvider
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import xyz.photocleaner.Graph
import xyz.photocleaner.api.MediaItem
import java.io.File
import java.io.FileOutputStream

/**
 * "Open in Google Photos" and "Share" for the item under review.
 *
 * Both exist for the moment mid-cleanup where you would rather keep a photo than
 * judge it — without leaving the app, finding it by hand, and losing your place.
 */
object ShareActions {

    private const val SHARE_DIR = "share"
    private const val PHOTOS_PACKAGE = "com.google.android.apps.photos"

    /** Progress while a file is being prepared for sharing. */
    data class Progress(val bytes: Long, val total: Long) {
        val fraction: Float get() = if (total <= 0) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f)
    }

    /**
     * Opens this item in the Google Photos app.
     *
     * A bare ACTION_VIEW on a photos.google.com URL lands in whatever handles the
     * link, which in practice is the default browser. Naming the package explicitly
     * sends it to the app; the browser is only a fallback for when it is absent.
     */
    fun openInGooglePhotos(context: Context, item: MediaItem, authUser: Int): Boolean {
        val uri = Uri.parse(item.googlePhotosUrl(authUser))

        val inApp = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(PHOTOS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(inApp)
            return true
        } catch (e: ActivityNotFoundException) {
            // Google Photos not installed, or it does not claim this link.
        }

        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Shares the actual file — the picture, or the video.
     *
     * Sharing the Google Photos link instead would be near-useless: those links are
     * private to the account, so a recipient without access sees nothing.
     *
     * Note there is deliberately no EXTRA_TEXT alongside the file. With both present,
     * many share targets pick the text and drop the attachment, which turns "share
     * this photo" into "share a link nobody can open".
     */
    suspend fun share(
        context: Context,
        item: MediaItem,
        authUser: Int,
        onProgress: (Progress) -> Unit = {},
    ): Result<Unit> = try {
        val (uri, mime) = withContext(Dispatchers.IO) {
            if (item.isVideo) {
                downloadVideo(context, item, authUser, onProgress)
            } else {
                cacheImage(context, item, authUser)
            }
        } ?: return Result.failure(IllegalStateException("Could not prepare the file"))

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, if (item.isVideo) "Share video" else "Share photo")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Renders the already-displayed image to a file the share sheet can read. */
    private suspend fun cacheImage(
        context: Context,
        item: MediaItem,
        authUser: Int,
    ): Pair<Uri, String>? {
        val request = ImageRequest.Builder(context)
            .data(item.previewUrl(maxSize = 2048, authUser = authUser))
            // Hardware bitmaps cannot be read back for compression.
            .allowHardware(false)
            .build()

        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)
            ?.drawable
            ?.let { it as? BitmapDrawable }
            ?.bitmap
            ?: return null

        val file = freshShareFile(context, "photo.jpg")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        return uriFor(context, file) to "image/jpeg"
    }

    /**
     * Downloads the video so it can be shared as a file, the way the Google Photos
     * app does. Tries each URL form in turn, since which one serves depends on the
     * video, and reports progress because this can be tens of megabytes.
     */
    private fun downloadVideo(
        context: Context,
        item: MediaItem,
        authUser: Int,
        onProgress: (Progress) -> Unit,
    ): Pair<Uri, String>? {
        for (url in item.videoUrls(authUser)) {
            val request = Request.Builder().url(url).build()
            try {
                Graph.httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use

                    val mime = response.header("Content-Type")
                        ?.substringBefore(';')
                        ?.takeIf { it.startsWith("video/") }
                        ?: "video/mp4"
                    val total = body.contentLength()

                    val file = freshShareFile(context, "video.mp4")
                    var written = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(file).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                written += read
                                onProgress(Progress(written, total))
                            }
                        }
                    }
                    if (written > 0) return uriFor(context, file) to mime
                }
            } catch (e: Exception) {
                // Try the next URL form.
            }
        }
        return null
    }

    /**
     * Clears the share directory and returns a new file in it.
     *
     * Only ever holds the share in flight: these are copies of personal photos, and
     * there is no reason for them to accumulate in the cache.
     */
    private fun freshShareFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, "${System.currentTimeMillis()}-$name")
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Removes anything staged for sharing. Called as part of the full local wipe. */
    fun clearCache(context: Context) {
        runCatching { File(context.cacheDir, SHARE_DIR).deleteRecursively() }
    }
}
