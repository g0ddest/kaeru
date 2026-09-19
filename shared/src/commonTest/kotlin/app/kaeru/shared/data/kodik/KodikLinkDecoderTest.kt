package app.kaeru.shared.data.kodik

import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.Test

class KodikLinkDecoderTest {

    private fun fixture(name: String): String =
        KodikFixtures.text(name)

    private fun rotate(s: String, n: Int): String = s.map { c ->
        when {
            c in 'a'..'z' -> 'a' + (c - 'a' + n).mod(26)
            c in 'A'..'Z' -> 'A' + (c - 'A' + n).mod(26)
            else -> c
        }
    }.joinToString("")

    /** Produces a string that decodeSrc(shift = n) will successfully decode back to [plain]. */
    private fun encodeForShift(plain: String, n: Int): String {
        val base64 = Base64.encode(plain.encodeToByteArray()).trimEnd('=')
        return rotate(base64, (26 - n).mod(26))
    }

    @Test
    fun `decode returns https urls with manifest for all qualities from real ftor response`() {
        val result = KodikLinkDecoder.decode(fixture("links.json"))

        assertEquals(setOf(360, 480, 720), result.keys)
        result.values.forEach { url ->
            assertTrue(url.startsWith("https://"))
            assertTrue(url.contains("manifest.m3u8"))
        }
    }

    @Test
    fun `decode throws ParserBroken links when json has no decodable src`() {
        val json = """{"links":{"360":[{"src":"not-base64-garbage!!!","type":"application/x-mpegURL"}]}}"""
        val error = assertFailsWith<KodikError.ParserBroken> {
            KodikLinkDecoder.decode(json)
        }
        assertEquals("links", error.step)
    }

    @Test
    fun `decodeSrc returns null for garbage input`() {
        assertNull(KodikLinkDecoder.decodeSrc("!!!not-valid-base64-at-all???"))
    }

    @Test
    fun `decodeSrc round trips a rotated base64 manifest url`() {
        val plain = "https://example.com/stream/manifest.m3u8?sig=abc"
        val encoded = encodeForShift(plain, 7)

        val decoded = KodikLinkDecoder.decodeSrc(encoded)

        assertEquals(plain, decoded)
    }

    @Test
    fun `decodeSrc prefixes https colon when decoded value is protocol relative`() {
        val plain = "//cdn.example.com/manifest.m3u8"
        val encoded = encodeForShift(plain, 3)

        val decoded = KodikLinkDecoder.decodeSrc(encoded)

        assertEquals("https:$plain", decoded)
    }
}
