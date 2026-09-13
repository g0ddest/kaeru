package app.kaeru.ui.common.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTheme

private const val DARK = 0xFF0B0C10

// The previews use the shows the app is actually for, with the numbers those shows actually have,
// so a line that will wrap in Russian wraps here too.
private const val FRIEREN = "Фрирен, провожающая в последний путь"
private const val DEMON_SLAYER = "Клинок, рассекающий демонов"
private const val JUJUTSU = "Магическая битва"
private const val DANDADAN = "Дандадан"
private const val SHADOW = "Восхождение в тени"

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 120)
@Composable
private fun TopBarPreview() = KaeruTheme {
    KaeruTopBar(
        title = "Мой список",
        navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Назад", {}) },
        actions = { IconAction(Icons.Default.Search, "Поиск", {}) },
    )
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 220)
@Composable
private fun TopBarTransparentPreview() = KaeruTheme {
    Box(Modifier.fillMaxWidth().height(220.dp)) {
        Backdrop(null, Modifier.fillMaxSize())
        KaeruTopBar(
            title = "Kaeru",
            transparent = true,
            actions = {
                IconAction(Icons.Default.Cast, "Транслировать на телевизор", {}, overArtwork = true)
                IconAction(Icons.Default.Settings, "Настройки", {}, overArtwork = true)
            },
        )
    }
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 260)
@Composable
private fun BackdropPreview() = KaeruTheme {
    Backdrop(null, Modifier.fillMaxWidth().height(260.dp), scrimBottom = true, scrimStart = true)
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 560)
@Composable
private fun HeroBannerPreview() = KaeruTheme {
    HeroBanner(
        title = FRIEREN,
        statusLine = "7 серия, осталось 14 мин",
        backdropUrl = null,
        primaryLabel = "Продолжить с 14:20",
        onPrimary = {},
        secondaryLabel = "Подробнее",
        onSecondary = {},
    )
}

/**
 * The narrow phone, where «Продолжить с 14:20» and «Подробнее» together are wider than the screen.
 * Neither label is allowed to truncate, so the pair wraps.
 */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 560)
@Composable
private fun HeroBannerNarrowPreview() = KaeruTheme {
    HeroBanner(
        title = DEMON_SLAYER,
        statusLine = "7 серия, осталось 14 мин",
        backdropUrl = null,
        primaryLabel = "Продолжить с 14:20",
        onPrimary = {},
        secondaryLabel = "Подробнее",
        onSecondary = {},
    )
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 300)
@Composable
private fun PosterCardPreview() = KaeruTheme {
    Column {
        RowHeader("Новые серии", action = RowAction("Всё") {})
        Row(
            Modifier.padding(start = KaeruTokens.GutterPhone, top = KaeruTokens.Space3),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
        ) {
            PosterCard(null, JUJUTSU, {}, badge = "8 серия", progress = 0.42f, subtitle = "7 из 12")
            PosterCard(null, DEMON_SLAYER, {}, badge = "3 серия")
            PosterCard(null, DANDADAN, {}, subtitle = "2025")
        }
    }
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 100)
@Composable
private fun RowHeaderPreview() = KaeruTheme {
    Column(verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
        RowHeader("Продолжить", action = RowAction("Всё") {})
        RowHeader("Дальше по списку")
    }
}

