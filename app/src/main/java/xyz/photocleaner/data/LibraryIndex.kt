package xyz.photocleaner.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
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
        const val SINGLETON = 0
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

    @Query("SELECT * FROM scan_state WHERE id = 0")
    suspend fun scanState(): ScanState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setScanState(state: ScanState)

    @Query("DELETE FROM scan_state")
    suspend fun clearScanState()
}
