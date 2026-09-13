package app.kaeru.ui.tv.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.player.EpisodeQueue
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.PosterImage
import app.kaeru.ui.common.design.IndeterminateStrip
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.design.formatTime
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTvTheme

private const val PAUSE = "Пауза"
private const val RESUME = "Продолжить"
/** The jump itself; which way it goes is the arrow beside it. */
private const val TEN_SECONDS = "10 с"
private const val SKIP_INTRO = "+85 с"
private const val NEXT_EPISODE = "Следующая серия"
private const val BUFFERING = "Загружаем"

/** The side margin the design system fixes for a television. No rail competes for it here. */
internal val PlayerGutter = KaeruTokens.GutterTv

/** How tall the scrim under the title runs before it gives the picture back. */
private val HeaderScrim = 168.dp

/** Where the panel's own background starts to take hold above it. */
private val PanelScrim = 96.dp

/** Thick enough to read from a sofa; the phone's timeline is four. */
private val LineHeight = 6.dp

/** The line's own row, tall enough that the timecodes beside it are not cramped. */
private val LineRow = 24.dp

private val BufferingDisc = 76.dp
private val BufferingMark = 40.dp

/**
 * The controls along the bottom of the picture: the two zones the spec asks for, with the
 * timeline drawn between them.
 *
 * Everything the panel offers is one vertical axis — the season, the voices, the timeline, the
 * quality, the transport row — and the D-pad walks it a rung at a time. The rows share a single
 * left edge with their names hanging in the margin beside them, so that axis is something the
 * eye can follow from across a room rather than a wall of chips.
 *
 * It is exactly as tall as its rows, so whatever the screen stacks above it — the offer to move
 * on, the wait for an episode that has not aired — sits on top of it without anyone having to
 * guess the height.
 *
 * Nothing here decides anything. Which rung is focused, whether the panel is up and what a press
 * means all live in [TvPanel] and [TvPlayerKeyHandler]; this draws the answer.
 */
@Composable
fun TvPlayerPanel(
    state: PlayerUiState,
    rungFocus: (TvPanelRung) -> FocusRequester,
    onPickEpisode: (Int) -> Unit,
    onPickTranslation: (Translation) -> Unit,
    onPickQuality: (Quality) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSkipIntro: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rungs = tvPanelRungs(state)

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(PanelScrim)
                .background(Brush.verticalGradient(listOf(Color.Transparent, KaeruBackground.copy(alpha = 0.94f)))),
        )
        Column(
            Modifier.fillMaxWidth()
                .background(KaeruBackground.copy(alpha = 0.94f))
                .padding(start = PlayerGutter, end = PlayerGutter, bottom = KaeruTokens.Space6),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
        ) {
            // The upper zone: what is playing.
            if (TvPanelRung.EPISODES in rungs) {
                TvEpisodeStrip(state, rungFocus(TvPanelRung.EPISODES), onPickEpisode)
            }
            TvTranslationStrip(state, rungFocus(TvPanelRung.TRANSLATIONS), onPickTranslation)

            // The line between the zones, which is also where the episode is.
            TvProgressLine(
                positionMs = state.positionMs,
                bufferedPositionMs = state.bufferedPositionMs,
                durationMs = state.durationMs,
                modifier = Modifier.padding(vertical = KaeruTokens.Space2),
            )

            // The lower zone: how it is playing.
            if (TvPanelRung.QUALITY in rungs) {
                TvQualityStrip(state, rungFocus(TvPanelRung.QUALITY), onPickQuality)
            }
            TvTransportRow(
                state = state,
                focus = rungFocus(TvPanelRung.TRANSPORT),
                onTogglePlayPause = onTogglePlayPause,
                onSeekBy = onSeekBy,
                onSkipIntro = onSkipIntro,
                onNext = onNext,
            )
        }
    }
}

/**
 * The title over the picture, on a scrim of its own, and nothing else.
 *
 * Which episode, whose voice and what quality all have a home of their own down in the panel,
 * each marked as the one in play, so repeating them here would be the app saying the same three
 * things twice on one screen.
 */
