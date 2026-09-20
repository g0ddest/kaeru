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
 * Two schemes for the same room. The https one is what goes in the share sheet: it is clickable
 * everywhere and lands on a page offering the app to whoever has not got it. The `kaeru://watch`
 * one is for the phone itself, and comes in two forms. With an address (`h`, `p`) it names a
 * phone on this Wi-Fi and is only meaningful while both phones are on it — which is why [parse]
 * refuses any address that could be routed off it. Without one it is the relay room the https
 * form names, and is what that page's button fires: a browser hands a same-site address to no
 * app, so the page has to say `kaeru://watch` itself.
 *
 * In the app's own scheme the key may sit in the query, as `k`, as well as in the fragment. A
 * custom-scheme intent is resolved on the device and never becomes a request, so nothing on the
 * way reads it; but a phone before Android 13 writes the query of a launched intent, and never its
 * fragment, into its own log. The fragment is therefore still the better carrier, the one this
 * app writes, and the one [parse] reads first. The query is for the page, whose intent syntax
 * has a use of its own for the `#`.
 */
data class RoomLink(val roomId: String, val key: ByteArray, val lan: LanEndpoint? = null) {

    /** The form to share. Nothing in it identifies the anime, the episode or either viewer. */
    fun toHttps(base: String = HTTPS_BASE): String = "$base$roomId#${encode(key)}"

    /** The form for one Wi-Fi: the same room, plus where to knock. */
    fun toLan(): String {
        val endpoint = requireNotNull(lan) { "A link without a LAN endpoint has no LAN form" }
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

        /** The room, in the query of the app's own scheme. */
        const val ROOM_PARAM = "r"

        /** The key, in the query of the app's own scheme, when the fragment is not to be had. */
        const val KEY_PARAM = "k"

        /** Published with the Cast skin on GitHub Pages, and the host `assetlinks.json` verifies. */
        const val HTTPS_BASE = "https://kaeru.vitaliy.velikodniy.name/w/"

        /** The path every https form of a room shares, and the only one [parse] will follow. */
        const val HTTPS_PATH = "/w/"

        /** The one domain an https invitation may name. */
        const val HTTPS_HOST = "kaeru.vitaliy.velikodniy.name"

        /** 64 bits: enough that a room cannot be found by guessing, short enough to stay readable. */
        const val ROOM_ID_BYTES = 8

        /** 128 bits, straight into AES-GCM. */
        const val KEY_BYTES = 16

        fun random(random: SecureRandom): RoomLink = RoomLink(
            roomId = encode(ByteArray(ROOM_ID_BYTES).also(random::nextBytes)),
            key = ByteArray(KEY_BYTES).also(random::nextBytes),
        )

        /**
         * Reads any of the forms, and believes none of them.
         *
         * Any application on the phone can fire `kaeru://watch`, and an https link can be handed
         * over by anybody at all. So the room name has to be exactly 64 bits of base64url, the key
         * exactly 128 — each spelled the one way this app spells it, see [isRoomId] — and a LAN
         * address has to be one of the ranges a home router hands out: the same four
         * [PairingRequest.isLanAddress] allows, and for the same reason. A link naming a public
         * address would have the phone open a session with a stranger's server, and one naming
         * loopback would have it open a session with whatever else is running on the phone.
         *
         * The key is the fragment. Only in the app's own scheme, and only when there is no
         * fragment at all, is it `k` in the query instead: a fragment that is there is the key,
         * however bad, and never falls through to the query. An address is all or nothing — `h`
         * without `p`, or `p` without `h`, is a local link with a piece missing, not a relay room.
         */
        fun parse(uri: String): Result<RoomLink> {
            val parsed = runCatching { URI(uri) }.getOrNull() ?: return rejected()
            return when {
                SCHEME.equals(parsed.scheme, ignoreCase = true) -> kaeru(parsed)
                "https".equals(parsed.scheme, ignoreCase = true) -> https(parsed)
                else -> rejected()
            }
        }

        /**
         * One domain, because this form names no address to dial: the room is joined through the
         * relay built into the app, so a link from anywhere else would quietly put a stranger's
         * room id into this viewer's session. App Links already hand this app only the verified
         * domain, but [parse] is public and takes a string from wherever the caller found it.
         *
         * Extra query parameters are ignored — chat applications append tracking junk, and a room
         * should survive it — and that includes a `k`. A key in an https query is one every
         * server, proxy and referrer on the way would see, so it is not read from there.
         */
        private fun https(uri: URI): Result<RoomLink> {
            val key = keyOf(uri.rawFragment) ?: return rejected()
            if (!HTTPS_HOST.equals(uri.host, ignoreCase = true)) return rejected()
            val path = uri.path ?: return rejected()
            if (!path.startsWith(HTTPS_PATH)) return rejected()
            val roomId = path.removePrefix(HTTPS_PATH)
            if (!isRoomId(roomId)) return rejected()
            return Result.success(RoomLink(roomId, key))
        }

        /**
         * The app's own scheme: the relay room when there is no address in it, a phone on this
         * Wi-Fi when there is one. The key may come as `k` in the query, which is where the
         * landing page's intent URI carries it — in Chrome's intent syntax the `#` already
         * introduces `#Intent;…;end`.
         */
        private fun kaeru(uri: URI): Result<RoomLink> {
            if (!AUTHORITY.equals(uri.authority, ignoreCase = true)) return rejected()
            val query = queryOf(uri.rawQuery ?: return rejected())
            val key = keyOf(uri.rawFragment ?: query[KEY_PARAM]) ?: return rejected()
            val roomId = query[ROOM_PARAM] ?: return rejected()
            if (!isRoomId(roomId)) return rejected()
            val host = query["h"]
            val port = query["p"]
            if (host == null && port == null) return Result.success(RoomLink(roomId, key))
            if (host == null || !PairingRequest.isLanAddress(host)) return rejected()
            val portNumber = port?.toIntOrNull() ?: return rejected()
            if (portNumber !in 1..65535) return rejected()
            return Result.success(RoomLink(roomId, key, LanEndpoint(host, portNumber)))
        }

        /**
         * Exactly how this app writes a key, and nothing else that happens to decode to sixteen
         * bytes — for the reasons [isRoomId] gives, and one more: the key is read from two places,
         * and a spelling one of them takes must not be one the other refuses.
         */
        private fun keyOf(encoded: String?): ByteArray? {
            val bytes = decode(encoded) ?: return null
            return bytes.takeIf { it.size == KEY_BYTES && encode(it) == encoded }
        }

        /**
         * Exactly how this app writes a room name, and nothing else that happens to decode to
         * eight bytes.
         *
         * Base64 ignores the unused trailing bits of the last character, so `AAAAAAAAAAA`,
         * `AAAAAAAAAAB` and `AAAAAAAAAAC` all decode to the same eight zero bytes — three names
         * for one room, and a link that survives a typo into a room nobody is in. Padding is worse
         * still: `=` is outside the relay's own room-id pattern, so such a link would parse here
         * and earn an HTTP 400 there. Re-encoding what came back and demanding the same string
         * settles both in one line.
         */
        private fun isRoomId(value: String): Boolean {
            val bytes = decode(value) ?: return false
            return bytes.size == ROOM_ID_BYTES && encode(bytes) == value
        }

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
