package app.kaeru.ui.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kaeru.player.EpisodeQueue
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.player.CastButton
import app.kaeru.ui.common.design.KaeruSeekBar
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.ProgressStrip
import app.kaeru.ui.common.design.formatTime
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruOnAccent
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import kotlin.math.roundToLong

/**
 * The phone while a Chromecast has the picture: a remote control, not a player.
 *
 * There is no surface to attach and nothing to hide the controls for, so everything is on
 * screen at once and nothing fades. The poster stands in for the video, which is the one thing
 * the viewer can no longer see from here.
 *
 * Volume is deliberately absent: the phone's own volume keys drive the receiver while a
 * session is up, so a slider here would be a second, worse way to do what the buttons under
 * the viewer's thumb already do.
 *
 * ## The two shapes this screen has
 *
 * A phone held upright gives this 360×780dp; turned on its side it gives 780×360dp, and a remote
 * that assumes one of those is broken on the other. It was: the whole of the screen lived in one
 * `Column`, and a `Column` hands its later children whatever the earlier ones left over, without
 * complaint and without a mark. In landscape the right-hand column wanted 255dp of the 180 it had,
 * so the timeline was measured at 45dp of 48, the transport row at **nothing at all**, and the
 * three discs were drawn 0dp tall over the top of «Серии» — which is the photograph this screen
 * arrived as. Upright the same arithmetic ran sideways: a 138dp column against a transport row
 * needing 256, so «вперёд на 10 секунд» measured 2dp wide and «Следующая серия» laid its label out
 * in fifteen lines of nothing.
 *
 * So the shape is decided from the room there actually is, measured after the system's own insets
 * rather than read off the display:
 *
 * * **[WIDE_ENOUGH] of width** and the timeline and transport stand in the column beside the
 *   poster, which is what a landscape phone and a tablet want. Below it they go full width under
 *   the poster instead — there is no honest way to fit a 184dp row of discs into a 138dp column.
 * * **Under [TALL_ENOUGH] of height** every vertical measurement comes down a step: one line of
 *   title, the episode number moved up beside the chips, a 56dp play button instead of 64, a
 *   shorter strip of episodes, and the text above the controls scrolling if it still will not fit.
 *   The controls themselves never scroll: they are the reason the screen exists.
 *
 * `RemoteRenderBudgetTest` composes all of this at both sizes in all three states and measures it.
 */
@Composable
fun RemoteControlScreen(
    state: PlayerUiState,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onNext: () -> Unit,
    onCancelAutoplay: () -> Unit,
    onOpenTranslations: () -> Unit,
    onOpenQualities: () -> Unit,
    onPickEpisode: (Int) -> Unit,
    onRetry: () -> Unit,
    onStopCasting: () -> Unit,
    /**
     * The friend on the other phone, while there is one.
     *
     * Casting changes nothing about a shared viewing: the friend's play, pause and seek are still
     * applied, now to the television. So the chip comes with it — it is the only way out of a
     * session, and a remote control without it is a session a viewer cannot leave.
     */
    togetherPeer: String? = null,
    onLeaveTogether: (() -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(KaeruBackground).safeDrawingPadding()) {
        val short = maxHeight < TALL_ENOUGH
        val beside = maxWidth >= WIDE_ENOUGH

        Column(Modifier.fillMaxSize()) {
            RemoteHeader(state, short, togetherPeer, onBack, onStopCasting, onLeaveTogether)

            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = KaeruTokens.Space6)) {
                RemotePoster(state, short)
                Column(Modifier.weight(1f).padding(start = KaeruTokens.Space6)) {
                    if (beside) {
                        // The text scrolls only where it has to, and only ever the text: the
                        // timeline and the discs are not weighted, so the column measures them
                        // first and hands what is left to the block above them. On a landscape
                        // phone that is enough for the name and the chips, and a failure — the one
                        // row that is there only sometimes — is what a viewer scrolls for.
                        if (short) {
                            RemoteFacts(
                                state = state,
                                short = true,
                                onOpenTranslations = onOpenTranslations,
                                onOpenQualities = onOpenQualities,
                                onRetry = onRetry,
                                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            )
                        } else {
                            RemoteFacts(state, false, onOpenTranslations, onOpenQualities, onRetry)
                            Spacer(Modifier.weight(1f))
                        }
                        Controls(state, short, true, onSeekTo, onTogglePlayPause, onSeekBy, onNext, onCancelAutoplay)
                    } else {
                        // Upright the column beside the poster is 138dp wide, which is a name and
                        // nothing else. Everything that needs a row of its own goes under both.
                        RemoteTitle(state, maxLines = 2)
                        EpisodeLine(state)
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            if (!beside) {
                Column(Modifier.fillMaxWidth().padding(horizontal = KaeruTokens.Space6)) {
                    ChipRow(state, withEpisode = false, onOpenTranslations, onOpenQualities)
                    ErrorRow(state, onRetry)
                    // The one gap the column beside the poster cannot afford and this one can:
                    // upright, the last line of a failure would otherwise sit on top of «10:00».
                    Spacer(Modifier.height(KaeruTokens.Space2))
                    Controls(state, short, false, onSeekTo, onTogglePlayPause, onSeekBy, onNext, onCancelAutoplay)
                }
            }

            // The one thing the phone can do that the television's own remote cannot: jump straight
            // to another episode. Across the bottom rather than beside the poster, because it is a
            // list of many and everything above it is a list of one.
            EpisodeStrip(state, short, onPickEpisode)
        }
    }
}

/** Where the picture went, and the two ways back from it. */
@Composable
private fun RemoteHeader(
    state: PlayerUiState,
    short: Boolean,
    togetherPeer: String?,
    onBack: () -> Unit,
    onStopCasting: () -> Unit,
    onLeaveTogether: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(
            horizontal = KaeruTokens.Space2,
            vertical = if (short) KaeruTokens.Space1 else KaeruTokens.Space2,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = KaeruText)
        }
        Text(
            // Quoted, because a device name is a name and «на Гостиная ТВ» declines badly.
            state.receiverName?.let { "Идёт трансляция на „$it“" } ?: "Идёт трансляция",
            style = MaterialTheme.typography.labelLarge,
            color = KaeruAccent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = KaeruTokens.Space1),
        )
        if (togetherPeer != null) {
            TogetherButton(togetherPeer, onShare = {}, onLeave = onLeaveTogether, canInvite = false)
        }
        CastButton()
        TextButton(onClick = onStopCasting) { Text("Отключить", color = KaeruText) }
    }
}

