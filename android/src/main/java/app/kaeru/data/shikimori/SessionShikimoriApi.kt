package app.kaeru.data.shikimori

import app.kaeru.data.auth.AuthTokens
import app.kaeru.data.auth.TokenSnapshot
import app.kaeru.data.auth.TokenStore
import app.kaeru.shared.ApiException
import app.kaeru.shared.data.shikimori.AnimeDto
import app.kaeru.shared.data.shikimori.ScreenshotDto
import app.kaeru.shared.data.shikimori.ShikimoriClient
import app.kaeru.shared.data.shikimori.UserDto
import app.kaeru.shared.data.shikimori.UserRateDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The shared client with this device's session on it.
 *
 * Every call goes out with the bearer the token store holds right now. A 401 earns exactly one
 * refresh and one retry: the refresh goes through Kaeru's proxy, and what it brings back replaces
 * the tokens only if nobody has touched them meanwhile — a logout or a newer login that landed
 * while the refresh was away wins, and the retry then does not happen at all. A second 401 on
 * the retry is the answer.
 *
 * Only a refresh token Shikimori itself refuses — `invalid_grant` — ends the session. Everything
 * else a refresh can fail with is the proxy having a bad moment, the network being gone, or a
 * build with no proxy address: none of it says anything about this session, and a session that
 * is cleared cannot be retried, so it stays.
 */
@Singleton
class SessionShikimoriApi @Inject constructor(
    private val client: ShikimoriClient,
    private val store: TokenStore,
    private val clock: Clock,
) : ShikimoriApi {
    /** One refresh at a time: the second request to hit a 401 finds the first one's answer. */
    private val refreshing = Mutex()

    override suspend fun whoami(accessToken: String?): UserDto =
        if (accessToken != null) client.whoami(accessToken) else authorized { client.whoami(it) }

    override suspend fun libraryRates(userId: Long): List<UserRateDto> = authorized { client.libraryRates(userId, it) }

    override suspend fun animesByIds(ids: List<Int>): List<AnimeDto> = authorized { client.animesByIds(ids, it) }

    override suspend fun catalogue(status: String?, season: String?): List<AnimeDto> =
        authorized { client.catalogue(status, season, it) }

    override suspend fun anime(id: Int): AnimeDto = authorized { client.anime(id, it) }

    override suspend fun screenshots(id: Int): List<ScreenshotDto> = authorized { client.screenshots(id, it) }

    override suspend fun search(query: String): List<AnimeDto> = authorized { client.search(query, it) }

    override suspend fun posters(ids: List<Int>): Map<Int, String> = authorized { client.posters(ids, it) }

    override suspend fun createUserRate(userId: Long, animeId: Int, status: String): UserRateDto =
        authorized { client.createUserRate(userId, animeId, status, token = it) }

    override suspend fun updateUserRate(id: Long, status: String?, episodes: Int?): UserRateDto =
        authorized { client.updateUserRate(id, status, episodes, it) }

    /** [call] with the session's bearer, and once more with a rotated one after a 401. */
    private suspend fun <T> authorized(call: suspend (token: String) -> T): T {
        // No session: the request goes out anonymous, and a 401 for it is simply the answer.
        val token = store.get()?.accessToken ?: return call("")
        return try {
            call(token)
        } catch (unauthorized: ApiException) {
            if (unauthorized.status != 401) throw unauthorized
            val fresh = rotated(token) ?: throw unauthorized
            call(fresh)
        }
    }

    /**
     * The token to retry with after [failed] was refused: one another request has already rotated
     * to, or one refreshed here. Null keeps the 401.
     */
    private suspend fun rotated(failed: String): String? = refreshing.withLock {
        val snapshot = store.snapshot()
        val current = snapshot.tokens ?: return null
        if (current.accessToken != failed) return current.accessToken
        refresh(snapshot)?.accessToken
    }

    private suspend fun refresh(snapshot: TokenSnapshot): AuthTokens? {
        val current = snapshot.tokens ?: return null
        // Nothing the viewer can do fixes a missing AUTH_PROXY_URL, and signing them out does not
        // help: the next build with an address must find the session still there.
        if (!client.oauthConfigured) return null
        val outcome = try {
            Result.success(client.token("refresh_token", current.refreshToken))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        val failure = outcome.exceptionOrNull()
        if (failure != null && !failure.rejectsRefreshToken()) return null
        val refreshed = outcome.getOrNull()?.let {
            AuthTokens(it.accessToken, it.refreshToken, clock.instant().epochSecond + it.expiresIn, current.userId)
        }
        // A stale success must not restore an old session; a stale failure must not clear a new one.
        return if (store.compareAndSet(snapshot, refreshed)) refreshed else null
    }
}

/**
 * True when the answer says the refresh token itself was refused — the one failure that ends a
 * session rather than a request. The shared client marks exactly that case and nothing else: the
 * proxy's own refusals are plain text, and a 400 naming some other OAuth error is a deploy to
 * fix, not a session to end.
 */
private fun Throwable.rejectsRefreshToken(): Boolean =
    this is ApiException && (status == 400 || status == 401) && oauthError == "invalid_grant"
