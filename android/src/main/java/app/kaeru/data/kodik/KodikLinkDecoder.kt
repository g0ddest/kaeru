package app.kaeru.data.kodik

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decodes the `/ftor` response body into quality -> playable HLS manifest URL.
 *
 * Kodik obfuscates each `links.<quality>[].src` value with a Caesar-style
 * rotation of letters (amount unknown up front) applied to a base64 string
 * whose padding has been stripped. We brute-force the rotation amount.
 */
object KodikLinkDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    /** links JSON from /ftor -> quality(Int) -> https URL of the HLS manifest. Throws ParserBroken("links") if nothing decodes. */
    fun decode(linksJson: String): Map<Int, String> {
        val root = json.parseToJsonElement(linksJson).jsonObject
        val links = (root["links"] as? JsonObject) ?: throw KodikError.ParserBroken("links")

        val result = mutableMapOf<Int, String>()
        for ((quality, entries) in links) {
            val q = quality.toIntOrNull() ?: continue
            val array = entries as? JsonArray ?: continue
            val url = array.firstNotNullOfOrNull { entry ->
                val encoded = (entry as? JsonObject)?.get("src")?.jsonPrimitive?.content
                encoded?.let { decodeSrc(it) }
            }
            if (url != null) result[q] = url
        }
        if (result.isEmpty()) throw KodikError.ParserBroken("links")
        return result
    }

    /** ROT 0..25 + base64, must contain "manifest". */
    internal fun decodeSrc(encoded: String): String? {
        for (n in 0..25) {
            val rotated = rotate(encoded, n)
            val padded = pad(rotated)
            val decoded = runCatching { String(Base64.getDecoder().decode(padded)) }.getOrNull() ?: continue
            if (decoded.contains("manifest")) {
                return if (decoded.startsWith("//")) "https:$decoded" else decoded
            }
        }
        return null
    }

    private fun rotate(s: String, n: Int): String = buildString(s.length) {
        for (c in s) {
            append(
                when {
                    c in 'a'..'z' -> 'a' + (c - 'a' + n) % 26
                    c in 'A'..'Z' -> 'A' + (c - 'A' + n) % 26
                    else -> c
                },
            )
        }
    }

    private fun pad(s: String): String {
        val remainder = s.length % 4
        return if (remainder == 0) s else s + "=".repeat(4 - remainder)
    }
}
