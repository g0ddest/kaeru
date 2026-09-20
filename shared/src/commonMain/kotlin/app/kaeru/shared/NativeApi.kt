package app.kaeru.shared

import app.kaeru.shared.data.kodik.KodikClient
import app.kaeru.shared.data.kodik.KodikStream
import app.kaeru.shared.data.kodik.KodikTranslationOption
import app.kaeru.shared.data.kodik.TranslationType
import app.kaeru.shared.data.network.*
import app.kaeru.shared.data.shikimori.*
import app.kaeru.shared.domain.Stream
import app.kaeru.shared.domain.StreamUrl
import app.kaeru.shared.domain.Translation
import io.ktor.client.HttpClient
import kotlinx.serialization.encodeToString

/**
 * Swift boundary for the online core. JSON schemas keep non-null core fields and nullable optional metadata.
 * Swift owns account generations, one 401 refresh/retry, Keychain and persistent outbox storage.
 * Keep one instance for the app lifetime, then close its owned clients when disposing it.
 */
class NativeApi internal constructor(
    clientId: String,
    proxyUrl: String,
    client: HttpClient,
    limiter: ShikimoriRateLimiter = ShikimoriRateLimiter.shared,
) {
    constructor(clientId: String, proxyUrl: String) : this(clientId, proxyUrl, platformHttpClient())

    private val http = HttpTransport(client)
    private val shikimori = ShikimoriClient(http, clientId, proxyUrl, limiter)
    private val kodik = KodikClient(http)

    @Throws(Exception::class)
    suspend fun search(query: String): String = wireJson.encodeToString(shikimori.search(query))

    @Throws(Exception::class)
    suspend fun discover(): String = wireJson.encodeToString(shikimori.discover())

    @Throws(Exception::class)
    suspend fun seasonal(year: Int, season: String): String = wireJson.encodeToString(shikimori.seasonal(year, season))

    /** A trimmed, nonblank token overrides automatic lookup; blank resets it and clears its cache. */
    fun configureKodikToken(token: String) = kodik.configureToken(token)

    @Throws(Exception::class)
    suspend fun details(animeId: Int): String = wireJson.encodeToString(shikimori.details(animeId))

    @Throws(Exception::class)
    suspend fun library(userId: Long, accessToken: String): String = wireJson.encodeToString(shikimori.library(userId, accessToken))

    @Throws(Exception::class)
    suspend fun exchange(code: String): String = shikimori.token("authorization_code", code)

    @Throws(Exception::class)
    suspend fun refresh(refreshToken: String): String = shikimori.token("refresh_token", refreshToken)

    @Throws(Exception::class)
    suspend fun account(accessToken: String): String = wireJson.encodeToString(shikimori.account(accessToken))

    @Throws(Exception::class)
    suspend fun setRate(animeId: Int, userId: Long, rateId: Long, status: String, episodes: Int, accessToken: String): String =
        wireJson.encodeToString(shikimori.setRate(animeId, userId, rateId, status, episodes, accessToken))

    @Throws(Exception::class)
    suspend fun translations(animeId: Int): String = wireJson.encodeToString(kodik.translations(animeId).map { it.toWire() })

    @Throws(Exception::class)
    suspend fun resolve(animeId: Int, translationId: Int, episode: Int): String =
        wireJson.encodeToString(kodik.resolve(animeId, translationId, episode).toWire())

    /**
     * Drops what is kept about this anime's tracks, so the next [translations] or [resolve] asks
     * Kodik afresh: for a retry over an episode a studio has released since the catalogue was read.
     */
    @Throws(Exception::class)
    suspend fun forgetTranslations(animeId: Int) = kodik.forget(animeId)

    fun close() = http.close()

    private fun KodikTranslationOption.toWire() = Translation(
        id, title, episodesCount ?: 0,
        kind = when (type) {
            TranslationType.VOICE -> "voice"
            TranslationType.SUBTITLES -> "subtitles"
        },
    )

    private fun KodikStream.toWire() = Stream(
        urls = urls.entries.sortedBy { it.key }.map { StreamUrl(it.key, it.value) },
        headers = headers,
        translation = translation.toWire(),
        episode = episode,
    )
}
