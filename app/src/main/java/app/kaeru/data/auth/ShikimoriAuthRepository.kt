package app.kaeru.data.auth

import app.kaeru.data.shikimori.SHIKIMORI_BASE_URL
import app.kaeru.data.shikimori.ShikimoriOAuthApi
import app.kaeru.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.net.URLEncoder
import java.time.Clock
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ShikimoriAuthRepository @Inject constructor(
    private val oauthApi: ShikimoriOAuthApi,
    private val store: TokenStore,
    @param:Named("shikimoriClientId") private val clientId: String,
    @param:Named("shikimoriClientSecret") private val clientSecret: String,
    private val clock: Clock,
) : AuthRepository {
    override val isLoggedIn: Flow<Boolean> = store.tokens.map { it != null }.distinctUntilChanged()

    override fun authorizeUrl(redirectUri: String): String {
        val redirect = URLEncoder.encode(redirectUri, "UTF-8")
        val client = URLEncoder.encode(clientId, "UTF-8")
        return "${SHIKIMORI_BASE_URL}oauth/authorize?client_id=$client&redirect_uri=$redirect&response_type=code&scope=user_rates"
    }

    override suspend fun exchangeCode(code: String, redirectUri: String): Result<Unit> = runCatching {
        val tokens = oauthApi.token(
            grantType = "authorization_code",
            clientId = clientId,
            clientSecret = clientSecret,
            code = code,
            redirectUri = redirectUri,
        )
        store.set(AuthTokens(tokens.accessToken, tokens.refreshToken, clock.instant().epochSecond + tokens.expiresIn))
    }

    override suspend fun logout() = store.set(null)
}
