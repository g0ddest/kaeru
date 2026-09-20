package app.kaeru.domain.together

import app.kaeru.domain.error.TogetherFailed
import app.kaeru.domain.error.TogetherFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class TogetherCodecTest {
    private val random = SecureRandom()
    private val room = RoomLink.random(random)
    private val key = room.key

    private val everyMessage = listOf(
        TogetherMessage.Hello("Виталий", animeId = 51_009, episode = 3, translationId = 610, positionMs = 12_345, playing = true, seq = 1),
        TogetherMessage.Hello("", animeId = 1, episode = 1, translationId = null, positionMs = 0, playing = false, seq = 2),
        TogetherMessage.Play(positionMs = 60_000, seq = 3),
        TogetherMessage.Pause(positionMs = 61_000, seq = 4),
        TogetherMessage.Seek(positionMs = 0, seq = 5),
        TogetherMessage.Episode(episode = 4, translationId = 610, seq = 6),
        TogetherMessage.Episode(episode = 5, translationId = null, seq = 7),
        TogetherMessage.State(positionMs = 62_000, playing = true, buffering = false, sentAt = 1_700_000_000_000, seq = 8),
        TogetherMessage.Chat("Стоп, что?", seq = 9),
        TogetherMessage.Reaction(ReactionKind.FIRE, seq = 10),
        TogetherMessage.Voice(chunk = 0, total = 2, bytes = ByteArray(4_096) { it.toByte() }, durationMs = 7_000, seq = 11),
        TogetherMessage.Ping(sentAt = 1_700_000_000_001, seq = 12),
        TogetherMessage.Pong(pingSentAt = 1, receivedAt = 2, sentAt = 3, seq = 13),
        TogetherMessage.Bye(seq = 14),
    )

    private fun encode(msg: TogetherMessage, from: Side = Side.HOST) =
        TogetherCodec.encode(msg, room, from, TogetherCodec.newNonce(random))

    private fun decode(frame: ByteArray, link: RoomLink = room, from: Side = Side.HOST) =
        TogetherCodec.decode(frame, link, from)

    private fun reasonOf(result: Result<*>): TogetherFailureReason? =
        (result.exceptionOrNull() as? TogetherFailed)?.reason

    /**
     * Both phones opened the link. Each seals as the guest and each reads under the host's seal,
     * so nothing opens — and, told apart from a mangled frame, that has a sentence of its own.
     * Not an oracle: the second attempt is under the same key, and its result stays on the phone.
     */
    @Test
    fun `a frame sealed by another guest is told apart from a mangled one`() {
        val fromAnotherGuest = encode(TogetherMessage.Chat("тоже гость", seq = 1), from = Side.GUEST)
        assertEquals(TogetherFailureReason.SAME_SIDE, reasonOf(TogetherCodec.decodeFromPeer(fromAnotherGuest, room, mine = Side.GUEST)))
        // The host reading the same frame is simply reading its guest.
        assertEquals(TogetherMessage.Chat("тоже гость", seq = 1), TogetherCodec.decodeFromPeer(fromAnotherGuest, room, mine = Side.HOST).getOrThrow())
        // Garbage is garbage under either seal.
        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(TogetherCodec.decodeFromPeer(ByteArray(64) { it.toByte() }, room, mine = Side.GUEST)))
        // And a frame from another room's key is not this room's business under either.
        val elsewhere = TogetherCodec.encode(TogetherMessage.Bye(seq = 2), RoomLink.random(random), Side.GUEST, TogetherCodec.newNonce(random))
        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(TogetherCodec.decodeFromPeer(elsewhere, room, mine = Side.GUEST)))
    }

    @Test
    fun `every message comes back off the wire as itself`() {
        everyMessage.forEach { message ->
            val decoded = decode(encode(message))

            assertEquals(message, decoded.getOrThrow())
        }
    }

    @Test
    fun `a frame carries its nonce in front and never the message in the clear`() {
        val message = TogetherMessage.Chat("Дальше!", seq = 1)
        val nonce = TogetherCodec.newNonce(random)

        val frame = TogetherCodec.encode(message, room, Side.HOST, nonce)

        assertTrue(nonce.contentEquals(frame.copyOfRange(0, TogetherCodec.NONCE_BYTES)))
        assertTrue(frame.size > TogetherCodec.NONCE_BYTES + TogetherCodec.TAG_BYTES)
        assertEquals(-1, String(frame, Charsets.ISO_8859_1).indexOf("chat"))
    }

    @Test
    fun `a frame somebody edited is refused, and so is one for another room`() {
        val frame = encode(TogetherMessage.Play(positionMs = 1_000, seq = 1))

        val flipped = frame.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val inTheBody = frame.copyOf().also { it[TogetherCodec.NONCE_BYTES + 1] = (it[TogetherCodec.NONCE_BYTES + 1].toInt() xor 1).toByte() }
        val movedNonce = frame.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(decode(flipped)))
        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(decode(inTheBody)))
        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(decode(movedNonce)))
        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(decode(frame, link = RoomLink.random(random))))
    }

    @Test
    fun `a frame sealed for one room does not open in another`() {
        val message = TogetherMessage.Seek(positionMs = 90_000, seq = 2)
        // The same key, deliberately, so nothing but the room's name can be doing the work.
        val elsewhere = RoomLink.random(random).copy(key = key)

        val frame = encode(message)

        assertEquals(message, decode(frame).getOrThrow())
        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(decode(frame, link = elsewhere)))
    }

    @Test
    fun `a frame handed back to the side that sent it does not open`() {
        val message = TogetherMessage.Seek(positionMs = 0, seq = 3)

        val fromHost = encode(message, from = Side.HOST)
        val fromGuest = encode(message, from = Side.GUEST)

        // What each side does with the other's frame: reads it as theirs, and it opens.
        assertEquals(message, decode(fromHost, from = Side.HOST).getOrThrow())
        assertEquals(message, decode(fromGuest, from = Side.GUEST).getOrThrow())
        // And what a side does when a relay bounces its own frame back at it: reads it as the
        // other side's, finds it was not, and refuses. That is reflection, dead on arrival.
        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(decode(fromHost, from = Side.GUEST)))
        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(decode(fromGuest, from = Side.HOST)))
    }

    @Test
    fun `a peer cannot announce that the peer left`() {
        // Nothing in this app sends one, but the type is in the sealed hierarchy and so is
        // encodable. What a receiver must never do is believe one that arrived over the wire.
        val frame = encode(TogetherMessage.PeerLeft())

        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(decode(frame)))
    }

    @Test
    fun `a voice slice larger than the protocol allows is not a voice slice`() {
        val overCap = runCatching {
            TogetherMessage.Voice(
                chunk = 0,
                total = 1,
                bytes = ByteArray(TogetherMessage.MAX_VOICE_CHUNK_BYTES + 1),
                durationMs = 30_000,
                seq = 1,
            )
        }.exceptionOrNull()

        assertTrue(overCap is IllegalArgumentException)
        // And one exactly at the cap is fine, so the boundary is where the constant says.
        TogetherMessage.Voice(0, 1, ByteArray(TogetherMessage.MAX_VOICE_CHUNK_BYTES), 30_000, 1)
    }

    @Test
    fun `a frame too short to hold anything is refused rather than read`() {
        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(decode(ByteArray(0))))
        assertEquals(
            TogetherFailureReason.TAMPERED,
            reasonOf(decode(ByteArray(TogetherCodec.NONCE_BYTES + TogetherCodec.TAG_BYTES))),
        )
    }

    @Test
    fun `nothing larger than a frame is written or read`() {
        val tooBig = ByteArray(TogetherCodec.MAX_FRAME_BYTES + 1)

        assertEquals(TogetherFailureReason.FRAME_TOO_LARGE, reasonOf(decode(tooBig)))

        val shout = TogetherMessage.Chat("а".repeat(TogetherCodec.MAX_FRAME_BYTES), seq = 1)
        val thrown = runCatching { encode(shout) }.exceptionOrNull()

        assertEquals(TogetherFailureReason.FRAME_TOO_LARGE, (thrown as? TogetherFailed)?.reason)
    }

    @Test
    fun `a voice slice at the protocol's cap still fits in a frame`() {
        val slice = TogetherMessage.Voice(
            chunk = 3,
            total = 4,
            bytes = ByteArray(TogetherMessage.MAX_VOICE_CHUNK_BYTES) { (it % 251).toByte() },
            durationMs = 30_000,
            seq = 99,
        )

        val frame = encode(slice)

        assertTrue(frame.size <= TogetherCodec.MAX_FRAME_BYTES)
        assertEquals(slice, decode(frame).getOrThrow())
    }

    @Test
    fun `no two frames are sealed under the same nonce`() {
        val message = TogetherMessage.Ping(sentAt = 1, seq = 1)

        val nonces = (1..1_000).map { TogetherCodec.newNonce(random).toList() }.toSet()
        val first = encode(message)
        val second = encode(message)

        assertEquals(1_000, nonces.size)
        assertNotEquals(first.toList(), second.toList())
    }
}
