package app.kaeru.data.together

import app.kaeru.data.pairing.LanAddresses
import app.kaeru.domain.error.TogetherFailed
import app.kaeru.domain.error.TogetherFailureReason
import app.kaeru.domain.together.ConnectionState
import app.kaeru.domain.together.LanEndpoint
import app.kaeru.domain.together.RoomLink
import app.kaeru.domain.together.Side
import app.kaeru.domain.together.TogetherCodec
import app.kaeru.domain.together.TogetherMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Real sockets on the loopback interface. The address a host advertises is a private one, exactly
 * as a router would hand out, and only the resolver is pointed at loopback — so the check that
 * refuses a public address is the shipped one rather than a test-shaped version of it.
 */
class LanSocketTransportTest {
    private val random = SecureRandom()
    private val timeouts = TogetherTimeouts(acceptMs = 3_000, connectMs = 1_000, idleMs = 3_000, authMs = 400)
    private val advertised = "192.168.1.42"
    private val opened = mutableListOf<LanSocketTransport>()

    /**
     * Collectors live here rather than in the test's own `runBlocking`, which would not return
     * until every one of them had finished — and a transport that is still connected has not.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun transport(
        siteLocal: String? = advertised,
        resolve: TogetherEndpoints = TogetherEndpoints { InetSocketAddress("127.0.0.1", it.port) },
    ) = LanSocketTransport(
        object : LanAddresses {
            override fun siteLocalIpv4() = siteLocal
        },
        resolve,
        timeouts,
        Dispatchers.IO,
    ).also(opened::add)

    @After
    fun tearDown() = runBlocking<Unit> {
        scope.coroutineContext.job.cancelAndJoin()
        opened.forEach { it.close() }
    }

    private fun inbox(
        transport: LanSocketTransport,
        link: RoomLink,
        asHost: Boolean,
    ): Channel<Result<TogetherMessage>> {
        val channel = Channel<Result<TogetherMessage>>(Channel.UNLIMITED)
        scope.launch {
            transport.connect(link, asHost).collect(channel::send)
            channel.close()
        }
        return channel
    }

    private suspend fun <T> soon(block: suspend () -> T): T = withTimeout(10_000) { block() }

    private fun reasonOf(result: Result<*>) = (result.exceptionOrNull() as? TogetherFailed)?.reason

    /** One length-prefixed, sealed frame, exactly as a guest's transport writes them. */
    private fun DataOutputStream.frame(message: TogetherMessage, link: RoomLink) {
        val sealed = TogetherCodec.encode(message, link, Side.GUEST, TogetherCodec.newNonce(random))
        writeInt(sealed.size)
        write(sealed)
        flush()
    }

    /** A raw peer that has proved it holds the key, so the host has given it the seat. */
    private suspend fun Socket.greet(host: LanSocketTransport, link: RoomLink): DataOutputStream {
        val out = DataOutputStream(getOutputStream())
        out.frame(TogetherMessage.Hello("Гость", animeId = 1, episode = 1, positionMs = 0, playing = false, seq = 1), link)
        soon { host.state.first { it == ConnectionState.CONNECTED } }
        return out
    }

    @Test
    fun `two phones on one network hear each other`() = runBlocking<Unit> {
        val host = transport()
        val endpoint = requireNotNull(host.hostEndpoint())
        val link = RoomLink.random(random).copy(lan = endpoint)
        val guest = transport(siteLocal = null)

        val heardByHost = inbox(host, link, asHost = true)
        val heardByGuest = inbox(guest, link, asHost = false)
        soon { guest.state.first { it == ConnectionState.CONNECTED } }

        val hello = TogetherMessage.Hello("Виталий", animeId = 51_009, episode = 3, translationId = 610, positionMs = 0, playing = false, seq = 1)
        val state = TogetherMessage.State(positionMs = 12_000, playing = true, buffering = false, sentAt = 7, seq = 2)
        // The greeting is what vouches for the guest; until one arrives the host has given nobody
        // the seat, which is the whole point of the provisional slot.
        guest.send(hello)
        soon { host.state.first { it == ConnectionState.CONNECTED } }
        host.send(state)

        assertEquals(advertised, endpoint.host)
        assertTrue(endpoint.port in 1..65535)
        assertEquals(hello, soon { heardByHost.receive() }.getOrThrow())
        assertEquals(state, soon { heardByGuest.receive() }.getOrThrow())
    }

