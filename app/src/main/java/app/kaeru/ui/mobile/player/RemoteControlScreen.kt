package app.kaeru.ui.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
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
 * ## The shapes this screen has
 *
 * A phone held upright gives this 360×780dp; turned on its side it gives 780×360dp; split-screen
 * gives it something like 390×360, which is neither. A remote that assumes one of those is broken
 * on the others, and it was: the whole of the screen lived in one `Column`, and a `Column` hands
 * its later children whatever the earlier ones left over, without complaint and without a mark.
 * In landscape the right-hand column wanted 255dp of the 180 it had, so the timeline was measured
 * at 45dp of 48, the transport row at **nothing at all**, and the three discs were drawn 0dp tall
 * over the top of «Серии» — which is the photograph this screen arrived as. Upright the same
 * arithmetic ran sideways: a 138dp column against a transport row needing 256, so «вперёд на 10
 * секунд» measured 2dp wide and «Следующая серия» laid its label out in fifteen lines of nothing.
 *
 * So the shape is decided from the room there actually is, measured after the system's own insets
 * rather than read off the display. Two questions — is there [WIDE_ENOUGH] of width, is there
 * [TALL_ENOUGH] of height — and four answers:
 *
 * * **Short and wide**, which is a landscape phone. The artwork takes the height of the row, the
 *   name and the chips share one line, the timecodes stand at the ends of the bar rather than on a
 *   line above it, and the timeline and the discs stand in the column beside the poster.
 * * **Short and narrow**, which is split screen. The artwork comes down to a fixed thumbnail, the
 *   name carries the episode number, the chips take the line under it, and the controls go full
 *   width beneath both — there is no honest way to fit a 184dp row of discs into a 170dp column.
 * * **Tall and wide**, a tablet on its side: the poster at its full size beside a column with
 *   everything else in it.
 * * **Tall and narrow**, a phone upright: the artwork above the words rather than beside them,
 *   which is what gives the name the width of the phone instead of 138dp of it.
 *
 * Nothing on this screen scrolls except the strip of episodes, and that is deliberate. A block
 * that scrolls has a fold, and a fold falls through whatever happens to be at it: at 130% type it
 * was falling through a pressable chip and through «Повторить», which is the original photograph
 * one step of the font-size slider along. What gives instead is the failure message, which says as
 * many lines as the room it was given holds.
 *
 * `RemoteRenderBudgetTest` composes all four shapes at both sizes of type, in every state, and
 * measures what it drew.
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
        // Upright there is a row of its own for it. Short of room it stands beside the discs —
        // as its words where there is a landscape phone's width for them, as its glyph where
        // there is not — unless it is counting down, which is a decision and needs its words.
        val nextBeside = beside || (short && state.autoplayCountdownSec == null)

        Column(Modifier.fillMaxSize()) {
            RemoteHeader(state, short, beside, togetherPeer, onBack, onStopCasting, onLeaveTogether)

            when {
                // A landscape phone, and anything else short and wide: the artwork takes the
                // height that is there, the facts take the width, and a failure takes whatever is
                // left of the column rather than being measured against a remainder nobody wrote
                // down. Nothing in here scrolls, so there is no fold for a control to be sliced by
                // — which is what this screen was sent back for.
                short && beside -> {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = KaeruTokens.Space6)) {
                        val artwork = maxHeight >= POSTER_MIN
                        Row(Modifier.fillMaxSize()) {
                            if (artwork) {
                                Poster(
                                    state.posterUrl,
                                    state.title,
                                    Modifier.fillMaxHeight().posterWidth(KaeruTokens.PosterAspect),
                                )
                                Spacer(Modifier.width(KaeruTokens.Space6))
                            }
                            Column(Modifier.weight(1f)) {
                                CompactFacts(state, true, onOpenTranslations, onOpenQualities)
                                RoomForAFailure(state, onRetry)
                                Controls(state, true, nextBeside, false, onSeekTo, onTogglePlayPause, onSeekBy, onNext, onCancelAutoplay)
                            }
                        }
                    }
                }

                // Half a phone wide and half a phone tall at once, which is what split screen
                // makes of an activity locked to `sensorLandscape`. The artwork comes down to a
                // thumbnail with a fixed height rather than being handed the leftover and measured
                // to nine device-independent pixels, and a failure gets the width of the window
                // rather than the 94dp left beside the picture — «Не удал…» is not a message.
                short -> {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = KaeruTokens.Space6)) {
                        Row(Modifier.fillMaxWidth()) {
                            Poster(
                                state.posterUrl,
                                state.title,
                                Modifier.height(POSTER_COMPACT).posterWidth(KaeruTokens.PosterAspect),
                            )
                            Spacer(Modifier.width(KaeruTokens.Space6))
                            Column(Modifier.weight(1f)) {
                                CompactFacts(state, false, onOpenTranslations, onOpenQualities)
                            }
                        }
                        RoomForAFailure(state, onRetry)
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = KaeruTokens.Space6)) {
                        Controls(state, true, nextBeside, true, onSeekTo, onTogglePlayPause, onSeekBy, onNext, onCancelAutoplay)
                    }
                }

                beside -> {
                    Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = KaeruTokens.Space6)) {
                        Poster(state.posterUrl, state.title, Modifier.width(POSTER_WIDTH).height(POSTER_HEIGHT))
                        Column(Modifier.weight(1f).padding(start = KaeruTokens.Space6)) {
                            RemoteFacts(state, false, onOpenTranslations, onOpenQualities, onRetry)
                            Spacer(Modifier.weight(1f))
                            Controls(state, false, nextBeside, false, onSeekTo, onTogglePlayPause, onSeekBy, onNext, onCancelAutoplay)
                        }
                    }
                }

                else -> {
                    // Upright the artwork goes above the words rather than beside them. Beside them
                    // it had a 138dp column to be a name in and 126dp of black down the side of the
                    // screen that nothing used; above them the name has the width of the phone, and
                    // the picture has everything the rest of the screen does not want — which on a
                    // 780dp phone is most of it.
                    PosterPanel(state)
                    Column(Modifier.fillMaxWidth().padding(horizontal = KaeruTokens.Space6)) {
                        RemoteTitle(state, maxLines = 2)
                        EpisodeLine(state)
                        ChipRow(state, withEpisode = false, onOpenTranslations, onOpenQualities)
                        ErrorRow(state, onRetry)
                        // The one gap the column beside the poster cannot afford and this one can:
                        // upright, the last line of a failure would otherwise sit on top of «10:00».
                        Spacer(Modifier.height(KaeruTokens.Space2))
                        Controls(state, false, nextBeside, false, onSeekTo, onTogglePlayPause, onSeekBy, onNext, onCancelAutoplay)
                    }
                }
            }

            // The one thing the phone can do that the television's own remote cannot: jump straight
            // to another episode. Across the bottom rather than beside the poster, because it is a
            // list of many and everything above it is a list of one.
            EpisodeStrip(state, short, beside, onPickEpisode)
        }
    }
}

