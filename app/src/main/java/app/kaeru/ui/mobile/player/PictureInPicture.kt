package app.kaeru.ui.mobile.player

import app.kaeru.ui.common.player.PlayerUiState
import kotlin.math.roundToInt

/**
 * The widest and tallest window Android will accept. Anything outside this range is refused
 * outright, which on a phone means the episode carries on full screen while the viewer watches
 * their home screen appear over it.
 */
const val PIP_MIN_RATIO = 1f / 2.39f
const val PIP_MAX_RATIO = 2.39f

/** The shape of the floating window, as a ratio the platform will take. */
data class PipAspect(val width: Int, val height: Int) {
    val ratio: Float get() = width.toFloat() / height.toFloat()
}

/**
 * The window's shape for a video of this size.
 *
 * The video's own proportions wherever the platform allows them, so a 4:3 television episode is
 * not stretched into a widescreen box. Sixteen by nine stands in until the first frame has been
 * decoded, because that is what nearly every episode turns out to be.
 */
fun pipAspect(videoWidth: Int, videoHeight: Int): PipAspect {
    if (videoWidth <= 0 || videoHeight <= 0) return DEFAULT_ASPECT
    val ratio = videoWidth.toFloat() / videoHeight.toFloat()
    return when {
        ratio > PIP_MAX_RATIO -> PipAspect(239, 100)
        ratio < PIP_MIN_RATIO -> PipAspect(100, 239)
        else -> PipAspect(videoWidth, videoHeight)
    }
}

private val DEFAULT_ASPECT = PipAspect(16, 9)

/** Where on screen the picture is, for the system to animate the window out of. */
data class PipBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * The part of the window the video actually occupies.
 *
 * A video is fitted inside the window, so a 4:3 episode on a long phone leaves bars at the sides
 * and the animation into a floating window should start from the picture rather than from the
 * bars. Android asks for this as a hint, and a wrong one only costs a slightly worse animation,
 * which is why an unmeasured window answers with nothing rather than guessing.
 */
fun pipSourceBounds(windowWidth: Int, windowHeight: Int, videoWidth: Int, videoHeight: Int): PipBounds {
    if (windowWidth <= 0 || windowHeight <= 0) return PipBounds(0, 0, 0, 0)
    if (videoWidth <= 0 || videoHeight <= 0) return PipBounds(0, 0, windowWidth, windowHeight)
    val windowRatio = windowWidth.toFloat() / windowHeight.toFloat()
    val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
    return if (videoRatio > windowRatio) {
        val height = (windowWidth / videoRatio).roundToInt()
        val top = (windowHeight - height) / 2
        PipBounds(0, top, windowWidth, top + height)
    } else {
        val width = (windowHeight * videoRatio).roundToInt()
        val left = (windowWidth - width) / 2
        PipBounds(left, 0, left + width, windowHeight)
    }
}

/** Everything the system window needs to know, decided in one place so it can be tested. */
data class PipPlan(
    /** Whether there is a picture on this device worth putting in a window at all. */
    val allowed: Boolean,
    /** Whether leaving the app should fold it into one without being asked. */
    val autoEnter: Boolean,
    /** Whether the window's controls include «следующая серия». */
    val showNext: Boolean,
    val playing: Boolean,
)

/**
 * When the player may fold into a floating window.
 *
 * Three things rule it out, all for the same reason — there is nothing on this screen to keep
 * watching: the picture is on a television, the stream failed, or nothing has loaded yet. Playing
 * is what turns permission into intent: a viewer who paused and went to answer a message is not
 * asking for a window to follow them around.
 *
 * Folding by itself on top of that needs the viewer's say-so — the switch in settings, on unless
 * they turned it off — and a picture with nothing over it. A chooser or a question on top means
 * the viewer is mid-decision, and Android 12 folds the window on every opaque task switch: a
 * messenger notification, a permission prompt, recents. With a friend driving the picture over a
 * session, whether a given trip out of the app produced a window used to depend on what the friend
 * had last done, which is what made it feel unruly.
 *
 * Two of the things that can be over the picture are not in [state] at all, and are passed in
 * rather than guessed at: a sheet belonging to the shared viewing, and a question this app put to
 * the system and has not had answered. Leaving them out is what put a floating window over the
 * messenger a host had just picked to send the invitation through.
 *
 * @param historyOpen everything said this session, in a sheet the player's own state knows
 *   nothing about.
 * @param promptUp a chooser or a permission request is up: launched from here, not yet answered.
 */
fun pipPlan(
    state: PlayerUiState,
    historyOpen: Boolean = false,
    promptUp: Boolean = false,
): PipPlan {
    val allowed = !state.isCasting && state.errorMessage == null && state.episode > 0
    val modal = state.sheet != null || state.completedPrompt || historyOpen || promptUp
    return PipPlan(
        allowed = allowed,
        autoEnter = state.pipOnLeave && allowed && state.isPlaying && !modal,
        showNext = state.nextEpisodeAvailable,
        playing = state.isPlaying,
    )
}
