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

internal class HttpTransport(private val original: HttpClient) {
    private val client = original.config {
        expectSuccess = false
        followRedirects = true
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    internal data class Response(val status: Int, val body: String, val retryAfter: String?) {
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
        // Ktor exceptions can embed an OAuth query or signed URL. Keep that out of NSError.
        throw Exception("Network request failed. Check the connection and try again.")
    }

    fun close() { client.close(); original.close() }
}

internal fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.int(key: String): Int = string(key).toIntOrNull() ?: 0
internal fun JsonObject.long(key: String): Long = string(key).toLongOrNull() ?: 0
internal fun parseObject(body: String): JsonObject = try {
    wireJson.parseToJsonElement(body).jsonObject
} catch (_: Exception) { throw Exception("Invalid API response.") }
