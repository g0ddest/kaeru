package app.kaeru.ui.tv.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.player.EpisodeQueue
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.player.formatTime
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.tv.requestFocusOrLog

/** Controls sit on video, so their quiet state is alpha over whatever frame is underneath. */
private val OnVideo = Color.White
private val OnVideoMuted = Color(0xFFD6D9DE)
private val Quiet = Color.White.copy(alpha = 0.12f)
private val ControlShape = RoundedCornerShape(10.dp)

/** Wide enough for a remote, far enough from the bezel for a television that overscans. */
private val EdgePadding = 48.dp

/**
 * The controls that sit over the picture: who is speaking at the top, where the episode is and
 * what can be done to it at the bottom.
 *
 * The panel is one column of the vertical axis the D-pad walks — strip, timeline, buttons — so
 * it takes the timeline back whenever a chooser above it closes.
 */
@Composable
fun TvPlayerPanel(
    state: PlayerUiState,
    overlayOpen: Boolean,
    progressFocus: FocusRequester,
    actionsFocus: FocusRequester,
    onFocus: (TvPlayerFocus) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSkipIntro: () -> Unit,
    onEpisodes: () -> Unit,
    onQualities: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    strip: @Composable () -> Unit = {},
) {
    // The timeline is where the panel opens and where it returns once a chooser is done with.
    LaunchedEffect(overlayOpen) {
        if (!overlayOpen) progressFocus.requestFocusOrLog("шкалу времени плеера")
    }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().height(180.dp)
                .background(Brush.verticalGradient(listOf(KaeruBackground.copy(alpha = 0.86f), Color.Transparent))),
        )
        TvPlayerHeader(state, Modifier.align(Alignment.TopStart))

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().height(120.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, KaeruBackground.copy(alpha = 0.92f)))),
            )
            // The rung above the timeline: a chooser, a countdown, or nothing at all.
            strip()
            Column(
                Modifier.fillMaxWidth()
                    .background(KaeruBackground.copy(alpha = 0.92f))
                    .padding(start = EdgePadding, end = EdgePadding, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                TvTimeline(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    focusRequester = progressFocus,
                    onFocused = { onFocus(TvPlayerFocus.PROGRESS) },
                )
                TvActionRow(
                    state = state,
                    actionsFocus = actionsFocus,
                    onFocused = { onFocus(TvPlayerFocus.ACTIONS) },
                    onTogglePlayPause = onTogglePlayPause,
                    onSeekBy = onSeekBy,
                    onSkipIntro = onSkipIntro,
                    onEpisodes = onEpisodes,
                    onQualities = onQualities,
                    onNext = onNext,
                )
            }
        }
    }
}

@Composable
private fun TvPlayerHeader(state: PlayerUiState, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = EdgePadding, top = 40.dp, end = EdgePadding).widthIn(max = 900.dp)) {
        Text(
            state.title,
            style = MaterialTheme.typography.headlineMedium,
            color = OnVideo,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val line = listOfNotNull(
            state.episode.takeIf { it > 0 }?.let { "$it серия" },
            state.translationTitle,
            state.quality?.let { "${it.height}p" },
        ).joinToString("    ")
        if (line.isNotEmpty()) {
            Text(
                line,
                style = MaterialTheme.typography.titleSmall,
                color = OnVideoMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Where the episode is. Not clickable — a remote has no pointer — but focusable, because left
 * and right mean "scrub" only while this is the thing the viewer is pointed at.
 */
@Composable
private fun TvTimeline(
    positionMs: Long,
    durationMs: Long,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val barHeight = if (focused) 10.dp else 6.dp
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (focused) Modifier.border(3.dp, KaeruAccent, RoundedCornerShape(14.dp)) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable(),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatTime(positionMs),
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) KaeruAccent else OnVideo,
            )
            Spacer(Modifier.weight(1f))
            Text(formatTime(durationMs), style = MaterialTheme.typography.titleMedium, color = OnVideoMuted)
        }
        Box(
            Modifier.padding(top = 10.dp).fillMaxWidth().height(barHeight)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.2f)),
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(KaeruAccent))
        }
    }
}

@Composable
private fun TvActionRow(
    state: PlayerUiState,
    actionsFocus: FocusRequester,
    onFocused: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSkipIntro: () -> Unit,
    onEpisodes: () -> Unit,
    onQualities: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvControl(
            label = if (state.isPlaying) "Пауза" else "Продолжить",
            icon = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            onClick = onTogglePlayPause,
            onFocused = onFocused,
            focusRequester = actionsFocus,
        )
        TvControl(
            label = "−10",
            icon = Icons.Default.Replay10,
            onClick = { onSeekBy(-EpisodeQueue.SEEK_STEP_MS) },
            onFocused = onFocused,
        )
        TvControl(
            label = "+10",
            icon = Icons.Default.Forward10,
            onClick = { onSeekBy(EpisodeQueue.SEEK_STEP_MS) },
            onFocused = onFocused,
        )
        TvControl(label = "+85 с", onClick = onSkipIntro, onFocused = onFocused)
        TvControl(label = "Серии и озвучка", onClick = onEpisodes, onFocused = onFocused)
        TvControl(label = "Качество", onClick = onQualities, onFocused = onFocused)
        if (state.nextEpisodeAvailable) {
            TvControl(
                label = "Следующая серия",
                icon = Icons.Default.SkipNext,
                onClick = onNext,
                onFocused = onFocused,
            )
        }
    }
}

