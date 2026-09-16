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
