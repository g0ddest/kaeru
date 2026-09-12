package app.kaeru.data.auth

import app.kaeru.data.shikimori.SHIKIMORI_BASE_URL
import app.kaeru.data.shikimori.ShikimoriOAuthApi
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.toDomainFailure
import app.kaeru.domain.error.AuthCallbackRejected
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.repository.MOBILE_REDIRECT
import app.kaeru.domain.repository.OOB_REDIRECT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ShikimoriAuthRepository @Inject constructor(
    private val oauthApi: ShikimoriOAuthApi,
    private val api: ShikimoriApi,
    private val session: AccountSession,
    @param:Named("shikimoriClientId") private val clientId: String,
    @param:Named("shikimoriClientSecret") private val clientSecret: String,
    private val clock: Clock,
) : AuthRepository {
    private val random = SecureRandom()

    /**
     * The `state` of the authorization attempt started last, held in memory only: after process
     * death there is no attempt in flight, and a callback arriving then is exactly the unsolicited
     * one we want to refuse.
     */
    @Volatile private var pendingState: String? = null

    override val isLoggedIn: Flow<Boolean> = session.userId.map { it != null }.distinctUntilChanged()

    override fun authorizeUrl(redirectUri: String): String {
        val state = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(random::nextBytes))
        pendingState = state
        val redirect = URLEncoder.encode(redirectUri, "UTF-8")
        val client = URLEncoder.encode(clientId, "UTF-8")
        return "${SHIKIMORI_BASE_URL}oauth/authorize?client_id=$client&redirect_uri=$redirect" +
            "&response_type=code&scope=user_rates&state=${URLEncoder.encode(state, "UTF-8")}"
    }

    override suspend fun exchangeRedirectCode(code: String, state: String?): Result<Unit> {
        // A silent account switch would wipe the signed-in user's cached rates and watch history.
        if (isLoggedIn.first()) return rejected("An account is already signed in")
        val expected = pendingState
        pendingState = null // Single use: a replayed callback finds nothing pending.
        if (expected == null) return rejected("No authorization was started from this app")
        if (state == null || !matches(expected, state)) return rejected("Callback state does not match")
        if (code.isBlank()) return rejected("Callback carried no authorization code")
        return exchangeCode(code, MOBILE_REDIRECT)
    }

    override suspend fun exchangeTypedCode(code: String): Result<Unit> =
        exchangeCode(code.trim(), OOB_REDIRECT)

    internal suspend fun exchangeCode(code: String, redirectUri: String): Result<Unit> = try {
        session.login {
            val tokens = oauthApi.token(
                grantType = "authorization_code",
                clientId = clientId,
                clientSecret = clientSecret,
                code = code,
                redirectUri = redirectUri,
            )
            val user = api.whoami("Bearer ${tokens.accessToken}")
            AuthTokens(tokens.accessToken, tokens.refreshToken, clock.instant().epochSecond + tokens.expiresIn, user.id)
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error.toDomainFailure())
    }

    override suspend fun logout() = session.logout()

    private fun rejected(reason: String): Result<Unit> = Result.failure(AuthCallbackRejected(reason))

    private fun matches(expected: String, actual: String) =
        MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
}
