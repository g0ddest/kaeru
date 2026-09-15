package app.kaeru.ui.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.LocalFocusPreview
import app.kaeru.ui.common.design.OFTEN_CHOSEN
import app.kaeru.ui.common.design.ProgressStrip
import app.kaeru.ui.common.design.kaeruFocus
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTvTheme

/**
 * The three rows of choices in the panel: which episode, whose voice, how good the picture.
 *
 * Every row is built the same way — a name hanging in the margin, and a row of chips the D-pad
 * walks — so the panel reads as one column of choices with a single left edge, and a viewer who
 * has learned one row has learned all three.
 */

private const val EPISODES = "Серии"
private const val TRACKS = "Озвучка"
private const val QUALITIES = "Качество"
private const val LOADING_TRACKS = "Загружаем озвучки…"
private const val CHOSEN = "Выбрано"
private const val PLAYING_NOW = "Сейчас"
private const val WATCHED = "Просмотрено"
private const val SUBTITLES = "субтитры"

/**
 * Where the row's name hangs. Wide enough for «Качество» at the television type scale, and the
 * one measurement that puts every chip in the panel on the same left edge.
 */
internal val LabelColumn = 132.dp

/** Every chip is the same height, so three rows of different things still read as one column. */
private val ChipHeight = 58.dp

/**
 * The margin a strip keeps around its chips, for the six per cent a focused one grows by.
 *
 * Named because two places have to agree on it: the loaded row carries it as content padding, and
 * the row's loading slot carries it as a margin. A strip that used it in one state and not the
 * other changed the height of the whole panel when the list arrived.
 */
private val StripInset = KaeruTokens.Space1

/** Room for a three-digit episode number; a studio name takes as much as it needs up to a cap. */
private val ChipMinWidth = 72.dp
private val ChipMaxWidth = 300.dp

private val WatchedMark = 18.dp

/**
 * The season, aired episodes only, opened on the one that is playing.
 *
 * [episodes] is already filtered; the strip takes the fields it draws rather than the whole
 * player state, so a position tick — four of them a second — leaves its arguments untouched and
 * the row, with every chip in it, skips.
 */
@Composable
fun TvEpisodeStrip(
    episodes: List<EpisodeCell>,
    episode: Int,
    focus: FocusRequester,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    TvStripRow(
        label = EPISODES,
        items = episodes,
        key = { it.number },
        current = { it.number == episode },
        focus = focus,
        modifier = modifier,
    ) { cell, chipModifier ->
        TvStripChip(
            label = cell.number.toString(),
            onClick = { onPick(cell.number) },
            modifier = chipModifier,
            caption = PLAYING_NOW.takeIf { cell.number == episode },
            selected = cell.number == episode,
            progress = cell.progress,
            watched = cell.watched,
        )
    }
}

/**
 * The voices the source offers, in the order the view model ranked them: the remembered track
 * first, then the studios this viewer keeps coming back to.
 */
@Composable
fun TvTranslationStrip(
    translations: List<RankedTranslation>,
    translationId: Int?,
    loading: Boolean,
    focus: FocusRequester,
    onPick: (Translation) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (translations.isEmpty()) {
        // A chip's worth of «on its way», so the row keeps its place in the column and the
        // viewer who pressed «Сменить озвучку» can see that something is happening. Not
        // focusable: there is nothing to choose yet, and the D-pad arrives when there is.
        if (loading) TvStripLoading(TRACKS, LOADING_TRACKS, modifier)
        return
    }
    TvStripRow(
        label = TRACKS,
        items = translations,
        key = { it.translation.id },
        current = { it.translation.id == translationId },
        focus = focus,
        modifier = modifier,
    ) { ranked, chipModifier ->
        TvStripChip(
            label = translationName(ranked),
            onClick = { onPick(ranked.translation) },
            modifier = chipModifier,
            caption = translationCaption(ranked, translationId),
            selected = ranked.translation.id == translationId,
        )
    }
}

/** The quality ladder, in the order the source lists it rather than tallest first. */
@Composable
fun TvQualityStrip(
    qualities: List<Quality>,
    quality: Quality?,
    focus: FocusRequester,
    onPick: (Quality) -> Unit,
    modifier: Modifier = Modifier,
) {
    TvStripRow(
        label = QUALITIES,
        items = qualities,
        key = { it.height },
        current = { it == quality },
        focus = focus,
        modifier = modifier,
    ) { rung, chipModifier ->
        TvStripChip(
            label = "${rung.height}p",
            onClick = { onPick(rung) },
            modifier = chipModifier,
            caption = CHOSEN.takeIf { rung == quality },
            selected = rung == quality,
        )
    }
}

/**
 * What a track's chip is called: the studio, and whether it is read rather than heard.
 *
 * The kind is part of the name rather than a caption because it is not a recommendation — a
 * viewer choosing between a dub and a subtitle track is choosing between two different things,
 * and the caption line is spoken for by what the app has to say about them.
 */
internal fun translationName(ranked: RankedTranslation): String {
    val track = ranked.translation
    return if (track.type == TranslationKind.SUBTITLES) "${track.title} ($SUBTITLES)" else track.title
}

/**
 * The line under a track's name, or null when there is nothing to add.
 *
 * The track in play says only that. Two marks on one chip say less than one — the same argument
 * the ranking itself makes when it stops calling a remembered track often-chosen.
 */
internal fun translationCaption(ranked: RankedTranslation, currentId: Int?): String? = when {
    ranked.translation.id == currentId -> CHOSEN
    ranked.oftenChosen -> OFTEN_CHOSEN
    else -> null
}

