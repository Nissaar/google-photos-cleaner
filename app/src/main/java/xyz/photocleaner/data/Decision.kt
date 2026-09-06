package xyz.photocleaner.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import xyz.photocleaner.api.MediaItem

enum class Verdict { KEEP, DELETE }

/**
 * A verdict you gave a photo, held only on this device.
 *
 * Deliberately stores no image bytes — just the keys needed to act on the item later
 * and enough metadata to render the review list without re-fetching.
 *
 * [applied] separates "you decided" from "we told Google", which is what makes the
 * review-then-commit flow safe: nothing is destructive until you confirm.
 */
@Entity(
    tableName = "decisions",
    indices = [Index("verdict"), Index("takenAt"), Index("applied")],
)
data class Decision(
    /** The key Google's trash/restore RPC operates on. */
    @PrimaryKey val dedupKey: String,
    /** The key album operations need. Not interchangeable with [dedupKey]. */
    val mediaKey: String,
    val takenAt: Long,
    val verdict: Verdict,
    val decidedAt: Long,
    val thumbBaseUrl: String,
    val isVideo: Boolean,
    /** True once the verdict has actually been carried out against Google Photos. */
    val applied: Boolean = false,
    val appliedAt: Long? = null,
) {
    companion object {
        fun from(item: MediaItem, verdict: Verdict, now: Long = System.currentTimeMillis()) =
            Decision(
                dedupKey = item.dedupKey,
                mediaKey = item.mediaKey,
                takenAt = item.timestamp,
                verdict = verdict,
                decidedAt = now,
                thumbBaseUrl = item.thumbBaseUrl,
                isVideo = item.isVideo,
            )
    }
}
