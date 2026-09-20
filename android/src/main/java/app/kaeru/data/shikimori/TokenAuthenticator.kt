package app.kaeru.data.shikimori

import app.kaeru.data.auth.AuthTokens
import app.kaeru.data.auth.TokenSnapshot
import app.kaeru.data.auth.TokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import java.time.Clock

/** Refreshes after a 401 and allows only one authenticated retry. */
class TokenAuthenticator(
    private val store: TokenStore,
    private val oauthApi: ShikimoriOAuthApi,
    private val clientId: String,
    private val clock: Clock,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.startsWith("/oauth/")) return null
        if (response.request.tag(ExplicitAuthorization::class.java) != null) return null
        if (response.priorResponse != null) return null
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        val fresh = synchronized(lock) {
            val snapshot = runBlocking { store.snapshot() }
            val current = snapshot.tokens ?: return null
            // Another request may already have completed the refresh while this one waited.
            if (current.accessToken != failedToken) return@synchronized current
            refresh(snapshot)
        } ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer ${fresh.accessToken}").build()
    }

    private fun refresh(snapshot: TokenSnapshot): AuthTokens? = runBlocking {
        val current = snapshot.tokens ?: return@runBlocking null
        val outcome = runCatching {
            oauthApi.token(
                grantType = "refresh_token",
                clientId = clientId,
                refreshToken = current.refreshToken,
            )
        }
        // A failure that says nothing about this refresh token costs the request and nothing more.
        // Clearing here is final — the viewer cannot retry a session that is gone, they have to
        // sign in again — and the trip now goes through Kaeru's own proxy, which has its own ways
        // to be briefly unavailable: a 429, a 502, a deploy without the secret, no network at all.
        val failure = outcome.exceptionOrNull()
        if (failure != null && !failure.rejectsRefreshToken()) return@runBlocking null
        val refreshed = outcome.getOrNull()?.let {
            AuthTokens(it.accessToken, it.refreshToken, clock.instant().epochSecond + it.expiresIn, current.userId)
        }
        // A stale success must not restore an old session; a stale failure must not clear a new one.
        if (store.compareAndSet(snapshot, refreshed)) refreshed else null
    }
}

/**
 * True when the answer says the refresh token itself was refused — the one failure that ends a
 * session rather than a request.
 *
 * Shikimori answers that with a 400 or a 401 carrying an OAuth error object, and the proxy hands
 * its status and body back verbatim. The proxy's own refusals are the same statuses in plain text
 * («unknown client», «body too large», «not configured»), and those are a deploy to fix, not a
 * session to end — so the body has to name an error before anything is cleared.
 */
private fun Throwable.rejectsRefreshToken(): Boolean {
    val http = this as? HttpException ?: return false
    if (http.code() != 400 && http.code() != 401) return false
    val body = runCatching { http.response()?.errorBody()?.string() }.getOrNull() ?: return false
    val answer = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return false
    return (answer["error"] as? JsonPrimitive)?.content?.isNotBlank() == true
}
