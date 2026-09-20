package app.kaeru.data.update

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.kaeru.domain.update.UpdatePolicy
import app.kaeru.domain.update.UpdateRepository
import app.kaeru.domain.update.UpdateResult
import app.kaeru.test.MutableClock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.time.Instant

/**
 * The one call in this feature that runs with nobody watching: the launch-time check, on the
 * application scope, during `Application.onCreate`.
 *
 * Nothing retries it and nothing reports it, so the only thing that matters is that it cannot take
 * the process with it. The real scope is a `SupervisorJob` with no handler of its own, so anything
 * that got past the starter would reach the thread's default handler and kill the app at launch —
 * over a background question about whether a newer APK exists.
 *
 * The scope below therefore carries a *recording* handler that production does not have. A failure
 * reaching it is the test's way of seeing what a device would experience as a crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckStarterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val clock = MutableClock(Instant.parse("2026-09-16T08:00:00Z"))

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() {
        server.shutdown()
        storeScope.cancel()
    }

    /** A repository whose every read of the device's own storage fails. */
    private class UnreadableStore(private val failure: Throwable) : UpdateRepository {
        override val lastResult: Flow<UpdateResult?> = flow { throw failure }

        override suspend fun check(force: Boolean): Result<UpdateResult> = throw failure
    }

    private fun assertSwallowed(failure: Throwable) {
        val escaped = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() +
                UnconfinedTestDispatcher() +
                CoroutineExceptionHandler { _, thrown -> escaped += thrown },
        )

        UpdateCheckStarter(UnreadableStore(failure)).start(scope)

        assertTrue("the check took the scope down with it", scope.isActive)
        assertNull("a failure escaped to the scope, which on a device is a crash", escaped.firstOrNull())
        scope.cancel()
    }

    @Test
    fun `a preferences file the device cannot read never escapes`() = assertSwallowed(
        CorruptionException("unreadable"),
    )

    @Test
    fun `a network failure never escapes either`() = assertSwallowed(IOException("no route"))

    @Test
    fun `nor does anything else`() = assertSwallowed(IllegalStateException("unexpected"))

    /**
     * And the repository holds up its own end: a `Result`-returning method must not throw, which
     * is what let a broken store reach the scope at all.
     */
    @Test
    fun `the repository answers a broken store with a failure rather than a throw`() = runTest {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(githubJson().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GitHubReleasesApi::class.java)
        val store = PreferenceDataStoreFactory.create(scope = storeScope) {
            // A directory where a file belongs: every read of it throws, which is the shape of a
            // preferences file the device cannot make sense of.
            tmp.newFolder("updates.preferences_pb")
        }
        val repository = GitHubUpdateRepository(
            api, UpdatePreferences(store), UpdatePolicy(), clock, "0.3.0",
        )

        val outcome = repository.check(force = false)

        assertNotNull("a broken store must not escape as a throw", outcome.exceptionOrNull())
    }
}
