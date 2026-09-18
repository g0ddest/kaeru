package app.kaeru.data.auth

import android.content.Context
import app.kaeru.BuildConfig
import app.kaeru.data.shikimori.ShikimoriOAuthApi
import app.kaeru.di.NetworkModule
import app.kaeru.domain.error.SignInUnavailable
import app.kaeru.domain.repository.MOBILE_REDIRECT
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import retrofit2.Retrofit
import java.time.Clock
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkModuleTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `production auth provider stores credentials only in no backup files directory`() = runTest {
        val files = folder.newFolder("files")
        val noBackup = folder.newFolder("no_backup")
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns files
        every { context.noBackupFilesDir } returns noBackup
        val store = DataStoreTokenStore(NetworkModule.authDataStore(context), SessionFence())
        store.set(AuthTokens("private-access", "private-refresh", 87_400))
        assertTrue("credentials must reside in noBackupFilesDir", noBackup.resolve("auth.preferences_pb").isFile)
        assertTrue("default backup-eligible token file must not be created", !files.resolve("datastore/auth.preferences_pb").exists())
        assertEquals(AuthTokens("private-access", "private-refresh", 87_400), store.get())
    }

    @Test
    fun `the token exchange goes to the proxy and carries no secret`() = runTest {
        MockWebServer().use { server ->
            server.start()
            // As it arrives from local.properties: an origin, no trailing slash, no path.
            val proxy = server.url("/").toString().removeSuffix("/")
            val api = NetworkModule.oauthApi(NetworkModule.plainClient(), NetworkModule.json(), proxy)
            server.enqueue(MockResponse().setBody("""{"access_token":"acc","refresh_token":"ref"}"""))

            api.token(grantType = "authorization_code", clientId = "cid", code = "abc", redirectUri = MOBILE_REDIRECT)

            val request = server.takeRequest()
            assertEquals("/oauth/token", request.path)
            assertEquals("POST", request.method)
            assertEquals(
                "grant_type=authorization_code&client_id=cid&code=abc&redirect_uri=kaeru%3A%2F%2Foauth",
                request.body.readUtf8(),
            )
        }
    }

    @Test
    fun `a build with no proxy address refuses the exchange instead of asking Shikimori`() = runTest {
        // Shikimori would answer `invalid_client`, and the only way past that is the secret this
        // app no longer has. A build assembled without the proxy cannot sign anyone in, and says so
        // rather than sending a code somewhere it cannot be redeemed.
        val api = NetworkModule.oauthApi(NetworkModule.plainClient(), NetworkModule.json(), "   ")

        val failure = runCatching {
            api.token(grantType = "refresh_token", clientId = "cid", refreshToken = "ref")
        }.exceptionOrNull()

        assertTrue("expected SignInUnavailable, got $failure", failure is SignInUnavailable)
    }

    @Test
    fun `a malformed proxy address refuses the exchange instead of killing the graph`() = runTest {
        // Retrofit's builder throws on an address with no scheme or a scheme it does not speak, and
        // it throws inside a @Provides — so the app would die at the first injection of the OAuth
        // API rather than show the message a missing address already has. `local.properties.example`
        // puts AUTH_PROXY_URL (https://) directly above TOGETHER_RELAY_URL (wss://) on the same
        // host, which is exactly the copy-paste that lands a wss:// value here.
        for (garbage in listOf("kaeru-relay.workers.dev", "wss://kaeru-relay.workers.dev", "not a url at all")) {
            val api = NetworkModule.oauthApi(NetworkModule.plainClient(), NetworkModule.json(), garbage)

            val failure = runCatching {
                api.token(grantType = "refresh_token", clientId = "cid", refreshToken = "ref")
            }.exceptionOrNull()

            assertTrue("expected SignInUnavailable for '$garbage', got $failure", failure is SignInUnavailable)
        }
    }

    @Test
    fun `full asynchronous API dispatcher can still refresh and complete every call`() {
        MockWebServer().use { server ->
            server.start()
            // Keep production providers and Retrofit suspend transport; rewrite only the destination.
            val plain = NetworkModule.plainClient().newBuilder().addInterceptor { chain ->
                val url = server.url(chain.request().url.encodedPath)
                chain.proceed(chain.request().newBuilder().url(url).build())
            }.build()
            val oauth = NetworkModule.oauthApi(plain, NetworkModule.json(), server.url("/").toString())
            val store = InMemoryTokenStore(AuthTokens("old", "refresh", 0))
            val authenticated = NetworkModule.shikimoriClient(plain, store, oauth, "cid", Clock.systemUTC())
            val slots = authenticated.dispatcher.maxRequestsPerHost
            val initialCalls = CountDownLatch(slots)
            val refreshes = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/oauth/token") {
                        refreshes.incrementAndGet()
                        return MockResponse().setBody("""{"access_token":"new","refresh_token":"new-refresh","expires_in":86400}""")
                    }
                    if (request.getHeader("Authorization") == "Bearer old") {
                        initialCalls.countDown()
                        check(initialCalls.await(5, TimeUnit.SECONDS))
                        return MockResponse().setResponseCode(401)
                    }
                    return MockResponse().setBody("{}")
                }
            }
            val completed = CountDownLatch(slots + 1)
            val outcomes = ConcurrentLinkedQueue<Int>()
            val failures = ConcurrentLinkedQueue<IOException>()
            try {
                repeat(slots + 1) {
                    authenticated.newCall(Request.Builder().url("https://shikimori.io/api/users/whoami").build())
                        .enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                failures.add(e)
                                completed.countDown()
                            }
                            override fun onResponse(call: Call, response: Response) {
                                response.use { outcomes.add(it.code) }
                                completed.countDown()
                            }
                        })
                }
                assertTrue("refresh must run while all $slots API slots are occupied", completed.await(5, TimeUnit.SECONDS))
                assertTrue(failures.toString(), failures.isEmpty())
                assertEquals(List(slots + 1) { 200 }, outcomes.toList())
                assertEquals(1, refreshes.get())
            } finally {
                // Also release a broken shared-dispatcher implementation after the bounded assertion.
                plain.dispatcher.cancelAll()
                authenticated.dispatcher.cancelAll()
                plain.dispatcher.maxRequestsPerHost = slots + 2
                authenticated.dispatcher.maxRequestsPerHost = slots + 2
                completed.await(5, TimeUnit.SECONDS)
                plain.dispatcher.executorService.shutdownNow()
                authenticated.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test
    fun `plain client stays anonymous while Shikimori client sends bearer and versioned agent`() {
        MockWebServer().use { server ->
            server.start()
            val plain = NetworkModule.plainClient()
            val oauth = Retrofit.Builder().baseUrl(server.url("/")).build().create(ShikimoriOAuthApi::class.java)
            val authenticated = NetworkModule.shikimoriClient(
                plain, InMemoryTokenStore(AuthTokens("access", "refresh", 0)), oauth, "cid", Clock.systemUTC(),
            )
            server.enqueue(MockResponse())
            server.enqueue(MockResponse())
            val request = Request.Builder().url(server.url("/api/users/whoami")).build()
            plain.newCall(request).execute().close()
            authenticated.newCall(request).execute().close()
            val anonymous = server.takeRequest()
            val authorized = server.takeRequest()
            assertNull(anonymous.getHeader("Authorization"))
            assertEquals("Bearer access", authorized.getHeader("Authorization"))
            assertEquals("Kaeru/${BuildConfig.VERSION_NAME}", anonymous.getHeader("User-Agent"))
            assertEquals("Kaeru/${BuildConfig.VERSION_NAME}", authorized.getHeader("User-Agent"))
            assertSame(Authenticator.NONE, plain.authenticator)
            assertEquals(listOf("UserAgentInterceptor", "HttpLoggingInterceptor", "RateLimitInterceptor", "AuthInterceptor"), authenticated.interceptors.map { it.javaClass.simpleName })
        }
    }
}
