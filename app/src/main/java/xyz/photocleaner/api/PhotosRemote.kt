package xyz.photocleaner.api

import xyz.photocleaner.api.PhotosApi.MutationResult
import xyz.photocleaner.api.PhotosApi.Source
import java.time.YearMonth
import java.time.ZoneId

/**
 * The Google Photos calls [xyz.photocleaner.data.CleanupRepository] makes.
 *
 * An interface so the repository's bookkeeping — what gets marked applied, what the
 * month tallies say — can be tested against a fake, without a live Google session.
 * [PhotosApi] is the real implementation.
 */
interface PhotosRemote {

    suspend fun getItemsByTakenDate(
        startTimestamp: Long? = null,
        pageId: String? = null,
        pageSize: Int = 200,
        source: Source = Source.LIBRARY,
    ): TimelinePage

    suspend fun getItemsForMonth(
        month: YearMonth,
        zone: ZoneId = ZoneId.systemDefault(),
        source: Source = Source.LIBRARY,
        onProgress: (Int) -> Unit = {},
    ): List<MediaItem>

    suspend fun moveToTrash(
        dedupKeys: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MutationResult

    suspend fun restoreFromTrash(
        dedupKeys: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MutationResult

    /** Finds an album by exact title, creating it if absent. */
    suspend fun findOrCreateAlbum(title: String): String?

    /** Adds items to an album. Takes **mediaKeys**, not dedupKeys. */
    suspend fun addToAlbum(
        albumMediaKey: String,
        mediaKeys: List<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): MutationResult
}