/**
 * The artwork upright: centred, and as big as everything else does not want to be.
 *
 * Gone under [POSTER_MIN], where it is a rounded rectangle with a letter in it that does not fit —
 * a smudge rather than a picture, and a smudge with a clipped letter is what a split screen made
 * of the old poster row.
 */
@Composable
private fun ColumnScope.PosterPanel(state: PlayerUiState) {
    BoxWithConstraints(
        Modifier.weight(1f).fillMaxWidth().padding(vertical = KaeruTokens.Space2),
        contentAlignment = Alignment.Center,
    ) {
        if (maxHeight >= POSTER_MIN) {
            Poster(
                state.posterUrl,
                state.title,
                Modifier.fillMaxHeight().posterWidth(KaeruTokens.PosterAspect),
            )
        }
    }
}

/** Where the picture went, and the two ways back from it. */
@Composable
private fun RemoteHeader(
    state: PlayerUiState,
    short: Boolean,
    beside: Boolean,
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
        // Half a phone wide there is room for one name in this row, and with a friend on the other
        // phone it is theirs: their chip is the only way out of a shared viewing, and the cast
        // glyph beside it already says where the picture went. With no friend the television gets
        // the room — the whole sentence where the screen is wide, the name alone where it is not,
        // because «Идёт трансляция на „…“» left the name seven device-independent pixels.
        if (beside || togetherPeer == null) {
            Text(
                // Quoted, because a device name is a name and «на Гостиная ТВ» declines badly.
                when {
                    state.receiverName == null -> "Идёт трансляция"
                    beside -> "Идёт трансляция на „${state.receiverName}“"
                    else -> state.receiverName
                },
                style = MaterialTheme.typography.labelLarge,
                color = KaeruAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = KaeruTokens.Space1),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (togetherPeer != null) {
            // Weighted too, so a long name shortens rather than pushing the cast button and the
            // way out of the session off the end of the row.
            Box(Modifier.weight(1f, fill = false)) {
                TogetherButton(togetherPeer, onShare = {}, onLeave = onLeaveTogether, canInvite = false)
            }
        }
        CastButton()
        TextButton(onClick = onStopCasting) { Text("Отключить", color = KaeruText, maxLines = 1) }
    }
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
        ChipRow(state, withEpisode = short, onOpenTranslations, onOpenQualities)
        ErrorRow(state, onRetry)
    }
}

