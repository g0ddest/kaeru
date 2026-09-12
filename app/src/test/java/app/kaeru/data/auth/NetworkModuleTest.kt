package app.kaeru.data.auth

import app.kaeru.BuildConfig
import app.kaeru.data.shikimori.ShikimoriOAuthApi
import app.kaeru.di.NetworkModule
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import retrofit2.Retrofit
import java.time.Clock

class NetworkModuleTest {
    @Test
    fun `plain client stays anonymous while Shikimori client sends bearer and versioned agent`() {
        MockWebServer().use { server ->
            server.start()
            val plain = NetworkModule.plainClient()
            val oauth = Retrofit.Builder().baseUrl(server.url("/")).build().create(ShikimoriOAuthApi::class.java)
            val authenticated = NetworkModule.shikimoriClient(
                plain, InMemoryTokenStore(AuthTokens("access", "refresh", 0)), oauth, "cid", "sec", Clock.systemUTC(),
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
