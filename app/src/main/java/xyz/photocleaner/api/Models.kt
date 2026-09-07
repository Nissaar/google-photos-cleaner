package xyz.photocleaner.api

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * One photo or video in the library.
 *
 * [dedupKey] is the identifier trash/restore operate on; [mediaKey] identifies the
 * item for album operations. They are different keys and are not interchangeable.
 */
data class MediaItem(
    val mediaKey: String,
    val dedupKey: String,
    /** Date taken, epoch millis. */
    val timestamp: Long,
    val creationTimestamp: Long,
    val timezoneOffsetSec: Long,
    val thumbBaseUrl: String,
    val width: Int,
    val height: Int,
    val isVideo: Boolean,
    val durationMs: Long?,
    val isArchived: Boolean,
    val isFavorite: Boolean,
) {
    /**
     * Google's thumbnail hosts accept sizing parameters appended to the base URL.
     * `-k-no` strips the video watermark, and `?authuser=` is what actually lifts the
     * auth requirement — without it the host returns nothing and the card renders blank.
     *
     * [authUser] must match the account index the session is on (the `/u/N/` in the
     * Photos URL), otherwise the image belongs to a different signed-in account.
     */
    fun thumbUrl(width: Int, height: Int, authUser: Int = 0): String =
        "$thumbBaseUrl=w$width-h$height-k-no?authuser=$authUser"

    fun previewUrl(maxSize: Int = 1600, authUser: Int = 0): String =
        "$thumbBaseUrl=w$maxSize-h$maxSize-k-no?authuser=$authUser"

    /**
     * Playable stream URLs for a video, best first.
     *
     * `=dv` asks for the original file, which is the documented way to get video bytes
     * but can be large and in a codec the device may not decode. `=m18` asks Google for
     * a transcoded H.264/MP4 rendition, which is small and near-universally playable.
     * Playback tries these in order, so a failure on one is not a dead end.
     */
    fun videoUrls(authUser: Int = 0): List<String> = listOf(
        "$thumbBaseUrl=m18?authuser=$authUser",
        "$thumbBaseUrl=dv?authuser=$authUser",
        "$thumbBaseUrl=m22?authuser=$authUser",
    )

    fun yearMonth(zone: ZoneId = ZoneId.systemDefault()): YearMonth =
        YearMonth.from(Instant.ofEpochMilli(timestamp).atZone(zone))
}

/** One page of the library timeline. */
data class TimelinePage(
    val items: List<MediaItem>,
    val nextPageId: String?,
    val lastItemTimestamp: Long?,
)

data class StorageQuota(
    val usedBytes: Long,
    val totalBytes: Long,
)
