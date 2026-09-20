package app.kaeru.shared.data.kodik

import app.kaeru.shared.TestFixtures
import kotlin.io.encoding.Base64
import kotlin.test.*

class KodikParserEdgeTest {
    @Test fun numericEntitiesPreserveAstralUnicodeAndRejectSurrogates() {
        assertEquals("😀", KodikHtmlParser.decodeHtmlEntities("&#x1F600;"))
        assertEquals("&#xD800;", KodikHtmlParser.decodeHtmlEntities("&#xD800;"))
    }
    @Test fun inlineFtorOverrideUsesCommonBase64() {
        val html = TestFixtures.text("kodik/player.html") + "<script>atob('/w==');atob('L2Z0b3Iy');</script>"
        assertEquals("/ftor2", KodikHtmlParser.parse(html).ftorPath)
    }
    @Test fun urlParamsOnlyPageDecodesReferenceExactlyOnce() {
        val html = TestFixtures.text("kodik/movie-single-track.html")
            .replace(Regex("var (domain|d_sign|pd|pd_sign|ref|ref_sign) = [^;]+;"), "")
        val page = KodikHtmlParser.parse(html)
        assertEquals("kodikplayer.com", page.domain)
        assertEquals("https://kodikplayer.com/", page.ref)
    }
    @Test fun malformedLinksAndUnsafeSchemesAreParserFailures() {
        assertFailsWith<KodikError.ParserBroken> { KodikLinkDecoder.decode("<html>denied</html>") }
        assertNull(KodikLinkDecoder.decodeSrc(Base64.encode("javascript:manifest()".encodeToByteArray())))
    }
    @Test fun allRotationsAndMissingPaddingDecode() {
        for (shift in 0..25) {
            val plain = "//cdn.example.com/manifest.m3u8?sig=x"
            val encoded = Base64.encode(plain.encodeToByteArray()).trimEnd('=')
                .map { c -> when(c) {
                    in 'a'..'z' -> 'a' + (c - 'a' + shift) % 26
                    in 'A'..'Z' -> 'A' + (c - 'A' + shift) % 26
                    else -> c
                } }.joinToString("")
            assertEquals("https:$plain", KodikLinkDecoder.decodeSrc(encoded))
        }
    }
}
