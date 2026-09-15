package app.kaeru.ui.tv.player

import app.kaeru.player.EpisodeQueue

/** A remote press, reduced to the keys the player has an opinion about. */
enum class TvKey {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    CENTER,
    BACK,

    /** One button for both, which is what most remotes and every headset carry. */
    MEDIA_PLAY_PAUSE,

    /** Two separate buttons, which a keyboard and some set-top remotes carry instead. */
    MEDIA_PLAY,
    MEDIA_PAUSE,

    MEDIA_FAST_FORWARD,
    MEDIA_REWIND,
    MEDIA_NEXT,

    /** Stop, which on a player with nothing queued after it means «leave». */
    MEDIA_STOP,

    /** Anything else on the remote: it wakes the panel and is then left to the system. */
    OTHER,
}

enum class KeyAction { DOWN, UP }

/** What a press means. Null means «not ours»: Compose moves focus, or the system takes it. */
sealed interface TvPlayerCommand {

    /**
     * Bring the controls up, standing on [rung] — or, when it is null, wherever they were left.
     */
    data class ShowPanel(val rung: TvPanelRung?) : TvPlayerCommand

    data object HidePanel : TvPlayerCommand

    /**
     * Swallow the press and change nothing.
     *
     * What a card on screen does with a direction that would walk out of it. Compose would
     * happily move focus from the autoplay offer down into the panel behind it, leaving the
     * viewer pressing a control they cannot see past the card they are being asked about.
     */
    data object KeepFocus : TvPlayerCommand

    /** Step one rung along the panel's vertical axis. */
    data class MoveRung(val down: Boolean) : TvPlayerCommand

    data object TogglePlayPause : TvPlayerCommand

    data class SeekBy(val deltaMs: Long) : TvPlayerCommand

    data object PlayNext : TvPlayerCommand

    /** Leave the player for wherever it was opened from, position saved. */
    data object Exit : TvPlayerCommand
}

/**
 * Whether a press should bring the panel back.
 *
 * Everything does, with one exception: scrubbing is the one thing the remote does to the picture
 * itself, and a panel that jumped up over the picture on every jog would be covering the very
 * frames the viewer is looking for. So left and right — and the two media keys that mean the
 * same thing — seek against a clear screen, and say so with a mark over the video instead.
 *
 * Once the panel is up every key keeps it up, including those two: there they mean «next chip»,
 * and a row that timed out under the viewer's thumb would be the panel's worst moment to leave.
 */
internal fun wakesPanel(key: TvKey, panelVisible: Boolean): Boolean = panelVisible || key !in ScrubKeys

private val ScrubKeys = setOf(TvKey.LEFT, TvKey.RIGHT, TvKey.MEDIA_REWIND, TvKey.MEDIA_FAST_FORWARD)

/**
 * The whole remote in one pure function, so the rules can be read and tested without a
 * television attached. The screen turns key events into [TvKey] and commands into calls; every
 * decision about what a press means is here.
 *
 * Two vocabularies meet here. With the controls down the remote drives the picture: left and
 * right scrub, the centre pauses, up and down bring the panel back at the strip they point at.
 * With the controls up the remote drives the panel: up and down walk its rungs, and everything
 * else belongs to whichever row has focus. The media keys mean the same thing either way, which
 * is what makes them media keys.
 */
object TvPlayerKeyHandler {

    /** A press held past this many repeats jumps by [HOLD_STEP_MS] instead of ten seconds. */
    private const val HOLD_REPEATS = 3

    /** Held longer still, by [FAST_STEP_MS]: a minute a press is a minute of hold per second. */
    private const val FAST_REPEATS = 8

    private const val HOLD_STEP_MS = 30_000L
    private const val FAST_STEP_MS = 60_000L

