package xyz.photocleaner.fakes

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import xyz.photocleaner.api.MediaItem
import xyz.photocleaner.api.PhotosApi.MutationResult
import xyz.photocleaner.api.PhotosApi.Source
import xyz.photocleaner.api.PhotosRemote
import xyz.photocleaner.api.TimelinePage
import xyz.photocleaner.data.CleanupMode
import xyz.photocleaner.data.Decision
import xyz.photocleaner.data.DecisionDao
import xyz.photocleaner.data.LibraryIndexDao
import xyz.photocleaner.data.MonthCount
import xyz.photocleaner.data.RecountMonth
import xyz.photocleaner.data.ScanState
import xyz.photocleaner.data.Settings
import xyz.photocleaner.data.Verdict
import xyz.photocleaner.session.SessionException
import java.io.File
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/** Every test works in UTC, so month boundaries do not depend on the machine. */
val UTC: ZoneId = ZoneOffset.UTC

/** A photo taken at noon on [day] of [month]; the key doubles as mediaKey "m-<key>". */
fun photo(key: String, month: String, day: Int = 15, hour: Int = 12): MediaItem {
    val ts = LocalDateTime.of(YearMonth.parse(month).atDay(day), java.time.LocalTime.of(hour, 0))
        .toInstant(ZoneOffset.UTC).toEpochMilli()
    return MediaItem(
        mediaKey = "m-$key", dedupKey = key, timestamp = ts, creationTimestamp = ts,
        timezoneOffsetSec = 0, thumbBaseUrl = "https://lh3.googleusercontent.com/$key",
        width = 4, height = 3, isVideo = false, durationMs = null,
        isArchived = false, isFavorite = false,
    )
}

