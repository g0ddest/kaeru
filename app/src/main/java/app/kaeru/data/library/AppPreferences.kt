package app.kaeru.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kaeru.domain.model.Quality
import app.kaeru.domain.playback.PlaybackPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class AppPreferences @Inject constructor(@param:Named("prefs") private val dataStore: DataStore<Preferences>) :
    PlaybackPreferences {
    private val userIdKey = longPreferencesKey("user_id")
    private val lastFullSyncKey = longPreferencesKey("last_full_sync")
    private val watchedThresholdKey = floatPreferencesKey("watched_threshold")
    private val preferredTranslationsKey = stringPreferencesKey("preferred_translations")
    private val autoplayNextKey = booleanPreferencesKey("autoplay_next")
    private val defaultQualityKey = intPreferencesKey("default_quality")

    suspend fun userId(): Long? = dataStore.data.first()[userIdKey]

    val userId: Flow<Long?> = dataStore.data.map { it[userIdKey] }

    suspend fun setUserId(id: Long) {
        dataStore.edit { it[userIdKey] = id }
    }

    suspend fun lastFullSync(): Instant? = dataStore.data.first()[lastFullSyncKey]?.let(Instant::ofEpochMilli)

    suspend fun setLastFullSync(at: Instant) {
        dataStore.edit { it[lastFullSyncKey] = at.toEpochMilli() }
    }

    override val watchedThreshold: Flow<Float> = dataStore.data.map { it[watchedThresholdKey] ?: 0.9f }

    /**
     * Dub studios in the order the viewer wants them offered; a track matches when its title
     * contains one of these names. Stored as one newline-joined string because a preference set
     * loses the order, and the order is the whole point. An empty list is a deliberate choice and
     * is kept: only an absent key falls back to [DEFAULT_PREFERRED_TRANSLATIONS].
     */
    override val preferredTranslations: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[preferredTranslationsKey]
            ?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: DEFAULT_PREFERRED_TRANSLATIONS
    }

    suspend fun setPreferredTranslations(studios: List<String>) {
        val cleaned = studios.map { it.trim() }.filter { it.isNotEmpty() }
        dataStore.edit { it[preferredTranslationsKey] = cleaned.joinToString("\n") }
    }

    override val autoplayNext: Flow<Boolean> = dataStore.data.map { it[autoplayNextKey] ?: true }

    suspend fun setAutoplayNext(enabled: Boolean) {
        dataStore.edit { it[autoplayNextKey] = enabled }
    }

    /**
     * Quality to start playback at, or null for the best the source offers. Stored as the height,
     * so a rung a future build adds (or drops) degrades to "best available" instead of crashing.
     */
    override val defaultQuality: Flow<Quality?> = dataStore.data.map { prefs ->
        prefs[defaultQualityKey]?.let { Quality.ofHeight(it) }
    }

    suspend fun setDefaultQuality(quality: Quality?) {
        dataStore.edit { prefs ->
            if (quality == null) prefs.remove(defaultQualityKey) else prefs[defaultQualityKey] = quality.height
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    /** Drops what belongs to the signed-in account. Playback settings are the device's, not the account's. */
    suspend fun clearAccount() {
        dataStore.edit {
            it.remove(userIdKey)
            it.remove(lastFullSyncKey)
        }
    }

    companion object {
        /** Studios that dub most of what this app plays, best first. */
        val DEFAULT_PREFERRED_TRANSLATIONS = listOf(
            "AniLibria", "AniDUB", "Crunchyroll", "Amazing Dubbing", "AniBaza",
            "AniMaunt", "JAM", "Dream Cast", "SHIZA Project",
        )
    }
}
