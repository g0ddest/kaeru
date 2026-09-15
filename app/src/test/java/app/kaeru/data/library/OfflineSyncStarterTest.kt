package app.kaeru.data.library

import app.kaeru.domain.connectivity.Connectivity
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.sync.OutboxSyncer
import app.kaeru.domain.sync.ReplayOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import javax.inject.Provider

/** The one thing that notices the network is back and empties the queue into Shikimori. */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSyncStarterTest {
    private val online = MutableStateFlow(false)
    private val connectivity = object : Connectivity {
        override val online: Flow<Boolean> = this@OfflineSyncStarterTest.online
    }
    private var replays = 0
    private var failNext = false
    private var refusedNext = emptySet<Int>()
    private val refreshed = mutableListOf<Int>()

    private val syncer = OutboxSyncer {
        replays++
        if (failNext) {
            Result.failure(IOException("still gone"))
        } else {
            Result.success(ReplayOutcome(sent = 1, refused = refusedNext))
        }
    }

    /** Only `refreshAnime` matters here: it is what a refused write asks the library for. */
    private val library = object : LibraryRepository {
        override fun observeLibrary(): Flow<List<LibraryEntry>> = flowOf(emptyList())
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = flowOf(null)
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = flowOf(null)
        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
        override suspend fun refreshAnime(id: Int): Result<Unit> {
            refreshed += id
            return Result.success(Unit)
        }
        override suspend fun search(query: String): Result<List<Anime>> = Result.success(emptyList())
        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> = Result.success(Unit)
        override suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit> = Result.success(Unit)
    }

    private fun starter(scope: TestScope, watch: Connectivity = connectivity) =
        OfflineSyncStarter(watch, syncer, Provider { library }, scope)

    @Test
    fun `nothing is sent while the device is offline`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        starter(scope).start()
        scope.runCurrent()

        assertEquals(0, replays)
    }

    @Test
    fun `the queue is emptied as soon as the network comes back`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        starter(scope).start()
        scope.runCurrent()

        online.value = true
        scope.runCurrent()

        assertEquals(1, replays)
    }

    @Test
    fun `every return of the network is another chance to send`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        starter(scope).start()
        online.value = true
        scope.runCurrent()
        online.value = false
        scope.runCurrent()
        online.value = true
        scope.runCurrent()

        assertEquals(2, replays)
    }

    @Test
    fun `a replay that fails does not stop the next one`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        starter(scope).start()
        failNext = true
        online.value = true
        scope.runCurrent()
        online.value = false
        failNext = false
        scope.runCurrent()
        online.value = true
        scope.runCurrent()

        assertEquals(2, replays)
    }

    @Test
    fun `starting twice still watches once`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val starter = starter(scope)
        starter.start()
        starter.start()
        online.value = true
        scope.runCurrent()

        assertEquals(1, replays)
    }

    @Test
    fun `a refused write sends the caller back to the server for that title`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        refusedNext = setOf(10, 20)
        starter(scope).start()
        online.value = true
        scope.runCurrent()

        assertEquals(listOf(10, 20), refreshed)
    }

    @Test
    fun `a write that asks for a drain gets one without waiting for the network to change`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        online.value = true
        val starter = starter(scope)
        starter.start()
        scope.runCurrent()
        assertEquals(1, replays)

        starter.requestReplay()
        scope.runCurrent()

        assertEquals(2, replays)
    }

    @Test
    fun `a drain asked for with no network is not attempted`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val starter = starter(scope)
        starter.start()

        starter.requestReplay()
        scope.runCurrent()

        assertEquals(0, replays)
    }

    @Test
    fun `a watch that throws is picked up again rather than abandoned`() = runTest {
        var collections = 0
        val flaky = object : Connectivity {
            override val online: Flow<Boolean> = flow {
                collections++
                if (collections == 1) throw IllegalStateException("callback died")
                emit(true)
            }
        }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        starter(scope, flaky).start()
        scope.runCurrent()
        assertEquals(0, replays)

        scope.advanceTimeBy(6_000)
        scope.runCurrent()

        assertEquals(1, replays)
    }
}