// --- the strips ----------------------------------------------------------------------------

/**
 * Episodes and voices, above the timeline: the two choices that change what is playing rather
 * than how it looks.
 */
@Composable
fun TvEpisodeStrip(
    state: PlayerUiState,
    onEpisode: (Int) -> Unit,
    onTranslation: (Translation) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.availableEpisodes == 0 && state.translations.isEmpty()) {
        TvStripMessage("Список серий пока недоступен", modifier)
        return
    }
    StripPanel(modifier) {
        // A show whose episode count has not arrived yet still gets its voices.
        if (state.availableEpisodes > 0) {
            TvChipRow(
                label = "Серии",
                items = (1..state.availableEpisodes).toList(),
                caption = { it.toString() },
                isCurrent = { it == state.episode },
                claimsFocus = true,
                onPick = onEpisode,
            )
        }
        if (state.translations.isNotEmpty()) {
            TvChipRow(
                label = "Озвучка",
                items = state.translations,
                caption = ::translationLabel,
                isCurrent = { it.id == state.translationId },
                claimsFocus = state.availableEpisodes == 0,
                onPick = onTranslation,
            )
        }
    }
}

/** The quality ladder, one rung a chip, tallest first is not the order — the source's is. */
@Composable
fun TvQualityStrip(
    state: PlayerUiState,
    onQuality: (Quality) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.qualities.isEmpty()) {
        TvStripMessage("Качество пока недоступно", modifier)
        return
    }
    StripPanel(modifier) {
        TvChipRow(
            label = "Качество",
            items = state.qualities,
            caption = { "${it.height}p" },
            isCurrent = { it == state.quality },
            claimsFocus = true,
            onPick = onQuality,
        )
    }
}

/** The strip's slot while there is nothing yet to choose from. */
@Composable
fun TvStripMessage(text: String, modifier: Modifier = Modifier) {
    StripPanel(modifier) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = OnVideoMuted)
    }
}

private fun translationLabel(track: Translation): String =
    if (track.type == TranslationKind.SUBTITLES) "${track.title} (субтитры)" else track.title

@Composable
private fun StripPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(KaeruBackground.copy(alpha = 0.94f))
            .padding(start = EdgePadding, end = EdgePadding, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = { content() },
    )
}

@Composable
private fun <T> TvChipRow(
    label: String,
    items: List<T>,
    caption: (T) -> String,
    isCurrent: (T) -> Boolean,
    claimsFocus: Boolean,
    onPick: (T) -> Unit,
) {
    val currentIndex = items.indexOfFirst(isCurrent)
    val listState = rememberLazyListState()
    // Opening a strip on episode forty must not start the viewer at episode one.
    LaunchedEffect(Unit) { listState.scrollToItem((currentIndex - 2).coerceAtLeast(0)) }
    val claimed = remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = OnVideoMuted)
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(items) { index, item ->
                // The chip claims focus itself: asking from the row can run before a chip far
                // down the list has been composed, and that request then fails for good.
                val focusRequester = remember { FocusRequester() }
                val wanted = claimsFocus && index == currentIndex.coerceAtLeast(0)
                LaunchedEffect(wanted) {
                    if (wanted && !claimed.value) {
                        claimed.value = true
                        focusRequester.requestFocusOrLog("выбранный элемент полосы «$label»")
                    }
                }
                TvControl(
                    label = caption(item),
                    onClick = { onPick(item) },
                    selected = isCurrent(item),
                    focusRequester = focusRequester,
                )
            }
        }
    }
}

// --- decisions -------------------------------------------------------------------------------

/** The offer to move on, with the time left to say no draining under the line. */
@Composable
fun TvAutoplayCard(
    episode: Int,
    countdownSec: Int,
    onNow: () -> Unit,
    onCancel: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { nowButton.requestFocusOrLog("кнопку «Смотреть сейчас»") }
    val drain by animateFloatAsState(
        targetValue = countdownSec.toFloat() / EpisodeQueue.AUTOPLAY_COUNTDOWN_SEC,
        label = "tvAutoplay",
    )
    Column(
        modifier
            .width(420.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(KaeruBackground.copy(alpha = 0.96f))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Следующая серия через $countdownSec", style = MaterialTheme.typography.titleLarge, color = OnVideo)
        Text("$episode серия", style = MaterialTheme.typography.titleSmall, color = OnVideoMuted)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxWidth(drain.coerceIn(0f, 1f)).fillMaxHeight().background(KaeruAccent))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvControl(
                label = "Смотреть сейчас",
                onClick = onNow,
                primary = true,
                focusRequester = nowButton,
                onFocused = onFocused,
            )
            TvControl(label = "Отмена", onClick = onCancel, onFocused = onFocused)
        }
    }
}