    @Test
    fun `a stranger on the network does not take the seat the link was sent to`() = runBlocking<Unit> {
        val host = transport()
        val endpoint = requireNotNull(host.hostEndpoint())
        val link = RoomLink.random(random).copy(lan = endpoint)
        val heard = inbox(host, link, asHost = true)

        // Somebody else on the same Wi-Fi, connecting first and saying nothing at all.
        Socket().use { silent ->
            silent.connect(InetSocketAddress("127.0.0.1", endpoint.port), 1_000)
            // And somebody who talks, but not with this room's key.
            Socket().use { wrongKey ->
                wrongKey.connect(InetSocketAddress("127.0.0.1", endpoint.port), 1_000)
                DataOutputStream(wrongKey.getOutputStream())
                    .frame(TogetherMessage.Bye(seq = 1), RoomLink.random(random))

                // Neither of them is the friend, so neither of them gets the seat.
                assertEquals(ConnectionState.CONNECTING, host.state.first())
            }
        }

        // The friend arrives afterwards and finds the door still open.
        val guest = transport(siteLocal = null)
        inbox(guest, link, asHost = false)
        val hello = TogetherMessage.Hello("Виталий", animeId = 51_009, episode = 3, translationId = 610, positionMs = 0, playing = false, seq = 1)
        soon { guest.state.first { it == ConnectionState.CONNECTED } }
        guest.send(hello)

        assertEquals(hello, soon { heard.receive() }.getOrThrow())
        soon { host.state.first { it == ConnectionState.CONNECTED } }
    }

    @Test
    fun `a link pointing off the local network is refused before a socket is opened`() = runBlocking<Unit> {
        val dialled = mutableListOf<LanEndpoint>()
        val guest = transport(
            siteLocal = null,
            resolve = TogetherEndpoints { dialled += it; InetSocketAddress("127.0.0.1", it.port) },
        )
        val public = RoomLink.random(random).copy(lan = LanEndpoint("8.8.8.8", 41_234))
        val nowhere = RoomLink.random(random)

        val refused = soon { guest.connect(public, asHost = false).first() }
        val addressless = soon { guest.connect(nowhere, asHost = false).first() }

        assertEquals(TogetherFailureReason.BAD_LINK, reasonOf(refused))
        assertEquals(TogetherFailureReason.BAD_LINK, reasonOf(addressless))
        assertTrue(dialled.isEmpty())
    }

    @Test
    fun `a host with no address on any network has nowhere to be knocked on`() {
        val host = transport(siteLocal = null)

        assertNull(host.hostEndpoint())
    }

    @Test
    fun `nobody listening is reported as unreachable rather than waited on forever`() = runBlocking<Unit> {
        val guest = transport(siteLocal = null)
        // A port this test owns and then gives back, so nothing is listening on it.
        val free = java.net.ServerSocket(0).use { it.localPort }
        val link = RoomLink.random(random).copy(lan = LanEndpoint(advertised, free))

        val refused = soon { guest.connect(link, asHost = false).first() }

        assertEquals(TogetherFailureReason.UNREACHABLE, reasonOf(refused))
        assertEquals(ConnectionState.CLOSED, guest.state.first())
    }

    @Test
    fun `a host nobody knocks on gives up rather than holding the port for good`() = runBlocking<Unit> {
        val host = LanSocketTransport(
            object : LanAddresses {
                override fun siteLocalIpv4() = advertised
            },
            TogetherEndpoints { InetSocketAddress("127.0.0.1", it.port) },
            TogetherTimeouts(acceptMs = 300, connectMs = 300, idleMs = 300, authMs = 300),
            Dispatchers.IO,
        ).also(opened::add)
        val link = RoomLink.random(random).copy(lan = requireNotNull(host.hostEndpoint()))

        val refused = soon { host.connect(link, asHost = true).first() }

        assertEquals(TogetherFailureReason.UNREACHABLE, reasonOf(refused))
    }