/**
 * The name and the chips on one line, which is what a short screen has room for.
 *
 * Stacked they are two rows of a block that has about a hundred device-independent pixels, and a
 * failure needs one of them. The name is the half that gives way — it is on the poster beside it
 * and on the screen this one was opened from, and it is the only thing here that is not something
 * to press or something that just changed.
 */
@Composable
private fun CompactFacts(
    state: PlayerUiState,
    beside: Boolean,
    onOpenTranslations: () -> Unit,
    onOpenQualities: () -> Unit,
) {
    if (beside) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RemoteTitle(state, maxLines = 1, modifier = Modifier.weight(1f))
            // Both weighted, so neither takes the whole line: unweighted the chips were measured
            // first, and a 34-character dub name left «Фр…» where the name should be. Half each,
            // and the chips give back whatever they do not need.
            ChipRow(
                state = state,
                withEpisode = true,
                onOpenTranslations = onOpenTranslations,
                onOpenQualities = onOpenQualities,
                modifier = Modifier.weight(1f, fill = false).padding(start = KaeruTokens.Space2),
                gap = 0.dp,
            )
        }
    } else {
        // Half a phone wide there is no line that holds a name and three chips at once: the name
        // would be «Фри…». So the name takes one line with the episode number on the end of it,
        // and the two chips take the next — three of them in a 170dp column left «AniLibria.TV»
        // 8dp wide with its label drawn at nothing.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RemoteTitle(state, maxLines = 1, modifier = Modifier.weight(1f))
            EpisodeLine(state, Modifier.padding(start = KaeruTokens.Space2), gap = 0.dp)
        }
        ChipRow(
            state = state,
            withEpisode = false,
            onOpenTranslations = onOpenTranslations,
            onOpenQualities = onOpenQualities,
            gap = KaeruTokens.Space1,
        )
    }
}

/**
 * Whatever the column has left after the controls have been measured, given to a failure.
 *
 * The room is weighted, so the timeline and the discs are never the ones that go short; the
 * message inside it says as many lines as the room holds and ellipsises the rest. With nothing
 * wrong it is empty space, and empty space at the top of the column is what puts the discs down
 * where a thumb is.
 */
@Composable
private fun ColumnScope.RoomForAFailure(state: PlayerUiState, onRetry: () -> Unit) {
    if (state.errorMessage == null) {
        Spacer(Modifier.weight(1f))
    } else {
        // Half the usual gap: the row below is a 48dp button in about fifty of them, and four
        // device-independent pixels are the difference between «Повторить» being drawn and being
        // drawn with its descenders shaved off.
        ErrorRow(state, onRetry, modifier = Modifier.weight(1f), gap = KaeruTokens.Space1)
    }
}