/** The finale is behind the viewer; the list is not going to update itself. */
@Composable
fun TvCompletedDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val yes = remember { FocusRequester() }
    LaunchedEffect(Unit) { yes.requestFocusOrLog("кнопку «Да»") }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(16.dp))
                .background(KaeruBackground).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Перевести «$title» в завершённые?",
                style = MaterialTheme.typography.headlineSmall,
                color = OnVideo,
                textAlign = TextAlign.Center,
            )
            Text("Серия была последней из вышедших.", style = MaterialTheme.typography.titleSmall, color = OnVideoMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvControl(label = "Да", onClick = onConfirm, primary = true, focusRequester = yes)
                TvControl(label = "Позже", onClick = onDismiss)
            }
        }
    }
}

/** Nothing is playing and nothing will until the viewer chooses one of two ways forward. */
@Composable
fun TvPlaybackFailure(message: String, onRetry: () -> Unit, onChangeTranslation: () -> Unit) {
    val retry = remember { FocusRequester() }
    LaunchedEffect(Unit) { retry.requestFocusOrLog("кнопку «Повторить»") }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 720.dp).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.headlineSmall,
                color = OnVideo,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvControl(label = "Повторить", onClick = onRetry, primary = true, focusRequester = retry)
                TvControl(label = "Сменить озвучку", onClick = onChangeTranslation)
            }
        }
    }
}

/** One line, said once, that needs no answer. */
@Composable
fun TvPlayerToast(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(KaeruBackground.copy(alpha = 0.94f))
            .padding(horizontal = 22.dp, vertical = 14.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleSmall, color = OnVideo)
    }
}

// --- the one control everything here is built from -------------------------------------------

/**
 * Every focusable thing in the player. Focus is a filled amber pill at 1.06 with a three-pixel
 * ring — the same language the home cards speak, loud enough to find from across a room.
 */
@Composable
private fun TvControl(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tvControlScale")
    val container = when {
        primary -> KaeruAccent
        selected -> KaeruAccent.copy(alpha = 0.24f)
        else -> Quiet
    }
    val content = when {
        primary -> Color.Black
        selected -> KaeruAccent
        else -> OnVideo
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .scale(scale)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .then(if (focused) Modifier.border(3.dp, KaeruAccent, ControlShape) else Modifier),
        // The scale is ours, animated alongside the ring; the component's own would fight it.
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape = ControlShape),
        colors = ButtonDefaults.colors(
            containerColor = container,
            contentColor = content,
            focusedContainerColor = KaeruAccent,
            focusedContentColor = Color.Black,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

/** The spinner the picture shows before the first frame, and whenever it runs dry. */
@Composable
fun TvBufferingMark(modifier: Modifier = Modifier) {
    Box(
        modifier.size(76.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.42f)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            color = KaeruAccent,
            strokeWidth = 3.dp,
            modifier = Modifier.size(40.dp),
        )
    }
}

// --- previews --------------------------------------------------------------------------------

private val previewState = PlayerUiState(
    title = "Восхождение в тени",
    episode = 7,
    availableEpisodes = 12,
    translationTitle = "AniLibria",
    isPlaying = true,
    isBuffering = false,
    positionMs = 12 * 60_000L + 4_000,
    durationMs = 24 * 60_000L + 31_000,
    quality = Quality.P1080,
    qualities = listOf(Quality.P480, Quality.P720, Quality.P1080),
    nextEpisodeAvailable = true,
    translations = listOf(
        Translation(1, "AniLibria", TranslationKind.VOICE, 12),
        Translation(2, "Studio Band", TranslationKind.VOICE, 12),
        Translation(3, "Crunchyroll", TranslationKind.SUBTITLES, 12),
    ),
)

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvPlayerPanelPreview() {
    KaeruTvTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF20242E))) {
            TvPlayerPanel(
                state = previewState,
                overlayOpen = false,
                progressFocus = remember { FocusRequester() },
                actionsFocus = remember { FocusRequester() },
                onFocus = {},
                onTogglePlayPause = {},
                onSeekBy = {},
                onSkipIntro = {},
                onEpisodes = {},
                onQualities = {},
                onNext = {},
            )
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvEpisodeStripPreview() {
    KaeruTvTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF20242E)), contentAlignment = Alignment.BottomStart) {
            TvEpisodeStrip(previewState.copy(translationId = 1), onEpisode = {}, onTranslation = {})
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvAutoplayCardPreview() {
    KaeruTvTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF20242E)), contentAlignment = Alignment.Center) {
            TvAutoplayCard(episode = 8, countdownSec = 6, onNow = {}, onCancel = {}, onFocused = {})
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvPlaybackFailurePreview() {
    KaeruTvTheme {
        TvPlaybackFailure("Источник временно недоступен", onRetry = {}, onChangeTranslation = {})
    }
}
