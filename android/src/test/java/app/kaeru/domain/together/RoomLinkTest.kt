package app.kaeru.domain.together

import app.kaeru.domain.error.TogetherFailed
import app.kaeru.domain.error.TogetherFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class RoomLinkTest {
    private val random = SecureRandom()

    private fun parsed(uri: String): RoomLink = RoomLink.parse(uri).getOrThrow()

    private fun reason(uri: String): TogetherFailureReason? =
        (RoomLink.parse(uri).exceptionOrNull() as? TogetherFailed)?.reason

    @Test
    fun `an invitation from somebody else's domain is not an invitation`() {
        val key = "AAAAAAAAAAAAAAAAAAAAAA"
        val room = "cm9vbTEyMzQ"
        assertTrue(RoomLink.parse("https://${RoomLink.HTTPS_HOST}/w/$room#$key").isSuccess)
        // The same shape, a different host. Nothing is dialled at it — the room would be opened on
        // this app's own relay, which is exactly why the name has to be checked here.
        assertTrue(RoomLink.parse("https://example.com/w/$room#$key").isFailure)
        assertTrue(RoomLink.parse("https://kaeru.vitaliy.velikodniy.name.evil.example/w/$room#$key").isFailure)
        // Domain names are not case-sensitive and neither is this.
        assertTrue(RoomLink.parse("https://KAERU.Vitaliy.Velikodniy.NAME/w/$room#$key").isSuccess)
    }

    @Test
    fun `a shared link comes back as the room it was made from`() {
        val link = RoomLink.random(random)

        val back = parsed(link.toHttps())

        assertEquals(link.roomId, back.roomId)
        assertTrue(link.key.contentEquals(back.key))
        assertNull(back.lan)
    }

    @Test
    fun `a local link comes back with the address to knock on`() {
        val link = RoomLink.random(random).copy(lan = LanEndpoint("192.168.1.42", 41_234))

        val back = parsed(link.toLan())

        assertEquals(link, back)
        assertEquals(LanEndpoint("192.168.1.42", 41_234), back.lan)
    }

    @Test
    fun `the key rides in the fragment, where no server sees it`() {
        val link = RoomLink.random(random)

        val https = link.toHttps()
        val beforeFragment = https.substringBefore('#')
        val fragment = https.substringAfter('#')

        assertEquals(1, https.count { it == '#' })
        assertEquals("${RoomLink.HTTPS_BASE}${link.roomId}", beforeFragment)
        assertFalse(beforeFragment.contains(fragment))
        assertEquals("kaeru://watch?h=192.168.1.42&p=41234&r=${link.roomId}#$fragment", link.copy(lan = LanEndpoint("192.168.1.42", 41_234)).toLan())
    }

    @Test
    fun `a room is 64 bits and a key is 128, and anything else is not a link`() {
        val link = RoomLink.random(random)

        assertEquals(RoomLink.KEY_BYTES, link.key.size)
        assertEquals(RoomLink.ROOM_ID_BYTES, java.util.Base64.getUrlDecoder().decode(link.roomId).size)
        // A key one byte short, and a room name one byte long.
        assertEquals(TogetherFailureReason.BAD_LINK, reason("${RoomLink.HTTPS_BASE}${link.roomId}#AAAAAAAAAAAAAAAAAAAA"))
        assertEquals(TogetherFailureReason.BAD_LINK, reason("${RoomLink.HTTPS_BASE}AAAAAAAAAAAAA#${link.toHttps().substringAfter('#')}"))
        assertEquals(TogetherFailureReason.BAD_LINK, reason("${RoomLink.HTTPS_BASE}${link.roomId}"))
        assertEquals(TogetherFailureReason.BAD_LINK, reason("${RoomLink.HTTPS_BASE}${link.roomId}#not base64!"))
    }

    @Test
    fun `a room name that is not exactly how this app writes one is not this app's room`() {
        val link = RoomLink.random(random)
        val key = link.toHttps().substringAfter('#')

        fun https(roomId: String) = reason("${RoomLink.HTTPS_BASE}$roomId#$key")

        // Padded, which the relay's own room-id pattern rejects, so the link would parse here and
        // earn an HTTP 400 there.
        assertEquals(TogetherFailureReason.BAD_LINK, https("AAAAAAAAAAA="))
        // Two spellings of the same eight bytes: base64 ignores the unused trailing bits, so these
        // would otherwise be two names for one room.
        assertEquals("AAAAAAAAAAA", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(8)))
        assertEquals(TogetherFailureReason.BAD_LINK, https("AAAAAAAAAAB"))
        assertEquals(TogetherFailureReason.BAD_LINK, https("AAAAAAAAAAC"))
        assertTrue(RoomLink.parse("${RoomLink.HTTPS_BASE}AAAAAAAAAAA#$key").isSuccess)
    }

    @Test
    fun `a local link to anywhere but the local network is refused`() {
        val link = RoomLink.random(random)
        val key = link.toHttps().substringAfter('#')

        fun lan(host: String, port: Int = 41_234) = "kaeru://watch?h=$host&p=$port&r=${link.roomId}#$key"

        assertEquals(TogetherFailureReason.BAD_LINK, reason(lan("8.8.8.8")))
        assertEquals(TogetherFailureReason.BAD_LINK, reason(lan("203.0.113.9")))
        // Loopback with the public internet: a link naming it would have the phone open a session
        // with whatever else is running on the phone.
        assertEquals(TogetherFailureReason.BAD_LINK, reason(lan("127.0.0.1")))
        assertEquals(TogetherFailureReason.BAD_LINK, reason(lan("172.32.0.1")))
        assertEquals(TogetherFailureReason.BAD_LINK, reason(lan("192.168.1.42", port = 0)))
        assertEquals(TogetherFailureReason.BAD_LINK, reason(lan("192.168.1.42", port = 70_000)))
        assertTrue(RoomLink.parse(lan("10.0.0.4")).isSuccess)
        assertTrue(RoomLink.parse(lan("172.16.3.1")).isSuccess)
        assertTrue(RoomLink.parse(lan("169.254.7.7")).isSuccess)
    }

    @Test
    fun `anything that is not one of the two forms is not a link`() {
        val link = RoomLink.random(random)
        val key = link.toHttps().substringAfter('#')

        assertEquals(TogetherFailureReason.BAD_LINK, reason("http://kaeru.vitaliy.velikodniy.name/w/${link.roomId}#$key"))
        assertEquals(TogetherFailureReason.BAD_LINK, reason("https://kaeru.vitaliy.velikodniy.name/x/${link.roomId}#$key"))
        assertEquals(TogetherFailureReason.BAD_LINK, reason("kaeru://pair?h=192.168.1.42&p=41234&r=${link.roomId}#$key"))
        assertEquals(TogetherFailureReason.BAD_LINK, reason("kaeru://watch?p=41234&r=${link.roomId}#$key"))
        assertEquals(TogetherFailureReason.BAD_LINK, reason("не ссылка"))
        assertEquals(TogetherFailureReason.BAD_LINK, reason(""))
    }

    // --- the app's own scheme without an address -------------------------------------------------

    @Test
    fun `a relay room travels in the app's own scheme with the key in the query`() {
        val link = RoomLink.random(random)
        val key = link.toHttps().substringAfter('#')

        val back = parsed("kaeru://watch?r=${link.roomId}&k=$key")

        assertEquals(parsed(link.toHttps()), back)
        assertEquals(link, back)
        assertNull(back.lan)
        // The order of the parameters is not part of the form.
        assertEquals(link, parsed("kaeru://watch?k=$key&r=${link.roomId}"))
        // And with the key where the https form keeps it, it is still the same room.
        assertEquals(link, parsed("kaeru://watch?r=${link.roomId}#$key"))
    }

    @Test
    fun `the local form takes its key from the query as well`() {
        val link = RoomLink.random(random)
        val key = link.toHttps().substringAfter('#')

        assertEquals(
            link.copy(lan = LanEndpoint("192.168.1.42", 41_234)),
            parsed("kaeru://watch?h=192.168.1.42&p=41234&r=${link.roomId}&k=$key"),
        )
    }

    @Test
    fun `a key in the query that is not sixteen bytes is refused`() {
        val link = RoomLink.random(random)

        fun relay(key: String) = reason("kaeru://watch?r=${link.roomId}&k=$key")

        // Fifteen bytes, then seventeen.
        assertEquals(TogetherFailureReason.BAD_LINK, relay("A".repeat(20)))
        assertEquals(TogetherFailureReason.BAD_LINK, relay("A".repeat(23)))
        // `+` is a legal query character and not in the base64url alphabet, so it is the decoder
        // that refuses this one, not the URI parser.
        assertEquals(TogetherFailureReason.BAD_LINK, relay("AAAAAAAAAAAAAAAAAAAA+A"))
        assertEquals(TogetherFailureReason.BAD_LINK, relay(""))
        assertEquals(TogetherFailureReason.BAD_LINK, reason("kaeru://watch?r=${link.roomId}"))
    }

    @Test
    fun `a key is spelled exactly one way, in the query and in the fragment alike`() {
        val link = RoomLink.random(random)
        // Sixteen zero bytes: `AAAAAAAAAAAAAAAAAAAAAA` is how this app writes them. Padded, and
        // with the unused trailing bits set, are two more spellings of the same bytes, and a key
        // read from one place must not be a key the other place refuses.
        assertTrue(RoomLink.parse("kaeru://watch?r=${link.roomId}&k=AAAAAAAAAAAAAAAAAAAAAA").isSuccess)
        assertTrue(RoomLink.parse("${RoomLink.HTTPS_BASE}${link.roomId}#AAAAAAAAAAAAAAAAAAAAAA").isSuccess)
        assertEquals(TogetherFailureReason.BAD_LINK, reason("kaeru://watch?r=${link.roomId}&k=AAAAAAAAAAAAAAAAAAAAAA=="))
        assertEquals(TogetherFailureReason.BAD_LINK, reason("kaeru://watch?r=${link.roomId}&k=AAAAAAAAAAAAAAAAAAAAAB"))
        assertEquals(TogetherFailureReason.BAD_LINK, reason("${RoomLink.HTTPS_BASE}${link.roomId}#AAAAAAAAAAAAAAAAAAAAAA=="))
        assertEquals(TogetherFailureReason.BAD_LINK, reason("${RoomLink.HTTPS_BASE}${link.roomId}#AAAAAAAAAAAAAAAAAAAAAB"))
    }

    @Test
    fun `when both are present, the fragment is the key`() {
        val link = RoomLink.random(random)
        val other = RoomLink.random(random)
        val inQuery = other.toHttps().substringAfter('#')
        val inFragment = link.toHttps().substringAfter('#')

        val back = parsed("kaeru://watch?r=${link.roomId}&k=$inQuery#$inFragment")

        assertTrue(back.key.contentEquals(link.key))
        assertFalse(back.key.contentEquals(other.key))
        // A fragment that is there is the key, however bad. It never falls through to the query —
        // not when it is three bytes, and not when it is nothing at all.
        assertEquals(TogetherFailureReason.BAD_LINK, reason("kaeru://watch?r=${link.roomId}&k=$inQuery#AAAA"))
        assertEquals(TogetherFailureReason.BAD_LINK, reason("kaeru://watch?r=${link.roomId}&k=$inQuery#"))
    }

    @Test
    fun `the https form keeps its key in the fragment`() {
        val link = RoomLink.random(random)
        val key = link.toHttps().substringAfter('#')

        // A key in an https query is one every server, proxy and referrer on the way would see.
        assertEquals(TogetherFailureReason.BAD_LINK, reason("${RoomLink.HTTPS_BASE}${link.roomId}?k=$key"))
    }

    @Test
    fun `a key in the query does not loosen the address rules`() {
        val link = RoomLink.random(random)
        val key = link.toHttps().substringAfter('#')

        assertEquals(TogetherFailureReason.BAD_LINK, reason("kaeru://watch?h=8.8.8.8&p=41234&r=${link.roomId}&k=$key"))
        // Half an address is not a relay room: it is a local link with a piece missing.
        assertEquals(TogetherFailureReason.BAD_LINK, reason("kaeru://watch?h=192.168.1.42&r=${link.roomId}&k=$key"))
        assertEquals(TogetherFailureReason.BAD_LINK, reason("kaeru://watch?p=41234&r=${link.roomId}&k=$key"))
        assertEquals(TogetherFailureReason.BAD_LINK, reason("kaeru://watch?h=192.168.1.42&r=${link.roomId}#$key"))
        // Without the `//` there is no authority, so nothing in it is `watch`.
        assertEquals(TogetherFailureReason.BAD_LINK, reason("kaeru:watch?r=${link.roomId}&k=$key"))
    }

    @Test
    fun `two rooms drawn in a row are not the same room`() {
        val first = RoomLink.random(random)
        val second = RoomLink.random(random)

        assertNotEquals(first.roomId, second.roomId)
        assertFalse(first.key.contentEquals(second.key))
    }

    @Test
    fun `a link never prints its own key`() {
        val link = RoomLink.random(random).copy(lan = LanEndpoint("192.168.1.42", 41_234))

        val printed = link.toString()

        assertTrue(printed.contains(link.roomId))
        assertTrue(printed.contains("192.168.1.42"))
        assertFalse(printed.contains(link.toHttps().substringAfter('#')))
    }
}
