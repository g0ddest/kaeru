package app.kaeru.ui.common.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTvTheme

private const val DARK = 0xFF0B0C10

private const val FRIEREN = "Фрирен, провожающая в последний путь"
private const val DEMON_SLAYER = "Клинок, рассекающий демонов"
private const val JUJUTSU = "Магическая битва"
private const val DANDADAN = "Дандадан"
private const val SHADOW = "Восхождение в тени"
private const val ONE_PUNCH = "Ванпанчмен"

@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvPosterCardPreview() = KaeruTvTheme {
    Column(Modifier.fillMaxSize().padding(vertical = 48.dp)) {
        RowHeader("Продолжить", gutter = KaeruTokens.GutterTv, action = RowAction("Всё") {})
        Row(
            Modifier.padding(start = KaeruTokens.GutterTv, top = KaeruTokens.Space4),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
        ) {
            TvPosterCard(null, JUJUTSU, {}, onLongClick = {}, badge = "8 серия", progress = 0.42f, subtitle = "7 из 12")
            TvPosterCard(null, DEMON_SLAYER, {}, onLongClick = {}, badge = "3 серия")
            TvPosterCard(null, FRIEREN, {}, subtitle = "28 из 28")
            TvPosterCard(null, DANDADAN, {}, badge = "12 серия", progress = 0.9f)
            TvPosterCard(null, SHADOW, {}, subtitle = "2026")
            TvPosterCard(null, ONE_PUNCH, {})
        }
    }
}

/**
 * The immersive shape of the television home screen: artwork behind, the scrim carrying the text
 * off the left edge, the row of cards below it.
 */
@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvImmersivePreview() = KaeruTvTheme {
    Box(Modifier.fillMaxSize()) {
        Backdrop(null, Modifier.fillMaxSize(), scrimBottom = true, scrimStart = true)
        Column(Modifier.align(Alignment.TopStart).padding(KaeruTokens.GutterTv).fillMaxWidth(0.5f)) {
            Text(FRIEREN, style = MaterialTheme.typography.displaySmall, color = KaeruText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "7 серия, осталось 14 мин",
                style = MaterialTheme.typography.bodyMedium,
                color = KaeruSecondary,
                modifier = Modifier.padding(top = KaeruTokens.Space3),
            )
            Row(
                Modifier.padding(top = KaeruTokens.Space4),
                horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
            ) {
                PrimaryButton("Продолжить с 14:20", {}, icon = Icons.Default.PlayArrow)
                SecondaryButton("Подробнее", {})
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = KaeruTokens.GutterTv, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
        ) {
            TvPosterCard(null, JUJUTSU, {}, badge = "8 серия", progress = 0.42f)
            TvPosterCard(null, DEMON_SLAYER, {}, badge = "3 серия")
            TvPosterCard(null, DANDADAN, {})
        }
    }
}

@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvControlsPreview() = KaeruTvTheme {
    Column(
        Modifier.fillMaxSize().padding(KaeruTokens.GutterTv),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space6),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
            PrimaryButton("Смотреть 1 серию", {}, icon = Icons.Default.PlayArrow)
            SecondaryButton("Подробнее", {})
            IconAction(Icons.Default.Search, "Поиск", {})
            IconAction(Icons.Default.Settings, "Настройки", {})
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
            MetaChip("2024")
            MetaChip("28 серий")
            MetaChip("9,1")
            MetaChip("Madhouse")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
            StatusPill("Смотрю", selected = true, onClick = {})
            StatusPill("В планах", selected = false, onClick = {})
        }
        ProgressStrip(0.42f, Modifier.fillMaxWidth(0.4f))
    }
}

/**
 * The focus treatment on the controls a television shares with the phone. A static preview cannot
 * move focus, so every control here is forced into its focused state at once; on a real screen
 * exactly one of them is.
 */
@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvFocusedControlsPreview() = KaeruTvTheme {
    CompositionLocalProvider(LocalFocusPreview provides true) {
        Column(
            Modifier.fillMaxSize().padding(KaeruTokens.GutterTv),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space8),
        ) {
            RowHeader("Продолжить", gutter = 0.dp, action = RowAction("Всё") {})
            Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space6)) {
                PrimaryButton("Смотреть 1 серию", {}, icon = Icons.Default.PlayArrow)
                SecondaryButton("Подробнее", {})
                IconAction(Icons.Default.Search, "Поиск", {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space6)) {
                StatusPill("Смотрю", selected = true, onClick = {})
                StatusPill("В планах", selected = false, onClick = {})
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvEmptyStatePreview() = KaeruTvTheme {
    EmptyState(
        title = "Здесь появятся тайтлы из списка «Смотрю»",
        text = "Добавьте аниме на Shikimori, и оно окажется здесь.",
        actionLabel = "Найти аниме",
        onAction = {},
        modifier = Modifier.fillMaxSize(),
    )
}

@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvErrorStatePreview() = KaeruTvTheme {
    ErrorState(
        message = "Нет соединения. Проверьте интернет",
        onRetry = {},
        secondaryLabel = "Сменить озвучку",
        onSecondary = {},
        modifier = Modifier.fillMaxSize(),
    )
}

/** At television type the sentence is wider, and the strip under it grows to match on its own. */
@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvSyncingNoticePreview() = KaeruTvTheme {
    Column(Modifier.fillMaxSize().padding(KaeruTokens.GutterTv)) {
        SyncingNotice(stripWidth = KaeruTokens.PosterWidthTv)
    }
}

@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvSkeletonPreview() = KaeruTvTheme {
    Column(Modifier.fillMaxSize()) {
        SkeletonHero(aspect = 16f / 5f)
        SkeletonRow(
            modifier = Modifier.padding(top = KaeruTokens.Space6),
            count = 4,
            gutter = KaeruTokens.GutterTv,
            posterWidth = KaeruTokens.PosterWidthTv,
        )
    }
}
