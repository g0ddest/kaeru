package app.kaeru.ui.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.theme.KaeruTheme

private const val DARK = 0xFF0B0C10

// Landscape, because that is the only way this screen is ever seen. The numbers are a real
// episode: twenty-four minutes, two thirds watched, one rung below the best the source offers.
private const val PHONE_WIDTH = 780
private const val PHONE_HEIGHT = 360

private const val FRIEREN = "Фрирен, провожающая в последний путь"

private val anilibria = Translation(11, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 28)
private val studioBanda = Translation(22, "Студийная банда", TranslationKind.VOICE, episodesCount = 28)
private val subtitles = Translation(33, "Субтитры", TranslationKind.SUBTITLES, episodesCount = 28)

private val casting = PlayerUiState(
    title = FRIEREN,
    posterUrl = null,
    episode = 7,
    availableEpisodes = 12,
    translationTitle = anilibria.title,
    translationId = anilibria.id,
    isPlaying = true,
    isBuffering = false,
    positionMs = 940_000,
    bufferedPositionMs = 1_120_000,
    durationMs = 1_440_000,
    quality = Quality.P720,
    qualities = listOf(Quality.P480, Quality.P720, Quality.P1080),
    translations = listOf(anilibria, studioBanda, subtitles).map { RankedTranslation(it, oftenChosen = false) },
    nextEpisodeAvailable = true,
    isCasting = true,
    receiverName = "Гостиная ТВ",
    episodes = (1..12).map { number ->
        EpisodeCell(
            number = number,
            watched = number < 7,
            progress = 0.65f.takeIf { number == 7 },
            aired = number <= 9,
        )
    },
)

@Preview(name = "Верх и низ плеера", showBackground = true, backgroundColor = DARK, widthDp = PHONE_WIDTH, heightDp = 220)
@Composable
private fun PlayerBarsPreview() = KaeruTheme {
    Column(Modifier.fillMaxWidth().background(Color.Black), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        PlayerTopBar(
            title = FRIEREN,
            episode = 7,
            translationTitle = anilibria.title,
            qualityLabel = "720p",
            onBack = {},
            onTranslations = {},
            onQualities = {},
            onEnterPictureInPicture = {},
        )
        PlayerBottomBar(
            positionMs = 940_000,
            bufferedPositionMs = 1_120_000,
            durationMs = 1_440_000,
            showNext = true,
            onSeekTo = {},
            onSeekBy = {},
            onSkipIntro = {},
            onNext = {},
        )
    }
}

@Preview(name = "Конец серии", showBackground = true, backgroundColor = DARK, widthDp = 340, heightDp = 340)
@Composable
private fun EndOfEpisodePreview() = KaeruTheme {
    Column(
        Modifier.background(Color.Black).padding(KaeruTokens.Space4),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        NextEpisodeCard(episode = 8, countdownSec = 6, onNow = {}, onCancel = {})
        LastEpisodeCard(waiting = "10 серия выйдет завтра")
    }
}

@Preview(name = "Яркость и громкость", showBackground = true, backgroundColor = DARK, widthDp = 340, heightDp = 260)
@Composable
private fun SwipeIndicatorPreview() = KaeruTheme {
    Row(
        Modifier.background(Color.Black).padding(KaeruTokens.Space4),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space8),
    ) {
        SwipeIndicator(PlayerSide.LEFT, level = 0.25f)
        SwipeIndicator(PlayerSide.RIGHT, level = 0.8f)
    }
}

@Preview(name = "Пульт при касте", showBackground = true, backgroundColor = DARK, widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
@Composable
private fun RemoteControlPreview() = KaeruTheme {
    Box(Modifier.fillMaxSize()) {
        RemoteControlScreen(
            state = casting,
            onBack = {},
            onTogglePlayPause = {},
            onSeekTo = {},
            onSeekBy = {},
            onNext = {},
            onCancelAutoplay = {},
            onOpenTranslations = {},
            onOpenQualities = {},
            onPickEpisode = {},
            onRetry = {},
            onStopCasting = {},
        )
    }
}
