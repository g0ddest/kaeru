package app.kaeru.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class DataStoreTokenStore @Inject constructor(
    @param:Named("auth") private val dataStore: DataStore<Preferences>,
) : TokenStore {
    private val access = stringPreferencesKey("access_token")
    private val refresh = stringPreferencesKey("refresh_token")
    private val expires = longPreferencesKey("expires_at")
    private val revision = longPreferencesKey("token_revision")
    private val userId = longPreferencesKey("token_user_id")

    override val tokens: Flow<AuthTokens?> = dataStore.data.map { it.read() }

    override suspend fun get(): AuthTokens? = dataStore.data.first().read()

    override suspend fun set(tokens: AuthTokens?) {
        dataStore.edit { it.write(tokens) }
    }

    override suspend fun snapshot(): TokenSnapshot = dataStore.data.first().snapshot()

    override suspend fun compareAndSet(expected: TokenSnapshot, tokens: AuthTokens?): Boolean {
        var applied = false
        // DataStore serializes the comparison and mutation, including across store wrappers.
        dataStore.edit {
            if (it.snapshot() == expected) {
                it.write(tokens)
                applied = true
            }
        }
        return applied
    }

    private fun MutablePreferences.write(tokens: AuthTokens?) {
        this[revision] = (this[revision] ?: 0L) + 1L
        if (tokens == null) {
            remove(access)
            remove(refresh)
            remove(expires)
            remove(userId)
        } else {
            this[access] = tokens.accessToken
            this[refresh] = tokens.refreshToken
            this[expires] = tokens.expiresAtEpochSec
            if (tokens.userId == null) remove(userId) else this[userId] = tokens.userId
        }
    }

    private fun Preferences.snapshot() = TokenSnapshot(read(), this[revision] ?: 0L)

    private fun Preferences.read(): AuthTokens? {
        val accessToken = this[access] ?: return null
        val refreshToken = this[refresh] ?: return null
        return AuthTokens(accessToken, refreshToken, this[expires] ?: 0L, this[userId])
    }
}
