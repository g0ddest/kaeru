package app.kaeru.data.kodik

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

fun kodikJson(): Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

/**
 * The answer of `POST kodik-api.com/get-player`, the one method the public
 * token is allowed to call.
 *
 * Only [found], [link] and [error] drive behaviour. [quality], [translation]
 * and [allowed] are informational and typed as raw JSON on purpose: we have
 * never captured a live payload of this endpoint, and guessing `String` where
 * Kodik sends an object would fail the whole chain at parse time.
 */
@Serializable
data class KodikGetPlayerDto(
    val found: Boolean = false,
    val link: String? = null,
    val error: String? = null,
    val quality: JsonElement? = null,
    val translation: JsonElement? = null,
    val allowed: JsonElement? = null,
) {
    /** Kodik answers `{"error": "Отсутствует или неверный токен"}`, sometimes with HTTP 401 and sometimes with 200. */
    val tokenRejected: Boolean
        get() = error?.let { it.contains("токен", ignoreCase = true) || it.contains("token", ignoreCase = true) } == true
}
