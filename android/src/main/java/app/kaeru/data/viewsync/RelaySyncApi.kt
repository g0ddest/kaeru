package app.kaeru.data.viewsync

import app.kaeru.domain.viewsync.SyncFailure
import app.kaeru.domain.viewsync.SyncTitles
import app.kaeru.domain.viewsync.ViewingSyncApi
import app.kaeru.shared.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The signed-in account's bearer for one request, and a refreshed one for a single retry after a
 * 401 — the same session every Shikimori call runs on.
 */
interface BearerSession {
    suspend fun <T> withBearer(call: suspend (token: String) -> T): T
}

/**
 * `GET /sync` and `POST /sync` on the relay with the Shikimori bearer.
 *
 * The relay is configured as the `wss://` address rooms use; the same worker answers `https://` on
 * the same host. A build without a relay has no endpoint, and every call fails as unavailable
 * without dialling anything.
 */
class RelaySyncApi(
    relayUrl: String,
    private val client: OkHttpClient,
    private val session: BearerSession,
    private val io: CoroutineDispatcher,
) : ViewingSyncApi {

    val endpoint: HttpUrl? = endpointOf(relayUrl)

    override suspend fun get(): SyncTitles = call(null)

    override suspend fun post(titles: SyncTitles): SyncTitles = call(SyncWire.body(titles))

    private suspend fun call(body: String?): SyncTitles {
        val url = endpoint ?: throw SyncFailure(SyncFailure.Kind.UNAVAILABLE)
        return session.withBearer { token ->
            // No session: nothing goes out anonymous, since the worker could only refuse it.
            if (token.isBlank()) throw SyncFailure(SyncFailure.Kind.SIGNED_OUT)
            exchange(url, token, body)
        }
    }

    private suspend fun exchange(url: HttpUrl, token: String, body: String?): SyncTitles = withContext(io) {
        // The bearer and the content type and nothing else: the worker's preflight allows only those.
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .apply { if (body == null) get() else post(body.toRequestBody(JSON)) }
            .build()
        val (status, text) = try {
            client.newCall(request).execute().use { response -> response.code to response.body.string() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            throw SyncFailure(SyncFailure.Kind.OFFLINE)
        }
        if (status !in 200..299) throw refusal(status, text)
        SyncWire.titles(text)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** `wss://host/…` → `https://host/sync`; null for anything that is not a relay address. */
        fun endpointOf(relayUrl: String): HttpUrl? {
            val trimmed = relayUrl.trim()
            val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
            val secure = when (scheme) {
                "wss", "https" -> true
                "ws", "http" -> false
                else -> return null
            }
            val rest = trimmed.substringAfter("://")
            val parsed = ((if (secure) "https://" else "http://") + rest).toHttpUrlOrNull() ?: return null
            if (parsed.host.isBlank()) return null
            return parsed.newBuilder().encodedPath("/sync").query(null).fragment(null).build()
        }

        /** A non-2xx answer. The 401 goes out as the [ApiException] the session refreshes on. */
        internal fun refusal(status: Int, text: String): Exception {
            val error = runCatching {
                ((Json.parseToJsonElement(text) as? JsonObject)?.get("error") as? JsonPrimitive)?.content
            }.getOrNull()
            return when {
                status == 401 || error == "sign_in" -> ApiException(401)
                status == 429 -> SyncFailure(SyncFailure.Kind.THROTTLED)
                error == "unavailable" -> SyncFailure(SyncFailure.Kind.UNAVAILABLE)
                error == "parameters" -> SyncFailure(SyncFailure.Kind.PARAMETERS)
                else -> SyncFailure(SyncFailure.Kind.UNKNOWN)
            }
        }
    }
}
