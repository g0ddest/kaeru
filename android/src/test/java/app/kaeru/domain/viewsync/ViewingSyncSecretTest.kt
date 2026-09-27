package app.kaeru.domain.viewsync

import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.SecretTitle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** «Смотреть украдкой» through `/sync`: sent only while sync is on, taken when the server's is newer. */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewingSyncSecretTest {
    private val accounts = MutableStateFlow<Long?>(ACCOUNT)
    private val enabled = MutableStateFlow(true)
    private val online = MutableStateFlow(true)
    private val api = FakeSyncApi { accounts.value }
    private val local = FakeLocalViewing()
    private val state = FakeSyncState()
    private val events = ViewingSyncEvents()

    private fun TestScope.now(): Long = BASE + testScheduler.currentTime

    private fun TestScope.started(): ViewingSync = ViewingSync(
        api = api, local = local, store = state, accounts = accounts, enabled = enabled, online = online,
        events = events, scope = backgroundScope, now = { now() },
    ).also {
        it.start()
        runCurrent()
    }

    private fun TestScope.secret(animeId: Int, on: Boolean, watched: Int): SecretTitle {
        val row = SecretTitle(animeId, on, watched, Instant.ofEpochMilli(now()))
        local.secretRows[animeId] = row
        events.secretChanged(row)
        return row
    }

    @Test
    fun `a secret change goes to the worker as secret while sync is on`() = runTest {
        started()
        advanceTimeBy(1_000)
        secret(7, on = true, watched = 3)
        runCurrent()
        advanceTimeBy(ViewingSync.PUSH_EVERY_MS)
        runCurrent()

        val sent = api.posts.last().second.getValue("7")
        assertEquals(SyncSecret(on = true, watched = 3, at = BASE + 1_000), sent.secret)
        assertEquals(SyncSecret(true, 3, BASE + 1_000), api.document(ACCOUNT)["7"]?.secret)
    }

    @Test
    fun `with sync off a secret change is neither sent nor queued`() = runTest {
        enabled.value = false
        started()
        secret(7, on = true, watched = 3)
        runCurrent()
        advanceTimeBy(10 * 60_000L)

        assertEquals(0, api.requests)
        assertTrue(state.outbox(ACCOUNT).isEmpty())
    }

    @Test
    fun `turning sync on sends the secrets the device already kept`() = runTest {
        enabled.value = false
        local.secretRows[7] = SecretTitle(7, true, 4, Instant.ofEpochMilli(BASE - 5_000))
        started()

        enabled.value = true
        runCurrent()

        assertEquals(SyncSecret(true, 4, BASE - 5_000), api.posts.single().second.getValue("7").secret)
    }

    @Test
    fun `a newer secret from the server is applied, an older one is not`() = runTest {
        local.secretRows[7] = SecretTitle(7, true, 2, Instant.ofEpochMilli(BASE - 10_000))
        local.secretRows[8] = SecretTitle(8, true, 9, Instant.ofEpochMilli(BASE - 1_000))
        api.document(ACCOUNT)["7"] = SyncTitle(secret = SyncSecret(true, 5, BASE - 5_000))
        api.document(ACCOUNT)["8"] = SyncTitle(secret = SyncSecret(false, 1, BASE - 20_000))
        api.document(ACCOUNT)["9"] = SyncTitle(secret = SyncSecret(true, 6, BASE - 3_000))
        // Seeded already, so only the read is under test.
        state.markSeeded(ACCOUNT)

        started()

        assertEquals(SecretTitle(7, true, 5, Instant.ofEpochMilli(BASE - 5_000)), local.secretRows[7])
        assertEquals(SecretTitle(8, true, 9, Instant.ofEpochMilli(BASE - 1_000)), local.secretRows[8])
        assertEquals(SecretTitle(9, true, 6, Instant.ofEpochMilli(BASE - 3_000)), local.secretRows[9])
        assertEquals(setOf(7, 9), local.applied.flatMap { it.secrets }.map { it.animeId }.toSet())
    }

    @Test
    fun `a secret turned off on another device is applied as off`() = runTest {
        local.secretRows[7] = SecretTitle(7, true, 2, Instant.ofEpochMilli(BASE - 10_000))
        api.document(ACCOUNT)["7"] = SyncTitle(secret = SyncSecret(false, 5, BASE - 5_000))
        state.markSeeded(ACCOUNT)

        started()

        assertEquals(false, local.secretRows[7]?.on)
    }

    @Test
    fun `a finished secret title leaves a tombstone, as a completed one does`() = runTest {
        local.listed.value = mapOf(7 to ListStatus.SECRET, 1 to ListStatus.WATCHING)
        started()
        advanceTimeBy(ViewingSync.PUSH_EVERY_MS)
        runCurrent()
        secret(7, on = true, watched = 12)
        local.listed.value = mapOf(7 to ListStatus.COMPLETED, 1 to ListStatus.WATCHING)
        runCurrent()
        advanceTimeBy(ViewingSync.PUSH_EVERY_MS)
        runCurrent()

        val stored = api.document(ACCOUNT).getValue("7")
        assertEquals(BASE + ViewingSync.PUSH_EVERY_MS, stored.gone)
    }

    @Test
    fun `merge and without treat secret like any other field`() {
        val older = SyncTitle(secret = SyncSecret(true, 2, 10))
        val newer = SyncTitle(secret = SyncSecret(false, 5, 20))
        assertEquals(newer.secret, SyncMerge.merge(older, newer).secret)
        assertEquals(newer.secret, SyncMerge.merge(newer, older).secret)
        assertNull(SyncMerge.without(older, newer).secret)
        assertEquals(newer.secret, SyncMerge.without(newer, older).secret)
        assertNull(SyncMerge.without(newer, SyncTitle(gone = 20)).secret)
    }

    private companion object {
        const val ACCOUNT = 42L
        const val BASE = 1_790_000_000_000L
    }
}
