package xyz.photocleaner.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/**
 * Drives what each month tile says and whether long-press offers a reset,
 * so the edge cases are worth pinning down.
 */
class MonthEntryTest {

    private val july = YearMonth.of(2024, 7)

    @Test
    fun `an untouched month is neither started nor finished`() {
        val entry = MonthEntry(july, total = 40, reviewed = 0)
        assertFalse(entry.started)
        assertFalse(entry.fullyReviewed)
        assertEquals(40, entry.remaining)
    }

    @Test
    fun `a partly reviewed month reports what is left`() {
        val entry = MonthEntry(july, total = 40, reviewed = 12)
        assertTrue(entry.started)
        assertFalse(entry.fullyReviewed)
        assertEquals(28, entry.remaining)
    }

    @Test
    fun `a fully reviewed month is marked done with nothing left`() {
        val entry = MonthEntry(july, total = 40, reviewed = 40)
        assertTrue(entry.started)
        assertTrue(entry.fullyReviewed)
        assertEquals(0, entry.remaining)
    }

    @Test
    fun `more reviewed than counted still reads as done, never negative`() {
        // Happens when photos are deleted elsewhere between scans: the cached tally
        // drops below the number of verdicts already recorded.
        val entry = MonthEntry(july, total = 5, reviewed = 9)
        assertTrue(entry.fullyReviewed)
        assertEquals(0, entry.remaining)
    }

    @Test
    fun `an empty month is not reported as reviewed`() {
        // Nothing to review is not the same as having reviewed everything, and a tile
        // claiming "All reviewed" over zero photos would be misleading.
        val entry = MonthEntry(july, total = 0, reviewed = 0)
        assertFalse(entry.fullyReviewed)
        assertFalse(entry.started)
        assertEquals(0, entry.remaining)
    }
}
