package app.kaeru.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

    override val tokens: Flow<AuthTokens?> = dataStore.data.map { it.read() }

    override suspend fun get(): AuthTokens? = dataStore.data.first().read()

    override suspend fun set(tokens: AuthTokens?) {
        dataStore.edit { preferences ->
            if (tokens == null) {
                preferences.remove(access)
                preferences.remove(refresh)
                preferences.remove(expires)
            } else {
                preferences[access] = tokens.accessToken
                preferences[refresh] = tokens.refreshToken
                preferences[expires] = tokens.expiresAtEpochSec
            }
        }
    }

    private fun Preferences.read(): AuthTokens? {
        val accessToken = this[access] ?: return null
        val refreshToken = this[refresh] ?: return null
        return AuthTokens(accessToken, refreshToken, this[expires] ?: 0L)
    }
}
