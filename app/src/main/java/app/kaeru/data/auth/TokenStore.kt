package app.kaeru.data.auth

import kotlinx.coroutines.flow.Flow

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long,
)

/** Revision changes on every write, including logout and login with identical credentials. */
data class TokenSnapshot(val tokens: AuthTokens?, val revision: Long)

interface TokenStore {
    val tokens: Flow<AuthTokens?>
    suspend fun get(): AuthTokens?
    suspend fun set(tokens: AuthTokens?)
    suspend fun snapshot(): TokenSnapshot

    /** Atomically writes only if no session or token mutation has occurred since [expected]. */
    suspend fun compareAndSet(expected: TokenSnapshot, tokens: AuthTokens?): Boolean
}
