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
    /** How far into this episode the viewer got, or null when they have not really started it. */
    val progress: Float?,
)

/** One row of the panel a long press of OK opens over an episode, in the order it lists them. */
enum class TvEpisodeAction {
    WATCH,
    MARK_WATCHED,
    MARK_UNWATCHED,
}

/**
 * What a long press of OK offers for one episode.
 *
 * The phone's version of this list is `ui.common.details.episodeActions`, and the difference
 * between them is the two entries about downloads: they exist on the phone only, so on a
 * television the panel is the mark and the way out of it.
 *
 * Both marks are here where the phone offers one and an «Отменить» snackbar. A television has no
 * snackbar, so the panel itself has to be the way back: an episode un-marked by a stray press of
 * OK is marked again from the same place, rather than being a change the remote cannot undo.
 */
fun tvEpisodeActions(cell: TvEpisodeCell): List<TvEpisodeAction> {
    if (!cell.aired) return emptyList()
    val mark = if (cell.watched) TvEpisodeAction.MARK_UNWATCHED else TvEpisodeAction.MARK_WATCHED
    return listOf(TvEpisodeAction.WATCH, mark)
}

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
    episodeCells(anime, entry?.rate, entry?.watch, entry?.progress ?: emptyList(), watchedThreshold).map { cell ->
        TvEpisodeCell(
            episode = cell.number,
            aired = cell.aired,
            watched = cell.watched,
            progress = cell.progress,
        )
    }
