package app.kaeru.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class AppPreferences @Inject constructor(@param:Named("prefs") private val dataStore: DataStore<Preferences>) {
    private val userIdKey = longPreferencesKey("user_id")
    private val lastFullSyncKey = longPreferencesKey("last_full_sync")
    private val watchedThresholdKey = floatPreferencesKey("watched_threshold")

    suspend fun userId(): Long? = dataStore.data.first()[userIdKey]

    val userId: Flow<Long?> = dataStore.data.map { it[userIdKey] }

    suspend fun setUserId(id: Long) {
        dataStore.edit { it[userIdKey] = id }
    }

    suspend fun lastFullSync(): Instant? = dataStore.data.first()[lastFullSyncKey]?.let(Instant::ofEpochMilli)

    suspend fun setLastFullSync(at: Instant) {
        dataStore.edit { it[lastFullSyncKey] = at.toEpochMilli() }
    }

    val watchedThreshold: Flow<Float> = dataStore.data.map { it[watchedThresholdKey] ?: 0.9f }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    suspend fun clearAccount() {
        dataStore.edit {
            it.remove(userIdKey)
            it.remove(lastFullSyncKey)
        }
    }
}
