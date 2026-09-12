package app.kaeru.data.auth

import kotlinx.coroutines.flow.Flow

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long,
)

interface TokenStore {
    val tokens: Flow<AuthTokens?>
    suspend fun get(): AuthTokens?
    suspend fun set(tokens: AuthTokens?)
}