/**
 * The artwork, which is the one thing the viewer can no longer see from here.
 *
 * On a short screen it takes the height of the row it is in and works its width back from the 2:3
 * the catalogue draws, rather than asking for 225dp of a 180dp row and being quietly cut to fit.
 */
@Composable
private fun RemotePoster(state: PlayerUiState, short: Boolean) {
    val modifier = if (short) {
        Modifier.fillMaxHeight().posterWidth(KaeruTokens.PosterAspect)
    } else {
        Modifier.width(POSTER_WIDTH).height(POSTER_HEIGHT)
    }
    Poster(state.posterUrl, state.title, modifier)
}

/**
 * A box as tall as it is given and [aspect] as wide as it is tall.
 *
 * `aspectRatio` would do this, but only by choosing one of the incoming constraints to honour and
 * overflowing the other; here the height is the one there is none of, so it is the one that rules.
 */
private fun Modifier.posterWidth(aspect: Float) = layout { measurable, constraints ->
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else POSTER_HEIGHT.roundToPx()
    val width = (height * aspect).toInt().coerceAtMost(constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width, minHeight = height))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** The name, the episode number and the chips: everything on this screen that is only a fact. */
@Composable
private fun RemoteFacts(
    state: PlayerUiState,
    short: Boolean,
    onOpenTranslations: () -> Unit,
    onOpenQualities: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        RemoteTitle(state, maxLines = if (short) 1 else 2)
        // Short of room the episode number joins the chips instead of taking a line of its own:
        // it is three words long and the row it joins has most of a landscape phone to spare.
        if (!short) EpisodeLine(state)
        // And a failure goes above the chips rather than under them, because on a short screen
        // this block is the part that scrolls: whichever row is last is the one a viewer has to
        // find, and «попробуйте ещё раз» is not something to make anybody look for.
        if (short) {
            ErrorRow(state, onRetry, maxLines = 2)
            ChipRow(state, withEpisode = true, onOpenTranslations, onOpenQualities)
        } else {
            ChipRow(state, withEpisode = false, onOpenTranslations, onOpenQualities)
            ErrorRow(state, onRetry)
        }
    }
}

