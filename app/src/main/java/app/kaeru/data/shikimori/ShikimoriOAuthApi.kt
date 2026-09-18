package app.kaeru.data.shikimori

import app.kaeru.domain.error.SignInUnavailable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

@Serializable
data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 86400,
    @SerialName("refresh_token") val refreshToken: String,
    val scope: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

interface ShikimoriOAuthApi {
    @FormUrlEncoded
    @POST("oauth/token")
    suspend fun token(
        @Field("grant_type") grantType: String,
        @Field("client_id") clientId: String,
        @Field("code") code: String? = null,
        @Field("redirect_uri") redirectUri: String? = null,
        @Field("refresh_token") refreshToken: String? = null,
    ): TokenResponseDto
}

/**
 * The token API of a build assembled without an `AUTH_PROXY_URL`.
 *
 * It fails every exchange rather than dialling Shikimori: the secret the exchange needs is the
 * worker's, so a call made from here could only come back `invalid_client` — after handing a live
 * authorization code to a request that cannot redeem it. Failing first keeps the code, and a
 * refresh token, on the device.
 */
object UnconfiguredOAuthApi : ShikimoriOAuthApi {
    override suspend fun token(
        grantType: String,
        clientId: String,
        code: String?,
        redirectUri: String?,
        refreshToken: String?,
    ): TokenResponseDto = throw SignInUnavailable()
}
