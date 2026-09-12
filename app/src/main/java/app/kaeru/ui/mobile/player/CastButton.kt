package app.kaeru.ui.mobile.player

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * Whether this device can cast at all, answered once by the activity that knows.
 *
 * A phone with no Google Play services has no Cast framework, and asking it for one throws.
 * Rather than let every screen find that out for itself, the two activities that host cast
 * buttons ask [app.kaeru.player.CastFramework] once and put the answer here; false is the
 * right default everywhere else, the TV included.
 */
val LocalCastAvailable = staticCompositionLocalOf { false }

/**
 * The cast button, in the one form the Cast framework will drive: `MediaRouteButton`.
 *
 * It manages its own presence — the framework hides it while there is no receiver on the
 * network and shows it when one appears — so the only thing worth deciding here is whether
 * there is a framework at all. Its size is fixed so the bar it sits in does not reflow when a
 * Chromecast is switched on across the room.
 *
 * Tapping it opens the route chooser, which is an AppCompat dialog hosted by the activity's
 * fragment manager: both hosting activities are `FragmentActivity` and `Theme.Kaeru` descends
 * from `Theme.AppCompat` for exactly this.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    if (!LocalCastAvailable.current) return
    AndroidView(
        modifier = modifier.size(BUTTON_SIZE),
        factory = { context ->
            MediaRouteButton(context).also {
                // Never fatal: a framework that will not wire the button costs casting, and
                // an app that crashes on its home screen costs everything.
                runCatching { CastButtonFactory.setUpMediaRouteButton(context.applicationContext, it) }
            }
        },
    )
}

private val BUTTON_SIZE = 40.dp
