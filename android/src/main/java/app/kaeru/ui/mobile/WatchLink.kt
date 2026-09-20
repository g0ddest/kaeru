package app.kaeru.ui.mobile

import android.net.Uri
import app.kaeru.domain.together.RoomLink

/**
 * The invitation an intent carries, or null when it carries none.
 *
 * Every form of a room lands on the launcher activity: the https one, which is what travels
 * through a messenger, and the `kaeru://watch` one, which either names a phone on this Wi-Fi or,
 * with no address in it, is the same relay room fired by the landing page's button. Any of them
 * can be fired by anything on the device, so the room is parsed here — before a screen opens —
 * and a link that is not a room never becomes a screen asking whether to join one.
 * [RoomLink.parse] is what says no: to a missing key, to a room id of the wrong length, and to a
 * LAN address that could be routed off this network.
 *
 * The host is checked here as well, and on purpose. [RoomLink.parse] reads the room and the key
 * and has no opinion about which site an https link came from — reasonably, since neither half of
 * a room is a place. But «somebody sent me a link» is exactly the case where the site matters: a
 * `/w/…` path on any other domain is not an invitation from this app, and must not open a screen
 * that looks like one.
 *
 * The string is handed on rather than the parsed room, because what crosses into the navigation
 * graph is an argument and the key is not something to put in one twice.
 */
fun watchLinkOf(data: Uri?): String? {
    val uri = data?.toString() ?: return null
    if (RoomLink.parse(uri).isFailure) return null
    val https = "https".equals(data.scheme, ignoreCase = true)
    if (https && !data.toString().startsWith(RoomLink.HTTPS_BASE)) return null
    return uri
}
