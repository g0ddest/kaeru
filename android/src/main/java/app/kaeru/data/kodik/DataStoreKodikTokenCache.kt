package app.kaeru.data.kodik

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kaeru.shared.data.kodik.KodikTokenCache
import kotlinx.coroutines.flow.first

/**
 * Where the Kodik token lives in the preference store.
 *
 * Named in one place rather than inline because these three are device configuration, not
 * account data: whoever wipes the store has to know to leave them alone, or a key somebody
 * typed in by hand disappears with a sign-out.
 */
internal object KodikTokenKeys {
    /** A key somebody typed into the settings; the shared client reads it before anything else. */
    val override = stringPreferencesKey("kodik_token_override")
    val token = stringPreferencesKey("kodik_token")
    val storedAt = longPreferencesKey("kodik_token_at")

    val all: List<Preferences.Key<*>> = listOf(override, token, storedAt)
}

/**
 * The scraped public token, kept across launches so a cold start does not fetch Kodik's embed
 * script for a value that has not changed. The shared client decides whether what is here is
 * still fresh; this only remembers.
 */
class DataStoreKodikTokenCache(private val dataStore: DataStore<Preferences>) : KodikTokenCache {
    override suspend fun load(): KodikTokenCache.Entry? {
        val prefs = dataStore.data.first()
        val token = prefs[KodikTokenKeys.token]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val storedAt = prefs[KodikTokenKeys.storedAt] ?: return null
        return KodikTokenCache.Entry(token, storedAt)
    }

    override suspend fun store(token: String, storedAtMillis: Long) {
        dataStore.edit {
            it[KodikTokenKeys.token] = token
            it[KodikTokenKeys.storedAt] = storedAtMillis
        }
    }

    override suspend fun clear() {
        dataStore.edit {
            it.remove(KodikTokenKeys.token)
            it.remove(KodikTokenKeys.storedAt)
        }
    }
}
