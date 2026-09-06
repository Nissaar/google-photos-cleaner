package xyz.photocleaner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DecisionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(decision: Decision)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(decisions: List<Decision>)

    @Query("SELECT * FROM decisions WHERE dedupKey = :dedupKey")
    suspend fun find(dedupKey: String): Decision?

    /** Keys already judged, so a month can resume where you left off. */
    @Query("SELECT dedupKey FROM decisions")
    suspend fun allDecidedKeys(): List<String>

    @Query("SELECT dedupKey FROM decisions WHERE takenAt >= :from AND takenAt < :to")
    suspend fun decidedKeysBetween(from: Long, to: Long): List<String>

    @Query("SELECT * FROM decisions WHERE verdict = :verdict AND applied = 0 ORDER BY decidedAt DESC")
    fun pending(verdict: Verdict = Verdict.DELETE): Flow<List<Decision>>

    @Query("SELECT COUNT(*) FROM decisions WHERE verdict = :verdict AND applied = 0")
    fun pendingCount(verdict: Verdict = Verdict.DELETE): Flow<Int>

    @Query("SELECT * FROM decisions WHERE verdict = :verdict AND applied = 0")
    suspend fun pendingOnce(verdict: Verdict = Verdict.DELETE): List<Decision>

    /** Items already sent to Google's trash — the source list for undo. */
    @Query("SELECT * FROM decisions WHERE verdict = 'DELETE' AND applied = 1 ORDER BY appliedAt DESC")
    fun applied(): Flow<List<Decision>>

    @Query("SELECT * FROM decisions WHERE verdict = 'DELETE' AND applied = 1 AND appliedAt >= :since")
    suspend fun appliedSince(since: Long): List<Decision>

    @Query("UPDATE decisions SET applied = 1, appliedAt = :at WHERE dedupKey IN (:keys)")
    suspend fun markApplied(keys: List<String>, at: Long)

    @Query("DELETE FROM decisions WHERE dedupKey IN (:keys)")
    suspend fun delete(keys: List<String>)

    @Query("DELETE FROM decisions WHERE dedupKey = :dedupKey")
    suspend fun deleteOne(dedupKey: String)

    @Query("DELETE FROM decisions")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM decisions WHERE verdict = 'KEEP'")
    fun keptCount(): Flow<Int>
}