@Composable
fun TvPlayerHeader(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(HeaderScrim)
            .background(Brush.verticalGradient(listOf(KaeruBackground.copy(alpha = 0.88f), Color.Transparent))),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = KaeruText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = PlayerGutter, top = KaeruTokens.Space8, end = PlayerGutter)
                // Short of the far corner, which is where a line the player has to say goes.
                .widthIn(max = 700.dp),
        )
    }
}

/**
 * Where the episode is, drawn rather than assembled and deliberately not focusable.
 *
 * A remote has no pointer, so there is nothing to grab: scrubbing is left and right against a
 * clear picture, and the two jumps in the row below. What this owes the viewer is the answer to
 * «how much is left» — the played head against the whole, the paler head the network has
 * managed, and both times in full — which is drawing, not a control.
 *
 * The same three colours as the phone's timeline, so the two screens describe one episode the
 * same way.
 */
@Composable
fun TvProgressLine(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val played = fractionOf(positionMs, durationMs)
    val ready = fractionOf(bufferedPositionMs, durationMs)
    val track = KaeruText.copy(alpha = 0.24f)
    val readyColour = KaeruText.copy(alpha = 0.44f)

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(positionMs), style = MaterialTheme.typography.titleMedium, color = KaeruText)
        Canvas(
            Modifier.weight(1f).height(LineRow).padding(horizontal = KaeruTokens.Space4),
        ) {
            val y = size.height / 2f
            val stroke = LineHeight.toPx()
            drawLine(track, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
            if (ready > 0f) {
                drawLine(readyColour, Offset(0f, y), Offset(size.width * ready, y), stroke, StrokeCap.Round)
            }
            if (played > 0f) {
                drawLine(KaeruAccent, Offset(0f, y), Offset(size.width * played, y), stroke, StrokeCap.Round)
            }
        }
        Text(formatTime(durationMs), style = MaterialTheme.typography.titleMedium, color = KaeruSecondary)
    }
}

private fun fractionOf(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

/**
 * The bottom rung: everything that acts on the episode rather than choosing a different one.
 *
 * Its name in the margin is the episode number, which is the one fact the panel does not carry
 * anywhere else and the one the buttons beside it are all about.
 */
@Composable
private fun TvTransportRow(
    state: PlayerUiState,
    focus: FocusRequester,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSkipIntro: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            state.episode.takeIf { it > 0 }?.let { "$it серия" }.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = KaeruSecondary,
            maxLines = 1,
            modifier = Modifier.width(LabelColumn),
        )
        Row(
            // A group, so left and right walk these buttons and stop at the ends rather than
            // climbing into the quality chips above them.
            Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
        ) {
            SecondaryButton(
                text = if (state.isPlaying) PAUSE else RESUME,
                onClick = onTogglePlayPause,
                modifier = Modifier.focusRequester(focus),
                icon = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                compact = true,
            )
            SecondaryButton(
                text = TEN_SECONDS,
                onClick = { onSeekBy(-EpisodeQueue.SEEK_STEP_MS) },
                icon = Icons.Default.FastRewind,
                compact = true,
            )
            SecondaryButton(
                text = TEN_SECONDS,
                onClick = { onSeekBy(EpisodeQueue.SEEK_STEP_MS) },
                icon = Icons.Default.FastForward,
                compact = true,
            )
            SecondaryButton(text = SKIP_INTRO, onClick = onSkipIntro, compact = true)
            // Only where there is something aired to move on to.
            if (state.nextEpisodeAvailable) {
                SecondaryButton(
                    text = NEXT_EPISODE,
                    onClick = onNext,
                    icon = Icons.Default.SkipNext,
                    compact = true,
                )
            }
        }
    }
}

/**
 * The picture before the first frame: the title's own poster, its name, and the app's one shape
 * for «something is on its way».
 *
 * A television resolving a link takes seconds, and seconds of black with a spinner on it is
 * indistinguishable from a set that has lost its signal. The poster says which show is coming,
 * which is the only thing the viewer wants confirmed while they wait.
 */
