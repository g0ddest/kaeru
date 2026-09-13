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
    MEDIA_PLAY_PAUSE,
    MEDIA_FAST_FORWARD,
    MEDIA_REWIND,
    MEDIA_NEXT,

    /** Anything else on the remote: it wakes the panel and is then left to the system. */
    OTHER,
}

enum class KeyAction { DOWN, UP }

/**
 * Where the D-pad is pointed. The player is one vertical column — the episodes strip above the
 * timeline, the buttons below it, the quality strip below those — and this says which rung the
 * viewer is standing on, so up and down mean the next rung rather than a fixed destination.
 */
enum class TvPlayerFocus {
    /** Nothing focusable: the panel is down and the video itself is the control. */
    NONE,

    /** The timeline. Left and right scrub; this is where the panel opens. */
    PROGRESS,

    /** One of the buttons under the timeline. Left and right walk the row. */
    ACTIONS,

    /** A chip in an open strip. The strip owns every direction until it is closed. */
    STRIP,
}

/** What a press means. Null means "not ours": Compose moves focus, or the system takes it. */
sealed interface TvPlayerCommand {
    /** Nothing to do but be visible. Not consumed, so volume and the like still reach the system. */
    data object ShowPanel : TvPlayerCommand

    data object HidePanel : TvPlayerCommand

    data object TogglePlayPause : TvPlayerCommand

    data class SeekBy(val deltaMs: Long) : TvPlayerCommand

    /** Episodes and voices, the strip above the timeline. */
    data object OpenEpisodes : TvPlayerCommand

    data object OpenQuality : TvPlayerCommand

    data object CloseStrip : TvPlayerCommand

    data object FocusProgress : TvPlayerCommand

    data object FocusActions : TvPlayerCommand

    data object PlayNext : TvPlayerCommand

    /** Leave the player for wherever it was opened from, position saved. */
    data object Exit : TvPlayerCommand
}

/**
 * The whole remote in one pure function, so the rules can be read and tested without a
 * television attached. The screen turns key events into [TvKey] and commands into calls; every
 * decision about what a press means is here.
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
     * @param repeatCount how many repeats the remote has already sent for this hold
     * @param focusedControl the rung the D-pad is standing on, ignored while the panel is down
     */
    fun onKey(
        key: TvKey,
        action: KeyAction,
        panelVisible: Boolean,
        repeatCount: Int = 0,
        focusedControl: TvPlayerFocus = TvPlayerFocus.NONE,
    ): TvPlayerCommand? {
        if (action != KeyAction.DOWN) return null
        // A panel that hid itself between the press and this call leaves nothing focused,
        // whatever the caller still believes: the video is in charge again.
        val focus = if (panelVisible) focusedControl else TvPlayerFocus.NONE
        val step = seekStep(repeatCount)

        // The media keys are the same buttons wherever the viewer is looking — that is what
        // makes them media keys — so they are answered before focus is consulted.
        when (key) {
            TvKey.MEDIA_PLAY_PAUSE -> return TvPlayerCommand.TogglePlayPause
            TvKey.MEDIA_FAST_FORWARD -> return TvPlayerCommand.SeekBy(step)
            TvKey.MEDIA_REWIND -> return TvPlayerCommand.SeekBy(-step)
            TvKey.MEDIA_NEXT -> return TvPlayerCommand.PlayNext
            TvKey.BACK -> return when {
                focus == TvPlayerFocus.STRIP -> TvPlayerCommand.CloseStrip
                panelVisible -> TvPlayerCommand.HidePanel
                else -> TvPlayerCommand.Exit
            }
            else -> Unit
        }

        // An open strip is a chooser: every direction walks its chips, and the centre picks one.
        if (focus == TvPlayerFocus.STRIP) return null

        return when (key) {
            TvKey.LEFT -> if (focus == TvPlayerFocus.ACTIONS) null else TvPlayerCommand.SeekBy(-step)
            TvKey.RIGHT -> if (focus == TvPlayerFocus.ACTIONS) null else TvPlayerCommand.SeekBy(step)
            TvKey.UP ->
                if (focus == TvPlayerFocus.ACTIONS) TvPlayerCommand.FocusProgress
                else TvPlayerCommand.OpenEpisodes
            TvKey.DOWN ->
                if (focus == TvPlayerFocus.PROGRESS) TvPlayerCommand.FocusActions
                else TvPlayerCommand.OpenQuality
            // A focused button presses itself; anywhere else the centre is the pause key.
            TvKey.CENTER -> if (focus == TvPlayerFocus.ACTIONS) null else TvPlayerCommand.TogglePlayPause
            TvKey.OTHER -> if (panelVisible) null else TvPlayerCommand.ShowPanel
        }
    }

    private fun seekStep(repeatCount: Int): Long = when {
        repeatCount >= FAST_REPEATS -> FAST_STEP_MS
        repeatCount >= HOLD_REPEATS -> HOLD_STEP_MS
        else -> EpisodeQueue.SEEK_STEP_MS
    }
}
