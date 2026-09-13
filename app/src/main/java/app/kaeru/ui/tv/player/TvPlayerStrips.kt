package app.kaeru.ui.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.design.OFTEN_CHOSEN
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.tv.requestFocusOrLog

/**
 * The choosers that ride the rung above the timeline: which episode and whose voice, and how
 * good the picture is. Both are rows of chips, because a remote is good at rows.
 */

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
                isCurrent = { it.translation.id == state.translationId },
                claimsFocus = state.availableEpisodes == 0,
                onPick = { onTranslation(it.translation) },
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

/**
 * What a voice chip says. A chip is a single line with nothing under it, so everything the row has
 * to say about a track goes in the label: what it is called, whether it is read rather than heard,
 * and whether this viewer keeps choosing it.
 *
 * The last of those joins as a phrase after a comma rather than behind a separator — «AniLibria,
 * часто выбираете» can be read out; «AniLibria · часто» is a meta string.
 */
internal fun translationLabel(ranked: RankedTranslation): String {
    val track = ranked.translation
    val name = if (track.type == TranslationKind.SUBTITLES) "${track.title} (субтитры)" else track.title
    return if (ranked.oftenChosen) "$name, $oftenChosenClause" else name
}

/** The phone's chip phrase, lower-cased because here it finishes the sentence the studio name starts. */
private val oftenChosenClause = OFTEN_CHOSEN.replaceFirstChar { it.lowercase() }

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

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvEpisodeStripPreview() {
    KaeruTvTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF20242E)), contentAlignment = Alignment.BottomStart) {
            TvEpisodeStrip(previewState.copy(translationId = 1), onEpisode = {}, onTranslation = {})
        }
    }
}
