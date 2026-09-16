package app.kaeru.domain.together

import app.kaeru.domain.error.TogetherFailed
import app.kaeru.domain.error.TogetherFailureReason
import app.kaeru.domain.pairing.PairingRequest
import java.net.URI
import java.security.SecureRandom
import java.util.Base64

/**
 * The whole of a shared viewing, small enough to paste into any chat.
 *
 * Two halves that travel differently on purpose. [roomId] is the name of the room and goes in the
 * path, because the relay has to read it to put two people in the same place. [key] is what makes
 * the room worth anything and goes in the fragment, which browsers, servers and every proxy in
 * between never see — so the relay carries ciphertext it cannot read, and a link forwarded on is
 * the only way anyone else gets in.
 *
 * Two forms of the same room. The https one is what goes in the share sheet: it is clickable
 * everywhere and lands on a page offering the app to whoever has not got it. The `kaeru://watch`
 * one carries an address as well, and is only meaningful while both phones are on one Wi-Fi —
 * which is why [parse] refuses any address that could be routed off it.
 */
data class RoomLink(val roomId: String, val key: ByteArray, val lan: LanEndpoint? = null) {

    /** The form to share. Nothing in it identifies the anime, the episode or either viewer. */
    fun toHttps(base: String = HTTPS_BASE): String = "$base$roomId#${encode(key)}"

    /** The form for one Wi-Fi: the same room, plus where to knock. */
    fun toLan(): String {
        val endpoint = requireNotNull(lan) { "A link without a LAN endpoint has no kaeru://watch form" }
        return "$SCHEME://$AUTHORITY?h=${endpoint.host}&p=${endpoint.port}&r=$roomId#${encode(key)}"
    }

    /** The key is a secret and a `data class` would print it. Everything else is safe to log. */
    override fun toString(): String = "RoomLink(roomId=$roomId, key=<redacted>, lan=$lan)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RoomLink) return false
        return roomId == other.roomId && key.contentEquals(other.key) && lan == other.lan
    }

    override fun hashCode(): Int {
        var result = roomId.hashCode()
        result = 31 * result + key.contentHashCode()
        result = 31 * result + (lan?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val SCHEME = "kaeru"
        const val AUTHORITY = "watch"

        /** Published with the Cast skin on GitHub Pages, and the host `assetlinks.json` verifies. */
        const val HTTPS_BASE = "https://kaeru.vitaliy.velikodniy.name/w/"

        /** The path every https form of a room shares, and the only one [parse] will follow. */
        const val HTTPS_PATH = "/w/"

        /** 64 bits: enough that a room cannot be found by guessing, short enough to stay readable. */
        const val ROOM_ID_BYTES = 8

        /** 128 bits, straight into AES-GCM. */
        const val KEY_BYTES = 16

        fun random(random: SecureRandom): RoomLink = RoomLink(
            roomId = encode(ByteArray(ROOM_ID_BYTES).also(random::nextBytes)),
            key = ByteArray(KEY_BYTES).also(random::nextBytes),
        )

        /**
         * Reads either form, and believes neither of them.
         *
         * Any application on the phone can fire `kaeru://watch`, and an https link can be handed
         * over by anybody at all. So the room name has to be exactly 64 bits of base64url, the key
         * exactly 128, and a LAN address has to be one of the ranges a home router hands out —
         * the same four [PairingRequest.isLanAddress] allows, and for the same reason: a link
         * naming a public address would have the phone open a session with a stranger's server,
         * and one naming loopback would have it open a session with whatever else is running on
         * the phone.
         */
        fun parse(uri: String): Result<RoomLink> {
            val parsed = runCatching { URI(uri) }.getOrNull() ?: return rejected()
            val key = decode(parsed.rawFragment)?.takeIf { it.size == KEY_BYTES } ?: return rejected()
            return when {
                SCHEME.equals(parsed.scheme, ignoreCase = true) -> lan(parsed, key)
                "https".equals(parsed.scheme, ignoreCase = true) -> https(parsed, key)
                else -> rejected()
            }
        }

        private fun https(uri: URI, key: ByteArray): Result<RoomLink> {
            val path = uri.path ?: return rejected()
            if (!path.startsWith(HTTPS_PATH)) return rejected()
            val roomId = path.removePrefix(HTTPS_PATH)
            if (!isRoomId(roomId)) return rejected()
            return Result.success(RoomLink(roomId, key))
        }

        private fun lan(uri: URI, key: ByteArray): Result<RoomLink> {
            if (!AUTHORITY.equals(uri.authority, ignoreCase = true)) return rejected()
            val query = queryOf(uri.rawQuery ?: return rejected())
            val roomId = query["r"] ?: return rejected()
            if (!isRoomId(roomId)) return rejected()
            val host = query["h"] ?: return rejected()
            if (!PairingRequest.isLanAddress(host)) return rejected()
            val port = query["p"]?.toIntOrNull() ?: return rejected()
            if (port !in 1..65535) return rejected()
            return Result.success(RoomLink(roomId, key, LanEndpoint(host, port)))
        }

        private fun isRoomId(value: String): Boolean = decode(value)?.size == ROOM_ID_BYTES

        private fun encode(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun decode(value: String?): ByteArray? {
            if (value.isNullOrEmpty()) return null
            return runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
        }

        private fun queryOf(raw: String): Map<String, String> = raw.split('&')
            .mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) null else pair.substring(0, eq) to pair.substring(eq + 1)
            }
            .toMap()

        private fun rejected(): Result<RoomLink> =
            Result.failure(TogetherFailed(TogetherFailureReason.BAD_LINK))
    }
}
