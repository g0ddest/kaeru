package app.kaeru.di

import app.kaeru.data.pairing.LanAddresses
import app.kaeru.data.together.LanSocketTransport
import app.kaeru.data.together.LanTogetherEndpoints
import app.kaeru.data.together.RelayTransport
import app.kaeru.data.together.TogetherTimeouts
import app.kaeru.domain.together.LanEndpoint
import app.kaeru.domain.together.RoomLink
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.inject.Provider

class TogetherModuleTest {
    private val random = SecureRandom()
    private val timeouts = TogetherTimeouts()

    private fun lan() = LanSocketTransport(
        object : LanAddresses {
            override fun siteLocalIpv4() = "192.168.1.42"
        },
        LanTogetherEndpoints(),
        timeouts,
        Dispatchers.IO,
    )

    private fun relay() = RelayTransport(OkHttpClient(), "", timeouts, Dispatchers.IO)

    private val factory = TogetherModule.transportFactory(Provider { lan() }, Provider { relay() })

    @Test
    fun `a link naming an address on this network is answered with sockets`() {
        val here = RoomLink.random(random).copy(lan = LanEndpoint("192.168.1.42", 41_234))

        assertTrue(factory.forLink(here) is LanSocketTransport)
    }

    @Test
    fun `a link with no address, or one off this network, goes through the relay`() {
        val elsewhere = RoomLink.random(random)
        // A link can be written by anybody; a public address in one is not a reason to open a
        // socket to it, and the check that says so is applied again here.
        val lying = RoomLink.random(random).copy(lan = LanEndpoint("8.8.8.8", 41_234))

        assertTrue(factory.forLink(elsewhere) is RelayTransport)
        assertTrue(factory.forLink(lying) is RelayTransport)
    }

    @Test
    fun `each session gets a transport of its own`() {
        val link = RoomLink.random(random).copy(lan = LanEndpoint("192.168.1.42", 41_234))

        // A transport carries one session: a port, a socket, a backlog and a state. Handing the
        // same instance to two sessions would have them trample each other.
        assertNotSame(factory.forLink(link), factory.forLink(link))
        assertNotSame(factory.forLink(RoomLink.random(random)), factory.forLink(RoomLink.random(random)))
    }
}
