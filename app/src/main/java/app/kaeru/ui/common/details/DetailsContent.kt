package app.kaeru.ui.common.details

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.design.pluralEpisodes
import app.kaeru.ui.common.design.PrimaryAction
import app.kaeru.ui.common.design.episodesLabel
import app.kaeru.ui.common.design.primaryAction
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Said when the anime could not be read and the failure itself left no message behind. */
private const val UNREADABLE = "Не удалось загрузить аниме. Проверьте соединение и повторите"

private const val DUB = "Озвучка"

/** Folds a description or a long season back up. One word, said the same way in both places. */
internal const val COLLAPSE = "Свернуть"

/**
 * Which of the three screens the title screen is at this moment.
 *
 * Cached content wins over everything: an anime the database already holds is worth showing while
 * a refresh runs, and a refresh that then fails is a snackbar rather than a wall. Only when there
 * is nothing to show does the failure become the screen — and it carries its message, so the
 * screen cannot render an error state without having one to render.
 */
sealed interface DetailsContent {
    /** Nothing cached yet and a load in flight: the shape of the screen, drawn empty. */
    data object Loading : DetailsContent

    /** Nothing to show, and a reason. */
    data class Error(val message: String) : DetailsContent

    data class Ready(val anime: Anime) : DetailsContent
}

/** The decision, made where a test can read it rather than inside a composition. */
fun detailsContentState(state: DetailsUiState): DetailsContent {
    val anime = state.anime
    return when {
        anime != null -> DetailsContent.Ready(anime)
        state.refreshing -> DetailsContent.Loading
        else -> DetailsContent.Error(state.errorMessage ?: UNREADABLE)
    }
}

/**
 * The facts about a title, as separate chips.
 *
 * They are a list of short strings and never one joined line: `2023 · 28 серий · 9,1` is the
 * templated meta string the design system bans, and reads as boilerplate rather than as facts.
 * Anything the catalogue does not know is left out instead of printed as a zero.
 *
 * A season says its announced length once. An ongoing show adds how much of it is out, because
 * that is the number that decides what can be pressed tonight; a show whose length nobody has
 * announced has only that second number, so it says it in full.
 */
fun detailsMeta(anime: Anime): List<String> = buildList {
    anime.year?.let { add(it.toString()) }
    val announced = anime.episodes
    val aired = anime.episodesAired
    when {
        announced > 0 -> {
            add(pluralEpisodes(announced))
            if (aired in 1 until announced) add("вышло $aired")
        }
        aired > 0 -> add("вышло ${pluralEpisodes(aired)}")
    }
    anime.score?.takeIf { it > 0 }?.let { add("★ ${score(it)}") }
    anime.studio?.takeIf { it.isNotBlank() }?.let(::add)
    add(airStatusLabel(anime.status))
}

/** `9,1` — a decimal comma, because that is how a score is written in Russian. */
private fun score(value: Double): String = String.format(Locale.ROOT, "%.1f", value).replace('.', ',')

private fun airStatusLabel(status: AnimeStatus): String = when (status) {
    AnimeStatus.ONGOING -> "Онгоинг"
    AnimeStatus.RELEASED -> "Вышло"
    AnimeStatus.ANONS -> "Анонс"
}

/**
 * What the dub control says.
 *
 * The remembered choice is an id; the studio behind it is only known once the source has been
 * asked, which happens the first time the chooser is opened. Until then the control says what it
 * opens and nothing more — a label that guessed a studio name would be a label that lies.
 */
fun translationLabel(translations: List<RankedTranslation>, currentId: Int?): String {
    val current = currentId?.let { id -> translations.firstOrNull { it.translation.id == id } } ?: return DUB
    return "$DUB: ${current.translation.title}"
}

/**
 * The watch button on this screen.
 *
 * A title in no list has nothing remembered about it, so it borrows an empty rate and asks the
 * same question the library's [primaryAction] answers everywhere else: is there an episode to
 * start, and which one. That is how an announcement with nothing aired gets «Ещё не вышло» here
 * rather than an offer to play an episode that does not exist.
 */
fun detailsAction(
    anime: Anime,
    entry: LibraryEntry?,
    watchedThreshold: Float,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): PrimaryAction = primaryAction(entry ?: LibraryEntry(anime, emptyRate(anime.id), null), watchedThreshold, now, zone)

/** Stands in for «this anime is in no list», which is the same thing as nothing watched. */
private fun emptyRate(animeId: Int) =
    UserRate(id = 0, animeId = animeId, status = ListStatus.PLANNED, episodes = 0, updatedAt = Instant.EPOCH)

/**
 * «20 из 28» under the episode grid.
 *
 * [total] is how many cells the grid actually drew, not what the catalogue announced. The two
 * differ whenever a season runs past its announced length or Shikimori's watched count does, and
 * «30 из 28» over a grid of thirty tiles is the screen contradicting itself. The count is still
 * the floor — a caller that somehow passes a smaller total gets the count — and a grid with no
 * cells prints «?» rather than inventing a length.
 */
fun watchedLine(watched: Int, total: Int): String =
    episodesLabel(watched, if (total > 0) maxOf(total, watched) else 0)