/**
 * One row of the panel: a name in the margin and a row of chips beside it.
 *
 * The row keeps an anchor — the chip the D-pad was last standing on — and hands [focus] to it,
 * so coming back to a row lands where the viewer left rather than throwing them back to the
 * episode that happens to be playing. Before anything has been chosen the anchor is that
 * episode, which is where a strip opened over a running show should start.
 */
@Composable
private fun <T : Any> TvStripRow(
    label: String,
    items: List<T>,
    key: (T) -> Any,
    current: (T) -> Boolean,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
    chip: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    if (items.isEmpty()) return
    // One value for both, so the anchor and the scroll cannot disagree, and neither of them can
    // be moved by a progress sample: opening a strip on episode forty must not start the viewer
    // at episode one, and neither must the fortieth second of episode forty.
    val placement = tvStripPlacement(items, key, current)
    var anchor by remember(placement) { mutableStateOf(placement.current) }
    val listState = rememberLazyListState()
    LaunchedEffect(placement) { listState.scrollToItem(placement.firstVisible) }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TvStripLabel(label)
        LazyRow(
            state = listState,
            // A group, so left and right at the end of a row stop there rather than jumping the
            // D-pad into whichever row happens to be nearest above or below.
            modifier = Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
            // The six per cent a focused chip grows by, which the row would otherwise clip.
            contentPadding = PaddingValues(StripInset),
        ) {
            items(items, key = key) { item ->
                chip(
                    item,
                    Modifier
                        .then(if (key(item) == anchor) Modifier.focusRequester(focus) else Modifier)
                        .onFocusChanged { if (it.isFocused) anchor = key(item) },
                )
            }
        }
    }
}

/**
 * The row's slot while the list is still on its way: a chip-shaped block that holds the row's
 * place in the column, so the panel does not change height under the viewer when it arrives.
 *
 * [StripInset] is why this reads as a chip-shaped block with a margin rather than just a chip. A
 * loaded row is a `LazyRow` carrying that inset as content padding, for the six per cent a focused
 * chip grows by; without the same inset here the panel stood eight device-independent pixels
 * shorter while the voices were loading and grew under the viewer when they landed.
 */
@Composable
private fun TvStripLoading(label: String, text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TvStripLabel(label)
        Box(
            Modifier
                .padding(StripInset)
                .height(ChipHeight)
                .clip(KaeruTokens.ButtonShape)
                .background(KaeruElevated)
                .padding(horizontal = KaeruTokens.Space4),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.titleSmall, color = KaeruSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun TvStripLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = KaeruSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(LabelColumn),
    )
}

/**
 * One choice in a strip.
 *
 * Three facts fit on it and each has its own place: the name in the middle, what the app has to
 * say about it on a second line under that, and — for an episode — how far into it the viewer
 * got, as the same 4dp of amber every poster card in the app draws. The tick in the corner is
 * the title screen's, so an episode behind the viewer looks the same in both places.
 *
 * [selected] is the thing in play, not the thing under the D-pad: focus is the amber ring, and
 * the two have to stay distinguishable while the viewer walks past what they are watching.
 */
@Composable
internal fun TvStripChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    selected: Boolean = false,
    progress: Float? = null,
    watched: Boolean = false,
) {
    Box(
        modifier
            .height(ChipHeight)
            .widthIn(min = ChipMinWidth, max = ChipMaxWidth)
            .kaeruFocus(KaeruTokens.ButtonShape)
            .clip(KaeruTokens.ButtonShape)
            .background(if (selected) KaeruAccent.copy(alpha = 0.22f) else KaeruElevated)
            .selectable(selected = selected, role = Role.Button, onClick = onClick),
    ) {
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = KaeruTokens.Space4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) KaeruAccent else KaeruText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = KaeruSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (watched) {
            Icon(
                Icons.Default.Check,
                contentDescription = WATCHED,
                tint = KaeruAccent,
                modifier = Modifier.align(Alignment.TopEnd).padding(KaeruTokens.Space1).size(WatchedMark),
            )
        }
        if (progress != null) {
            ProgressStrip(progress, Modifier.align(Alignment.BottomStart))
        }
    }
}

/** One chip's worth of episode, for the previews and for anything that has to size the row. */
internal fun previewCell(number: Int, watched: Boolean = false, progress: Float? = null) =
    EpisodeCell(number = number, watched = watched, progress = progress, aired = true)

// --- previews ----------------------------------------------------------------------------------

/**
 * Every signal one chip can carry, side by side, and the focus ring beside them.
 *
 * The four have to stay distinguishable from three metres: behind the viewer is a tick, part-way
 * through is a strip of amber along the bottom edge, in play is an amber wash and a caption, and
 * under the D-pad is the ring. The last one is forced on here — a static preview cannot move
 * focus, and the ring is the thing a preview of a television control is for.
 */
@Preview(device = Devices.TV_1080p, backgroundColor = 0xFF0B0C10, showBackground = true)
@Composable
private fun TvStripChipPreview() {
    KaeruTvTheme {
        Row(
            Modifier.padding(KaeruTokens.GutterTv),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvStripChip(label = "12", onClick = {})
            TvStripChip(label = "3", onClick = {}, watched = true, progress = 1f)
            TvStripChip(label = "4", onClick = {}, progress = 0.42f)
            TvStripChip(label = "7", onClick = {}, caption = PLAYING_NOW, selected = true, progress = 0.5f)
            TvStripChip(label = "AniLibria", onClick = {}, caption = CHOSEN, selected = true)
            TvStripChip(label = "Studio Band", onClick = {}, caption = OFTEN_CHOSEN)
            CompositionLocalProvider(LocalFocusPreview provides true) {
                TvStripChip(label = "1080p", onClick = {})
            }
        }
    }
}
