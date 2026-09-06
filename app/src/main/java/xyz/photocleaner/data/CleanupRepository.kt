package xyz.photocleaner.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.photocleaner.api.MediaItem
import xyz.photocleaner.api.PhotosApi
import java.time.YearMonth
import java.time.ZoneId

/** Outcome of applying pending verdicts. */
data class ApplyResult(
    val mode: CleanupMode,
    val succeeded: Int,
    val failed: Int,
    val albumName: String? = null,
    val error: String? = null,
)

/**
 * Ties local verdicts to the remote actions that carry them out.
 *
 * The ordering rule throughout: mark local state as applied only *after* Google has
 * accepted the change. A crash mid-batch then leaves items pending — safe to retry —
 * rather than silently marked done while still in the library.
 */
class CleanupRepository(
    private val api: PhotosApi,
    private val dao: DecisionDao,
    private val indexDao: LibraryIndexDao,
    private val settings: Settings,
) {

    companion object {
        /** Safety stop: ~200 items a page, so this covers a very large library. */
        private const val MAX_SCAN_PAGES = 2_000
    }

    fun pendingDeletes(): Flow<List<Decision>> = dao.pending(Verdict.DELETE)
    fun pendingDeleteCount(): Flow<Int> = dao.pendingCount(Verdict.DELETE)
    fun keptCount(): Flow<Int> = dao.keptCount()
    fun appliedDeletes(): Flow<List<Decision>> = dao.applied()

    suspend fun record(item: MediaItem, verdict: Verdict) {
        dao.upsert(Decision.from(item, verdict))
    }

    /** Undo the most recent verdict for a single item, before it has been applied. */
    suspend fun undo(dedupKey: String) = dao.deleteOne(dedupKey)

    /**
     * Loads a month's photos, optionally hiding ones already judged.
     * Returns them newest-first, which is the order the swipe deck consumes.
     */
    suspend fun loadMonth(
        month: YearMonth,
        zone: ZoneId = ZoneId.systemDefault(),
        onProgress: (Int) -> Unit = {},
    ): List<MediaItem> {
        val source = if (settings.includeArchived.first()) {
            PhotosApi.Source.BOTH
        } else {
            PhotosApi.Source.LIBRARY
        }
        val items = api.getItemsForMonth(month, zone, source, onProgress)
        if (!settings.skipDecided.first()) return items

        val startMs = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val decided = dao.decidedKeysBetween(startMs, endMs).toHashSet()
        return items.filterNot { it.dedupKey in decided }
    }

    /** Month tallies held locally, shown instantly on launch with no network at all. */
    fun observeMonthCounts(): Flow<Map<YearMonth, Int>> =
        indexDao.observeMonthCounts().map { rows ->
            rows.mapNotNull { row ->
                runCatching { YearMonth.parse(row.yearMonth) }.getOrNull()?.let { it to row.count }
            }.toMap()
        }

    /** True when the month grid has nothing to show yet. */
    suspend fun needsInitialScan(): Boolean = indexDao.scanState() == null

    /** True when a first pass is still part-way through the library. */
    suspend fun scanIncomplete(): Boolean = indexDao.scanState()?.complete == false

    /**
     * Brings the month index up to date.
     *
     * Three modes, chosen from the stored state:
     *  - **first pass** — walk the whole timeline, newest to oldest;
     *  - **resume** — pick a interrupted first pass back up where it stopped;
     *  - **incremental** — once complete, read only photos added since the last run.
     *
     * Counts are written after *every page*. That is what makes the months appear as
     * they are found, and what means closing the app mid-scan costs one page rather
     * than the entire run.
     */
    suspend fun refreshMonthCounts(
        full: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
        onProgress: (Int) -> Unit = {},
    ) {
        val source = if (settings.includeArchived.first()) {
            PhotosApi.Source.BOTH
        } else {
            PhotosApi.Source.LIBRARY
        }

        if (full) {
            indexDao.clearMonthCounts()
            indexDao.clearScanState()
        }
        val previous = if (full) null else indexDao.scanState()

        val resuming = previous != null && !previous.complete && previous.resumeTimestamp != null
        val incremental = previous != null && previous.complete

        // Seed from what is already stored so a resumed run keeps its earlier tallies.
        val counts = LinkedHashMap<String, Int>()
        indexDao.monthCounts().forEach { counts[it.yearMonth] = it.count }
        var total = counts.values.sum()

        // An incremental run stops once it reaches ground already covered.
        val stopAt = if (incremental) previous!!.newestTimestamp else null

        var newest: Long? = previous?.newestTimestamp
        var lastTimestamp: Long? = previous?.resumeTimestamp
        var lastKey: String? = previous?.resumeKey
        // On resume, skip forward past the item we stopped on rather than recounting it.
        var skipUntilKey: String? = if (resuming) previous!!.resumeKey else null

        var pageId: String? = null
        var firstRequest = true
        var pages = 0
        var finished = false

        while (pages++ < MAX_SCAN_PAGES) {
            val page = api.getItemsByTakenDate(
                startTimestamp = if (firstRequest && resuming) previous!!.resumeTimestamp else null,
                pageId = pageId,
                source = source,
            )
            if (page.items.isEmpty()) {
                finished = true
                break
            }

            var reachedKnown = false
            for (item in page.items) {
                if (newest == null || item.timestamp > newest!!) newest = item.timestamp

                if (skipUntilKey != null) {
                    if (item.dedupKey == skipUntilKey) skipUntilKey = null
                    continue
                }
                if (stopAt != null && item.timestamp <= stopAt) {
                    reachedKnown = true
                    break
                }

                val key = item.yearMonth(zone).toString()
                counts[key] = (counts[key] ?: 0) + 1
                total++
                lastTimestamp = item.timestamp
                lastKey = item.dedupKey
            }
            // The resume marker only ever applies to the first page fetched.
            skipUntilKey = null
            firstRequest = false

            saveProgress(counts, newest, lastTimestamp, lastKey, complete = false)
            onProgress(total)

            if (reachedKnown) {
                finished = true
                break
            }
            pageId = page.nextPageId
            if (pageId == null) {
                finished = true
                break
            }
        }

        saveProgress(counts, newest, lastTimestamp, lastKey, complete = finished)
    }

    private suspend fun saveProgress(
        counts: Map<String, Int>,
        newest: Long?,
        resumeTimestamp: Long?,
        resumeKey: String?,
        complete: Boolean,
    ) {
        if (counts.isNotEmpty()) {
            indexDao.upsertMonthCounts(counts.map { MonthCount(it.key, it.value) })
        }
        if (newest != null) {
            indexDao.setScanState(
                ScanState(
                    newestTimestamp = newest,
                    resumeTimestamp = resumeTimestamp,
                    resumeKey = resumeKey,
                    complete = complete,
                    scannedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Keeps the cached month tallies honest as photos leave or come back.
     *
     * [delta] is -1 when items are trashed and +1 when they are restored — restoring
     * has to put the count back, or the grid under-reports for good.
     */
    private suspend fun adjustMonthCounts(
        decisions: List<Decision>,
        delta: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (decisions.isEmpty()) return
        val affected = decisions.groupingBy {
            YearMonth.from(java.time.Instant.ofEpochMilli(it.takenAt).atZone(zone)).toString()
        }.eachCount()

        val current = indexDao.monthCounts().associate { it.yearMonth to it.count }
        val updated = affected.map { (ym, n) ->
            val base = current[ym] ?: 0
            MonthCount(ym, (base + delta * n).coerceAtLeast(0))
        }
        if (updated.isNotEmpty()) indexDao.upsertMonthCounts(updated)
    }

    /**
     * Carries out every pending DELETE verdict, using whichever mode is configured.
     * Nothing here runs without an explicit user confirmation upstream.
     */
    suspend fun applyPending(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ApplyResult {
        val pending = dao.pendingOnce(Verdict.DELETE)
        val mode = settings.mode.first()
        if (pending.isEmpty()) return ApplyResult(mode, 0, 0)

        return when (mode) {
            CleanupMode.TRASH -> applyTrash(pending, onProgress)
            CleanupMode.ALBUM -> applyAlbum(pending, onProgress)
        }
    }

    private suspend fun applyTrash(
        pending: List<Decision>,
        onProgress: (Int, Int) -> Unit,
    ): ApplyResult {
        val keys = pending.map { it.dedupKey }
        return try {
            val result = api.moveToTrash(keys, onProgress)
            val done = result.succeeded
            if (done.isNotEmpty()) {
                dao.markApplied(done, System.currentTimeMillis())
                val doneSet = done.toHashSet()
                adjustMonthCounts(pending.filter { it.dedupKey in doneSet }, delta = -1)
            }
            ApplyResult(
                mode = CleanupMode.TRASH,
                succeeded = done.size,
                failed = keys.size - done.size,
                error = result.error,
            )
        } catch (e: Exception) {
            ApplyResult(CleanupMode.TRASH, 0, keys.size, error = e.message ?: "Failed")
        }
    }

    private suspend fun applyAlbum(
        pending: List<Decision>,
        onProgress: (Int, Int) -> Unit,
    ): ApplyResult {
        val name = settings.albumName.first()
        return try {
            val albumKey = api.findOrCreateAlbum(name)
                ?: return ApplyResult(CleanupMode.ALBUM, 0, pending.size, name, "Could not create album")
            // Album membership is addressed by mediaKey, not dedupKey.
            val added = api.addToAlbum(albumKey, pending.map { it.mediaKey }, onProgress)
            if (added > 0) dao.markApplied(pending.map { it.dedupKey }, System.currentTimeMillis())
            ApplyResult(CleanupMode.ALBUM, added, pending.size - added, name)
        } catch (e: Exception) {
            ApplyResult(CleanupMode.ALBUM, 0, pending.size, name, e.message ?: "Failed")
        }
    }

    /**
     * Restores items already sent to the trash. Google keeps trashed items for 60
     * days, so this only succeeds inside that window.
     */
    suspend fun restore(
        decisions: List<Decision>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Int {
        if (decisions.isEmpty()) return 0
        val restored = api.restoreFromTrash(decisions.map { it.dedupKey }, onProgress).succeeded
        if (restored.isNotEmpty()) {
            // Restored items are no longer condemned; drop the verdict entirely so they
            // reappear next time the month is reviewed.
            dao.delete(restored)
            // And they are back in the library, so the month tallies must reflect that.
            val restoredSet = restored.toHashSet()
            adjustMonthCounts(decisions.filter { it.dedupKey in restoredSet }, delta = +1)
        }
        return restored.size
    }

    suspend fun clearAllDecisions() = dao.clearAll()
}