@Composable
private fun RemoteTitle(state: PlayerUiState, maxLines: Int) {
    Text(
        state.title,
        style = MaterialTheme.typography.headlineSmall,
        color = KaeruText,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EpisodeLine(state: PlayerUiState) {
    if (state.episode <= 0) return
    Text(
        "${state.episode} серия",
        style = MaterialTheme.typography.bodyMedium,
        color = KaeruSecondary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ChipRow(
    state: PlayerUiState,
    withEpisode: Boolean,
    onOpenTranslations: () -> Unit,
    onOpenQualities: () -> Unit,
) {
    Row(
        Modifier.padding(top = KaeruTokens.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        if (withEpisode && state.episode > 0) {
            Text(
                "${state.episode} серия",
                style = MaterialTheme.typography.bodyMedium,
                color = KaeruSecondary,
                maxLines = 1,
            )
        }
        state.translationTitle?.let { RemoteChip(it, onOpenTranslations) }
        state.quality?.let { RemoteChip("${it.height}p", onOpenQualities) }
    }
}

/**
 * What went wrong, inline rather than on a screen of its own.
 *
 * From here the two things worth doing are trying again and watching on the phone instead, and
 * both need the rest of this screen to stay reachable.
 */
@Composable
private fun ErrorRow(state: PlayerUiState, onRetry: () -> Unit, maxLines: Int = Int.MAX_VALUE) {
    val message = state.errorMessage ?: return
    Row(
        Modifier.padding(top = KaeruTokens.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text("Повторить", color = KaeruAccent) }
    }
}

/** The timeline and the discs under it: the part of this screen that is never allowed to scroll. */
@Composable
private fun Controls(
    state: PlayerUiState,
    short: Boolean,
    nextBeside: Boolean,
    onSeekTo: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onNext: () -> Unit,
    onCancelAutoplay: () -> Unit,
) {
    Timeline(state, onSeekTo)
    Transport(state, short, nextBeside, onTogglePlayPause, onSeekBy, onNext, onCancelAutoplay)
}

/**
 * The season, as something to press.
 *
 * The same three facts the title screen's grid draws — counted, in progress, not aired yet — in
 * a strip that fits under a landscape remote. An episode that has not aired is drawn and not
 * offered, so the shape of the season is still readable.
 */
@Composable
private fun EpisodeStrip(state: PlayerUiState, short: Boolean, onPick: (Int) -> Unit) {
    if (state.episodes.size < 2) return
    val listState = rememberLazyListState()
    // Opens on what is playing rather than on episode one: a viewer on episode 24 should not
    // have to scroll to find where they are.
    LaunchedEffect(state.episode, state.episodes.size) {
        val index = state.episodes.indexOfFirst { it.number == state.episode }
        if (index >= 0) listState.scrollToItem(index)
    }
    Column(
        Modifier.padding(
            top = if (short) KaeruTokens.Space2 else KaeruTokens.Space3,
            bottom = if (short) KaeruTokens.Space3 else KaeruTokens.Space4,
        ),
    ) {
        Text(
            "Серии",
            style = MaterialTheme.typography.titleMedium,
            color = KaeruText,
            modifier = Modifier.padding(
                start = KaeruTokens.Space6,
                bottom = if (short) KaeruTokens.Space1 else KaeruTokens.Space2,
            ),
        )
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = KaeruTokens.Space6),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
        ) {
            items(state.episodes, key = { it.number }) { cell ->
                EpisodeTile(
                    cell = cell,
                    size = if (short) TILE_SIZE_SHORT else TILE_SIZE,
                    playing = cell.number == state.episode,
                    onPick = { onPick(cell.number) },
                )
            }
        }
    }
}

@Composable
private fun EpisodeTile(cell: EpisodeCell, size: Dp, playing: Boolean, onPick: () -> Unit) {
    val label = when {
        playing -> "${cell.number} серия, идёт сейчас"
        !cell.aired -> "${cell.number} серия, ещё не вышла"
        cell.watched -> "${cell.number} серия, просмотрена"
        else -> "${cell.number} серия"
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(if (playing) KaeruAccent else KaeruElevated)
            .clickable(enabled = cell.aired && !playing, onClickLabel = label, onClick = onPick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            cell.number.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = when {
                playing -> KaeruOnAccent
                !cell.aired -> KaeruSecondary
                cell.watched -> KaeruSecondary
                else -> KaeruText
            },
        )
        cell.progress?.takeIf { !playing }?.let {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                ProgressStrip(it)
            }
        }
    }
}

private val TILE_SIZE = 56.dp

/** Smaller on a short screen, and no smaller than a finger: the floor a tile may come down to. */
private val TILE_SIZE_SHORT = 44.dp

@Composable
private fun Timeline(state: PlayerUiState, onSeekTo: (Long) -> Unit) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val shown = scrubbing?.roundToLong() ?: state.positionMs
    val durationSafe = maxOf(state.durationMs, 1L)
    Row(Modifier.fillMaxWidth().padding(horizontal = KaeruTokens.SeekInset)) {
        Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = KaeruText)
        Spacer(Modifier.weight(1f))
        Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium, color = KaeruSecondary)
    }
    KaeruSeekBar(
        progress = shown.coerceIn(0, maxOf(state.durationMs, 0)).toFloat() / durationSafe.toFloat(),
        onScrub = { fraction -> scrubbing = fraction.coerceIn(0f, 1f) * durationSafe },
        onScrubEnd = {
            scrubbing?.let { onSeekTo(it.roundToLong()) }
            scrubbing = null
        },
        enabled = state.durationMs > 0,
        // A receiver buffers on its own side and tells us nothing about it, so there is nothing
        // honest to draw ahead of the position while casting.
        buffered = 0f,
    )
}

