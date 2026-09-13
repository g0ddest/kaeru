package app.kaeru.domain.pairing

import app.kaeru.domain.error.PairingFailed
import app.kaeru.domain.error.PairingFailureReason
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What the television puts in its QR code: where to reach it, the one-time secret it will accept,
 * and the name to show the person holding the phone.
 *
 * The address is checked here rather than where the socket is opened, because this is the first
 * place the link is looked at and the check is the whole point of the type. Any application or web
 * page can fire `kaeru://pair`, so a link is only honoured when it points at an address that cannot
 * be routed off the local network: the four ranges a home router hands out, and nothing else.
 * Loopback is refused with the public internet — a television is never at `127.0.0.1`, and allowing
 * it would let anything on the phone collect an authorization code by standing up a local server.
 */
data class PairingRequest(
    val host: String,
    val port: Int,
    val nonce: String,
    val name: String,
) {
    /** The payload of the QR code, and the deep link the phone receives. */
    fun toUri(): String = buildString {
        append(SCHEME).append("://").append(AUTHORITY).append('?')
        append("host=").append(encode(host))
        append("&port=").append(port)
        append("&nonce=").append(encode(nonce))
        append("&name=").append(encode(name.take(MAX_NAME)))
    }

    companion object {
        const val SCHEME = "kaeru"
        const val AUTHORITY = "pair"

        /** A name is a label on a screen, not a payload; anything longer is a link worth suspecting. */
        const val MAX_NAME = 64

        /** The nonce this app issues is 43 characters of base64url; the cap leaves room and no more. */
        const val MAX_NONCE = 128

        fun parse(uri: String): Result<PairingRequest> {
            val parsed = runCatching { URI(uri) }.getOrNull() ?: return rejected()
            if (!SCHEME.equals(parsed.scheme, ignoreCase = true)) return rejected()
            if (!AUTHORITY.equals(parsed.authority, ignoreCase = true)) return rejected()
            val query = queryOf(parsed.rawQuery ?: return rejected())
            val host = query["host"] ?: return rejected()
            if (!isLanAddress(host)) return rejected()
            val port = query["port"]?.toIntOrNull() ?: return rejected()
            if (port !in 1..65535) return rejected()
            val nonce = query["nonce"] ?: return rejected()
            if (nonce.isEmpty() || nonce.length > MAX_NONCE) return rejected()
            val name = (query["name"] ?: "").take(MAX_NAME)
            return Result.success(PairingRequest(host, port, nonce, name))
        }

        /**
         * True for the IPv4 literals a device can only be reached at from inside the same network:
         * `10/8`, `172.16/12`, `192.168/16` and the link-local `169.254/16` a television falls back
         * to when there is no router handing out addresses.
         */
        fun isLanAddress(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4) return false
            val octets = parts.map { part ->
                // A leading zero reads as octal in some resolvers and as decimal in others; a link
                // whose meaning depends on who parses it is not a link worth following.
                if (part.isEmpty() || part.length > 3 || (part.length > 1 && part[0] == '0')) return false
                part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
            }
            return when {
                octets[0] == 10 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                octets[0] == 169 && octets[1] == 254 -> true
                else -> false
            }
        }

        private fun rejected(): Result<PairingRequest> =
            Result.failure(PairingFailed(PairingFailureReason.BAD_LINK))

        private fun queryOf(raw: String): Map<String, String> = raw.split('&')
            .mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val key = decode(pair.substring(0, eq))
                val value = decode(pair.substring(eq + 1))
                if (key == null || value == null) null else key to value
            }
            .toMap()

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

        private fun decode(value: String): String? =
            runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrNull()
    }
}
