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
import xyz.photocleaner.api.MediaItem
import java.io.File
import java.io.FileOutputStream

/**
 * "Open in Google Photos" and "Share" for the item currently under review.
 *
 * Both exist for the moment mid-cleanup where you would rather keep a photo than
 * judge it — without having to leave the app, find it by hand, and lose your place.
 */
object ShareActions {

    private const val SHARE_DIR = "share"

    /**
     * Opens this item in Google Photos.
     *
     * Android hands photos.google.com links to the Google Photos app when it is
     * installed, so this usually lands in the app rather than a browser. Returns
     * false if nothing can handle it at all.
     */
    fun openInGooglePhotos(context: Context, item: MediaItem, authUser: Int): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.googlePhotosUrl(authUser)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Shares the actual picture.
     *
     * Sharing the Google Photos link instead would be near-useless: those links are
     * private to the account, so a recipient without access sees nothing. The image
     * comes from the loader that is already displaying it, so the cookie handling and
     * disk cache are reused rather than duplicated.
     *
     * Videos share their Google Photos link instead — downloading the file could be
     * tens of megabytes, which is not a reasonable thing to do behind a single tap.
     */
    suspend fun share(context: Context, item: MediaItem, authUser: Int): Result<Unit> {
        if (item.isVideo) return shareLink(context, item, authUser)

        return try {
            val uri = withContext(Dispatchers.IO) { cacheForSharing(context, item, authUser) }
                ?: return shareLink(context, item, authUser)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, item.googlePhotosUrl(authUser))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Share photo")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun shareLink(context: Context, item: MediaItem, authUser: Int): Result<Unit> = try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, item.googlePhotosUrl(authUser))
        }
        context.startActivity(
            Intent.createChooser(intent, "Share link")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Fetches the image and writes it somewhere the share sheet can read. */
    private suspend fun cacheForSharing(
        context: Context,
        item: MediaItem,
        authUser: Int,
    ): Uri? {
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

        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        // Keep only the current share: these are copies of personal photos, and there
        // is no reason for them to accumulate in the cache.
        dir.listFiles()?.forEach { it.delete() }

        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Removes anything staged for sharing. Called as part of the full local wipe. */
    fun clearCache(context: Context) {
        runCatching { File(context.cacheDir, SHARE_DIR).deleteRecursively() }
    }
}