@Composable
private fun RemoteTitle(state: PlayerUiState, maxLines: Int, modifier: Modifier = Modifier) {
    Text(
        state.title,
        style = MaterialTheme.typography.headlineSmall,
        color = KaeruText,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun EpisodeLine(state: PlayerUiState, modifier: Modifier = Modifier, gap: Dp = 6.dp) {
    if (state.episode <= 0) return
    Text(
        "${state.episode} серия",
        style = MaterialTheme.typography.bodyMedium,
        color = KaeruSecondary,
        maxLines = 1,
        modifier = modifier.padding(top = gap),
    )
}

@Composable
private fun ChipRow(
    state: PlayerUiState,
    withEpisode: Boolean,
    onOpenTranslations: () -> Unit,
    onOpenQualities: () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = KaeruTokens.Space2,
) {
    Row(
        modifier.padding(top = gap),
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
        // Weighted, and the only thing in this row that is: a dub name runs to thirty-odd
        // characters and the quality beside it is five, so when the row runs out of width it is
        // the name that shortens rather than «1080p» being measured down to nothing on the end.
        state.translationTitle?.let {
            RemoteChip(it, onOpenTranslations, Modifier.weight(1f, fill = false))
        }
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
private fun ErrorRow(
    state: PlayerUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = KaeruTokens.Space2,
) {
    val message = state.errorMessage ?: return
    Row(
        modifier.padding(top = gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        // As many lines as there are, rather than a number picked in advance. A `Text` given
        // fewer pixels than its `maxLines` need does not shorten itself — it draws the top of the
        // paragraph and lets the rest be clipped — so the count comes from the room the row was
        // actually given. Where the room is unbounded, which is every upright screen, it is the
        // whole message.
        BoxWithConstraints(Modifier.weight(1f)) {
            val line = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
            val fits = if (maxHeight.isFinite && line > 0.dp) {
                (maxHeight / line).toInt().coerceAtLeast(1)
            } else {
                Int.MAX_VALUE
            }
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = fits,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRetry) { Text("Повторить", color = KaeruAccent, maxLines = 1) }
    }
}

/** The timeline and the discs under it: the part of this screen that is never allowed to scroll. */
@Composable
private fun Controls(
    state: PlayerUiState,
    short: Boolean,
    nextBeside: Boolean,
    narrow: Boolean,
    onSeekTo: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onNext: () -> Unit,
    onCancelAutoplay: () -> Unit,
) {
    Timeline(state, short, onSeekTo)
    Transport(state, short, nextBeside, narrow, onTogglePlayPause, onSeekBy, onNext, onCancelAutoplay)
}

/**
 * The season, as something to press.
 *
 * The same three facts the title screen's grid draws — counted, in progress, not aired yet — in
 * a strip that fits under a landscape remote. An episode that has not aired is drawn and not
 * offered, so the shape of the season is still readable.
 */
@Composable
private fun EpisodeStrip(state: PlayerUiState, short: Boolean, beside: Boolean, onPick: (Int) -> Unit) {
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
        // Half a phone wide and half a phone tall at once, the heading is 28dp that a failure
        // needs more: a row of numbered squares under the discs says «серии» on its own, and a
        // word over it that costs a control is a word too many.
        if (beside || !short) {
            Text(
                "Серии",
                style = MaterialTheme.typography.titleMedium,
                color = KaeruText,
                modifier = Modifier.padding(
                    start = KaeruTokens.Space6,
                    bottom = if (short) KaeruTokens.Space1 else KaeruTokens.Space2,
                ),
            )
        }
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

/**
 * Where the episode is, and how much of it there is.
 *
 * On a short screen the two timecodes stand at the ends of the bar rather than on a line of their
 * own above it. The bar is already 48dp of touch target with a 4dp track drawn down the middle of
 * it, so there is room either side of the track for a label without either one touching the other
 * — and the line it saves is the 19dp that decides whether the chips and a failure both fit above
 * the discs. That line was the difference: at the fourth step of the font-size slider the block
 * above the discs overflowed by 13dp and the fold cut a pressable chip in half, which is the
 * photograph this screen arrived as, one slider step along.
 */
@Composable
private fun Timeline(state: PlayerUiState, short: Boolean, onSeekTo: (Long) -> Unit) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val shown = scrubbing?.roundToLong() ?: state.positionMs
    val durationSafe = maxOf(state.durationMs, 1L)
    val bar: @Composable (Modifier) -> Unit = { modifier ->
        KaeruSeekBar(
            progress = shown.coerceIn(0, maxOf(state.durationMs, 0)).toFloat() / durationSafe.toFloat(),
            onScrub = { fraction -> scrubbing = fraction.coerceIn(0f, 1f) * durationSafe },
            onScrubEnd = {
                scrubbing?.let { onSeekTo(it.roundToLong()) }
                scrubbing = null
            },
            modifier = modifier,
            enabled = state.durationMs > 0,
            // A receiver buffers on its own side and tells us nothing about it, so there is nothing
            // honest to draw ahead of the position while casting.
            buffered = 0f,
        )
    }
    if (short) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = KaeruText, maxLines = 1)
            bar(Modifier.weight(1f))
            Text(
                formatTime(state.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = KaeruSecondary,
                maxLines = 1,
            )
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = KaeruTokens.SeekInset)) {
            Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = KaeruText)
            Spacer(Modifier.weight(1f))
            Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium, color = KaeruSecondary)
        }
        bar(Modifier)
    }
}

