package app.kaeru.ui.mobile.together

import app.kaeru.domain.together.RoomLink
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The two files that make a link in a messenger open the app rather than a browser tab.
 *
 * Checked here because they are published from a different branch and nothing else in the build
 * ever reads them: a fingerprint that stops matching the key the app is signed with turns every
 * invitation into a web page, silently, and the only symptom is that nobody joins.
 */
@RunWith(RobolectricTestRunner::class)
class AssetLinksTest {

    /** Tests run from the module directory; the published site lives beside it. */
    private val site = File("../docs/cast")

    private val release = "77:4C:B7:B0:FF:31:BF:E7:DA:AC:DB:9F:05:81:8D:EE:" +
        "D4:35:41:A1:07:41:7C:96:7F:B1:96:92:C6:EB:39:46"

    private val debug = "BD:BF:74:BA:85:55:20:B1:F6:05:AB:27:F9:35:F5:61:" +
        "6B:E1:0C:0B:85:1E:D3:EF:E8:B5:56:0A:54:A9:37:0C"

    /** Where a person with no app is sent. The list, not `/latest`, which skips pre-releases. */
    private val RELEASES = "https://github.com/g0ddest/kaeru/releases"

    private fun fingerprints(): List<String> {
        val statements = JSONArray(File(site, ".well-known/assetlinks.json").readText())
        return (0 until statements.length()).flatMap { i ->
            val target = statements.getJSONObject(i).getJSONObject("target")
            assertEquals("android_app", target.getString("namespace"))
            assertEquals("app.kaeru", target.getString("package_name"))
            val relation = statements.getJSONObject(i).getJSONArray("relation")
            assertTrue(
                "a statement that does not hand over links is not a statement about links",
                (0 until relation.length()).any {
                    relation.getString(it) == "delegate_permission/common.handle_all_urls"
                },
            )
            val list = target.getJSONArray("sha256_cert_fingerprints")
            (0 until list.length()).map { normalize(list.getString(it)) }
        }
    }

    /** A fingerprint is 32 bytes; how they are punctuated is not part of the value. */
    private fun normalize(value: String) = value.replace(":", "").lowercase()

    @Test
    fun `both signing keys are handed the app's own links`() {
        val listed = fingerprints()
        assertTrue("release certificate missing from assetlinks.json", normalize(release) in listed)
        assertTrue("debug certificate missing from assetlinks.json", normalize(debug) in listed)
    }

