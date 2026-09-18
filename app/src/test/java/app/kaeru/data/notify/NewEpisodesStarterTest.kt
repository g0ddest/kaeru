package app.kaeru.data.notify

import app.kaeru.domain.notify.NotifiedEpisode
import app.kaeru.domain.notify.NotifiedEpisodes
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.repository.PairingAuthorization
import app.kaeru.domain.settings.FakeSettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** When the six-hourly check is put on, and when it is taken off again. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewEpisodesStarterTest {
    private val auth = FakeAuth()
    private val schedule = FakeSchedule()
    private val settings = FakeSettingsStore()
    private val remembered = FakeNotifiedEpisodes()

    private fun starter(television: Boolean = false) =
        NewEpisodesStarter(auth, settings, schedule, remembered, Television { television })

    @Test
    fun `somebody signed in gets the check`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        starter().start(scope)
        scope.runCurrent()

        assertEquals(listOf("enable"), schedule.calls)
    }

    @Test
    fun `signing out takes the check off`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        starter().start(scope)
        scope.runCurrent()

        auth.loggedIn.value = false
        scope.runCurrent()

        assertEquals(listOf("enable", "disable"), schedule.calls)
    }

    @Test
    fun `a viewer who was never signed in has no check to take off twice`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        auth.loggedIn.value = false
        starter().start(scope)
        scope.runCurrent()

        assertEquals(listOf("disable"), schedule.calls)
    }

    @Test
    fun `a television is never given one, whoever is signed in`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        starter(television = true).start(scope)
        scope.runCurrent()

        assertEquals(listOf("disable"), schedule.calls)
    }

    @Test
    fun `turning the setting off takes the check off`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        starter().start(scope)
        scope.runCurrent()

        settings.newEpisodeNotifications.value = false
        scope.runCurrent()

        assertEquals(listOf("enable", "disable"), schedule.calls)
    }

    @Test
    fun `turning it back on puts the check back`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        settings.newEpisodeNotifications.value = false
        starter().start(scope)
        scope.runCurrent()

        settings.newEpisodeNotifications.value = true
        scope.runCurrent()

        assertEquals(listOf("disable", "enable"), schedule.calls)
    }

    @Test
    fun `the setting on its own is not enough while nobody is signed in`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        auth.loggedIn.value = false
        starter().start(scope)
        scope.runCurrent()

        settings.newEpisodeNotifications.value = true
        scope.runCurrent()

        assertEquals(listOf("disable"), schedule.calls)
    }

    @Test
    fun `starting twice starts one watch`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val starter = starter()
        starter.start(scope)
        starter.start(scope)
        scope.runCurrent()

        auth.loggedIn.value = false
        scope.runCurrent()

        assertEquals(listOf("enable", "disable"), schedule.calls)
    }

    @Test
    fun `turning the setting back on forgets everything the check had seen`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        remembered.rows += NotifiedEpisode(1, 7)
        starter().start(scope)
        scope.runCurrent()

        settings.newEpisodeNotifications.value = false
        scope.runCurrent()
        settings.newEpisodeNotifications.value = true
        scope.runCurrent()

        assertEquals(emptyList<NotifiedEpisode>(), remembered.rows)
    }

    @Test
    fun `a start with the setting already on forgets nothing`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        remembered.rows += NotifiedEpisode(1, 7)
        starter().start(scope)
        scope.runCurrent()

        assertEquals(listOf(NotifiedEpisode(1, 7)), remembered.rows)
    }

    @Test
    fun `signing back in is not what forgets, so an untouched switch stays remembered`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        remembered.rows += NotifiedEpisode(1, 7)
        starter().start(scope)
        scope.runCurrent()

        auth.loggedIn.value = false
        scope.runCurrent()
        auth.loggedIn.value = true
        scope.runCurrent()

        assertEquals(listOf(NotifiedEpisode(1, 7)), remembered.rows)
    }

    private class FakeNotifiedEpisodes : NotifiedEpisodes {
        val rows = mutableListOf<NotifiedEpisode>()
        override suspend fun all(): List<NotifiedEpisode> = rows.toList()
        override suspend fun record(episodes: List<NotifiedEpisode>, at: Instant) {
            episodes.forEach { if (it !in rows) rows += it }
        }

        override suspend fun forget() = rows.clear()
    }

    private class FakeSchedule : NewEpisodesSchedule {
        val calls = mutableListOf<String>()
        override fun enable() { calls += "enable" }
        override fun disable() { calls += "disable" }
    }

    private class FakeAuth : AuthRepository {
        val loggedIn = MutableStateFlow(true)
        override val isLoggedIn: Flow<Boolean> = loggedIn
        override fun authorizeUrl(redirectUri: String) = "https://auth.test/"
        override fun pairingAuthorization() = PairingAuthorization("https://auth.test/", "state")
        override suspend fun exchangeRedirectCode(code: String, state: String?) = Result.success(Unit)
        override suspend fun exchangeTypedCode(code: String) = Result.success(Unit)
        override suspend fun exchangePairedCode(code: String, redirectUri: String) = Result.success(Unit)
        override suspend fun logout() = Unit
    }
}
