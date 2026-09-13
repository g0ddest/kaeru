package app.kaeru.data.kodik

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kaeru.di.IoDispatcher
import app.kaeru.di.KodikPlayerClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Supplies the token every `kodik-api.com` call needs.
 *
 * Kodik never issued us a private key, so the fallback is the public token that
 * Kodik's own embed script carries: it is accepted by `get-player` (and only by
 * `get-player` — `search` rejects it).
 */
interface KodikTokenProvider {
    /** @param forceRefresh re-extracts the script token instead of using the cached one, after Kodik rejected it. */
    suspend fun token(forceRefresh: Boolean = false): String
}

/**
 * Where the Kodik token lives in the preference store.
 *
 * Named in one place rather than inline because these three are device configuration, not
 * account data: whoever wipes the store has to know to leave them alone, or a key somebody
 * typed in by hand disappears with a sign-out.
 */
internal object KodikTokenKeys {
    val override = stringPreferencesKey("kodik_token_override")
    val token = stringPreferencesKey("kodik_token")
    val storedAt = longPreferencesKey("kodik_token_at")

    val all: List<Preferences.Key<*>> = listOf(override, token, storedAt)
}

/**
 * Resolution order: a token stored by settings, then the one baked in at build
 * time, then the public token scraped from `add-players.min.js`. Only the
 * scraped token is cached (24 h) and only it is re-extracted on `forceRefresh`:
 * the first two are deliberate configuration, and refetching them would yield
 * the same value anyway.
 */
@Singleton
class DefaultKodikTokenProvider @Inject constructor(
    @param:KodikPlayerClient private val client: OkHttpClient,
    @param:Named("prefs") private val dataStore: DataStore<Preferences>,
    @param:Named("kodikConfiguredToken") private val configuredToken: String,
    private val clock: Clock,
    @param:Named("kodikAddPlayersUrl") private val addPlayersUrl: String,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : KodikTokenProvider {

    private val overrideKey = KodikTokenKeys.override
    private val tokenKey = KodikTokenKeys.token
    private val tokenAtKey = KodikTokenKeys.storedAt

    /** Serialises extraction so a burst of resolves fetches the script once, not once per caller. */
    private val fetchLock = Mutex()

    override suspend fun token(forceRefresh: Boolean): String {
        settingsToken()?.let { return it }
        configuredToken.trim().takeIf { it.isNotEmpty() }?.let { return it }
        return fetchLock.withLock {
            val cached = if (forceRefresh) null else cachedToken()
            cached ?: extractAndCache()
        }
    }

    private suspend fun settingsToken(): String? =
        dataStore.data.first()[overrideKey]?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun cachedToken(): String? {
        val prefs = dataStore.data.first()
        val token = prefs[tokenKey]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val storedAt = prefs[tokenAtKey]?.let(Instant::ofEpochMilli) ?: return null
        val age = Duration.between(storedAt, clock.instant())
        // A negative age means the device clock moved backwards; re-extract rather than trust it.
        if (age.isNegative || age >= CACHE_TTL) return null
        return token
    }

    private suspend fun extractAndCache(): String {
        val script = download(addPlayersUrl)
        val token = KodikHtmlParser.extractPublicToken(script) ?: throw KodikError.NoToken()
        dataStore.edit {
            it[tokenKey] = token
            it[tokenAtKey] = clock.instant().toEpochMilli()
        }
        return token
    }

    private suspend fun download(url: String): String = withContext(io) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", KodikConstants.BROWSER_UA)
            .header("Referer", "${KodikConstants.PLAYER_HOST}/")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                // A non-2xx here means no key, not a dead connection: say so, so the
                // user is not told to check an internet connection that works.
                if (!response.isSuccessful) {
                    throw KodikError.NoToken(IOException("add-players.min.js answered HTTP ${response.code}"))
                }
                response.body.string()
            }
        } catch (e: IOException) {
            throw KodikError.Network(e)
        }
    }

    private companion object {
        val CACHE_TTL: Duration = Duration.ofHours(24)
    }
}