    @Test
    fun `every fingerprint in the file is a whole sha-256 and nothing else`() {
        fingerprints().forEach { value ->
            assertEquals(value, 64, value.length)
            assertTrue(value, value.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun `the landing page offers the app to somebody who has it and to somebody who has not`() {
        val page = File(site, "w/index.html").readText()
        assertTrue("the page must hand the link back to the app", page.contains("Открыть в Kaeru"))
        assertTrue("the page must offer the build", page.contains("Скачать APK"))
        assertTrue("the page names the feature", page.contains("совместный просмотр"))
        assertTrue("the page declares its language", page.contains("lang=\"ru\""))
        assertTrue("a messenger needs a picture", page.contains("og:image"))
    }

    /**
     * Pages is a static host with no rewrites, and every invitation is `/w/<roomId>` — a path
     * with no file behind it. The custom 404 is the only document those links can land on, so it
     * has to be the landing page rather than a page about a landing page.
     */
    @Test
    fun `the page a real invitation lands on is the same page`() {
        val landing = File(site, "w/index.html").readBytes()
        val fallback = File(site, "404.html")
        assertTrue("docs/cast/404.html is what /w/<room> actually serves", fallback.exists())
        assertTrue(
            "404.html and w/index.html have drifted apart; publish them together",
            fallback.readBytes().contentEquals(landing),
        )
    }

    /**
     * «latest» excludes pre-releases and every Kaeru build so far is one, so the released-version
     * URL is a 404. The list page is not, and the script upgrades it to the APK itself.
     */
    @Test
    fun `the build is offered from a url that exists`() {
        val page = File(site, "w/index.html").readText()
        assertFalse("/releases/latest 404s while every build is a pre-release", page.contains("/releases/latest"))
        assertTrue(page.contains("https://github.com/g0ddest/kaeru/releases"))
        assertTrue("the newest pre-release has to be asked for by name", page.contains("api.github.com"))
    }

    /** Nothing but that one API call leaves this origin, and least of all a font. */
    @Test
    fun `the page fetches nothing else from anywhere else`() {
        val page = File(site, "w/index.html").readText()
        listOf("fonts.googleapis.com", "fonts.gstatic.com", "cdn.", "unpkg", "analytics").forEach {
            assertFalse("$it has no business on an invitation page", page.contains(it))
        }
        // The one external call, and it must never carry the room: the fragment is not part of a
        // request, and the referrer is turned off so the room id does not travel either.
        assertTrue(page.contains("referrerPolicy: 'no-referrer'"))
        val fetched = page.substringAfter("fetch(").substringBefore(",")
        assertFalse("nothing about the room may travel to github", fetched.contains("location"))
    }

    /** With no script there is still one thing to do, and it is not the site root. */
    @Test
    fun `the buttons lead somewhere without javascript`() {
        val page = File(site, "w/index.html").readText()
        val anchors = Regex("""<a class="button[^"]*" id="[^"]*" href="([^"]+)"""").findAll(page).map { it.groupValues[1] }.toList()
        assertEquals(2, anchors.size)
        anchors.forEach { href ->
            assertEquals("a button with no script behind it must still install the app", RELEASES, href)
        }
        assertTrue("the no-script label has to be honest about what it does", page.contains("Установить Kaeru"))
    }

    /**
     * A same-site address is never handed to an app, and a press on `#fragment` alone is not a
     * navigation at all — so «Открыть в Kaeru» has to be an intent URI: Chrome resolves it on the
     * phone, opens the package it names, and goes to the fallback when there is no such package.
     * Built by the script, because with no script the button must still be the releases page.
     */
    @Test
    fun `the button opens the app through an intent uri and falls back to the releases`() {
        val page = File(site, "w/index.html").readText()
        val script = page.substringAfter("<script>").substringBefore("</script>")
        assertTrue("the room goes in the query", script.contains("'intent://${RoomLink.AUTHORITY}?${RoomLink.ROOM_PARAM}='"))
        assertTrue("so does the key: the fragment is Chrome's", script.contains("'&${RoomLink.KEY_PARAM}='"))
        assertTrue(script.contains("'#Intent;scheme=${RoomLink.SCHEME};package=app.kaeru;S.browser_fallback_url='"))
        assertTrue(
            "the fallback is encoded, so no `;` or `#` of its own can end the intent early",
            script.contains("encodeURIComponent('$RELEASES')"),
        )
        assertTrue(script.contains("';end'"))
        // Only an address the app would accept becomes the button: a room of eight bytes and a
        // key of sixteen, in base64url without padding. Anything else stays «Установить Kaeru».
        val roomChars = (RoomLink.ROOM_ID_BYTES * 8 + 5) / 6
        val keyChars = (RoomLink.KEY_BYTES * 8 + 5) / 6
        assertTrue(script.contains("/^[A-Za-z0-9_-]{$roomChars}$/.test(room)"))
        assertTrue(script.contains("/^[A-Za-z0-9_-]{$keyChars}$/.test(key)"))
        assertTrue(
            "the visitor is told what to do when the button does nothing",
            page.contains("Если кнопка не сработала — нажмите ссылку в чате ещё раз: после установки Android откроет её в Kaeru"),
        )
    }

    /** The key is read once, checked, and written into that one address — and nowhere else. */
    @Test
    fun `the hash is used only to build the intent uri`() {
        val page = File(site, "w/index.html").readText()
        val script = page.substringAfter("<script>").substringBefore("</script>")
        assertEquals("the hash is read in one place", 1, Regex("location\\.hash").findAll(page).count())
        assertTrue("and that place is before the one request the page makes", script.indexOf("location.hash") in 0 until script.indexOf("fetch("))
        // Comments aside, the identifier the hash lands in appears three times — read, checked,
        // written into the intent — and never after the intent is finished.
        val code = script.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val key = Regex("\\bkey\\b")
        assertEquals(3, key.findAll(code).count())
        assertFalse(key.containsMatchIn(code.substringAfter("';end'")))
    }

    @Test
    fun `the page is served from the host the links point at`() {
        assertEquals("kaeru.vitaliy.velikodniy.name", File(site, "CNAME").readText().trim())
        assertTrue(RoomLink.HTTPS_BASE.startsWith("https://kaeru.vitaliy.velikodniy.name/w/"))
        // GitHub Pages hides dot-directories from Jekyll; without this file the statement 404s.
        assertTrue(File(site, ".nojekyll").exists())
    }
}
