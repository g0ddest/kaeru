package app.kaeru.ui.mobile.player

import android.app.Activity
import android.media.AudioManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import kotlin.math.roundToInt

/** Which half of the picture a finger came down on, and therefore what a swipe there means. */
enum class PlayerSide { LEFT, RIGHT }

/** What a double tap means, by where it landed. */
enum class DoubleTapZone { BACK, TOGGLE, FORWARD }

/**
 * The arithmetic behind the gestures, with no Android and no Compose in it.
 *
 * A player with its controls hidden is driven almost entirely by where a finger lands and which
 * way it moves, so these few rules decide most of what the screen does. They are here rather than
 * inside the modifier so they can be read and argued with on their own.
 */
object GestureMath {

    /**
     * How much of the screen's height one end-to-end sweep covers.
     *
     * Less than the whole screen: a thumb on a phone held in landscape cannot reach the top and
     * the bottom edges without the hand moving, and a gesture that needs the whole height to go
     * from silent to loud reads as unresponsive.
     */
    const val FULL_SWEEP = 0.6f

    /** The picture is split down the middle: brightness on the left, volume on the right. */
    fun side(x: Float, width: Float): PlayerSide =
        if (x < width / 2f) PlayerSide.LEFT else PlayerSide.RIGHT

    /**
     * Thirds, not halves: the middle of the screen is where a viewer taps to bring the controls
     * back, and a double tap there must not throw the picture ten seconds off course.
     */
    fun doubleTapZone(x: Float, width: Float): DoubleTapZone {
        val third = width / 3f
        return when {
            x < third -> DoubleTapZone.BACK
            x > width - third -> DoubleTapZone.FORWARD
            else -> DoubleTapZone.TOGGLE
        }
    }

    /**
     * Where a level ends up after a finger has travelled [dragPx] from where it came down.
     *
     * Compose counts y downwards and both of these levels count upwards, hence the sign. The
     * whole drag is measured from the start of the gesture rather than accumulated frame by
     * frame, so a level that hits an end and comes back lands exactly where it started.
     */
    fun adjust(current: Float, dragPx: Float, heightPx: Float): Float {
        val sweep = heightPx * FULL_SWEEP
        if (sweep <= 0f) return current
        return (current - dragPx / sweep).coerceIn(0f, 1f)
    }

    /** A level as one of the rungs the audio system actually has. */
    fun step(level: Float, max: Int): Int =
        if (max <= 0) 0 else (level.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)

    /** The rung a device is on, back as a level. */
    fun level(step: Int, max: Int): Float =
        if (max <= 0) 0f else (step.toFloat() / max).coerceIn(0f, 1f)
}

/**
 * Everything a finger can say to a video with nothing drawn on it.
 *
 * One tap brings the controls back or sends them away. A double tap near either edge steps ten
 * seconds; one in the middle is still a tap, because that is where a thumb lands when the viewer
 * only wants to see where they are. A vertical drag is brightness on the left of the picture and
 * volume on the right — the arrangement every player on a phone uses, so it needs no teaching.
 *
 * Taps and drags are two separate pointer handlers on purpose: the drag one claims the pointer
 * only once it has moved far enough to be a drag, which is what lets a tap that wandered a few
 * pixels still count as a tap.
 *
 * @param onSwipeStart asked, when a drag begins on [PlayerSide], for the level that side is at
 *   now; everything the drag does is measured from it.
 * @param onSwipe the new level for that side, from 0f to 1f.
 */
fun Modifier.playerGestures(
    enabled: Boolean = true,
    onTap: () -> Unit,
    onSeek: (forward: Boolean) -> Unit,
    onSwipeStart: (PlayerSide) -> Float,
    onSwipe: (PlayerSide, Float) -> Unit,
    onSwipeEnd: () -> Unit,
): Modifier = this
    .pointerInput(enabled) {
        if (!enabled) return@pointerInput
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { offset ->
                when (GestureMath.doubleTapZone(offset.x, size.width.toFloat())) {
                    DoubleTapZone.BACK -> onSeek(false)
                    DoubleTapZone.FORWARD -> onSeek(true)
                    DoubleTapZone.TOGGLE -> onTap()
                }
            },
        )
    }
    .pointerInput(enabled) {
        if (!enabled) return@pointerInput
        var side = PlayerSide.LEFT
        var from = 0f
        var travelled = 0f
        detectVerticalDragGestures(
            onDragStart = { offset ->
                side = GestureMath.side(offset.x, size.width.toFloat())
                from = onSwipeStart(side)
                travelled = 0f
            },
            onDragEnd = { onSwipeEnd() },
            onDragCancel = { onSwipeEnd() },
        ) { change, dragAmount ->
            travelled += dragAmount
            onSwipe(side, GestureMath.adjust(from, travelled, size.height.toFloat()))
            change.consume()
        }
    }

/**
 * The two things a swipe over the video reaches for, which are neither the app's state nor the
 * player's: this window's brightness, and the device's media volume.
 *
 * Brightness is set on the window rather than on the device, so it lasts exactly as long as the
 * player is on screen and the phone goes back to whatever it was doing afterwards — which is what
 * a viewer expects from dimming a video at night.
 */
@Immutable
class PlayerHardware(private val activity: Activity?, private val audio: AudioManager?, private val systemBrightness: Float) {

    fun brightness(): Float {
        val set = activity?.window?.attributes?.screenBrightness ?: return systemBrightness
        // Negative means «whatever the device is set to»: the window has not been overridden yet.
        return if (set < 0f) systemBrightness else set.coerceIn(0f, 1f)
    }

    fun setBrightness(level: Float) {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply {
            // Never all the way to zero: a black screen with no controls on it is indistinguishable
            // from a phone that has crashed.
            screenBrightness = level.coerceIn(MIN_BRIGHTNESS, 1f)
        }
    }

    fun volume(): Float {
        val manager = audio ?: return 0f
        // Reading a device's own volume should not be able to fail, and on some it does. A gesture
        // that starts from silence is wrong; a gesture that crashes the player is worse.
        return runCatching {
            GestureMath.level(
                manager.getStreamVolume(AudioManager.STREAM_MUSIC),
                manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            )
        }.getOrDefault(0f)
    }

    fun setVolume(level: Float) {
        val manager = audio ?: return
        // No system flag: the screen draws its own indicator, and two of them at once is one too many.
        runCatching {
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, GestureMath.step(level, max), 0)
        }
    }

    private companion object {
        /** Dim, but still a picture. */
        const val MIN_BRIGHTNESS = 0.02f
    }
}

/** [PlayerHardware] for the window this screen is in, read once. */
@Composable
fun rememberPlayerHardware(): PlayerHardware {
    val activity = LocalActivity.current
    val context = LocalContext.current
    return remember(activity, context) {
        val audio = context.getSystemService<AudioManager>()
        // The device's own setting, for a window that has not been dimmed yet. Unreadable on some
        // devices and meaningless on one with automatic brightness, so half is the fallback.
        val system = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0f, 1f)
        PlayerHardware(activity, audio, system)
    }
}
