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
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.kaeru.data.kodik.KodikTokenKeys
import app.kaeru.data.download.StrandedDownloads
import app.kaeru.domain.download.DeferredRemovals
import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.download.DownloadedEpisode
import app.kaeru.domain.model.Account
import app.kaeru.domain.model.Quality
import app.kaeru.domain.playback.PlaybackNotificationPrompt
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.settings.SettingsStore
import app.kaeru.domain.settings.WATCHED_THRESHOLD_RANGE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** «Без лимита», stored rather than left absent so that turning the limit off is remembered. */
private const val UNLIMITED = -1L

/** «Как при просмотре»: no height of its own, take whatever playback would take. */
private const val QUALITY_AS_PLAYBACK = 0

@Singleton
class AppPreferences @Inject constructor(@param:Named("prefs") private val dataStore: DataStore<Preferences>) :
    PlaybackPreferences, PlaybackNotificationPrompt, SettingsStore, DeferredRemovals, StrandedDownloads {
    private val userIdKey = longPreferencesKey("user_id")
    private val lastFullSyncKey = longPreferencesKey("last_full_sync")
    private val watchedThresholdKey = floatPreferencesKey("watched_threshold")
    private val preferredTranslationsKey = stringPreferencesKey("preferred_translations")
    private val autoplayNextKey = booleanPreferencesKey("autoplay_next")
    private val pipOnLeaveKey = booleanPreferencesKey("pip_on_leave")
    private val newEpisodeNotificationsKey = booleanPreferencesKey("new_episode_notifications")
    private val defaultQualityKey = intPreferencesKey("default_quality")
    private val notificationsAskedKey = booleanPreferencesKey("notifications_asked")
    private val accountNicknameKey = stringPreferencesKey("account_nickname")
    private val accountAvatarKey = stringPreferencesKey("account_avatar")
    private val downloadLimitKey = longPreferencesKey("download_limit_bytes")
    private val downloadWifiOnlyKey = booleanPreferencesKey("download_wifi_only")
    private val downloadDeleteWatchedKey = booleanPreferencesKey("download_delete_watched")
    private val downloadQualityKey = intPreferencesKey("download_quality")
    private val pendingRemovalsKey = stringSetPreferencesKey("download_pending_removals")
    private val strandedDownloadsKey = stringSetPreferencesKey("download_stranded")

    /**
     * What a wipe leaves behind: configuration of the device, not of whoever is signed in.
     *
     * The two download sets are here for a plainer reason than the settings above them: downloads
     * are the device's and survive a logout, so a note about what to delete and about what the
     * network stranded has to survive with them, or the files they speak for are orphaned.
     */
    private val deviceKeys: List<Preferences.Key<*>> = KodikTokenKeys.all + notificationsAskedKey +
        downloadLimitKey + downloadWifiOnlyKey + downloadDeleteWatchedKey + downloadQualityKey +
        pendingRemovalsKey + strandedDownloadsKey

    suspend fun userId(): Long? = dataStore.data.first()[userIdKey]

    val userId: Flow<Long?> = dataStore.data.map { it[userIdKey] }

    suspend fun setUserId(id: Long) {
        dataStore.edit { it[userIdKey] = id }
    }

    suspend fun lastFullSync(): Instant? = dataStore.data.first()[lastFullSyncKey]?.let(Instant::ofEpochMilli)

    suspend fun setLastFullSync(at: Instant) {
        dataStore.edit { it[lastFullSyncKey] = at.toEpochMilli() }
    }

    /**
     * Who is signed in, as far as this device remembers.
     *
     * An id with no nickname beside it is deliberately not an account: it means `whoami` has not
     * answered yet, or this install predates the screen that shows a name, and answering with a
     * nameless account would put an empty line where a nickname belongs. The settings screen asks
     * Shikimori again on open, so the gap closes by itself.
     */
    val account: Flow<Account?> = dataStore.data.map { prefs ->
        val id = prefs[userIdKey]
        val nickname = prefs[accountNicknameKey]
        if (id == null || nickname == null) null else Account(id, nickname, prefs[accountAvatarKey])
    }

    suspend fun setAccountProfile(nickname: String, avatarUrl: String?) {
        dataStore.edit { prefs ->
            prefs[accountNicknameKey] = nickname
            if (avatarUrl.isNullOrBlank()) prefs.remove(accountAvatarKey) else prefs[accountAvatarKey] = avatarUrl
        }
    }

    override val watchedThreshold: Flow<Float> = dataStore.data.map { it[watchedThresholdKey] ?: 0.9f }

    /** The screen offers 0.8 to 0.95; [WATCHED_THRESHOLD_RANGE] guards everything that is not it. */
    override suspend fun setWatchedThreshold(fraction: Float) {
        // Not a number is not a share of an episode. Writing it would make every later comparison
        // false and silently stop marking anything watched at all.
        if (!fraction.isFinite()) return
        dataStore.edit { it[watchedThresholdKey] = fraction.coerceIn(WATCHED_THRESHOLD_RANGE) }
    }

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

    override suspend fun setPreferredTranslations(studios: List<String>) {
        val cleaned = studios.map { it.trim() }.filter { it.isNotEmpty() }
        dataStore.edit { it[preferredTranslationsKey] = cleaned.joinToString("\n") }
    }

    override val autoplayNext: Flow<Boolean> = dataStore.data.map { it[autoplayNextKey] ?: true }

    override suspend fun setAutoplayNext(enabled: Boolean) {
        dataStore.edit { it[autoplayNextKey] = enabled }
    }

    /** On until somebody says otherwise: the window is what plan 3 asked for, the switch is the way out. */
    override val pipOnLeave: Flow<Boolean> = dataStore.data.map { it[pipOnLeaveKey] ?: true }

    override suspend fun setPipOnLeave(enabled: Boolean) {
        dataStore.edit { it[pipOnLeaveKey] = enabled }
    }

    /** On until somebody says otherwise; turning it off also takes the background check off. */
    override val newEpisodeNotifications: Flow<Boolean> =
        dataStore.data.map { it[newEpisodeNotificationsKey] ?: true }

    override suspend fun setNewEpisodeNotifications(enabled: Boolean) {
        dataStore.edit { it[newEpisodeNotificationsKey] = enabled }
    }

    /**
     * Quality to start playback at, or null for the best the source offers. Stored as the height,
     * so a rung a future build adds (or drops) degrades to "best available" instead of crashing.
     */
    override val defaultQuality: Flow<Quality?> = dataStore.data.map { prefs ->
        prefs[defaultQualityKey]?.let { Quality.ofHeight(it) }
    }

    override suspend fun setDefaultQuality(quality: Quality?) {
        dataStore.edit { prefs ->
            if (quality == null) prefs.remove(defaultQualityKey) else prefs[defaultQualityKey] = quality.height
        }
    }

    /**
     * A Kodik key somebody typed in, or null for the public one. It is the same key
     * `DefaultKodikTokenProvider` reads first, so a token saved here takes effect on the next
     * request rather than on the next launch.
     */
    override val kodikToken: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KodikTokenKeys.override]?.trim()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun setKodikToken(token: String?) {
        val cleaned = token?.trim().orEmpty()
        dataStore.edit { prefs ->
            if (cleaned.isEmpty()) prefs.remove(KodikTokenKeys.override) else prefs[KodikTokenKeys.override] = cleaned
        }
    }

    /**
     * The download rules, with two values that have to be spelled out rather than left absent.
     *
     * An absent key means «never set» and reads back as the shipped default — 5 GB at 720p — so
     * «без лимита» is stored as `-1` and «как при просмотре» as height `0`. Without the sentinels
     * a viewer who turned the limit off would find it back at 5 GB on the next launch.
     *
     * A height this build no longer offers degrades to «как при просмотре», the same way
     * [defaultQuality] degrades to «лучшее доступное», rather than crashing on a rung that has
     * been dropped.
     */
    override val downloadPolicy: Flow<DownloadPolicy> = dataStore.data.map { prefs ->
        DownloadPolicy(
            limitBytes = when (val stored = prefs[downloadLimitKey]) {
                null -> DownloadPolicy.DEFAULT.limitBytes
                in Long.MIN_VALUE..0L -> null
                else -> stored
            },
            wifiOnly = prefs[downloadWifiOnlyKey] ?: DownloadPolicy.DEFAULT.wifiOnly,
            deleteWatched = prefs[downloadDeleteWatchedKey] ?: DownloadPolicy.DEFAULT.deleteWatched,
            quality = when (val height = prefs[downloadQualityKey]) {
                null -> DownloadPolicy.DEFAULT.quality
                else -> Quality.ofHeight(height)
            },
        )
    }

    override suspend fun setDownloadPolicy(policy: DownloadPolicy) {
        dataStore.edit { prefs ->
            prefs[downloadLimitKey] = policy.limitBytes ?: UNLIMITED
            prefs[downloadWifiOnlyKey] = policy.wifiOnly
            prefs[downloadDeleteWatchedKey] = policy.deleteWatched
            prefs[downloadQualityKey] = policy.quality?.height ?: QUALITY_AS_PLAYBACK
        }
    }

    override suspend fun notificationsAsked(): Boolean = dataStore.data.first()[notificationsAskedKey] ?: false

    override suspend fun markNotificationsAsked() {
        dataStore.edit { it[notificationsAskedKey] = true }
    }

    // --- «Удалять просмотренные»: what is promised and not yet done -----------------------------

    override suspend fun pending(): Set<DownloadedEpisode> =
        dataStore.data.first()[pendingRemovalsKey].orEmpty().mapNotNull(::toEpisode).toSet()

    override suspend fun record(episode: DownloadedEpisode) {
        dataStore.edit { it[pendingRemovalsKey] = it[pendingRemovalsKey].orEmpty() + episode.stored() }
    }

    override suspend fun forget(episode: DownloadedEpisode) {
        dataStore.edit { it[pendingRemovalsKey] = it[pendingRemovalsKey].orEmpty() - episode.stored() }
    }

    override suspend fun forgetAll() {
        dataStore.edit { it.remove(pendingRemovalsKey) }
    }

    /** «100:4» — two numbers and a separator neither of them can contain. */
    private fun DownloadedEpisode.stored() = "$animeId:$episode"

    /** A row that does not read as two numbers is dropped rather than guessed at. */
    private fun toEpisode(stored: String): DownloadedEpisode? {
        val parts = stored.split(':')
        if (parts.size != 2) return null
        val animeId = parts[0].toIntOrNull() ?: return null
        val episode = parts[1].toIntOrNull() ?: return null
        return DownloadedEpisode(animeId, episode)
    }

    // --- which downloads the network stranded ----------------------------------------------------

    override suspend fun stranded(): Set<String> = dataStore.data.first()[strandedDownloadsKey].orEmpty()

    override suspend fun recordStranded(id: String) {
        dataStore.edit { it[strandedDownloadsKey] = it[strandedDownloadsKey].orEmpty() + id }
    }

    override suspend fun forgetStranded(id: String) {
        dataStore.edit { it[strandedDownloadsKey] = it[strandedDownloadsKey].orEmpty() - id }
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
            it.remove(accountNicknameKey)
            it.remove(accountAvatarKey)
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
