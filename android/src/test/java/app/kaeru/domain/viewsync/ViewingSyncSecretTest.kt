package app.kaeru.domain.viewsync

import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.SecretTitle
import app.kaeru.shared.domain.sync.SyncSecret
import app.kaeru.shared.domain.sync.SyncTitle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun `a tombstone from another device over a secret title reads as watched through, and goes nowhere`() = runTest {
        local.secretRows[7] = SecretTitle(7, true, 9, Instant.ofEpochMilli(BASE - 10_000))
        local.announced[7] = 12
        // Stamped after the tombstone: switched or counted here since, so left as it is.
        local.secretRows[8] = SecretTitle(8, true, 3, Instant.ofEpochMilli(BASE - 500))
        local.announced[8] = 12
        // No card here, so no length known: left as it is.
        local.secretRows[9] = SecretTitle(9, true, 4, Instant.ofEpochMilli(BASE - 10_000))
        for (id in listOf("7", "8", "9")) api.document(ACCOUNT)[id] = SyncTitle(gone = BASE - 1_000)
        state.markSeeded(ACCOUNT)

        started()
        advanceTimeBy(10 * 60_000L)
        runCurrent()

        // Every announced episode watched, stamped as it was here: nothing new for the server.
        assertEquals(SecretTitle(7, true, 12, Instant.ofEpochMilli(BASE - 10_000)), local.secretRows[7])
        assertEquals(SecretTitle(8, true, 3, Instant.ofEpochMilli(BASE - 500)), local.secretRows[8])
        assertEquals(SecretTitle(9, true, 4, Instant.ofEpochMilli(BASE - 10_000)), local.secretRows[9])
        assertTrue(api.posts.isEmpty())
        assertTrue(state.outbox(ACCOUNT).isEmpty())
    }

    @Test
    fun `a title watched through that way leaves one tombstone of this device's own, and then nothing more`() = runTest {
        local.secretRows[7] = SecretTitle(7, true, 9, Instant.ofEpochMilli(BASE - 10_000))
        local.announced[7] = 12
        local.listed.value = mapOf(7 to ListStatus.SECRET, 1 to ListStatus.WATCHING)
        api.document(ACCOUNT)["7"] = SyncTitle(gone = BASE - 1_000)
        state.markSeeded(ACCOUNT)
        val sync = started()
        assertEquals(12, local.secretRows[7]?.watched)

        // The list reads a finished show watched through as completed (RoomLocalViewing.statuses).
        local.listed.value = mapOf(7 to ListStatus.COMPLETED, 1 to ListStatus.WATCHING)
        runCurrent()

        assertEquals(listOf(ACCOUNT to mapOf("7" to SyncTitle(gone = BASE))), api.posts)
        // Read again later, the server's tombstone is this device's own: nothing changes, nothing goes.
        sync.wentToBackground()
        advanceTimeBy(ViewingSync.PULL_AFTER_BACKGROUND_MS)
        sync.becameActive()
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertEquals(2, api.gets)
        assertEquals(1, api.posts.size)
        assertEquals(SecretTitle(7, true, 12, Instant.ofEpochMilli(BASE - 10_000)), local.secretRows[7])
        assertEquals(SyncTitle(gone = BASE), api.document(ACCOUNT)["7"])
    }

    private companion object {
        const val ACCOUNT = 42L
        const val BASE = 1_790_000_000_000L
    }
}
