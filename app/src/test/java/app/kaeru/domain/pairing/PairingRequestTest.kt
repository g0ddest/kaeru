package app.kaeru.domain.pairing

import app.kaeru.domain.error.PairingFailed
import app.kaeru.domain.error.PairingFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRequestTest {

    private fun parsed(uri: String) = PairingRequest.parse(uri).getOrNull()

    @Test
    fun `a request survives the trip through its own uri`() {
        val request = PairingRequest("192.168.1.42", 41234, "9nKXr-_A0", "Гостиная TV")
        assertEquals(request, PairingRequest.parse(request.toUri()).getOrThrow())
    }

    @Test
    fun `the uri is the one the television shows on its screen`() {
        val uri = PairingRequest("10.0.0.7", 8080, "abc", "TV").toUri()
        assertTrue(uri, uri.startsWith("kaeru://pair?"))
        assertTrue(uri, uri.contains("host=10.0.0.7"))
        assertTrue(uri, uri.contains("port=8080"))
        assertTrue(uri, uri.contains("nonce=abc"))
        assertTrue(uri, uri.contains("name=TV"))
    }

    @Test
    fun `every private range a home router hands out is accepted`() {
        val hosts = listOf("10.0.0.7", "10.255.255.254", "172.16.0.1", "172.31.255.4", "192.168.1.42", "169.254.3.9")
        hosts.forEach { host ->
            assertEquals(host, parsed("kaeru://pair?host=$host&port=9&nonce=n&name=TV")?.host)
        }
    }

    @Test
    fun `an address that can be reached from outside the home is refused`() {
        val hosts = listOf(
            "8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "192.169.1.1", "11.0.0.1",
            "127.0.0.1", "0.0.0.0", "255.255.255.255",
        )
        hosts.forEach { host ->
            assertNull(host, parsed("kaeru://pair?host=$host&port=9&nonce=n&name=TV"))
        }
    }

    @Test
    fun `a name that is not an address at all is refused`() {
        listOf("shikimori.io", "tv.local", "localhost", "::1", "fe80::1", "010.0.0.1", "10.0.0", "10.0.0.256")
            .forEach { host -> assertNull(host, parsed("kaeru://pair?host=$host&port=9&nonce=n&name=TV")) }
    }

    @Test
    fun `a refused link says the link is the problem`() {
        val failure = PairingRequest.parse("kaeru://pair?host=8.8.8.8&port=9&nonce=n&name=TV").exceptionOrNull()
        assertTrue(failure.toString(), failure is PairingFailed)
        assertEquals(PairingFailureReason.BAD_LINK, (failure as PairingFailed).reason)
    }

    @Test
    fun `a link that is not a pairing link at all is refused`() {
        listOf(
            "kaeru://oauth?code=abc",
            "https://shikimori.io/pair?host=10.0.0.1&port=9&nonce=n&name=TV",
            "kaeru://pair",
            "not a uri at all",
            "",
        ).forEach { uri -> assertNull(uri, parsed(uri)) }
    }

    @Test
    fun `a port outside the range a socket can listen on is refused`() {
        listOf("0", "-1", "65536", "80x", "").forEach { port ->
            assertNull(port, parsed("kaeru://pair?host=10.0.0.1&port=$port&nonce=n&name=TV"))
        }
    }

    @Test
    fun `a missing or overlong nonce is refused`() {
        assertNull(parsed("kaeru://pair?host=10.0.0.1&port=9&name=TV"))
        assertNull(parsed("kaeru://pair?host=10.0.0.1&port=9&nonce=&name=TV"))
        assertNull(parsed("kaeru://pair?host=10.0.0.1&port=9&nonce=${"a".repeat(200)}&name=TV"))
    }

    @Test
    fun `a television with no name of its own still pairs`() {
        assertEquals("", parsed("kaeru://pair?host=10.0.0.1&port=9&nonce=n")?.name)
    }

    @Test
    fun `the name is cut rather than allowed to fill the screen`() {
        val long = "Т".repeat(300)
        val request = PairingRequest("10.0.0.1", 9, "n", long)
        assertEquals(PairingRequest.MAX_NAME, PairingRequest.parse(request.toUri()).getOrThrow().name.length)
    }

    @Test
    fun `the lan check is available to the layer that opens the socket`() {
        assertTrue(PairingRequest.isLanAddress("192.168.0.1"))
        assertTrue(PairingRequest.isLanAddress("169.254.0.1"))
        assertTrue(!PairingRequest.isLanAddress("127.0.0.1"))
        assertTrue(!PairingRequest.isLanAddress("example.org"))
    }
}
