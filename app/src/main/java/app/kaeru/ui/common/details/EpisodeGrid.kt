package app.kaeru.ui.common.details

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import java.time.Instant

/**
 * One cell of a season grid, with every fact it draws already decided.
 *
 * Three different things are true of an episode and each is drawn differently, so none of them
 * has to stand in for another: [watched] is Shikimori's count, [progress] is where this device
 * stopped inside it, and [aired] is whether there is anything to play at all.
 */
data class EpisodeCell(
    val number: Int,
    val watched: Boolean,
    /** How far into this episode the viewer got, or null when it is not the one in progress. */
    val progress: Float?,
    val aired: Boolean,
)

/**
 * The season as a grid: every episode the show has announced, marked with what is behind the
 * viewer, what they are in the middle of, and what has not arrived yet.
 *
 * Two different numbers decide the shape. What can be played is what has aired; how far the grid
 * runs is what the season was announced to hold — so an episode still to come is drawn as waiting
 * rather than missing. Progress the viewer actually has wins over both, because an episode they
 * watched plainly exists whatever the catalogue says about it.
 *
 * A show with nothing announced and nothing aired returns no cells at all: an announcement with no
 * episodes has no season to draw, and a lone placeholder tile would be an invitation to press
 * something that does not exist.
 *
 * [rate] and [watch] are nullable because this screen also opens on an anime that is in no list:
 * everything is then simply unwatched.
 */
fun episodeCells(anime: Anime, rate: UserRate?, watch: WatchState?, watchedThreshold: Float): List<EpisodeCell> {
    val seen = rate?.episodes ?: 0
    val reached = maxOf(seen, watch?.episode ?: 0)
    val playable = maxOf(anime.availableEpisodes, reached)
    val announced = maxOf(anime.episodes, playable)
    // The rule for "is there a position worth showing" lives on LibraryEntry and is the same rule
    // the watch button obeys; a title outside the list borrows an empty rate to ask it, rather
    // than this file growing a second copy that can drift from the first.
    val inProgress = LibraryEntry(anime, rate ?: emptyRate(anime.id), watch).progressFraction(watchedThreshold)
    return (1..announced).map { episode ->
        EpisodeCell(
            number = episode,
            watched = episode <= seen,
            progress = inProgress?.takeIf { watch?.episode == episode },
            aired = episode <= playable,
        )
    }
}

/** Stands in for "this anime is in no list", which is the same thing as nothing watched. */
private fun emptyRate(animeId: Int) =
    UserRate(id = 0, animeId = animeId, status = ListStatus.PLANNED, episodes = 0, updatedAt = Instant.EPOCH)
