package app.kaeru.domain.repository

import kotlinx.coroutines.flow.Flow

const val OOB_REDIRECT = "urn:ietf:wg:oauth:2.0:oob"
const val MOBILE_REDIRECT = "kaeru://oauth"

interface AuthRepository {
    val isLoggedIn: Flow<Boolean>
    fun authorizeUrl(redirectUri: String): String
    suspend fun exchangeCode(code: String, redirectUri: String): Result<Unit>
    suspend fun logout()
}