    /**
     * @param key what was pressed
     * @param action [KeyAction.DOWN] acts; a release never does
     * @param panelVisible whether the controls are on screen, which is what back undoes first
     * @param isPlaying what the picture is doing, which is the whole difference between a remote
     *   with one play/pause button and a remote with two
     * @param cardOpen a card is asking a question — the autoplay offer, a failure, the question
     *   about closing the show off — and the D-pad is walking its buttons rather than the panel
     * @param repeatCount how many repeats the remote has already sent for this hold
     */
    fun onKey(
        key: TvKey,
        action: KeyAction,
        panelVisible: Boolean,
        isPlaying: Boolean,
        cardOpen: Boolean = false,
        repeatCount: Int = 0,
    ): TvPlayerCommand? {
        if (action != KeyAction.DOWN) return null
        val step = seekStep(repeatCount)

        // The media keys are the same buttons wherever the viewer is looking — that is what
        // makes them media keys — so they are answered before anything else is consulted.
        when (key) {
            TvKey.MEDIA_PLAY_PAUSE -> return TvPlayerCommand.TogglePlayPause
            // A dedicated key is not a toggle: play on a running episode has to do nothing, or a
            // viewer pressing it twice stops the very thing they asked to start.
            TvKey.MEDIA_PLAY -> return TvPlayerCommand.TogglePlayPause.takeIf { !isPlaying }
            TvKey.MEDIA_PAUSE -> return TvPlayerCommand.TogglePlayPause.takeIf { isPlaying }
            TvKey.MEDIA_FAST_FORWARD -> return TvPlayerCommand.SeekBy(step)
            TvKey.MEDIA_REWIND -> return TvPlayerCommand.SeekBy(-step)
            TvKey.MEDIA_NEXT -> return TvPlayerCommand.PlayNext
            // Nothing is queued behind this episode, so stopping it and leaving are the same
            // thing — and leaving is the one that saves the position.
            TvKey.MEDIA_STOP -> return TvPlayerCommand.Exit
            else -> Unit
        }

        // A card is a question, and the D-pad is how it gets answered: left and right walk its
        // buttons. Up and down have nowhere to go — the card is one row — so they are swallowed
        // rather than left to Compose, which would walk them into the panel behind the card.
        // Back does not come through here at all while one is up: the screen's own back handler
        // dismisses it.
        if (cardOpen) {
            return if (key == TvKey.UP || key == TvKey.DOWN) TvPlayerCommand.KeepFocus else null
        }

        if (panelVisible) {
            return when (key) {
                TvKey.UP -> TvPlayerCommand.MoveRung(down = false)
                TvKey.DOWN -> TvPlayerCommand.MoveRung(down = true)
                TvKey.BACK -> TvPlayerCommand.HidePanel
                // Left, right and the centre are the focused row's: walking its chips and
                // pressing one are the only things they can mean up here.
                else -> null
            }
        }

        return when (key) {
            TvKey.LEFT -> TvPlayerCommand.SeekBy(-step)
            TvKey.RIGHT -> TvPlayerCommand.SeekBy(step)
            // The spec's two shortcuts: up is the season and the voices, down is the quality.
            TvKey.UP -> TvPlayerCommand.ShowPanel(TvPanelRung.EPISODES)
            TvKey.DOWN -> TvPlayerCommand.ShowPanel(TvPanelRung.QUALITY)
            TvKey.CENTER -> TvPlayerCommand.TogglePlayPause
            TvKey.BACK -> TvPlayerCommand.Exit
            TvKey.OTHER -> TvPlayerCommand.ShowPanel(null)
            // The media keys left above; the compiler knows, so a new key added to [TvKey] will
            // fail here rather than quietly doing nothing on a remote nobody tested with.
            TvKey.MEDIA_PLAY_PAUSE, TvKey.MEDIA_PLAY, TvKey.MEDIA_PAUSE, TvKey.MEDIA_FAST_FORWARD,
            TvKey.MEDIA_REWIND, TvKey.MEDIA_NEXT, TvKey.MEDIA_STOP,
            -> null
        }
    }

    private fun seekStep(repeatCount: Int): Long = when {
        repeatCount >= FAST_REPEATS -> FAST_STEP_MS
        repeatCount >= HOLD_REPEATS -> HOLD_STEP_MS
        else -> EpisodeQueue.SEEK_STEP_MS
    }
}
