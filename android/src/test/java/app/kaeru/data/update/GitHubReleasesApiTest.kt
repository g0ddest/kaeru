package app.kaeru.data.update

import app.kaeru.domain.update.UpdateFailed
import app.kaeru.domain.update.UpdateFailure
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** The one request this feature makes, against a real answer from the GitHub API. */
class GitHubReleasesApiTest {
    private val server = MockWebServer()
    private lateinit var api: GitHubReleasesApi

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream("update/$name")!!.bufferedReader().readText()

    @Before
    fun setUp() {
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(githubJson().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GitHubReleasesApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `asks the right repository with the versioned media type`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("releases.json")))

        api.releases()

        val request = server.takeRequest()
        assertEquals("/repos/g0ddest/kaeru/releases?per_page=5", request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
    }

    @Test
    fun `parses the fields this app uses and ignores the rest`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("releases.json")))

        val releases = api.releases()

        assertEquals(3, releases.size)
        assertEquals("v0.4.0", releases[0].tagName)
        assertTrue(releases[0].prerelease)
        assertEquals("2026-09-16T08:00:00Z", releases[0].publishedAt)
        assertEquals("Kaeru-0.4.0.apk", releases[0].assets.single().name)
        assertEquals(31_457_280L, releases[0].assets.single().size)
        assertTrue(releases[1].draft)
    }

    /** The whole chain, from a real answer to the release the screen would show. */
    @Test
    fun `the newest published release with an apk is the one selected`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("releases.json")))

        val release = newestRelease(api.releases())?.toUpdateRelease()

        assertEquals("0.4.0", release?.version)
        assertEquals(31_457_280L, release?.sizeBytes)
        assertEquals(
            "https://github.com/g0ddest/kaeru/releases/download/v0.4.0/Kaeru-0.4.0.apk",
            release?.apkUrl,
        )
        assertEquals("Что нового\n\n• Обновления внутри приложения\n• Исправлен плеер", release?.notes)
    }

    /**
     * GitHub answers a spent budget with a `403`, which everywhere else in this app means «signed
     * out». The header is the only thing that tells the two apart.
     */
    @Test
    fun `a spent rate limit is read as a rate limit and not as a refusal`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("X-RateLimit-Remaining", "0")
                .setBody("""{"message":"API rate limit exceeded"}"""),
        )

        val failure = runCatching { api.releases() }.exceptionOrNull()

        assertEquals(
            UpdateFailure.RATE_LIMITED,
            (failure!!.toUpdateFailure() as UpdateFailed).reason,
        )
        assertTrue(failure is HttpException)
    }

    @Test
    fun `a forbidden answer with budget left is not a rate limit`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("X-RateLimit-Remaining", "57")
                .setBody("""{"message":"Forbidden"}"""),
        )

        val failure = runCatching { api.releases() }.exceptionOrNull()!!

        assertEquals(
            UpdateFailure.UNKNOWN,
            (failure.toUpdateFailure() as UpdateFailed).reason,
        )
    }

    @Test
    fun `too many requests is the same news said the modern way`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        val failure = runCatching { api.releases() }.exceptionOrNull()!!

        assertEquals(
            UpdateFailure.RATE_LIMITED,
            (failure.toUpdateFailure() as UpdateFailed).reason,
        )
    }
}
