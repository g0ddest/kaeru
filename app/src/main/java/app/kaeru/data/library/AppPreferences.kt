package app.kaeru.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kaeru.data.kodik.KodikTokenKeys
import app.kaeru.domain.model.Quality
import app.kaeru.domain.playback.PlaybackNotificationPrompt
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
    PlaybackPreferences, PlaybackNotificationPrompt {
    private val userIdKey = longPreferencesKey("user_id")
    private val lastFullSyncKey = longPreferencesKey("last_full_sync")
    private val watchedThresholdKey = floatPreferencesKey("watched_threshold")
    private val preferredTranslationsKey = stringPreferencesKey("preferred_translations")
    private val autoplayNextKey = booleanPreferencesKey("autoplay_next")
    private val defaultQualityKey = intPreferencesKey("default_quality")
    private val notificationsAskedKey = booleanPreferencesKey("notifications_asked")

    /** What a wipe leaves behind: configuration of the device, not of whoever is signed in. */
    private val deviceKeys: List<Preferences.Key<*>> = KodikTokenKeys.all + notificationsAskedKey

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
     * Dub studios in the order the viewer wants them offered, and nothing else; a track matches
     * when its title contains one of these names. Stored as one newline-joined string because a
     * preference set loses the order, and the order is the whole point.
     *
     * An absent key reads as an empty list rather than as the studios the app ships with. Those
     * live in `TranslationRanker.DEFAULT_STUDIOS` and rank below what this viewer actually
     * watches, which is a decision for the ranker to make — a store that answered with them could
     * not say whether a viewer had chosen them or simply never opened the setting.
     */
    override val preferredTranslations: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[preferredTranslationsKey]
            ?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
            .orEmpty()
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

    override suspend fun notificationsAsked(): Boolean = dataStore.data.first()[notificationsAskedKey] ?: false

    override suspend fun markNotificationsAsked() {
        dataStore.edit { it[notificationsAskedKey] = true }
    }

    /**
     * Wipes the store except for what belongs to this device rather than to the app: the Kodik
     * key somebody typed in, the token scraped for it, and the note that the notification
     * question has already been put. Those are configuration — a wipe that took them would leave
     * a device that used to play silently unable to, and would put a system prompt the viewer
     * has already answered in front of them again.
     */
    suspend fun clear() {
        dataStore.edit { prefs ->
            val kept = deviceKeys.mapNotNull { key -> prefs[key]?.let { key to it } }
            prefs.clear()
            kept.forEach { (key, value) -> prefs.put(key, value) }
        }
    }

    /** Drops what belongs to the signed-in account. Playback settings are the device's, not the account's. */
    suspend fun clearAccount() {
        dataStore.edit {
            it.remove(userIdKey)
            it.remove(lastFullSyncKey)
        }
    }
}

/**
 * The one cast the preference API cannot express on its own: a value just read from [key] is by
 * construction a value [key] accepts.
 */
@Suppress("UNCHECKED_CAST")
private fun MutablePreferences.put(key: Preferences.Key<*>, value: Any) {
    this[key as Preferences.Key<Any>] = value
}
