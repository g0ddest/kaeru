package app.kaeru.domain.viewsync

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.ListStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Viewing sync against a worker, a device and a clock that the test owns: what is read and taken,
 * what is sent and when, and what is never sent at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewingSyncTest {
    private val accounts = MutableStateFlow<Long?>(ACCOUNT)
    private val enabled = MutableStateFlow(true)
    private val online = MutableStateFlow(true)
    private val api = FakeSyncApi { accounts.value }
    private val local = FakeLocalViewing()
    private val state = FakeSyncState()
    private val events = ViewingSyncEvents()

    private fun TestScope.now(): Long = BASE + testScheduler.currentTime

    private fun TestScope.started(): ViewingSync = ViewingSync(
        api = api,
        local = local,
        store = state,
        accounts = accounts,
        enabled = enabled,
        online = online,
        events = events,
        scope = backgroundScope,
        now = { now() },
    ).also {
        it.start()
        runCurrent()
    }

    private fun TestScope.watched(animeId: Int, episode: Int, positionMs: Long = 60_000): EpisodeProgress {
        val row = EpisodeProgress(animeId, episode, positionMs, 1_440_000, Instant.ofEpochMilli(now()))
        local.put(row)
        events.positionSaved(row)
        return row
    }

    private fun sentEpisodes(post: Int): Map<String, Set<String>> =
        api.posts[post].second.mapValues { (_, title) -> title.eps.orEmpty().keys }

    // --- the switch -----------------------------------------------------------------------------

    @Test
    fun `with the switch off nothing is read, sent or queued`() = runTest {
        enabled.value = false
        local.put(EpisodeProgress(5, 3, 90_000, 1_440_000, Instant.ofEpochMilli(BASE - 1_000)))
        started()
        watched(5, 4)
        events.push(SyncReason.PAUSE)
        runCurrent()
        advanceTimeBy(10 * 60_000L)

        assertEquals(0, api.requests)
        assertTrue(state.outbox(ACCOUNT).isEmpty())
    }

    @Test
    fun `turning it on reads the document, then sends once what the device already had`() = runTest {
        enabled.value = false
        local.put(EpisodeProgress(5, 3, 90_000, 1_440_000, Instant.ofEpochMilli(BASE - 1_000)))
        local.remembered[5] = RememberedDub(610, "AniLibria.TV")
        started()
        assertEquals(0, api.requests)

        enabled.value = true
        runCurrent()

        assertEquals(1, api.gets)
        assertEquals(1, api.posts.size)
        val sent = api.posts.single().second.getValue("5")
        assertEquals(SyncPosition(90_000, 1_440_000, BASE - 1_000), sent.eps?.get("3"))
        // Chosen before there were stamps: the oldest a dub can be, so any other device's wins.
        assertEquals(SyncDub(610, "AniLibria.TV", 0), sent.dub)
        assertTrue(ACCOUNT in state.seeded())
    }

    @Test
    fun `turning it off stops every request and drops what was waiting`() = runTest {
        started()
        watched(5, 1)
        runCurrent()
        val before = api.requests
        advanceTimeBy(10_000)
        watched(5, 2)
        runCurrent()
        assertFalse(state.outbox(ACCOUNT).isEmpty())

        enabled.value = false
        runCurrent()
        watched(5, 3)
        events.push(SyncReason.LEAVING)
        runCurrent()
        advanceTimeBy(10 * 60_000L)

        assertEquals(before, api.requests)
        assertTrue(state.outbox(ACCOUNT).isEmpty())
        // Back on, the device's positions go once in full again: nothing done meanwhile was queued.
        assertFalse(ACCOUNT in state.seeded())
    }

    // --- reading --------------------------------------------------------------------------------

    @Test
    fun `a read takes positions and dubs only where they are newer than this device's`() = runTest {
        enabled.value = false
        started()
        local.put(
            EpisodeProgress(5, 1, 10_000, 1_440_000, Instant.ofEpochMilli(100)),
            EpisodeProgress(5, 2, 20_000, 1_440_000, Instant.ofEpochMilli(300)),
        )
        local.remembered[5] = RememberedDub(1, "Here")
        local.remembered[6] = RememberedDub(2, "Mine")
        state.markSeeded(ACCOUNT)
        state.setDubStamps(ACCOUNT, mapOf(6 to 900L))
        api.document(ACCOUNT)["5"] = SyncTitle(
            dub = SyncDub(610, "AniLibria.TV", 500),
            eps = mapOf(
                "1" to SyncPosition(700_000, 1_440_000, 200),
                "2" to SyncPosition(5_000, 1_440_000, 250),
                "3" to SyncPosition(1_000, 0, 999),
            ),
        )
        api.document(ACCOUNT)["6"] = SyncTitle(dub = SyncDub(3, "Theirs", 800))
        api.document(ACCOUNT)["7"] = SyncTitle(eps = mapOf("4" to SyncPosition(30_000, 1_400_000, 400)))

        enabled.value = true
        runCurrent()

        assertEquals(700_000L, local.rows.getValue(5 to 1).positionMs)
        assertEquals(20_000L, local.rows.getValue(5 to 2).positionMs)
        // No length: nothing to resume from.
        assertNull(local.rows[5 to 3])
        assertEquals(Instant.ofEpochMilli(400), local.rows.getValue(7 to 4).updatedAt)
        // Remembered before stamps: older than anything the server has.
        assertEquals(RememberedDub(610, "AniLibria.TV"), local.remembered[5])
        // Chosen here after the server's.
        assertEquals(RememberedDub(2, "Mine"), local.remembered[6])
        // Nothing here is news to the server, so nothing goes back.
        assertTrue(api.posts.isEmpty())
    }

    @Test
    fun `a tombstone from the server removes positions saved at or before it`() = runTest {
        local.put(
            EpisodeProgress(5, 1, 10_000, 1_440_000, Instant.ofEpochMilli(100)),
            EpisodeProgress(5, 2, 10_000, 1_440_000, Instant.ofEpochMilli(200)),
            EpisodeProgress(5, 3, 10_000, 1_440_000, Instant.ofEpochMilli(300)),
        )
        state.markSeeded(ACCOUNT)
        api.document(ACCOUNT)["5"] = SyncTitle(gone = 200)

        started()

        assertEquals(setOf(5 to 3), local.rows.keys)
    }

    @Test
    fun `coming back after five minutes in the background reads again, not sooner`() = runTest {
        val sync = started()
        assertEquals(1, api.gets)

        sync.wentToBackground()
        runCurrent()
        advanceTimeBy(4 * 60_000L)
        sync.becameActive()
        runCurrent()
        assertEquals(1, api.gets)

        sync.wentToBackground()
        runCurrent()
        advanceTimeBy(5 * 60_000L)
        sync.becameActive()
        runCurrent()
        assertEquals(2, api.gets)
    }

    @Test
    fun `the document a write answers with is taken as well`() = runTest {
        started()
        api.document(ACCOUNT)["9"] = SyncTitle(eps = mapOf("1" to SyncPosition(50_000, 1_400_000, 10)))
        watched(5, 1)
        runCurrent()

        assertEquals(1, api.posts.size)
        assertEquals(50_000L, local.rows.getValue(9 to 1).positionMs)
    }

    // --- writing --------------------------------------------------------------------------------

    @Test
    fun `positions go in batches, at most one a minute`() = runTest {
        started()
        watched(5, 1)
        runCurrent()
        assertEquals(1, api.posts.size)

        advanceTimeBy(10_000)
        watched(5, 2)
        runCurrent()
        advanceTimeBy(10_000)
        watched(5, 3)
        runCurrent()
        advanceTimeBy(39_000)
        assertEquals(1, api.posts.size)

        advanceTimeBy(1_001)
        assertEquals(2, api.posts.size)
        assertEquals(mapOf("5" to setOf("2", "3")), sentEpisodes(1))
        assertTrue(state.outbox(ACCOUNT).isEmpty())
    }

    @Test
    fun `a pause, another episode, leaving the player and the background send at once`() = runTest {
        val sync = started()
        watched(5, 1)
        runCurrent()
        var expected = 1
        val pushes: List<() -> Unit> = SyncReason.entries.map { reason -> { events.push(reason) } } +
            { sync.wentToBackground() }
        for ((index, push) in pushes.withIndex()) {
            advanceTimeBy(5_000)
            watched(5, 2 + index)
            runCurrent()
            assertEquals(expected, api.posts.size)
            push()
            runCurrent()
            expected++
            assertEquals(expected, api.posts.size)
            assertEquals(mapOf("5" to setOf((2 + index).toString())), sentEpisodes(expected - 1))
        }
    }

    @Test
    fun `nothing is sent when nothing changed`() = runTest {
        started()
        events.push(SyncReason.PAUSE)
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        assertEquals(0, api.attempts)
    }

    @Test
    fun `a failed batch stays and is tried again a minute later`() = runTest {
        started()
        api.failure = SyncFailure(SyncFailure.Kind.OFFLINE)
        watched(5, 1)
        runCurrent()
        assertEquals(1, api.attempts)
        assertTrue(api.posts.isEmpty())

        api.failure = null
        advanceTimeBy(59_000)
        assertTrue(api.posts.isEmpty())
        advanceTimeBy(1_001)
        assertEquals(mapOf("5" to setOf("1")), sentEpisodes(0))
        assertTrue(state.outbox(ACCOUNT).isEmpty())
    }

    @Test
    fun `the network coming back sends what waited for it`() = runTest {
        started()
        api.failure = SyncFailure(SyncFailure.Kind.OFFLINE)
        online.value = false
        watched(5, 1)
        runCurrent()
        api.failure = null
        advanceTimeBy(20_000)

        online.value = true
        runCurrent()

        assertEquals(1, api.posts.size)
    }

    @Test
    fun `the queue outlives the process`() = runTest {
        started()
        api.failure = SyncFailure(SyncFailure.Kind.UNAVAILABLE)
        watched(5, 1)
        runCurrent()
        assertTrue(api.posts.isEmpty())

        // Another launch, with the same preferences behind it.
        api.failure = null
        started()

        assertEquals(mapOf("5" to setOf("1")), sentEpisodes(0))
    }

    @Test
    fun `what the device had before sync goes once per account`() = runTest {
        local.put(EpisodeProgress(5, 3, 90_000, 1_440_000, Instant.ofEpochMilli(BASE - 1_000)))
        started()
        assertEquals(1, api.posts.size)

        started()
        assertEquals(1, api.posts.size)
    }

    // --- accounts -------------------------------------------------------------------------------

    @Test
    fun `nothing is read or sent without an account`() = runTest {
        accounts.value = null
        started()
        watched(5, 1)
        events.push(SyncReason.LEAVING)
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        assertEquals(0, api.requests)
    }

    @Test
    fun `one account's changes never go out under another`() = runTest {
        local.owner = ACCOUNT
        started()
        api.failure = SyncFailure(SyncFailure.Kind.OFFLINE)
        watched(5, 1)
        runCurrent()
        assertFalse(state.outbox(ACCOUNT).isEmpty())

        // Another viewer signs in; the sign-in empties this device's tables first.
        accounts.value = null
        runCurrent()
        local.rows.clear()
        local.owner = OTHER
        api.failure = null
        accounts.value = OTHER
        runCurrent()
        watched(8, 1)
        runCurrent()
        advanceTimeBy(10 * 60_000L)

        assertTrue(api.posts.isNotEmpty())
        for ((who, titles) in api.posts) {
            assertEquals(OTHER, who)
            assertFalse("5" in titles)
        }
    }

    // --- finished titles ------------------------------------------------------------------------

    @Test
    fun `a title turning completed leaves a tombstone, one already completed does not`() = runTest {
        local.listed.value = mapOf(5 to ListStatus.COMPLETED, 6 to ListStatus.WATCHING)
        started()
        advanceTimeBy(1_000)

        local.listed.value = mapOf(5 to ListStatus.COMPLETED, 6 to ListStatus.COMPLETED)
        runCurrent()

        val sent = api.posts.single().second
        assertEquals(setOf("6"), sent.keys)
        assertEquals(now(), sent.getValue("6").gone)

        // Its positions stop going anywhere.
        advanceTimeBy(60_000)
        watched(6, 12)
        runCurrent()
        advanceTimeBy(5 * 60_000L)
        assertEquals(1, api.posts.size)
    }

    @Test
    fun `a status turned back before the tombstone went out takes it back`() = runTest {
        local.listed.value = mapOf(6 to ListStatus.WATCHING)
        started()
        watched(5, 1)
        runCurrent()
        assertEquals(1, api.posts.size)

        advanceTimeBy(1_000)
        local.listed.value = mapOf(6 to ListStatus.COMPLETED)
        runCurrent()
        local.listed.value = mapOf(6 to ListStatus.WATCHING)
        runCurrent()
        advanceTimeBy(5 * 60_000L)

        assertEquals(1, api.posts.size)
    }

    private companion object {
        const val ACCOUNT = 42L
        const val OTHER = 77L
        const val BASE = 1_790_000_000_000L
    }
}
