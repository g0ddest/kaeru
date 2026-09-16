package app.kaeru.data.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.kaeru.domain.update.UpdateFailed
import app.kaeru.domain.update.UpdateFailure
import app.kaeru.domain.update.UpdatePolicy
import app.kaeru.test.MutableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Duration
import java.time.Instant

/**
 * The check as a whole: the throttle, what counts as newer, and what a device is left remembering
 * afterwards. Real preferences on a temporary folder and a real HTTP client against a mock server,
 * because the two things most worth getting wrong here are the storage and the status codes.
 */
class GitHubUpdateRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val clock = MutableClock(Instant.parse("2026-09-16T08:00:00Z"))
    private lateinit var prefs: UpdatePreferences
    private lateinit var api: GitHubReleasesApi

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream("update/$name")!!.bufferedReader().readText()

    @Before
    fun setUp() {
        server.start()
        prefs = UpdatePreferences(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                tmp.root.resolve("updates.preferences_pb")
            },
        )
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(githubJson().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GitHubReleasesApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
        storeScope.cancel()
    }

    private fun repository(installed: String = "0.3.0") =
        GitHubUpdateRepository(api, prefs, UpdatePolicy(), clock, installed)

    private fun releases() = server.enqueue(MockResponse().setBody(fixture("releases.json")))

    @Test
    fun `a newer release is found and written down`() = runTest {
        releases()

        val result = repository().check(force = false).getOrThrow()

        assertEquals("0.4.0", result.release?.version)
        assertEquals(clock.instant(), result.checkedAt)
        assertEquals("0.4.0", prefs.last()?.release?.version)
    }

    @Test
    fun `an app already on the newest release is up to date`() = runTest {
        releases()

        val result = repository(installed = "0.4.0").check(force = false).getOrThrow()

        assertNull(result.release)
        assertEquals(clock.instant(), result.checkedAt)
    }

    @Test
    fun `an app newer than anything published is up to date`() = runTest {
        releases()

        assertNull(repository(installed = "0.9.0").check(force = false).getOrThrow().release)
    }

    /** The throttle: the second launch of the day asks nobody. */
    @Test
    fun `a second check within the day does not reach the network`() = runTest {
        releases()
        val repo = repository()
        repo.check(force = false).getOrThrow()

        clock.advance(Duration.ofHours(6))
        val again = repo.check(force = false).getOrThrow()

        assertEquals(1, server.requestCount)
        // The old answer, with the old timestamp on it: nothing new was learned.
        assertEquals(Instant.parse("2026-09-16T08:00:00Z"), again.checkedAt)
        assertEquals("0.4.0", again.release?.version)
    }

    @Test
    fun `a day later the check runs again`() = runTest {
        releases()
        val repo = repository()
        repo.check(force = false).getOrThrow()

        clock.advance(Duration.ofHours(24))
        releases()
        val again = repo.check(force = false).getOrThrow()

        assertEquals(2, server.requestCount)
        assertEquals(Instant.parse("2026-09-17T08:00:00Z"), again.checkedAt)
    }

    /** «Проверить» is a question asked directly, and a cached answer would be the button lying. */
    @Test
    fun `a forced check ignores the throttle`() = runTest {
        releases()
        val repo = repository()
        repo.check(force = false).getOrThrow()

        clock.advance(Duration.ofMinutes(1))
        releases()
        repo.check(force = true).getOrThrow()

        assertEquals(2, server.requestCount)
    }

    /**
     * A result is about the build that asked for it. After an update the stored answer still names
     * the version now running, and honouring the throttle would keep offering it.
     */
    @Test
    fun `a result from a previous build is not reused`() = runTest {
        releases()
        repository(installed = "0.3.0").check(force = false).getOrThrow()

        clock.advance(Duration.ofMinutes(5))
        releases()
        val afterUpdate = repository(installed = "0.4.0").check(force = false).getOrThrow()

        assertEquals(2, server.requestCount)
        assertNull(afterUpdate.release)
    }

    @Test
    fun `a newer release with no apk on it is a failure and not a quiet up-to-date`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"tag_name":"v0.4.0","draft":false,"prerelease":true,"assets":[]}]""",
            ),
        )

        val failure = repository().check(force = false).exceptionOrNull()

        assertEquals(UpdateFailure.NO_ASSET, (failure as UpdateFailed).reason)
    }

    @Test
    fun `a rate limit is named as one`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0").setBody("{}"),
        )

        val failure = repository().check(force = false).exceptionOrNull()

        assertEquals(UpdateFailure.RATE_LIMITED, (failure as UpdateFailed).reason)
    }

    @Test
    fun `no network is named as no network`() = runTest {
        server.shutdown()

        val failure = repository().check(force = false).exceptionOrNull()

        assertEquals(UpdateFailure.NO_NETWORK, (failure as UpdateFailed).reason)
    }

    /** A failure is not an answer, so the next launch tries again rather than waiting out a day. */
    @Test
    fun `a failed check writes nothing down`() = runTest {
        server.shutdown()

        repository().check(force = false)

        assertNull(prefs.last())
    }

    @Test
    fun `a failed check leaves the last good answer where the screen can still read it`() = runTest {
        releases()
        val repo = repository()
        repo.check(force = true).getOrThrow()

        clock.advance(Duration.ofDays(2))
        server.shutdown()
        val failure = repo.check(force = true)

        assertTrue(failure.isFailure)
        assertEquals("0.4.0", repo.lastResult.first()?.release?.version)
    }
}
