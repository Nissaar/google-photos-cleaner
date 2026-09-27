package xyz.photocleaner.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.photocleaner.data.CleanupRepository
import xyz.photocleaner.data.Verdict
import xyz.photocleaner.fakes.FakeDecisionDao
import xyz.photocleaner.fakes.FakeIndexDao
import xyz.photocleaner.fakes.FakeRemote
import xyz.photocleaner.fakes.photo
import xyz.photocleaner.fakes.settings
import java.time.YearMonth
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class SwipeViewModelTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = FakeDecisionDao()
    private val month = YearMonth.parse("2024-03")
    private val photos = listOf(photo("p1", "2024-03", 20), photo("p2", "2024-03", 10), photo("p3", "2024-03", 5))
    private lateinit var vm: SwipeViewModel
    private lateinit var savedZone: TimeZone

    @Before
    fun setUp() {
        // The deck uses the device zone for month boundaries; pin it.
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Dispatchers.setMain(dispatcher)
        val repo = CleanupRepository(FakeRemote(photos), dao, FakeIndexDao(), settings(tmp.root, storeScope))
        vm = SwipeViewModel(repo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        storeScope.cancel()
        TimeZone.setDefault(savedZone)
    }

    private fun TestScope.loaded() {
        vm.load(month)
        // DataStore reads on its own thread; keep advancing until the deck is dealt.
        repeat(200) {
            advanceUntilIdle()
            if (!vm.state.value.loading && vm.state.value.items.isNotEmpty()) return
            Thread.sleep(5)
        }
        error("deck never loaded")
    }

    @Test
    fun `a verdict for a photo no longer on screen is ignored`() = runTest(dispatcher) {
        loaded()
        vm.keep("p1")
        // The fly-off animation of the earlier swipe finishing late, naming p1 again.
        vm.delete("p1")
        advanceUntilIdle()

        assertEquals(1, vm.state.value.index)
        assertEquals(mapOf("p1" to Verdict.KEEP), dao.all.associate { it.dedupKey to it.verdict })
    }

    @Test
    fun `a quick undo cannot leave the verdict behind`() = runTest(dispatcher) {
        loaded()
        dao.upsertDelayMs = 100 // the insert is slow; the undo's delete is not
        vm.delete("p1")
        vm.undo()
        advanceUntilIdle()

        assertEquals(0, vm.state.value.index)
        assertTrue("undone verdict must not persist", dao.all.isEmpty())
    }
}
