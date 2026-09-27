package xyz.photocleaner.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.photocleaner.api.MediaItem
import xyz.photocleaner.api.PhotosApi
import xyz.photocleaner.api.PhotosRemote
import java.time.YearMonth
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/** Outcome of applying pending verdicts. */
data class ApplyResult(
    val mode: CleanupMode,
    val succeeded: Int,
    val failed: Int,
    val albumName: String? = null,
    val error: String? = null,
)

/** Outcome of restoring items from the trash. */
data class RestoreResult(
    val restored: Int,
    val failed: Int,
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
    private val api: PhotosRemote,
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

    /** How many photos have been judged in each month, for review progress. */
    fun decidedByMonth(zone: ZoneId = ZoneId.systemDefault()): Flow<Map<YearMonth, Int>> =
        dao.observeDecidedTimestamps().map { timestamps ->
            timestamps.groupingBy {
                YearMonth.from(java.time.Instant.ofEpochMilli(it).atZone(zone))
            }.eachCount()
        }

    /**
     * Clears the verdicts for one month so its photos come back up for review.
     * Returns how many were forgotten. Nothing in Google Photos is touched.
     */
    suspend fun resetMonth(month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Int {
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.clearUnappliedBetween(from, to)
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
     * A first pass writes its counts after *every page*. That is what makes the months
     * appear as they are found, and what means closing the app mid-scan costs one page
     * rather than the entire run.
     *
     * An incremental run writes nothing until it has finished, then everything at once.
     * Saving it page by page would store the index as incomplete, and an interrupted
     * run would then be resumed as a first pass — walking the whole library again and
     * adding every photo on top of the counts it already had.
     */
    suspend fun refreshMonthCounts(
        full: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
        onProgress: (Int) -> Unit = {},
    ) {
        if (full) {
            indexDao.clearMonthCounts()
            indexDao.clearRecountCounts()
            indexDao.clearScanState()
            // A full rescan produces correct tallies itself, so no recount is owed.
            settings.setRecountDone()
        }
        walk(IndexTarget.LIVE, zone, onProgress)
    }

    /**
     * True while tallies saved by an older version still need rebuilding.
     *
     * Before interrupted syncs were fixed, one could double a library's counts. Only
     * an index built by those versions can be affected; with nothing counted yet
     * there is nothing to repair, and the flag is settled at once.
     */
    suspend fun recountPending(): Boolean {
        if (settings.recountDone.first()) return false
        val anything = indexDao.scanState() != null || indexDao.scanState(ScanState.RECOUNT) != null
        if (!anything) settings.setRecountDone()
        return anything
    }

    /**
     * Rebuilds every month tally from scratch, without disturbing the grid.
     *
     * Counts go into their own table, saved per page so an interrupted recount resumes
     * where it stopped, and replace the old ones in a single transaction at the end.
     * Verdicts are never touched: this only ever concerns the month totals.
     */
    suspend fun recount(zone: ZoneId = ZoneId.systemDefault(), onProgress: (Int) -> Unit = {}) {
        val result = walk(IndexTarget.RECOUNT, zone, onProgress) ?: return
        indexDao.promoteRecount(result.copy(id = ScanState.SINGLETON, complete = true))
        settings.setRecountDone()
    }

    /** Which tallies a walk reads from and saves to. */
    private enum class IndexTarget(val stateId: Int) {
        LIVE(ScanState.SINGLETON),
        RECOUNT(ScanState.RECOUNT),
    }

    /**
     * Walks the timeline into [target]. Returns the final scan state when the walk
     * got to the end, or null if it stopped short (it can then be resumed).
     */
    private suspend fun walk(
        target: IndexTarget,
        zone: ZoneId,
        onProgress: (Int) -> Unit,
    ): ScanState? {
        val source = if (settings.includeArchived.first()) {
            PhotosApi.Source.BOTH
        } else {
            PhotosApi.Source.LIBRARY
        }

        val previous = indexDao.scanState(target.stateId)

        val resuming = previous != null && !previous.complete && previous.resumeTimestamp != null
        // A recount is never complete until it is promoted, so it is never incremental.
        val incremental = previous != null && previous.complete

        // Seed from what is already stored so a resumed run keeps its earlier tallies.
        val counts = LinkedHashMap<String, Int>()
        when (target) {
            IndexTarget.LIVE -> indexDao.monthCounts().forEach { counts[it.yearMonth] = it.count }
            IndexTarget.RECOUNT -> indexDao.recountCounts().forEach { counts[it.yearMonth] = it.count }
        }
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

        fun state(complete: Boolean) = newest?.let {
            ScanState(
                id = target.stateId,
                newestTimestamp = it,
                resumeTimestamp = lastTimestamp,
                resumeKey = lastKey,
                complete = complete,
                scannedAt = System.currentTimeMillis(),
            )
        }

        suspend fun save(complete: Boolean) {
            when (target) {
                IndexTarget.LIVE -> indexDao.saveIndex(
                    counts.map { MonthCount(it.key, it.value) },
                    state(complete),
                )
                IndexTarget.RECOUNT -> indexDao.saveRecount(
                    counts.map { RecountMonth(it.key, it.value) },
                    state(complete),
                )
            }
        }

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

            if (!incremental) save(complete = false)
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

        // An unfinished incremental run is dropped whole; the next one redoes it.
        if (incremental && !finished) return null
        // A recount is only ever saved as in progress; promoting it is what completes it.
        save(complete = finished && target == IndexTarget.LIVE)
        return if (finished) state(complete = true) else null
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

        // A recount in progress has already counted everything newer than where it has
        // reached. Those items must move with the live tallies, or promoting the
        // recount would bring back photos just sent to the trash (or drop restored ones).
        val recountAt = indexDao.scanState(ScanState.RECOUNT)?.resumeTimestamp ?: return
        val passed = decisions.filter { it.takenAt >= recountAt }.groupingBy {
            YearMonth.from(java.time.Instant.ofEpochMilli(it.takenAt).atZone(zone)).toString()
        }.eachCount()
        if (passed.isEmpty()) return
        val draft = indexDao.recountCounts().associate { it.yearMonth to it.count }
        indexDao.upsertRecountCounts(
            passed.map { (ym, n) -> RecountMonth(ym, ((draft[ym] ?: 0) + delta * n).coerceAtLeast(0)) },
        )
    }

    /**
     * Carries out every pending DELETE verdict, using whichever mode is configured.
     * Nothing here runs without an explicit user confirmation upstream.
     */
    suspend fun applyPending(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ApplyResult {
        val mode = settings.mode.first()
        var pending = emptyList<Decision>()
        // Anything unexpected becomes a result to show, not a crash in the middle of a
        // run the user is watching. Whatever Google already accepted is recorded below.
        return try {
            pending = dao.pendingOnce(Verdict.DELETE)
            if (pending.isEmpty()) return ApplyResult(mode, 0, 0)
            when (mode) {
                CleanupMode.TRASH -> applyTrash(pending, onProgress)
                CleanupMode.ALBUM -> applyAlbum(pending, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApplyResult(mode, 0, pending.size, error = e.message ?: "Something went wrong")
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
                dao.markApplied(done, System.currentTimeMillis(), CleanupMode.TRASH)
                val doneSet = done.toHashSet()
                adjustMonthCounts(pending.filter { it.dedupKey in doneSet }, delta = -1)
            }
            ApplyResult(
                mode = CleanupMode.TRASH,
                succeeded = done.size,
                failed = keys.size - done.size,
                error = result.error,
            )
        } catch (e: CancellationException) {
            throw e
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
            val result = api.addToAlbum(albumKey, pending.map { it.mediaKey }, onProgress)
            // Only what Google accepted is done; the rest stays pending for a retry.
            val added = result.succeeded.toHashSet()
            val done = pending.filter { it.mediaKey in added }.map { it.dedupKey }
            if (done.isNotEmpty()) {
                dao.markApplied(done, System.currentTimeMillis(), CleanupMode.ALBUM)
            }
            ApplyResult(CleanupMode.ALBUM, done.size, pending.size - done.size, name, result.error)
        } catch (e: CancellationException) {
            throw e
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
    ): RestoreResult {
        if (decisions.isEmpty()) return RestoreResult(0, 0)
        return try {
            val result = api.restoreFromTrash(decisions.map { it.dedupKey }, onProgress)
            val restored = result.succeeded
            if (restored.isNotEmpty()) {
                // Restored items are no longer condemned; drop the verdict entirely so
                // they reappear next time the month is reviewed.
                dao.delete(restored)
                // And they are back in the library, so the month tallies must reflect that.
                val restoredSet = restored.toHashSet()
                adjustMonthCounts(decisions.filter { it.dedupKey in restoredSet }, delta = +1)
            }
            RestoreResult(restored.size, decisions.size - restored.size, result.error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RestoreResult(0, decisions.size, e.message ?: "Something went wrong")
        }
    }

    suspend fun clearAllDecisions() = dao.clearAll()
}