@Composable
private fun Transport(
    state: PlayerUiState,
    short: Boolean,
    nextBeside: Boolean,
    narrow: Boolean,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onNext: () -> Unit,
    onCancelAutoplay: () -> Unit,
) {
    val disc = if (short) MAIN_DISC_SHORT else MAIN_DISC
    val glyph = if (short) MAIN_GLYPH_SHORT else MAIN_GLYPH
    Column(
        Modifier.fillMaxWidth().padding(
            // The bar above keeps 48dp of touch target around a 4dp track, so short of room the
            // discs can sit straight under it without touching anything.
            top = if (short) 0.dp else KaeruTokens.Space1,
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
                // A row of its own for what is left of the line rather than a weighted spacer
                // beside a weighted button: the two of them split the remainder in half, and half
                // of it drew «Следующа…» with 80dp of empty row to its left.
                Row(
                    Modifier.weight(1f).padding(start = KaeruTokens.Space3),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NextEpisode(state, narrow, onNext, onCancelAutoplay)
                }
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
                NextEpisode(state, false, onNext, onCancelAutoplay)
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
private fun RowScope.NextEpisode(
    state: PlayerUiState,
    narrow: Boolean,
    onNext: () -> Unit,
    onCancelAutoplay: () -> Unit,
) {
    if (!state.nextEpisodeAvailable) return
    val countdown = state.autoplayCountdownSec
    if (narrow) {
        // Half a phone wide, the three discs and a sentence do not share a line — and the sentence
        // is what the glyph already says. Counting down it gets its own row instead, because then
        // it is not a button but a question with a deadline on it.
        IconButton(onClick = onNext) {
            Icon(Icons.Default.SkipNext, contentDescription = "Следующая серия", tint = KaeruText)
        }
        return
    }
    if (countdown == null) {
        TextButton(onClick = onNext) {
            Icon(Icons.Default.SkipNext, contentDescription = null, tint = KaeruText)
            Spacer(Modifier.width(6.dp))
            Text("Следующая серия", color = KaeruText, maxLines = 1)
        }
    } else {
        // Weighted, and «Отмена» is not: a countdown reading «Следующая серия через 5» is 273dp of
        // a 312dp row and it took all of it, leaving the one way to stop it 39dp wide with its
        // label drawn at fifteen. The sentence is the half that can be shortened.
        TextButton(onClick = onNext, modifier = Modifier.weight(1f, fill = false)) {
            Text(
                "Следующая серия через $countdown",
                color = KaeruAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onCancelAutoplay) { Text("Отмена", color = KaeruSecondary, maxLines = 1) }
    }
}

@Composable
private fun RemoteChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(KaeruElevated),
    ) {
        Text(
            text,
            color = KaeruText,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

/**
 * The smallest the artwork may be and still be artwork.
 *
 * Under it the poster is a rounded rectangle with the title's first letter in it, and the letter
 * does not fit — which is what a split screen made of the old poster row: 9dp of box for a 34dp
 * letter, and in the render the picture, the name and the episode number were simply not there.
 * Nothing is better than a smudge, and nothing is also 40dp of room for the things that are.
 */
private val POSTER_MIN = 64.dp

/**
 * The artwork in a window that is narrow and short at once.
 *
 * Fixed rather than given the leftover, because in that window the leftover is what a failure
 * needs: at 88dp the picture is a thumbnail a viewer recognises and the row it is in is still
 * shorter than the name and the chips beside it, so it costs nothing that was being used.
 */
private val POSTER_COMPACT = 88.dp
