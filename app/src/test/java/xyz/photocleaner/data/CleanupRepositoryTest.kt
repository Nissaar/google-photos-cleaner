package xyz.photocleaner.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.photocleaner.api.MediaItem
import xyz.photocleaner.fakes.FakeDecisionDao
import xyz.photocleaner.fakes.FakeIndexDao
import xyz.photocleaner.fakes.FakeRemote
import xyz.photocleaner.fakes.UTC
import xyz.photocleaner.fakes.photo
import xyz.photocleaner.fakes.settings

/**
 * The repository's bookkeeping: what the month tallies say, and what is recorded as
 * done in Google. Wrong tallies mislead; wrong "applied" flags lose track of photos.
 */
class CleanupRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: Settings
    private val dao = FakeDecisionDao()
    private val index = FakeIndexDao()

    /** Eleven photos over three months: more than three pages at the fake's page size. */
    private val library = listOf(
        photo("a1", "2024-03", 20), photo("a2", "2024-03", 10), photo("a3", "2024-03", 1),
        photo("b1", "2024-02", 25), photo("b2", "2024-02", 15), photo("b3", "2024-02", 5),
        photo("b4", "2024-02", 2),
        photo("c1", "2024-01", 28), photo("c2", "2024-01", 18), photo("c3", "2024-01", 8),
        photo("c4", "2024-01", 3),
    )
    private val truth = mapOf("2024-03" to 3, "2024-02" to 4, "2024-01" to 4)

    @Before
    fun setUp() {
        settings = settings(tmp.root, storeScope)
    }

    @After
    fun tearDown() = storeScope.cancel()

    private fun repo(remote: FakeRemote) = CleanupRepository(remote, dao, index, settings)

    private suspend fun CleanupRepository.scan() = refreshMonthCounts(zone = UTC)

    // ---- the month index ----------------------------------------------------------

    @Test
    fun `first pass counts every month`() = runTest {
        repo(FakeRemote(library)).scan()
        assertEquals(truth, index.counts.value)
        assertTrue(index.states[ScanState.SINGLETON]!!.complete)
    }

    @Test
    fun `interrupted first pass resumes without counting anything twice`() = runTest {
        val remote = FakeRemote(library).apply { failOnCall = 3 }
        runCatching { repo(remote).scan() }
        assertFalse(index.states[ScanState.SINGLETON]!!.complete)

        remote.failOnCall = null
        repo(remote).scan()
        assertEquals(truth, index.counts.value)
    }

    @Test
    fun `incremental sync adds only new photos`() = runTest {
        val remote = FakeRemote(library)
        repo(remote).scan()
        remote.library += listOf(photo("d1", "2024-04", 2), photo("d2", "2024-04", 1))

        repo(remote).scan()
        assertEquals(truth + ("2024-04" to 2), index.counts.value)
    }

    /** The bug that doubled month totals: an incremental sync closed part-way. */
    @Test
    fun `interrupted incremental sync leaves the index as it was`() = runTest {
        val remote = FakeRemote(library)
        repo(remote).scan()
        val newPhotos = (1..5).map { photo("d$it", "2024-04", 20 - it) }
        remote.library += newPhotos

        remote.timelineCalls = 0
        remote.failOnCall = 2
        runCatching { repo(remote).scan() }
        assertEquals("nothing saved from a partial run", truth, index.counts.value)
        assertTrue("still complete, so the next run is incremental", index.states[0]!!.complete)

        remote.failOnCall = null
        repo(remote).scan()
        assertEquals(truth + ("2024-04" to 5), index.counts.value)
    }

    // ---- the one-time recount ---------------------------------------------------

    private suspend fun seedInflatedIndex() {
        index.counts.value = truth.mapValues { it.value * 2 }
        index.states[0] = ScanState(newestTimestamp = library.first().timestamp, complete = true, scannedAt = 0)
    }

    @Test
    fun `nothing to recount on a fresh install`() = runTest {
        assertFalse(repo(FakeRemote(library)).recountPending())
        assertTrue(settings.recountDone.first())
    }

    @Test
    fun `recount repairs inflated tallies, keeping the old ones until it finishes`() = runTest {
        seedInflatedIndex()
        val remote = FakeRemote(library).apply { failOnCall = 3 }
        val repo = repo(remote)
        assertTrue(repo.recountPending())

        runCatching { repo.recount(UTC) }
        assertEquals("grid unchanged while recounting", truth.mapValues { it.value * 2 }, index.counts.value)
        assertTrue(repo.recountPending())

        remote.failOnCall = null
        repo.recount(UTC)
        assertEquals(truth, index.counts.value)
        assertFalse(repo.recountPending())
        assertTrue(index.states[ScanState.SINGLETON]!!.complete)
        assertTrue(index.recount.isEmpty())
        assertTrue(ScanState.RECOUNT !in index.states)
    }

    @Test
    fun `trashing during a recount is not undone when it finishes`() = runTest {
        seedInflatedIndex()
        val remote = FakeRemote(library).apply { failOnCall = 2 }
        val repo = repo(remote)
        runCatching { repo.recount(UTC) } // first page, the March photos, is counted

        repo.record(library.first(), Verdict.DELETE) // a1, already counted by the recount
        repo.applyPending()

        remote.failOnCall = null
        repo.recount(UTC)
        assertEquals(truth + ("2024-03" to 2), index.counts.value)
    }

    @Test
    fun `full rescan settles the recount`() = runTest {
        seedInflatedIndex()
        val repo = repo(FakeRemote(library))
        repo.refreshMonthCounts(full = true, zone = UTC)
        assertEquals(truth, index.counts.value)
        assertFalse(repo.recountPending())
    }

    // ---- applying verdicts --------------------------------------------------------

    private suspend fun CleanupRepository.markForDeletion(vararg items: MediaItem) =
        items.forEach { record(it, Verdict.DELETE) }

    @Test
    fun `trash marks only what Google accepted`() = runTest {
        val remote = FakeRemote(library).apply { acceptOnly = 2 }
        val repo = repo(remote)
        repo.scan()
        repo.markForDeletion(library[0], library[1], library[2])

        val result = repo.applyPending()

        assertEquals(2, result.succeeded)
        assertEquals(1, result.failed)
        assertNotNull(result.error)
        assertEquals(2, dao.all.count { it.applied && it.appliedMode == CleanupMode.TRASH })
        assertEquals(1, dao.pendingOnce(Verdict.DELETE).size)
        assertEquals(1, index.counts.value["2024-03"])
    }

    @Test
    fun `album mode marks only what was added, and never shows up as trashed`() = runTest {
        settings.setMode(CleanupMode.ALBUM)
        val remote = FakeRemote(library).apply { acceptOnly = 1 }
        val repo = repo(remote)
        repo.markForDeletion(library[0], library[1])

        val result = repo.applyPending()

        assertEquals(1, result.succeeded)
        assertEquals(1, result.failed)
        assertEquals(listOf("m-a1"), remote.inAlbum)
        assertEquals(CleanupMode.ALBUM, dao.all.single { it.applied }.appliedMode)
        assertEquals("a2", dao.pendingOnce(Verdict.DELETE).single().dedupKey)
        assertTrue("album items are not restorable", repo.appliedDeletes().first().isEmpty())
    }

    @Test
    fun `restore puts back what Google restored and reports the rest`() = runTest {
        val remote = FakeRemote(library)
        val repo = repo(remote)
        repo.scan()
        repo.markForDeletion(library[0], library[1])
        repo.applyPending()
        assertEquals(1, index.counts.value["2024-03"])

        remote.acceptOnly = 1
        val result = repo.restore(repo.appliedDeletes().first())

        assertEquals(1, result.restored)
        assertEquals(1, result.failed)
        assertNotNull(result.error)
        assertEquals("restored item's verdict is gone", 1, dao.all.size)
        assertEquals(2, index.counts.value["2024-03"])
    }

    @Test
    fun `an unexpected error while applying is a result, not a crash`() = runTest {
        val repo = CleanupRepository(FakeRemote(library), object : xyz.photocleaner.data.DecisionDao by dao {
            override suspend fun pendingOnce(verdict: Verdict): List<Decision> = error("disk on fire")
        }, index, settings)

        val result = repo.applyPending()
        assertEquals("disk on fire", result.error)
        assertEquals(0, result.succeeded)
    }
}
