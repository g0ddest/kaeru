package app.kaeru.data.shikimori

import app.kaeru.data.auth.AuthTokens
import app.kaeru.data.auth.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.time.Clock

/** Refreshes after a 401 and allows only one authenticated retry. */
class TokenAuthenticator(
    private val store: TokenStore,
    private val oauthApi: ShikimoriOAuthApi,
    private val clientId: String,
    private val clientSecret: String,
    private val clock: Clock,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.startsWith("/oauth/")) return null
        if (response.priorResponse != null) return null
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        val fresh = synchronized(lock) {
            val current = runBlocking { store.get() } ?: return null
            // Another request may already have completed the refresh while this one waited.
            if (current.accessToken != failedToken) return@synchronized current
            refresh(current)
        } ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer ${fresh.accessToken}").build()
    }

    private fun refresh(current: AuthTokens): AuthTokens? = runBlocking {
        runCatching {
            oauthApi.token(
                grantType = "refresh_token",
                clientId = clientId,
                clientSecret = clientSecret,
                refreshToken = current.refreshToken,
            )
        }.map { AuthTokens(it.accessToken, it.refreshToken, clock.instant().epochSecond + it.expiresIn) }
            .onSuccess { store.set(it) }
            .onFailure { store.set(null) }
            .getOrNull()
    }
}
