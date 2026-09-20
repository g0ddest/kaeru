package app.kaeru.data.kodik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KodikHtmlParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("kodik/$name")!!.bufferedReader().readText()

    @Test
    fun `parse extracts signing params from real player page`() {
        val page = KodikHtmlParser.parse(fixture("player.html"))

        assertEquals("kodikplayer.com", page.domain)
        assertEquals("7af8577bd2663bbd586a90cccaf740b83784ee5a37334b1b56363ea06d7755a8:2609140747", page.dSign)
        assertEquals("kodikplayer.com", page.pd)
        assertEquals("7af8577bd2663bbd586a90cccaf740b83784ee5a37334b1b56363ea06d7755a8:2609140747", page.pdSign)
        assertEquals("https://kodikplayer.com/", page.ref)
        assertEquals("6137eaa1d4c94e3b6a15aaf56eb92ada806bda5bbec4784915459162b3ed622b:2609140747", page.refSign)
    }

    @Test
    fun `parse extracts current video info`() {
        val page = KodikHtmlParser.parse(fixture("player.html"))

        assertEquals("seria", page.currentType)
        assertEquals("cf62e729fdb71a0b7fb148ba6fc48ad6", page.currentHash)
        assertEquals("1211482", page.currentId)
    }

    @Test
    fun `parse extracts all translations with first being studiya bubnyazha`() {
        val page = KodikHtmlParser.parse(fixture("player.html"))

        assertEquals(33, page.translations.size)
        val first = page.translations.first()
        assertTrue(first.title.startsWith("#студияБУБНЯЖА"))
        assertEquals(28, first.episodesCount)
        assertEquals(TranslationType.VOICE, first.type)
        assertEquals(3560, first.id)
        assertEquals("55917", first.mediaId)
        assertEquals("d1d44d5cd59af5af897ce899a776dacf", first.mediaHash)
    }

    @Test
    fun `parse decodes html entities in translation titles`() {
        val page = KodikHtmlParser.parse(fixture("player.html"))

        val translation = page.translations.first { it.id == 2821 }
        assertTrue(translation.title.startsWith("AEROChannelEkat & Risha"))
        assertTrue(!translation.title.contains("&amp;"))
    }

    @Test
    fun `parse extracts subtitle translations`() {
        val page = KodikHtmlParser.parse(fixture("player.html"))

        val subtitleTranslations = page.translations.filter { it.type == TranslationType.SUBTITLES }
        assertTrue(subtitleTranslations.isNotEmpty())
        assertTrue(subtitleTranslations.any { it.title == "AniRise.Subtitles" })
    }

    @Test
    fun `parse extracts all 28 episodes numbered 1 through 28`() {
        val page = KodikHtmlParser.parse(fixture("player.html"))

        assertEquals(28, page.episodes.size)
        assertEquals((1..28).toList(), page.episodes.map { it.number })
        val firstEpisode = page.episodes.first { it.number == 1 }
        assertEquals("1211482", firstEpisode.mediaId)
        assertEquals("cf62e729fdb71a0b7fb148ba6fc48ad6", firstEpisode.mediaHash)
    }

    @Test
    fun `parse defaults ftorPath to slash ftor when no atob override present`() {
        val page = KodikHtmlParser.parse(fixture("player.html"))

        assertEquals("/ftor", page.ftorPath)
    }

    @Test
    fun `parse throws ParserBroken naming the missing step when html is garbage`() {
        val error = assertThrows(KodikError.ParserBroken::class.java) {
            KodikHtmlParser.parse("<html><body>nothing here</body></html>")
        }
        assertTrue(error.step.isNotBlank())
    }

    @Test
    fun `extractPublicToken finds token in add-players fragment`() {
        val token = KodikHtmlParser.extractPublicToken(fixture("add-players.js"))
        assertEquals("0000000000000000000000000000abcd", token)
    }

    @Test
    fun `extractPublicToken returns null when no token present`() {
        val token = KodikHtmlParser.extractPublicToken("var x = 1;")
        assertNull(token)
    }

    @Test
    fun `decodeHtmlEntities decodes named entities`() {
        assertEquals(
            "A & B < C > D \" E ' F",
            KodikHtmlParser.decodeHtmlEntities("A &amp; B &lt; C &gt; D &quot; E &apos; F"),
        )
    }

    @Test
    fun `decodeHtmlEntities decodes decimal and hex numeric entities`() {
        assertEquals("A", KodikHtmlParser.decodeHtmlEntities("&#65;"))
        assertEquals("A", KodikHtmlParser.decodeHtmlEntities("&#x41;"))
        assertEquals("A", KodikHtmlParser.decodeHtmlEntities("&#X41;"))
    }

    @Test
    fun `decodeHtmlEntities leaves plain text and unknown entities untouched`() {
        assertEquals("plain text", KodikHtmlParser.decodeHtmlEntities("plain text"))
        assertEquals("&notareal;", KodikHtmlParser.decodeHtmlEntities("&notareal;"))
    }

    @Test
    fun `decodeHtmlEntities passes through numeric entities outside the valid code point range without throwing`() {
        assertEquals("&#x110000;", KodikHtmlParser.decodeHtmlEntities("&#x110000;"))
        assertEquals("&#99999999999;", KodikHtmlParser.decodeHtmlEntities("&#99999999999;"))
    }

    private fun minimalPage(
        type: String = "seria",
        translationsBoxClass: String? = "serial-translations-box",
        seriesBoxClass: String? = "serial-series-box",
    ): String {
        val translationsBox = translationsBoxClass?.let {
            """
            <div class="$it">
              <select>
                <option value="1" data-id="1" data-translation-type="voice" data-media-id="10" data-media-hash="h10" data-title="Test">Test (1 эп.)</option>
              </select>
            </div>
            """
        }.orEmpty()
        val seriesBox = seriesBoxClass?.let {
            """
            <div class="$it">
              <select>
                <option value="1" data-id="1" data-hash="hash1" data-title="1 серия">1 серия</option>
              </select>
            </div>
            """
        }.orEmpty()
        return """
            <html><body>
            <script>
              var domain = "kodikplayer.com";
              var d_sign = "sign1";
              var pd = "kodikplayer.com";
              var pd_sign = "sign2";
              var ref = "https://kodikplayer.com/";
              var ref_sign = "sign3";
            </script>
            <script>
               vInfo.type = '$type';
               vInfo.hash = 'hash1';
               vInfo.id = '1';
            </script>
            $translationsBox
            $seriesBox
            </body></html>
        """.trimIndent()
    }

    private fun minimalHtml(seriesBoxClass: String): String = minimalPage(seriesBoxClass = seriesBoxClass)

    @Test
    fun `parse accepts a movie page that has no episode list`() {
        val page = KodikHtmlParser.parse(fixture("movie.html"))

        assertEquals("video", page.currentType)
        assertEquals("990011", page.currentId)
        assertEquals("aa11bb22cc33dd44ee55ff6677889900", page.currentHash)
        assertTrue("a movie has no episodes to list", page.episodes.isEmpty())
    }

    @Test
    fun `parse reads translations out of a movie translations box`() {
        val page = KodikHtmlParser.parse(fixture("movie.html"))

        assertEquals(33, page.translations.size)
        assertEquals(3560, page.translations.first().id)
        assertEquals("55917", page.translations.first().mediaId)
    }

    @Test
    fun `a movie page without any translations box parses with an empty track list`() {
        val page = KodikHtmlParser.parse(minimalPage(type = "video", translationsBoxClass = null, seriesBoxClass = null))

        assertTrue(page.translations.isEmpty())
        assertTrue(page.episodes.isEmpty())
    }

    @Test
    fun `a page names the track it is itself showing`() {
        val page = KodikHtmlParser.parse(fixture("movie.html"))

        assertEquals(923, page.currentTranslationId)
        assertEquals("AnimeVost", page.currentTranslationTitle)
    }

    @Test
    fun `a film with a single voice still names it, even with no chooser on the page`() {
        val page = KodikHtmlParser.parse(fixture("movie-single-track.html"))

        assertTrue("this fixture is the one without a chooser", page.translations.isEmpty())
        assertEquals(923, page.currentTranslationId)
        assertEquals("AnimeVost", page.currentTranslationTitle)
        assertEquals("990011", page.currentId)
        assertEquals("aa11bb22cc33dd44ee55ff6677889900", page.currentHash)
    }

    @Test
    fun `a page that names no track at all says nothing rather than guessing`() {
        val page = KodikHtmlParser.parse(minimalPage(type = "video", translationsBoxClass = null, seriesBoxClass = null))

        assertNull(page.currentTranslationId)
        assertNull(page.currentTranslationTitle)
    }

    @Test
    fun `a serial page without a translations box still throws`() {
        val error = assertThrows(KodikError.ParserBroken::class.java) {
            KodikHtmlParser.parse(minimalPage(translationsBoxClass = null))
        }
        assertEquals("translations", error.step)
    }

    @Test
    fun `a serial page without an episode list still throws`() {
        val error = assertThrows(KodikError.ParserBroken::class.java) {
            KodikHtmlParser.parse(minimalPage(seriesBoxClass = null))
        }
        assertEquals("episodes", error.step)
    }

    @Test
    fun `parse does not treat a div whose class merely starts with the box name as the series box`() {
        val error = assertThrows(KodikError.ParserBroken::class.java) {
            KodikHtmlParser.parse(minimalHtml("serial-series-box-extra"))
        }
        assertEquals("episodes", error.step)
    }

    @Test
    fun `parse finds the series box when it carries additional class tokens`() {
        val page = KodikHtmlParser.parse(minimalHtml("serial-series-box active"))
        assertEquals(1, page.episodes.size)
    }
}
