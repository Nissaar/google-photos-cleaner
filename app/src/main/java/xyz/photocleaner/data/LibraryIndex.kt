package xyz.photocleaner.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Cached count of photos in one month, e.g. "2024-07".
 *
 * Scanning the whole timeline takes minutes on a large library, so the result is kept
 * and only the newly-added tail is re-read on subsequent launches.
 */
@Entity(tableName = "month_counts")
data class MonthCount(
    @PrimaryKey val yearMonth: String,
    val count: Int,
)

/**
 * A month tally from a recount still in progress, kept apart from [MonthCount] so the
 * grid goes on showing the old numbers until the new ones are complete.
 */
@Entity(tableName = "month_counts_recount")
data class RecountMonth(
    @PrimaryKey val yearMonth: String,
    val count: Int,
)

/**
 * Where the scan got to. Written after every page, so closing the app mid-scan
 * costs at most one page of progress rather than the whole run.
 *
 * On a 20,000-photo library a first scan takes a while, so it has to be resumable.
 */
@Entity(tableName = "scan_state")
data class ScanState(
    @PrimaryKey val id: Int = SINGLETON,
    /** Date-taken of the newest item seen. Later incremental syncs stop here. */
    val newestTimestamp: Long,
    /** How far back the scan has reached, used to resume an interrupted run. */
    val resumeTimestamp: Long? = null,
    /** dedupKey of the last counted item, so resuming does not double-count it. */
    val resumeKey: String? = null,
    /** False while a first pass is still working its way back through the library. */
    val complete: Boolean = false,
    val scannedAt: Long,
) {
    companion object {
        /** The index the grid shows. */
        const val SINGLETON = 0

        /** Progress of a recount into [RecountMonth], resumable like a first pass. */
        const val RECOUNT = 1
    }
}

@Dao
interface LibraryIndexDao {

    @Query("SELECT * FROM month_counts")
    fun observeMonthCounts(): Flow<List<MonthCount>>

    @Query("SELECT * FROM month_counts")
    suspend fun monthCounts(): List<MonthCount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMonthCounts(counts: List<MonthCount>)

    @Query("DELETE FROM month_counts")
    suspend fun clearMonthCounts()

    @Query("SELECT * FROM scan_state WHERE id = :id")
    suspend fun scanState(id: Int = ScanState.SINGLETON): ScanState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setScanState(state: ScanState)

    /** Every scan marker, a recount's included. */
    @Query("DELETE FROM scan_state")
    suspend fun clearScanState()

    @Query("DELETE FROM scan_state WHERE id = :id")
    suspend fun deleteScanState(id: Int)

    @Query("SELECT * FROM month_counts_recount")
    suspend fun recountCounts(): List<RecountMonth>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecountCounts(counts: List<RecountMonth>)

    @Query("DELETE FROM month_counts_recount")
    suspend fun clearRecountCounts()

    @Query("INSERT INTO month_counts (yearMonth, count) SELECT yearMonth, count FROM month_counts_recount")
    suspend fun copyRecountIntoIndex()

    /** Recount progress, saved together for the same reason as [saveIndex]. */
    @Transaction
    suspend fun saveRecount(counts: List<RecountMonth>, state: ScanState?) {
        if (counts.isNotEmpty()) upsertRecountCounts(counts)
        if (state != null) setScanState(state)
    }

    /**
     * Swaps a finished recount in for the index, in one step: the grid goes straight
     * from the old numbers to the new ones, never through an empty or partial state.
     */
    @Transaction
    suspend fun promoteRecount(state: ScanState) {
        clearMonthCounts()
        copyRecountIntoIndex()
        clearRecountCounts()
        deleteScanState(ScanState.RECOUNT)
        setScanState(state)
    }

    /**
     * Writes tallies and the scan marker together. Written separately, a process
     * death between the two leaves counts that include a page the marker says has
     * not been read — and the next run counts that page again.
     */
    @Transaction
    suspend fun saveIndex(counts: List<MonthCount>, state: ScanState?) {
        if (counts.isNotEmpty()) upsertMonthCounts(counts)
        if (state != null) setScanState(state)
    }
}
