package app.kaeru.ui.tv.player

import androidx.compose.runtime.Immutable
import app.kaeru.domain.model.Quality
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.player.PlayerUiState

/** Four seconds of playing with nothing pressed and the controls get out of the picture. */
internal const val PANEL_LINGER_MS = 4_000L

/** How often a held key is allowed to restart the linger timer. */
private const val WAKE_THROTTLE_MS = 500L

/**
 * The rungs of the panel's vertical axis, top to bottom.
 *
 * Two zones, as the spec draws them: what is playing above the timeline — which episode, whose
 * voice — and how it is playing below it. The order of the constants is the order on screen, and
 * everything that walks the axis relies on it.
 */
enum class TvPanelRung {
    /** The season, aired episodes only. */
    EPISODES,

    /** The voices the source offers for this anime. */
    TRANSLATIONS,

    /** The quality ladder. */
    QUALITY,

    /** Play, pause, the two ten-second jumps, the opening skip and the next episode. */
    TRANSPORT,
}

/**
 * Where the panel stands: whether it is on screen and which rung the D-pad is on.
 *
 * A value rather than four pieces of composition state, so that «what does this press do to the
 * panel» is a question with an answer a test can read. The composition holds one of these and
 * renders it; every rule about it is in this file.
 */
data class TvPanel(
    val visible: Boolean = true,
    val rung: TvPanelRung = TvPanelRung.TRANSPORT,
    /** Bumped by every wake that gets past the throttle, so the linger timer restarts. */
    val wake: Int = 0,
    /** The press the throttle measures the next one against. */
    val lastWakeMs: Long = 0,
) {

    /**
     * Puts the panel up on [rung] — by default the one it was left on — and restarts the linger
     * timer if [atMs] is far enough past the last press to be worth a recomposition.
     *
     * @param atMs the time of the key behind this wake, or null when no key is behind it.
     */
    fun shown(rung: TvPanelRung = this.rung, atMs: Long? = null): TvPanel {
        val next = nextWake(atMs, lastWakeMs)
        return copy(
            visible = true,
            rung = rung,
            wake = if (next == null) wake else wake + 1,
            lastWakeMs = next ?: lastWakeMs,
        )
    }

    /** Takes the panel down. The rung is kept: the panel comes back where it was left. */
    fun hidden(): TvPanel = copy(visible = false)

    /**
     * Whether this panel should take itself down after [PANEL_LINGER_MS].
     *
     * Two things stop the clock. A paused picture keeps its controls — there is nothing behind
     * them worth looking at, and pausing is usually how a viewer asks to read them. So does
     * anything still being chosen from, read or waited on, which is what [asking] carries.
     */
    fun hidesItself(playing: Boolean, asking: Boolean): Boolean = visible && playing && !asking
}

/**
 * Where the panel's throttle stands after a wake at [atMs], or null when the wake came too soon
 * after [lastWakeMs] to restart the linger timer. A held button repeats twenty times a second
 * and every restart recomposes the panel, so twice a second is enough.
 *
 * A wake with no key behind it — the autoplay offer — always counts and leaves the reference
 * point where it was. Standing it on a time no press can ever beat is what used to leave every
 * later press reading as «too soon», so a held button stopped keeping the panel up.
 */
internal fun nextWake(atMs: Long?, lastWakeMs: Long): Long? = when {
    atMs == null -> lastWakeMs
    atMs - lastWakeMs >= WAKE_THROTTLE_MS -> atMs
    else -> null
}

/**
 * The panel a key press leaves behind: up, on the rung the press asks for, and its linger timer
 * restarted if the press is far enough past the last one.
 *
 * It takes no list of rungs, and that is the point rather than an omission. What the panel holds is
 * the rung the *viewer* wants; which rung the D-pad can actually stand on is a separate question,
 * answered against the content at the moment focus is asked for — see [tvRungOrNearest]. Deciding
 * it here instead would write an answer about this instant into a value that outlives it: press
 * anything while the voices are still loading and their strip does not exist yet, so the panel
 * would settle on the quality row and still be sitting there when the list arrived.
 *
 * @param command what the press meant, or null when it meant nothing to the player.
 * @param atMs the time of the key behind the wake.
 */
internal fun tvPanelAfterWake(panel: TvPanel, command: TvPlayerCommand?, atMs: Long): TvPanel {
    val wanted = (command as? TvPlayerCommand.ShowPanel)?.rung ?: panel.rung
    return panel.shown(wanted, atMs)
}

/**
 * Everything the panel draws except the clock.
 *
 * A value, and the whole point of it is what it leaves out. The engine polls the position every
 * 250 ms, so a fresh [PlayerUiState] arrives four times a second while an episode runs; handing
 * that straight to the panel rebuilt both lazy rows and every visible chip to move one amber
 * line. This carries only what the choices are made of, so a tick that moved nothing but the
 * position produces an equal value and the panel skips it.
 *
 * [episodes] is already filtered to what has aired.
 */