@Composable
private fun Transport(
    state: PlayerUiState,
    short: Boolean,
    nextBeside: Boolean,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onNext: () -> Unit,
    onCancelAutoplay: () -> Unit,
) {
    val disc = if (short) MAIN_DISC_SHORT else MAIN_DISC
    val glyph = if (short) MAIN_GLYPH_SHORT else MAIN_GLYPH
    Column(
        Modifier.fillMaxWidth().padding(
            top = KaeruTokens.Space1,
            bottom = if (short) 0.dp else KaeruTokens.Space3,
        ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RemoteButton(Icons.Default.Replay10, "Назад на 10 секунд") { onSeekBy(-EpisodeQueue.SEEK_STEP_MS) }
            Spacer(Modifier.width(KaeruTokens.Space3))
            Box(Modifier.size(disc), contentAlignment = Alignment.Center) {
                if (state.isBuffering) {
                    CircularProgressIndicator(color = KaeruAccent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                } else {
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(disc).clip(CircleShape).background(KaeruElevated),
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Пауза" else "Продолжить",
                            tint = KaeruText,
                            modifier = Modifier.size(glyph),
                        )
                    }
                }
            }
            Spacer(Modifier.width(KaeruTokens.Space3))
            RemoteButton(Icons.Default.Forward10, "Вперёд на 10 секунд") { onSeekBy(EpisodeQueue.SEEK_STEP_MS) }
            if (nextBeside) {
                Spacer(Modifier.weight(1f))
                NextEpisode(state, onNext, onCancelAutoplay)
            }
        }
        // A 312dp row cannot hold three discs and a sentence, and the sentence is the part that
        // has somewhere else to go: upright it takes the line under them rather than being
        // measured down to nothing on the end of theirs.
        if (!nextBeside && state.nextEpisodeAvailable) {
            Row(
                Modifier.fillMaxWidth().padding(top = KaeruTokens.Space1),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NextEpisode(state, onNext, onCancelAutoplay)
            }
        }
    }
}

/**
 * What happens when this episode runs out.
 *
 * The countdown takes over the button rather than floating over it: this screen has nothing to
 * float above, and one decision deserves one place to make it.
 */
@Composable
private fun NextEpisode(state: PlayerUiState, onNext: () -> Unit, onCancelAutoplay: () -> Unit) {
    if (!state.nextEpisodeAvailable) return
    val countdown = state.autoplayCountdownSec
    if (countdown == null) {
        TextButton(onClick = onNext) {
            Icon(Icons.Default.SkipNext, contentDescription = null, tint = KaeruText)
            Spacer(Modifier.width(6.dp))
            Text("Следующая серия", color = KaeruText, maxLines = 1)
        }
    } else {
        TextButton(onClick = onNext) {
            Text("Следующая серия через $countdown", color = KaeruAccent, maxLines = 1)
        }
        TextButton(onClick = onCancelAutoplay) { Text("Отмена", color = KaeruSecondary, maxLines = 1) }
    }
}

@Composable
private fun RemoteChip(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(KaeruElevated),
    ) {
        Text(text, color = KaeruText, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun RemoteButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(SIDE_DISC).clip(CircleShape).background(KaeruElevated)) {
        Icon(icon, contentDescription = description, tint = KaeruText)
    }
}

/**
 * Where the screen is short, the play button comes down a step and the two beside it do not.
 *
 * The row is as tall as its tallest disc, so shrinking the side pair buys no height at all — and
 * Material's own icon button will not go under 48dp of interactive box whatever size is asked of
 * it, which is the floor the app's [KaeruTokens.MinTouchTarget] names for the same reason.
 */
private val SIDE_DISC = KaeruTokens.MinTouchTarget
private val MAIN_DISC = 64.dp
private val MAIN_DISC_SHORT = 56.dp
private val MAIN_GLYPH = 34.dp
private val MAIN_GLYPH_SHORT = 28.dp

/** Above this there is room for the layout the screen was drawn for; under it, for less of it. */
private val TALL_ENOUGH = 420.dp

/** And the width at which the controls can stand beside the poster rather than under it. */
private val WIDE_ENOUGH = 600.dp

private val POSTER_WIDTH = 150.dp
private val POSTER_HEIGHT = 225.dp
