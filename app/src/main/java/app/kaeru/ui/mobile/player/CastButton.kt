package app.kaeru.ui.mobile.player

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import app.kaeru.ui.common.design.KaeruTokens
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
 *
 * @param overArtwork draws a dark disc behind the icon, for the one place it sits on a poster
 *   rather than on the app's own background and its contrast is otherwise whatever the artwork
 *   happens to be. The disc is the button's own view background, so it comes and goes with it.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier, overArtwork: Boolean = false) {
    if (!LocalCastAvailable.current) return
    AndroidView(
        modifier = modifier.size(BUTTON_SIZE),
        factory = { context ->
            MediaRouteButton(context).also { button ->
                if (overArtwork) {
                    button.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(SCRIM)
                    }
                }
                // Never fatal: a framework that will not wire the button costs casting, and
                // an app that crashes on its home screen costs everything.
                runCatching { CastButtonFactory.setUpMediaRouteButton(context.applicationContext, button) }
            }
        },
    )
}

/**
 * Black at 42 %, the same disc `IconAction` draws under a glyph over artwork: the cast button sits
 * beside one in the home screen's top bar, and two discs of different weights read as two controls
 * of different importance.
 */
private const val SCRIM = 0x6B000000

/** The app's floor for anything a finger reaches, and the size of the gear it sits next to. */
private val BUTTON_SIZE = KaeruTokens.MinTouchTarget