@Immutable
data class TvPanelContent(
    val episodes: List<EpisodeCell> = emptyList(),
    val episode: Int = 0,
    val translations: List<RankedTranslation> = emptyList(),
    val translationId: Int? = null,
    val loadingTranslations: Boolean = false,
    val qualities: List<Quality> = emptyList(),
    val quality: Quality? = null,
    val isPlaying: Boolean = false,
    val nextEpisodeAvailable: Boolean = false,
) {

    /**
     * The rungs this content has, top to bottom.
     *
     * A strip with nothing in it is not a place for the D-pad to land: a remote that stops on an
     * empty row has nowhere to go but back, and from three metres away the viewer cannot tell an
     * empty row from a row that has not drawn yet. Only the transport row is unconditional —
     * there is always something to press there, even over a picture that failed to start.
     */
    val rungs: List<TvPanelRung> = buildList {
        if (episodes.isNotEmpty()) add(TvPanelRung.EPISODES)
        if (translations.isNotEmpty()) add(TvPanelRung.TRANSLATIONS)
        if (qualities.isNotEmpty()) add(TvPanelRung.QUALITY)
        add(TvPanelRung.TRANSPORT)
    }
}

/** What the panel draws, read off the whole state in one place. */
fun tvPanelContent(state: PlayerUiState) = TvPanelContent(
    episodes = tvAiredEpisodes(state),
    episode = state.episode,
    translations = state.translations,
    translationId = state.translationId,
    loadingTranslations = state.loadingTranslations,
    qualities = state.qualities,
    quality = state.quality,
    isPlaying = state.isPlaying,
    nextEpisodeAvailable = state.nextEpisodeAvailable,
)

/** The rungs this state has, top to bottom. */
fun tvPanelRungs(state: PlayerUiState): List<TvPanelRung> = tvPanelContent(state).rungs

/**
 * Whether a failure still owns the screen, or has stepped aside for the voices strip.
 *
 * «Сменить озвучку» is an answer to the failure, not a dismissal of it, so the message goes only
 * once there is something to answer it with: a list on its way, or a list that arrived. A load
 * that fails or comes back empty puts the message back, because the alternative is a panel
 * standing over a dead picture with no voices row and «Повторить» nowhere on screen — back hides
 * the panel, back again leaves the player, and the viewer never got to retry.
 */
fun tvShowsFailure(state: PlayerUiState, choosingTrack: Boolean): Boolean =
    state.errorMessage != null &&
        !(choosingTrack && (state.translations.isNotEmpty() || state.loadingTranslations))

/**
 * The episodes the strip offers: the ones that have aired.
 *
 * The title screen draws an episode still to come as waiting, because there the season is the
 * subject. Here the strip is a way to change what is playing, so an episode nobody can start is
 * left out rather than drawn unpressable.
 */
fun tvAiredEpisodes(state: PlayerUiState): List<EpisodeCell> = state.episodes.filter { it.aired }

/**
 * The rung the D-pad reaches by stepping from [rung], or the same rung at either end.
 *
 * It stops rather than wrapping: a press that means «one more down» should not move the viewer's
 * eye the full height of the screen.
 */
fun tvStepRung(rungs: List<TvPanelRung>, rung: TvPanelRung, down: Boolean): TvPanelRung {
    val here = rungs.indexOf(rung)
    if (here < 0) return tvRungOrNearest(rungs, rung)
    val there = if (down) here + 1 else here - 1
    return rungs.getOrNull(there) ?: rung
}

/**
 * The rung the panel stands on when [wanted] is asked for but this state has no such strip:
 * the next one down, or the last rung there is.
 *
 * Down rather than up, because the axis ends in the transport row and that is the one rung
 * always worth landing on.
 */
fun tvRungOrNearest(rungs: List<TvPanelRung>, wanted: TvPanelRung): TvPanelRung =
    rungs.firstOrNull { it.ordinal >= wanted.ordinal }
        ?: rungs.lastOrNull()
        // Unreachable through [TvPanelContent.rungs], which always carries the transport row.
        // Here so the function is total: an answer rather than an exception if it ever is not.
        ?: TvPanelRung.TRANSPORT

/**
 * Where a strip stands: what it is a strip of, which chip is in play, and where the row has to
 * start for that chip to be on screen with something behind it.
 *
 * One value, because the row keys both its anchor and its opening scroll on it and the two must
 * not be able to disagree. It is deliberately built out of the chips' keys rather than the chips
 * themselves: the episode in play has its position rewritten every few seconds while it runs, and
 * a placement that noticed would scroll the row back under the viewer's thumb mid-browse and
 * forget the chip they had walked to.
 */
data class TvStripPlacement(val keys: List<Any>, val current: Any?, val firstVisible: Int)

/** Two chips of lead-in, so the one in play is not flat against the left edge. */
private const val STRIP_LEAD = 2

fun <T> tvStripPlacement(items: List<T>, key: (T) -> Any, isCurrent: (T) -> Boolean): TvStripPlacement {
    val index = items.indexOfFirst(isCurrent).coerceAtLeast(0)
    val keys = items.map(key)
    return TvStripPlacement(
        keys = keys,
        current = keys.getOrNull(index),
        firstVisible = (index - STRIP_LEAD).coerceAtLeast(0),
    )
}
