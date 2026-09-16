package app.kaeru.ui.mobile

import android.net.Uri
import app.kaeru.domain.together.RoomLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom

/**
 * What the app is willing to read out of an invitation somebody tapped.
 *
 * Both forms land on the same activity as the two deep links already there, and like them neither
 * is trusted: the room is parsed before the screen opens, so a link that is not a room never
 * becomes a screen asking whether to join one.
 */
@RunWith(RobolectricTestRunner::class)
class WatchLinkTest {

    private val room = RoomLink.random(SecureRandom())

    @Test
    fun `the link a friend sends in a messenger is read`() {
        val https = room.toHttps()
        assertEquals(https, watchLinkOf(Uri.parse(https)))
    }

    @Test
    fun `so is the one that names a phone on this Wi-Fi`() {
        val lan = room.copy(lan = app.kaeru.domain.together.LanEndpoint("192.168.1.7", 41234)).toLan()
        assertEquals(lan, watchLinkOf(Uri.parse(lan)))
    }

    @Test
    fun `the key never leaves the fragment it arrived in`() {
        val https = room.toHttps()
        val read = watchLinkOf(Uri.parse(https))!!
        assertEquals(RoomLink.parse(read).getOrNull(), room)
        assertEquals(https.substringAfter('#'), Uri.parse(read).fragment)
    }

    @Test
    fun `an invitation with no key in it is not an invitation`() {
        assertNull(watchLinkOf(Uri.parse("https://kaeru.vitaliy.velikodniy.name/w/${room.roomId}")))
    }

    @Test
    fun `nor is a room on somebody else's site`() {
        assertNull(watchLinkOf(Uri.parse("https://example.com/w/${room.roomId}#AAAAAAAAAAAAAAAAAAAAAA")))
    }

    @Test
    fun `nor a host that merely starts the same way`() {
        // The check is a prefix on the whole address, so a name that begins with ours and carries
        // on into somebody else's domain must not read as ours.
        assertNull(watchLinkOf(Uri.parse("https://kaeru.vitaliy.velikodniy.name.evil.com/w/${room.roomId}#AAAAAAAAAAAAAAAAAAAAAA")))
        assertNull(watchLinkOf(Uri.parse("https://evil.com/kaeru.vitaliy.velikodniy.name/w/${room.roomId}#AAAAAAAAAAAAAAAAAAAAAA")))
    }

    @Test
    fun `and a scheme in the wrong case is not a way round the host check`() {
        val shouted = room.toHttps().replaceFirst("https://", "HTTPS://")
        assertNull(watchLinkOf(Uri.parse(shouted)))
    }

    @Test
    fun `nor a kaeru link that means something else entirely`() {
        assertNull(watchLinkOf(Uri.parse("kaeru://oauth?code=1&state=2")))
        assertNull(watchLinkOf(Uri.parse("kaeru://pair?h=192.168.1.7&p=41234&n=abc&t=TV")))
    }

    @Test
    fun `and an intent carrying nothing carries no invitation`() {
        assertNull(watchLinkOf(null))
    }
}
