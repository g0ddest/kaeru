package app.kaeru.domain.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One frame, sealed by the iOS client exactly as it puts it on the wire, opened here.
 *
 * The two codecs were written from the same description and never once spoken to each other. A
 * greeting that does not authenticate is a room where the host simply never answers — which is
 * what a phone shows as «Подключаемся…» for ever.
 */
class IosFrameCompatibilityTest {
    private val roomId = "AAAAAAAAAAA"
    private val key = ByteArray(16) { it.toByte() }

    @Test
    fun `a hello sealed by the iOS guest opens here`() {
        val frame = VECTOR.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val link = RoomLink(roomId, key)
        val decoded = TogetherCodec.decode(frame, link, Side.GUEST)
        assertTrue("iOS frame did not authenticate: ${decoded.exceptionOrNull()}", decoded.isSuccess)
        val hello = decoded.getOrThrow() as TogetherMessage.Hello
        assertEquals("iPhone", hello.name)
        assertEquals(1L, hello.seq)
        assertEquals(0, hello.episode)
    }

    private companion object {
        const val VECTOR = "6465666768696a6b6c6d6e6f61402fcbb26fddb3ff1c6d20aae84e9c59fb179bf78be76ad84ddff6d197d5c99895024ce47642b3a67a572aecfb2d69bcbf2715fc6a324defac7132bff711090bf45f7e8e8da2a605d45ba310d1005ef4c60020968d5de8567367d6c2b61c01ad192865e4284520e78d8852"
    }
}
