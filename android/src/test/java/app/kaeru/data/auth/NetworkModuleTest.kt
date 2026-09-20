package app.kaeru.data.auth

import android.content.Context
import app.kaeru.BuildConfig
import app.kaeru.di.NetworkModule
import app.kaeru.domain.repository.MOBILE_REDIRECT
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
    fun `the token exchange goes to the proxy, carries no secret and names this build`() = runTest {
        MockWebServer().use { server ->
            server.start()
            // As it arrives from local.properties: an origin, no trailing slash, no path.
            val proxy = server.url("/").toString().removeSuffix("/")
            val client = NetworkModule.shikimoriClient(NetworkModule.sharedTransport(), "cid", proxy)
            server.enqueue(MockResponse().setBody("""{"access_token":"acc","refresh_token":"ref"}"""))

            client.token("authorization_code", "abc", MOBILE_REDIRECT)

            val request = server.takeRequest()
            assertEquals("/oauth/token", request.path)
            assertEquals("POST", request.method)
            assertEquals(
                "grant_type=authorization_code&client_id=cid&code=abc&redirect_uri=kaeru%3A%2F%2Foauth",
                request.body.readUtf8(),
            )
            assertEquals("Kaeru/${BuildConfig.VERSION_NAME}", request.getHeader("User-Agent"))
        }
    }

    @Test
    fun `a build with no proxy address, or a malformed one, knows it cannot sign anyone in`() {
        // Shikimori would answer `invalid_client`, and the only way past that is the secret this
        // app no longer has. `local.properties.example` puts AUTH_PROXY_URL (https://) directly
        // above TOGETHER_RELAY_URL (wss://) on the same host, which is exactly the copy-paste that
        // lands a wss:// value here — and it must not kill the graph at the first injection.
        for (garbage in listOf("   ", "kaeru-relay.workers.dev", "wss://kaeru-relay.workers.dev", "not a url at all")) {
            val client = NetworkModule.shikimoriClient(NetworkModule.sharedTransport(), "cid", garbage)
            assertFalse("'$garbage' must not count as a proxy", client.oauthConfigured)
        }
    }

    @Test
    fun `the plain client carries the app's name, and the shared transport leaves the name to its clients`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse())
            server.enqueue(MockResponse())

            NetworkModule.plainClient().newCall(Request.Builder().url(server.url("/plain")).build()).execute().close()
            NetworkModule.sharedTransport().request(server.url("/shared").toString())

            val plain = server.takeRequest()
            assertEquals("Kaeru/${BuildConfig.VERSION_NAME}", plain.getHeader("User-Agent"))
            assertNull(plain.getHeader("Authorization"))
            // Shikimori gets `Kaeru/<version>` and Kodik a browser's, each set by the shared
            // client itself; an interceptor forcing the app's name here would undo the second.
            val shared = server.takeRequest()
            assertFalse(shared.getHeader("User-Agent").orEmpty().startsWith("Kaeru/"))
        }
    }
}
