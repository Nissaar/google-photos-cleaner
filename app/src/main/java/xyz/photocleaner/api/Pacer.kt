package xyz.photocleaner.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Keeps our request pattern inside the envelope a real person browsing
 * photos.google.com would produce.
 *
 * This is the main defence against the realistic failure mode. Google's observed
 * response to bulk-deletion tools is rate-limiting and temporary blocks, not
 * account action — so the goal is simply never to trip that threshold.
 *
 * Three rules:
 *   1. A minimum gap between calls, with jitter (fixed intervals look automated).
 *   2. Mutations are capped per batch, matching what the web UI sends.
 *   3. A 429 escalates a cooldown that decays only on sustained success.
 */
class Pacer {
    private val mutex = Mutex()
    private var lastCallAt = 0L
    private var consecutiveFailures = 0

    companion object {
        /** The web client fires timeline pages roughly this far apart while scrolling. */
        const val MIN_READ_GAP_MS = 350L

        /** Mutations are rarer and more conspicuous, so space them out further. */
        const val MIN_WRITE_GAP_MS = 1_200L

        /**
         * Trash batch size. The web UI sends selections of this order; much larger
         * batches are the single most obvious automation signal.
         */
        const val MAX_TRASH_BATCH = 50

        /** Pause inserted between mutation batches. */
        const val BATCH_REST_MS = 2_500L
    }

    suspend fun beforeRead() = pace(MIN_READ_GAP_MS)
    suspend fun beforeWrite() = pace(MIN_WRITE_GAP_MS)

    private suspend fun pace(minGapMs: Long) {
        val waitFor: Long
        mutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastCallAt
            // +-25% jitter so the cadence never looks machine-regular.
            val jitter = Random.nextLong(-minGapMs / 4, minGapMs / 4 + 1)
            val target = (minGapMs + jitter).coerceAtLeast(100L)
            val penalty = cooldownMs()
            waitFor = (target - elapsed).coerceAtLeast(0L) + penalty
            lastCallAt = now + waitFor
        }
        if (waitFor > 0) delay(waitFor)
    }

    /** Exponential cooldown after rate-limiting, capped at one minute. */
    private fun cooldownMs(): Long =
        if (consecutiveFailures == 0) 0L
        else (2_000L * (1L shl (consecutiveFailures - 1).coerceAtMost(5))).coerceAtMost(60_000L)

    suspend fun onRateLimited() = mutex.withLock { consecutiveFailures++ }

    suspend fun onSuccess() = mutex.withLock {
        if (consecutiveFailures > 0) consecutiveFailures--
    }

    suspend fun restBetweenBatches() = delay(BATCH_REST_MS + Random.nextLong(0, 1_200))
}
