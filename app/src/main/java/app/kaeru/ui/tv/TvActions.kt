package app.kaeru.ui.tv

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.ui.common.details.episodeCells

/** One cell of the title screen's episode grid. */
data class TvEpisodeCell(
    val episode: Int,
    /** Out already, so there is something to play. Anything watched counts, whatever the catalogue says. */
    val aired: Boolean,
    val watched: Boolean,
    /** How far into this episode the viewer got, or null when it is not the one in progress. */
    val progress: Float?,
)

/**
 * The season as a grid, as the television draws it.
 *
 * The rule itself lives in `ui.common.details`, shared with the phone: this is only the shape
 * change between the two cell types. Two implementations of «which episodes exist, which are
 * behind the viewer, which one is in progress» would drift the day one of them is fixed.
 *
 * [entry] is null for an anime the viewer has in no list, which the title screen opens on whenever
 * it is reached from search or from the catalogue rows: everything is then simply unwatched.
 */
fun tvEpisodeGrid(anime: Anime, entry: LibraryEntry?, watchedThreshold: Float): List<TvEpisodeCell> =
    episodeCells(anime, entry?.rate, entry?.watch, watchedThreshold).map { cell ->
        TvEpisodeCell(
            episode = cell.number,
            aired = cell.aired,
            watched = cell.watched,
            progress = cell.progress,
        )
    }