    @Test
    fun `closing ends the channel and there is nothing left to write to`() = runBlocking<Unit> {
        val host = transport()
        val endpoint = requireNotNull(host.hostEndpoint())
        val link = RoomLink.random(random).copy(lan = endpoint)
        val guest = transport(siteLocal = null)
        val ended = Channel<Unit>(Channel.UNLIMITED)
        scope.launch {
            host.connect(link, asHost = true).collect { }
            ended.send(Unit)
        }
        inbox(guest, link, asHost = false)
        soon { guest.state.first { it == ConnectionState.CONNECTED } }
        guest.send(TogetherMessage.Hello("Гость", animeId = 1, episode = 1, positionMs = 0, playing = false, seq = 1))
        soon { host.state.first { it == ConnectionState.CONNECTED } }

        host.close()

        soon { ended.receive() }
        assertEquals(ConnectionState.CLOSED, host.state.first())
        val thrown = runCatching { host.send(TogetherMessage.Bye(seq = 1)) }.exceptionOrNull()
        assertEquals(TogetherFailureReason.DISCONNECTED, (thrown as? TogetherFailed)?.reason)
    }

    @Test
    fun `a frame that will not decrypt is one frame's problem, not the session's`() = runBlocking<Unit> {
        val host = transport()
        val endpoint = requireNotNull(host.hostEndpoint())
        val link = RoomLink.random(random).copy(lan = endpoint)
        val heard = inbox(host, link, asHost = true)

        Socket().use { peer ->
            peer.connect(InetSocketAddress("127.0.0.1", endpoint.port), 1_000)
            val out = peer.greet(host, link)
            soon { heard.receive() }.getOrThrow()

            val junk = ByteArray(64) { it.toByte() }
            out.writeInt(junk.size)
            out.write(junk)
            out.flush()
            out.frame(TogetherMessage.Bye(seq = 9), link)

            assertEquals(TogetherFailureReason.TAMPERED, reasonOf(soon { heard.receive() }))
            assertEquals(TogetherMessage.Bye(seq = 9), soon { heard.receive() }.getOrThrow())
        }
    }

    @Test
    fun `a length nobody could mean ends the connection instead of being allocated`() = runBlocking<Unit> {
        val host = transport()
        val endpoint = requireNotNull(host.hostEndpoint())
        val link = RoomLink.random(random).copy(lan = endpoint)
        val heard = inbox(host, link, asHost = true)

        Socket().use { peer ->
            peer.connect(InetSocketAddress("127.0.0.1", endpoint.port), 1_000)
            val out = peer.greet(host, link)
            soon { heard.receive() }.getOrThrow()

            out.writeInt(TogetherCodec.MAX_FRAME_BYTES + 1)
            out.flush()

            assertEquals(TogetherFailureReason.FRAME_TOO_LARGE, reasonOf(soon { heard.receive() }))
            soon { host.state.first { it == ConnectionState.CLOSED } }
        }
    }

    @Test
    fun `what goes on the wire is a length and then a sealed frame`() = runBlocking<Unit> {
        val host = transport()
        val endpoint = requireNotNull(host.hostEndpoint())
        val link = RoomLink.random(random).copy(lan = endpoint)
        inbox(host, link, asHost = true)

        Socket().use { peer ->
            peer.connect(InetSocketAddress("127.0.0.1", endpoint.port), 1_000)
            peer.greet(host, link)
            val chat = TogetherMessage.Chat("Дальше!", seq = 4)
            host.send(chat)

            val input = DataInputStream(peer.getInputStream())
            val length = input.readInt()
            val frame = ByteArray(length).also(input::readFully)

            assertTrue(length <= TogetherCodec.MAX_FRAME_BYTES)
            // The host sealed it, so that is the side the guest on the other end reads it as.
            assertEquals(chat, TogetherCodec.decode(frame, link, Side.HOST).getOrThrow())
        }
    }
}
