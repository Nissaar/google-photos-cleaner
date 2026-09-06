package xyz.photocleaner.api

/**
 * batchexecute RPC identifiers used by the Google Photos web client.
 *
 * These are Google's own internal ids, observed from photos.google.com traffic.
 * They are undocumented and can change without notice — [PhotosApi] is written so
 * that a changed id surfaces as a clean error rather than silent data loss.
 */
object Rpc {
    /** Library timeline, ordered by date taken. */
    const val ITEMS_BY_TAKEN_DATE = "lcxiM"

    /** Generic library page (search / favourites / by upload date). */
    const val ITEMS_GENERIC = "EzkLib"

    /** Trash and restore share one id; the argument shape selects the direction. */
    const val TRASH_OR_RESTORE = "XwAOJf"

    /** Album operations, used by the safer "move to album" mode. */
    const val ALBUM_CREATE = "OXvT9d"
    const val ALBUM_ADD_ITEMS = "E1Cajb"
    const val ALBUM_LIST = "Z5xsfc"

    /** Storage quota, shown on the summary screen. */
    const val STORAGE_QUOTA = "EzwWhf"
}
