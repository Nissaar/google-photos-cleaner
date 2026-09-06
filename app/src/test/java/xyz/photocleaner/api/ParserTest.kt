package xyz.photocleaner.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser reads Google's positional arrays, where a shifted index would mean
 * acting on the wrong photo. These tests pin the indices that matter.
 */
class ParserTest {

    private val json = Json { isLenient = true }

    private fun parse(text: String) = json.parseToJsonElement(text)

    /** A photo entry shaped like a real `lcxiM` response element. */
    private val photoItem = """
        [
          "AF1QipMediaKey123",
          ["https://lh3.googleusercontent.com/abc", 4032, 3024],
          1719830400000,
          "dedup-key-abc",
          7200,
          1719830500000,
          [],
          [[1],[2]],
          null, null, null, null,
          [10],
          false,
          {"163238866":[true]}
        ]
    """.trimIndent()

    /** A video entry: same shape, plus a duration under key 76647426. */
    private val videoItem = """
        [
          "AF1QipVideoKey456",
          ["https://lh3.googleusercontent.com/xyz", 1920, 1080],
          1719916800000,
          "dedup-key-video",
          7200,
          1719916900000,
          [], [[1]], null, null, null, null, [10], true,
          {"76647426":[15000]}
        ]
    """.trimIndent()

    @Test
    fun `reads the identifying keys from the right indices`() {
        val item = Parser.parseMediaItem(parse(photoItem))
        assertNotNull(item)
        item!!
        // Index 0 and index 3 are different keys with different uses; mixing them up
        // would send album operations the trash key and vice versa.
        assertEquals("AF1QipMediaKey123", item.mediaKey)
        assertEquals("dedup-key-abc", item.dedupKey)
        assertEquals(1719830400000L, item.timestamp)
        assertEquals(1719830500000L, item.creationTimestamp)
        assertEquals(7200L, item.timezoneOffsetSec)
    }

    @Test
    fun `reads thumbnail and dimensions`() {
        val item = Parser.parseMediaItem(parse(photoItem))!!
        assertEquals("https://lh3.googleusercontent.com/abc", item.thumbBaseUrl)
        assertEquals(4032, item.width)
        assertEquals(3024, item.height)
    }

    @Test
    fun `sizing suffix requests an unauthenticated render`() {
        val item = Parser.parseMediaItem(parse(photoItem))!!
        // `-k-no` strips the watermark; the authuser query is what actually lifts the
        // auth requirement. Without it the host returns nothing and cards render blank.
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w300-h300-k-no?authuser=0",
            item.thumbUrl(300, 300),
        )
        assertTrue(item.previewUrl().endsWith("-k-no?authuser=0"))
    }

    @Test
    fun `image urls carry the signed-in account index`() {
        val item = Parser.parseMediaItem(parse(photoItem))!!
        // A multi-account user on /u/1/ must request authuser=1, or the image
        // resolves against the wrong account and comes back empty.
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w300-h300-k-no?authuser=1",
            item.thumbUrl(300, 300, authUser = 1),
        )
        assertTrue(item.previewUrl(authUser = 2).endsWith("?authuser=2"))
    }

    @Test
    fun `detects video by presence of a duration`() {
        val photo = Parser.parseMediaItem(parse(photoItem))!!
        assertFalse(photo.isVideo)
        assertNull(photo.durationMs)

        val video = Parser.parseMediaItem(parse(videoItem))!!
        assertTrue(video.isVideo)
        assertEquals(15000L, video.durationMs)
    }

    @Test
    fun `reads favourite and archived flags`() {
        val photo = Parser.parseMediaItem(parse(photoItem))!!
        assertTrue(photo.isFavorite)
        assertFalse(photo.isArchived)

        val video = Parser.parseMediaItem(parse(videoItem))!!
        assertFalse(video.isFavorite)
        assertTrue(video.isArchived)
    }

    @Test
    fun `drops entries missing a dedupKey rather than guessing`() {
        // A null dedupKey must never fall back to another field — that would trash
        // whatever item that key happened to belong to.
        val noDedup = """["mediaKey",["https://x",10,10],1719830400000,null,0,0]"""
        assertNull(Parser.parseMediaItem(parse(noDedup)))

        val noThumb = """["mediaKey",null,1719830400000,"dedup",0,0]"""
        assertNull(Parser.parseMediaItem(parse(noThumb)))

        val noTimestamp = """["mediaKey",["https://x",10,10],null,"dedup",0,0]"""
        assertNull(Parser.parseMediaItem(parse(noTimestamp)))
    }

    @Test
    fun `survives a truncated entry without throwing`() {
        assertNull(Parser.parseMediaItem(parse("""["only-one-field"]""")))
        assertNull(Parser.parseMediaItem(parse("""[]""")))
        assertNull(Parser.parseMediaItem(parse("""null""")))
    }

    @Test
    fun `parses a timeline page with its paging cursor`() {
        val page = parse("""[[$photoItem,$videoItem],"next-page-token","1719830400000"]""")
        val result = Parser.parseTimelinePage(page)

        assertEquals(2, result.items.size)
        assertEquals("next-page-token", result.nextPageId)
        assertEquals(1719830400000L, result.lastItemTimestamp)
    }

    @Test
    fun `a final page reports no next cursor`() {
        val page = parse("""[[$photoItem],null,null]""")
        val result = Parser.parseTimelinePage(page)

        assertEquals(1, result.items.size)
        assertNull(result.nextPageId)
    }

    @Test
    fun `an empty response yields an empty page rather than an error`() {
        assertEquals(0, Parser.parseTimelinePage(parse("null")).items.size)
        assertEquals(0, Parser.parseTimelinePage(parse("[]")).items.size)
        assertEquals(0, Parser.parseTimelinePage(parse("""[null,null,null]""")).items.size)
    }

    @Test
    fun `a page containing one malformed entry keeps the good ones`() {
        val page = parse("""[[$photoItem,["broken"],$videoItem],null,null]""")
        val result = Parser.parseTimelinePage(page)
        assertEquals(2, result.items.size)
    }

    @Test
    fun `parses album listings`() {
        val albums = """
            [[
              ["albumKey1",["https://thumb1"],null,null,null,null,[],
               {"72930366":[null,"To Be Deleted",[],42]}],
              ["albumKey2",["https://thumb2"],null,null,null,null,[],
               {"72930366":[null,"Holidays",[],7]}]
            ],"next"]
        """.trimIndent()
        val result = Parser.parseAlbums(parse(albums))

        assertEquals(2, result.size)
        assertEquals("albumKey1" to "To Be Deleted", result[0])
        assertEquals("albumKey2" to "Holidays", result[1])
    }

    @Test
    fun `parses storage quota`() {
        val quota = Parser.parseStorageQuota(parse("""[["1500000000"],"16000000000"]"""))
        assertNotNull(quota)
        assertEquals(1_500_000_000L, quota!!.usedBytes)
        assertEquals(16_000_000_000L, quota.totalBytes)
    }
}
