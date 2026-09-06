package xyz.photocleaner.api

import kotlinx.serialization.json.JsonElement
import xyz.photocleaner.session.GPhotosSession
import xyz.photocleaner.session.SessionException
import java.time.YearMonth
import java.time.ZoneId

/**
 * Typed wrapper over the Google Photos web RPCs.
 *
 * Every call goes through [Pacer] first, so the whole app shares one request budget
 * regardless of which screen is driving.
 */
class PhotosApi(
    private val session: GPhotosSession,
    private val pacer: Pacer = Pacer(),
) {

    enum class Source(val code: Int) { LIBRARY(1), ARCHIVE(2), BOTH(3) }

    private suspend fun call(rpcid: String, args: String, write: Boolean): JsonElement {
        if (write) pacer.beforeWrite() else pacer.beforeRead()
        return try {
            session.rpc(rpcid, args).also { pacer.onSuccess() }
        } catch (e: SessionException) {
            if (e.code == "RATE_LIMITED") pacer.onRateLimited()
            throw e
        }
    }

    private fun jsonArg(vararg values: Any?): String =
        values.joinToString(prefix = "[", postfix = "]") { v ->
            when (v) {
                null -> "null"
                is String -> "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                is Boolean -> if (v) "true" else "false"
                is Int, is Long -> v.toString()
                is List<*> -> v.joinToString(prefix = "[", postfix = "]") { s ->
                    "\"${(s as String).replace("\\", "\\\\").replace("\"", "\\\"")}\""
                }
                else -> "null"
            }
        }

    /**
     * One page of the timeline, newest first.
     *
     * [startTimestamp] seeks into the timeline by date-taken, which is what makes
     * month-scoped browsing possible without walking the whole library.
     */
    suspend fun getItemsByTakenDate(
        startTimestamp: Long? = null,
        pageId: String? = null,
        pageSize: Int = 200,
        source: Source = Source.LIBRARY,
    ): TimelinePage {
        val args = jsonArg(pageId, startTimestamp, pageSize, null, 1, source.code)
        return Parser.parseTimelinePage(call(Rpc.ITEMS_BY_TAKEN_DATE, args, write = false))
    }

    /**
     * Every item taken within [month], oldest boundary respected.
     *
     * Google returns the timeline in descending date order, so we seek to the end of
     * the month and page until we fall off the start of it. [onProgress] is invoked
     * as pages arrive so the UI can show real progress on a large month.
     */
    suspend fun getItemsForMonth(
        month: YearMonth,
        zone: ZoneId = ZoneId.systemDefault(),
        source: Source = Source.LIBRARY,
        onProgress: (Int) -> Unit = {},
    ): List<MediaItem> {
        val startMs = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val collected = LinkedHashMap<String, MediaItem>()
        var pageId: String? = null
        // Seek to just inside the end of the month rather than "now".
        var cursor: Long? = endMs - 1
        var guard = 0

        while (guard++ < 500) {
            val page = getItemsByTakenDate(
                startTimestamp = if (pageId == null) cursor else null,
                pageId = pageId,
                source = source,
            )
            if (page.items.isEmpty()) break

            var reachedStart = false
            for (item in page.items) {
                when {
                    item.timestamp >= endMs -> Unit // newer than the month; skip
                    item.timestamp < startMs -> reachedStart = true
                    else -> collected[item.dedupKey] = item
                }
            }
            onProgress(collected.size)

            if (reachedStart) break
            pageId = page.nextPageId ?: break
            cursor = page.lastItemTimestamp
        }
        return collected.values.sortedByDescending { it.timestamp }
    }

    /**
     * Outcome of a batched mutation.
     *
     * [succeeded] is always accurate even when [error] is set: a batch failing part
     * way through must not lose track of the batches Google already accepted, or the
     * local database would disagree with the account.
     */
    data class MutationResult(
        val succeeded: List<String>,
        val error: String? = null,
    )

    /**
     * Moves items to the Google Photos trash, where they stay recoverable for 60 days.
     */
    suspend fun moveToTrash(
        dedupKeys: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MutationResult = mutate(dedupKeys, restore = false, onProgress)

    /** Restores previously trashed items — the undo path for [moveToTrash]. */
    suspend fun restoreFromTrash(
        dedupKeys: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MutationResult = mutate(dedupKeys, restore = true, onProgress)

    private suspend fun mutate(
        dedupKeys: List<String>,
        restore: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): MutationResult {
        if (dedupKeys.isEmpty()) return MutationResult(emptyList())
        val done = mutableListOf<String>()
        val batches = dedupKeys.distinct().chunked(Pacer.MAX_TRASH_BATCH)

        for ((index, batch) in batches.withIndex()) {
            // Same rpcid both directions; the leading/trailing codes pick the action.
            val args = if (restore) {
                jsonArg(null, 3, batch, 2)
            } else {
                jsonArg(null, 1, batch, 3)
            }
            try {
                call(Rpc.TRASH_OR_RESTORE, args, write = true)
            } catch (e: Exception) {
                // Stop here, but report everything Google already accepted.
                return MutationResult(done, e.message ?: "Request failed")
            }
            done += batch
            onProgress(done.size, dedupKeys.size)
            if (index < batches.lastIndex) pacer.restBetweenBatches()
        }
        return MutationResult(done)
    }

    /** Lists the user's albums as (mediaKey, title). */
    suspend fun listAlbums(pageSize: Int = 100): List<Pair<String, String>> =
        Parser.parseAlbums(call(Rpc.ALBUM_LIST, jsonArg(null, null, pageSize, null, 1), write = false))

    /** Creates an album and returns its mediaKey. */
    suspend fun createAlbum(title: String): String? =
        Parser.parseCreatedAlbumKey(call(Rpc.ALBUM_CREATE, jsonArg(title, null, 2), write = true))

    /**
     * Adds items to an existing album. Note this takes **mediaKeys**, not dedupKeys.
     */
    suspend fun addToAlbum(
        albumMediaKey: String,
        mediaKeys: List<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Int {
        if (mediaKeys.isEmpty()) return 0
        var added = 0
        val batches = mediaKeys.distinct().chunked(Pacer.MAX_TRASH_BATCH)
        for ((index, batch) in batches.withIndex()) {
            call(Rpc.ALBUM_ADD_ITEMS, jsonArg(batch, albumMediaKey), write = true)
            added += batch.size
            onProgress(added, mediaKeys.size)
            if (index < batches.lastIndex) pacer.restBetweenBatches()
        }
        return added
    }

    /**
     * Finds an album by exact title, creating it if absent.
     * Backs the "move to a To Be Deleted album" mode.
     */
    suspend fun findOrCreateAlbum(title: String): String? =
        listAlbums().firstOrNull { it.second == title }?.first ?: createAlbum(title)

    suspend fun storageQuota(): StorageQuota? =
        Parser.parseStorageQuota(call(Rpc.STORAGE_QUOTA, "[]", write = false))
}