/** What a hardware keyboard, and every television screen, shows: scale plus a ring, no glow. */
@Preview(showBackground = true, backgroundColor = DARK, heightDp = 200)
@Composable
private fun FocusedControlsPreview() = KaeruTheme {
    CompositionLocalProvider(LocalFocusPreview provides true) {
        Column(
            Modifier.padding(KaeruTokens.GutterPhone),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4)) {
                PrimaryButton("Смотреть 1 серию", {}, icon = Icons.Default.PlayArrow)
                SecondaryButton("Подробнее", {})
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
            ) {
                StatusPill("Смотрю", selected = true, onClick = {})
                StatusPill("В планах", selected = false, onClick = {})
                IconAction(Icons.Default.Cast, "Транслировать на телевизор", {})
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 300)
@Composable
private fun ButtonsPreview() = KaeruTheme {
    Column(
        Modifier.padding(KaeruTokens.GutterPhone),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
            PrimaryButton("Смотреть 1 серию", {}, icon = Icons.Default.PlayArrow)
            SecondaryButton("Подробнее", {})
        }
        PrimaryButton("Серия ещё не вышла", {}, enabled = false)
        Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
            DestructiveButton("Выйти", {})
            SecondaryButton("Отмена", {})
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
        ) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Назад", {})
            IconAction(Icons.Default.Cast, "Транслировать на телевизор", {}, overArtwork = true)
            IconAction(Icons.Default.Settings, "Настройки", {}, enabled = false)
        }
    }
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 220)
@Composable
private fun ChipsPreview() = KaeruTheme {
    Column(
        Modifier.padding(KaeruTokens.GutterPhone),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
            MetaChip("2024")
            MetaChip("28 серий")
            MetaChip("9,1")
            MetaChip("Madhouse")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
            Text("AniLibria.TV", style = MaterialTheme.typography.titleSmall, color = KaeruText)
            OftenChosenChip()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
            StatusPill("Смотрю", selected = true, onClick = {})
            StatusPill("В планах", selected = false, onClick = {})
        }
    }
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 320)
@Composable
private fun EmptyStatePreview() = KaeruTheme {
    EmptyState(
        title = "Здесь появятся тайтлы из списка «Смотрю»",
        text = "Добавьте аниме на Shikimori или найдите его в поиске, и оно окажется здесь.",
        actionLabel = "Найти аниме",
        onAction = {},
        modifier = Modifier.fillMaxSize(),
    )
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 320)
@Composable
private fun ErrorStatePreview() = KaeruTheme {
    ErrorState(
        message = "Нет соединения. Проверьте интернет и повторите",
        onRetry = {},
        secondaryLabel = "Сменить озвучку",
        onSecondary = {},
        modifier = Modifier.fillMaxSize(),
    )
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 520)
@Composable
private fun SkeletonHeroPreview() = KaeruTheme {
    Column {
        SkeletonHero()
        SkeletonRow(count = 3, modifier = Modifier.padding(top = KaeruTokens.Space4))
    }
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 260)
@Composable
private fun SkeletonRowPreview() = KaeruTheme { SkeletonRow(count = 3) }

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 480)
@Composable
private fun SkeletonGridPreview() = KaeruTheme { SkeletonGrid(count = 6) }

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 140)
@Composable
private fun ProgressStripPreview() = KaeruTheme {
    Column(
        Modifier.padding(KaeruTokens.GutterPhone),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        ProgressStrip(0.08f)
        ProgressStrip(0.42f)
        ProgressStrip(0.93f)
        Skeleton(Modifier.fillMaxWidth().height(20.dp))
    }
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 300)
@Composable
private fun PosterCardStatesPreview() = KaeruTheme {
    Row(
        Modifier.padding(KaeruTokens.GutterPhone),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
    ) {
        PosterCard(null, SHADOW, {}, badge = "8 серия", progress = 0.66f)
        PosterCard(null, FRIEREN, {}, subtitle = "28 из 28")
    }
}

@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 260)
@Composable
private fun SearchFieldPreview() = KaeruTheme {
    Column(
        Modifier.padding(KaeruTokens.GutterPhone),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        // Empty, so the placeholder shows and the clear icon is absent.
        SearchField(query = "", onQueryChange = {}, onSubmit = {})
        // Typed into: the clear icon takes the place the spacer was holding.
        SearchField(query = "Фрирен", onQueryChange = {}, onSubmit = {})
        // While a search is in flight the field stays readable and stops taking keystrokes.
        SearchField(query = "Клинок, рассекающий демонов", onQueryChange = {}, onSubmit = {}, enabled = false)
    }
}

@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 200)
@Composable
private fun SearchFieldFocusedPreview() = KaeruTheme {
    CompositionLocalProvider(LocalFocusPreview provides true) {
        Column(
            Modifier.padding(KaeruTokens.GutterPhone),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
        ) {
            // The ring, at the full width it has to stay inside.
            SearchField(query = "Дандадан", onQueryChange = {}, onSubmit = {})
        }
    }
}

@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 160)
@Composable
private fun CompactActionsPreview() = KaeruTheme {
    Row(
        Modifier.padding(KaeruTokens.GutterPhone),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
    ) {
        // The three things a card action says, at the width a grid column gives it.
        SecondaryButton("В планы", {}, Modifier.weight(1f), compact = true)
        SecondaryButton("Добавляем…", {}, Modifier.weight(1f), enabled = false, compact = true)
        SecondaryButton("В списке", {}, Modifier.weight(1f), enabled = false, compact = true)
    }
}
