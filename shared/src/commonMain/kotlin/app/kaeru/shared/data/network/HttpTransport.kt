package app.kaeru.shared.data.network

import app.kaeru.shared.ApiException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal expect fun platformHttpClient(): HttpClient

internal val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * The request never got an answer: no network, a dead host, a timeout, a dropped connection.
 *
 * Its own type rather than a bare `Exception`, because the two consumers of this module read it
 * differently. Swift only ever shows the message. Android decides on it: a rate written without a
 * network is applied locally and queued, and only a failure of *this* kind may do that — a
 * refusal must not be mistaken for a tunnel.
 *
 * No cause on purpose: Ktor's exceptions can embed an OAuth query or a signed URL, and the
 * exception object crosses into an `NSError` that ends up in crash reports.
 */
class NetworkException : Exception("Network request failed. Check the connection and try again.")

/**
 * One HTTP client with the module's timeouts on it, and a response reduced to what the clients
 * read: a status, a body, a `Retry-After`.
 *
 * Public because Android builds it over its own OkHttp-backed Ktor client and hands it to both
 * clients of this module; iOS gets it through [app.kaeru.shared.NativeApi].
 */
class HttpTransport(private val original: HttpClient) {
    private val client = original.config {
        expectSuccess = false
        followRedirects = true
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    class Response(val status: Int, val body: String, val retryAfter: String?) {
        fun successfulBody(): String {
            if (status !in 200..299) throw ApiException(status)
            return body
        }
    }

    suspend fun request(url: String, configure: HttpRequestBuilder.() -> Unit = {}): Response = try {
        val response = client.request(url, configure)
        Response(response.status.value, response.bodyAsText(), response.headers["Retry-After"])
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw NetworkException()
    }

    fun close() { client.close(); original.close() }
}

internal fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.int(key: String): Int = string(key).toIntOrNull() ?: 0
internal fun JsonObject.long(key: String): Long = string(key).toLongOrNull() ?: 0
internal fun parseObject(body: String): JsonObject = try {
    wireJson.parseToJsonElement(body).jsonObject
} catch (_: Exception) { throw Exception("Invalid API response.") }
