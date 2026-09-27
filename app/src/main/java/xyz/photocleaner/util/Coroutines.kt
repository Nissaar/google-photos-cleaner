package xyz.photocleaner.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` for suspending code.
 *
 * Plain runCatching also catches CancellationException, which turns "the user left
 * the screen" into an error to report and lets cancelled work carry on. Cancellation
 * is rethrown here; everything else becomes a failed Result.
 */
inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