fun settings(dir: File, scope: CoroutineScope): Settings =
    Settings(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") })

/**
 * A library held in memory, served newest first in small pages so paging, resuming
 * and interruptions are all exercised. [failOnCall] makes that numbered timeline
 * call throw, the way a network drop does part-way through a scan.
 */
class FakeRemote(photos: List<MediaItem> = emptyList(), private val pageSize: Int = 3) : PhotosRemote {
    val library = photos.toMutableList()
    var timelineCalls = 0
    var failOnCall: Int? = null

    /** How many keys each mutation accepts before failing; null accepts all. */
    var acceptOnly: Int? = null
    val trashed = mutableListOf<String>()
    val inAlbum = mutableListOf<String>()

    override suspend fun getItemsByTakenDate(
        startTimestamp: Long?,
        pageId: String?,
        pageSize: Int,
        source: Source,
    ): TimelinePage {
        timelineCalls++
        if (timelineCalls == failOnCall) throw SessionException("TIMEOUT")
        val sorted = library.sortedByDescending { it.timestamp }
        val from = when {
            pageId != null -> pageId.toInt()
            startTimestamp != null -> sorted.indexOfFirst { it.timestamp <= startTimestamp }
                .let { if (it < 0) sorted.size else it }
            else -> 0
        }
        val items = sorted.drop(from).take(this.pageSize)
        val next = (from + this.pageSize).takeIf { it < sorted.size }?.toString()
        return TimelinePage(items, next, items.lastOrNull()?.timestamp)
    }

    override suspend fun getItemsForMonth(
        month: YearMonth,
        zone: ZoneId,
        source: Source,
        onProgress: (Int) -> Unit,
    ): List<MediaItem> = library.filter { it.yearMonth(zone) == month }.sortedByDescending { it.timestamp }

    override suspend fun moveToTrash(dedupKeys: List<String>, onProgress: (Int, Int) -> Unit): MutationResult {
        val accepted = accept(dedupKeys)
        trashed += accepted
        library.removeAll { it.dedupKey in accepted }
        return result(accepted, dedupKeys)
    }

    override suspend fun restoreFromTrash(dedupKeys: List<String>, onProgress: (Int, Int) -> Unit): MutationResult {
        val accepted = accept(dedupKeys)
        trashed -= accepted.toSet()
        return result(accepted, dedupKeys)
    }

    override suspend fun findOrCreateAlbum(title: String): String = "album-key"

    override suspend fun addToAlbum(
        albumMediaKey: String,
        mediaKeys: List<String>,
        onProgress: (Int, Int) -> Unit,
    ): MutationResult {
        val accepted = accept(mediaKeys)
        inAlbum += accepted
        return result(accepted, mediaKeys)
    }

    private fun accept(keys: List<String>) = keys.take(acceptOnly ?: keys.size)

    private fun result(accepted: List<String>, asked: List<String>) =
        MutationResult(accepted, if (accepted.size < asked.size) "Request failed" else null)
}

/** DecisionDao over a map, with the same semantics as its SQL. */
class FakeDecisionDao : DecisionDao {
    val rows = MutableStateFlow<Map<String, Decision>>(emptyMap())

    /** Makes upsert slow, to expose writes that race each other. */
    var upsertDelayMs = 0L

    val all: List<Decision> get() = rows.value.values.toList()

    override suspend fun upsert(decision: Decision) {
        if (upsertDelayMs > 0) delay(upsertDelayMs)
        rows.update { it + (decision.dedupKey to decision) }
    }

    override suspend fun decidedKeysBetween(from: Long, to: Long) =
        all.filter { it.takenAt in from until to }.map { it.dedupKey }

    override fun observeDecidedTimestamps(): Flow<List<Long>> = rows.map { m -> m.values.map { it.takenAt } }

    override suspend fun clearUnappliedBetween(from: Long, to: Long): Int {
        val gone = all.filter { it.takenAt in from until to && !it.applied }.map { it.dedupKey }.toSet()
        rows.update { m -> m.filterKeys { it !in gone } }
        return gone.size
    }

    private fun pendingOf(verdict: Verdict, rows: Map<String, Decision>) =
        rows.values.filter { it.verdict == verdict && !it.applied }.sortedByDescending { it.decidedAt }

    override fun pending(verdict: Verdict) = rows.map { pendingOf(verdict, it) }
    override fun pendingCount(verdict: Verdict) = rows.map { pendingOf(verdict, it).size }
    override suspend fun pendingOnce(verdict: Verdict) = pendingOf(verdict, rows.value)

    override fun applied() = rows.map { m ->
        m.values.filter { it.verdict == Verdict.DELETE && it.applied && it.appliedMode == CleanupMode.TRASH }
            .sortedByDescending { it.appliedAt }
    }

    override suspend fun markApplied(keys: List<String>, at: Long, mode: CleanupMode) = rows.update { m ->
        m.mapValues { (k, d) -> if (k in keys) d.copy(applied = true, appliedAt = at, appliedMode = mode) else d }
    }

    override suspend fun delete(keys: List<String>) = rows.update { m -> m.filterKeys { it !in keys } }
    override suspend fun deleteOne(dedupKey: String) = rows.update { it - dedupKey }
    override suspend fun clearAll() = rows.update { emptyMap() }
    override fun keptCount() = rows.map { m -> m.values.count { it.verdict == Verdict.KEEP } }
}

/**
 * LibraryIndexDao over maps. Its @Transaction methods are the real ones, inherited
 * from the interface, so the tests exercise the actual promote/save logic.
 */
class FakeIndexDao : LibraryIndexDao {
    val counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val recount = mutableMapOf<String, Int>()
    val states = mutableMapOf<Int, ScanState>()

    override fun observeMonthCounts(): Flow<List<MonthCount>> =
        counts.map { m -> m.map { MonthCount(it.key, it.value) } }

    override suspend fun monthCounts() = counts.value.map { MonthCount(it.key, it.value) }
    override suspend fun upsertMonthCounts(counts: List<MonthCount>) =
        this.counts.update { m -> m + counts.associate { it.yearMonth to it.count } }

    override suspend fun clearMonthCounts() = counts.update { emptyMap() }
    override suspend fun scanState(id: Int) = states[id]
    override suspend fun setScanState(state: ScanState) { states[state.id] = state }
    override suspend fun clearScanState() = states.clear()
    override suspend fun deleteScanState(id: Int) { states.remove(id) }
    override suspend fun recountCounts() = recount.map { RecountMonth(it.key, it.value) }
    override suspend fun upsertRecountCounts(counts: List<RecountMonth>) =
        counts.forEach { recount[it.yearMonth] = it.count }

    override suspend fun clearRecountCounts() = recount.clear()
    override suspend fun copyRecountIntoIndex() = counts.update { it + recount }
}
