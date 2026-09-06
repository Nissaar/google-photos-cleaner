package xyz.photocleaner.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decodes Google's positional-array responses.
 *
 * The wire format has no field names — everything is an index into a nested array,
 * and trailing "extension" data hangs off the last element under large numeric keys.
 * Every access here is therefore defensive: a shape change upstream must degrade to
 * a dropped field or a skipped item, never a crash and never a wrong dedupKey (which
 * is the key deletion operates on).
 */
object Parser {

    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    private fun JsonElement?.text(): String? {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.content.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JsonElement?.long(): Long? = text()?.toLongOrNull()

    private fun JsonElement?.int(): Int? = text()?.toIntOrNull()

    private fun JsonElement?.bool(): Boolean = text() == "true"

    private fun JsonArray.at(index: Int): JsonElement? =
        getOrNull(index)?.takeIf { it !is JsonNull }

    /** Extension blob on the last array element, keyed by Google's numeric field ids. */
    private fun extra(item: JsonArray, key: String): JsonElement? =
        item.lastOrNull().obj()?.get(key)?.takeIf { it !is JsonNull }

    fun parseMediaItem(raw: JsonElement?): MediaItem? {
        val item = raw.arr() ?: return null

        // Without these two keys the entry is unusable: mediaKey identifies it for
        // album operations, dedupKey is what trash/restore act on. Never guess either.
        val mediaKey = item.at(0).text() ?: return null
        val dedupKey = item.at(3).text() ?: return null

        val thumbBlock = item.at(1).arr()
        val thumb = thumbBlock?.at(0).text() ?: return null
        val timestamp = item.at(2).long() ?: return null

        val durationMs = extra(item, "76647426").arr()?.at(0).long()

        return MediaItem(
            mediaKey = mediaKey,
            dedupKey = dedupKey,
            timestamp = timestamp,
            creationTimestamp = item.at(5).long() ?: timestamp,
            timezoneOffsetSec = item.at(4).long() ?: 0L,
            thumbBaseUrl = thumb,
            width = thumbBlock?.at(1).int() ?: 0,
            height = thumbBlock?.at(2).int() ?: 0,
            isVideo = durationMs != null,
            durationMs = durationMs,
            isArchived = item.at(13).bool(),
            isFavorite = extra(item, "163238866").arr()?.at(0).bool(),
        )
    }

    fun parseTimelinePage(raw: JsonElement?): TimelinePage {
        val data = raw.arr() ?: return TimelinePage(emptyList(), null, null)
        val items = (data.at(0).arr() ?: JsonArray(emptyList()))
            .mapNotNull { parseMediaItem(it) }
        return TimelinePage(
            items = items,
            nextPageId = data.at(1).text(),
            lastItemTimestamp = data.at(2).long(),
        )
    }

    fun parseStorageQuota(raw: JsonElement?): StorageQuota? {
        val data = raw.arr() ?: return null
        // Values arrive as strings; used may be nested one level depending on account type.
        val used = data.at(0).arr()?.at(0).long() ?: data.at(0).long() ?: return null
        val total = data.at(1).long() ?: return null
        return StorageQuota(used, total)
    }

    /** The album-create response returns the new album's mediaKey. */
    fun parseCreatedAlbumKey(raw: JsonElement?): String? {
        val data = raw.arr() ?: return null
        return data.at(0).text() ?: data.at(0).arr()?.at(0).text()
    }

    /** Albums page: an array of albums then a nextPageId; title sits under key 72930366. */
    fun parseAlbums(raw: JsonElement?): List<Pair<String, String>> {
        val data = raw.arr() ?: return emptyList()
        val list = data.at(0).arr() ?: return emptyList()
        return list.mapNotNull { entry ->
            val album = entry.arr() ?: return@mapNotNull null
            val key = album.at(0).text() ?: return@mapNotNull null
            val title = extra(album, "72930366").arr()?.at(1).text() ?: return@mapNotNull null
            key to title
        }
    }
}