@Composable
fun TvFirstFrame(state: PlayerUiState, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(KaeruBackground),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PosterImage(
            state.posterUrl,
            state.title,
            Modifier.width(KaeruTokens.PosterWidthTv).height(KaeruTokens.PosterWidthTv / KaeruTokens.PosterAspect),
        )
        Text(
            state.title,
            style = MaterialTheme.typography.headlineSmall,
            color = KaeruText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = KaeruTokens.Space4).widthIn(max = 720.dp),
        )
        state.episode.takeIf { it > 0 }?.let {
            Text(
                "$it серия",
                style = MaterialTheme.typography.titleSmall,
                color = KaeruSecondary,
                modifier = Modifier.padding(top = KaeruTokens.Space1),
            )
        }
        IndeterminateStrip(
            Modifier.padding(top = KaeruTokens.Space6).width(KaeruTokens.PosterWidthTv),
        )
    }
}

/** The mark the picture wears when it runs dry mid-episode, with a frame still behind it. */
@Composable
fun TvBufferingMark(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(BufferingDisc)
            .clip(CircleShape)
            .background(KaeruBackground.copy(alpha = 0.72f))
            .semantics { contentDescription = BUFFERING },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = KaeruAccent,
            trackColor = Color.Transparent,
            strokeWidth = 3.dp,
            modifier = Modifier.size(BufferingMark),
        )
    }
}

// --- previews ----------------------------------------------------------------------------------

internal val tvPlayerPreviewState = PlayerUiState(
    title = "Восхождение в тени",
    episode = 7,
    availableEpisodes = 10,
    translationTitle = "AniLibria",
    translationId = 1,
    isPlaying = true,
    isBuffering = false,
    positionMs = 12 * 60_000L + 4_000,
    bufferedPositionMs = 17 * 60_000L,
    durationMs = 24 * 60_000L + 31_000,
    quality = Quality.P1080,
    qualities = listOf(Quality.P480, Quality.P720, Quality.P1080),
    nextEpisodeAvailable = true,
    moreEpisodesComing = true,
    episodes = (1..10).map { previewCell(it, watched = it < 7, progress = if (it == 4) 0.42f else null) },
    translations = listOf(
        RankedTranslation(Translation(1, "AniLibria", TranslationKind.VOICE, 10), oftenChosen = false),
        RankedTranslation(Translation(2, "Studio Band", TranslationKind.VOICE, 10), oftenChosen = true),
        RankedTranslation(Translation(3, "Crunchyroll", TranslationKind.SUBTITLES, 10), oftenChosen = false),
    ),
)

/** Both zones at once, over the flat grey that stands in for a frame of video. */
@Preview(device = Devices.TV_1080p)
@Composable
private fun TvPlayerPanelPreview() {
    KaeruTvTheme {
        val requesters = remember { TvPanelRung.entries.associateWith { FocusRequester() } }
        Box(Modifier.fillMaxSize().background(Color(0xFF20242E))) {
            TvPlayerHeader(tvPlayerPreviewState.title, Modifier.align(Alignment.TopStart))
            TvPlayerPanel(
                state = tvPlayerPreviewState,
                rungFocus = { requesters.getValue(it) },
                onPickEpisode = {},
                onPickTranslation = {},
                onPickQuality = {},
                onTogglePlayPause = {},
                onSeekBy = {},
                onSkipIntro = {},
                onNext = {},
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

/** The panel a show with one track and no season list is left with: the timeline and the row. */
@Preview(device = Devices.TV_1080p)
@Composable
private fun TvPlayerPanelBarePreview() {
    KaeruTvTheme {
        val requesters = remember { TvPanelRung.entries.associateWith { FocusRequester() } }
        Box(Modifier.fillMaxSize().background(Color(0xFF20242E))) {
            TvPlayerPanel(
                state = tvPlayerPreviewState.copy(
                    episodes = emptyList(),
                    translations = emptyList(),
                    qualities = emptyList(),
                    isPlaying = false,
                    nextEpisodeAvailable = false,
                ),
                rungFocus = { requesters.getValue(it) },
                onPickEpisode = {},
                onPickTranslation = {},
                onPickQuality = {},
                onTogglePlayPause = {},
                onSeekBy = {},
                onSkipIntro = {},
                onNext = {},
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvFirstFramePreview() {
    KaeruTvTheme { TvFirstFrame(tvPlayerPreviewState.copy(isBuffering = true, durationMs = 0)) }
}
