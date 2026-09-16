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
    private val key = RoomLink.random(random).key

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

    private fun encode(msg: TogetherMessage) =
        TogetherCodec.encode(msg, key, TogetherCodec.newNonce(random))

    private fun reasonOf(result: Result<*>): TogetherFailureReason? =
        (result.exceptionOrNull() as? TogetherFailed)?.reason

    @Test
    fun `every message comes back off the wire as itself`() {
        everyMessage.forEach { message ->
            val decoded = TogetherCodec.decode(encode(message), key)

            assertEquals(message, decoded.getOrThrow())
        }
    }

    @Test
    fun `a frame carries its nonce in front and never the message in the clear`() {
        val message = TogetherMessage.Chat("Дальше!", seq = 1)
        val nonce = TogetherCodec.newNonce(random)

        val frame = TogetherCodec.encode(message, key, nonce)

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

        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(TogetherCodec.decode(flipped, key)))
        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(TogetherCodec.decode(inTheBody, key)))
        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(TogetherCodec.decode(movedNonce, key)))
        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(TogetherCodec.decode(frame, RoomLink.random(random).key)))
    }

    @Test
    fun `a frame too short to hold anything is refused rather than read`() {
        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(TogetherCodec.decode(ByteArray(0), key)))
        assertEquals(
            TogetherFailureReason.TAMPERED,
            reasonOf(TogetherCodec.decode(ByteArray(TogetherCodec.NONCE_BYTES + TogetherCodec.TAG_BYTES), key)),
        )
    }

    @Test
    fun `nothing larger than a frame is written or read`() {
        val tooBig = ByteArray(TogetherCodec.MAX_FRAME_BYTES + 1)

        assertEquals(TogetherFailureReason.FRAME_TOO_LARGE, reasonOf(TogetherCodec.decode(tooBig, key)))

        val clip = TogetherMessage.Voice(chunk = 0, total = 1, bytes = ByteArray(TogetherCodec.MAX_FRAME_BYTES), durationMs = 30_000, seq = 1)
        val thrown = runCatching { encode(clip) }.exceptionOrNull()

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
        assertEquals(slice, TogetherCodec.decode(frame, key).getOrThrow())
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
