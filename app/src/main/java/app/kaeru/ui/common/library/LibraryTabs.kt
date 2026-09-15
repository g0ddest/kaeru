package app.kaeru.ui.common.library

import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.ui.common.design.episodesLabel
import app.kaeru.ui.common.design.pluralEpisodes
import app.kaeru.ui.common.design.statusLabel

/**
 * The order the tabs sit in, which is the order a viewer reaches for them rather than the order
 * the API declares them: what is running now, what is queued, what is finished, and then the three
 * a list is only occasionally opened at.
 */
private val TabOrder = listOf(
    ListStatus.WATCHING,
    ListStatus.PLANNED,
    ListStatus.COMPLETED,
    ListStatus.REWATCHING,
    ListStatus.ON_HOLD,
    ListStatus.DROPPED,
)

/**
 * One tab of the list.
 *
 * [text] is the whole label: the status and how many titles are under it, with a space between
 * them and nothing else. «Смотрю (12)» reads as an aside and «Смотрю · 12» is the templated meta
 * string the design system bans; «Смотрю 12» reads the way a person says it.
 */
data class LibraryTab(val status: ListStatus, val label: String, val count: Int) {
    val text: String get() = "$label $count"
}

/**
 * Every status, always, with what [libraryCounts] found under it.
 *
 * A status with nothing in it keeps its tab and shows a zero. Hiding it would make the row shorter
 * and the list less honest: «Отложено 0» answers the question, while an absent tab leaves a viewer
 * wondering where the titles they parked went.
 */
fun libraryTabs(counts: Map<ListStatus, Int>): List<LibraryTab> = TabOrder.map { status ->
    LibraryTab(status, statusLabel(status), counts[status] ?: 0)
}

/**
 * The one fact under a title in the grid: how far through it the viewer is.
 *
 * A title already started counts against the season, because that is the number the viewer came
 * to check. One not started yet says how long the season is instead — «0 из 28» is the same fact
 * written as a disappointment, and on the «В планах» tab every card would carry one. A title with
 * nothing aired and nothing watched says nothing at all rather than printing a zero or a `?`.
 */
fun libraryCardSubtitle(entry: LibraryEntry): String? {
    val anime = entry.anime
    val total = if (anime.episodes > 0) anime.episodes else anime.availableEpisodes
    val watched = entry.rate.episodes
    return when {
        watched > 0 -> episodesLabel(watched, total)
        total > 0 -> pluralEpisodes(total)
        else -> null
    }
}

/**
 * What a tab with nothing under it says, and whether it offers a way to fill itself.
 *
 * «Смотрю» and «В планах» are the tabs a viewer fills on purpose, so both offer the way to fill
 * them. The other four fill themselves as a consequence of watching, and a button on «Брошено»
 * would be an invitation to go and abandon something.
 */
data class EmptyTabCopy(val title: String, val text: String, val offersSearch: Boolean = false)

fun emptyTabCopy(status: ListStatus): EmptyTabCopy = when (status) {
    ListStatus.WATCHING -> EmptyTabCopy(
        title = "Вы ничего не смотрите",
        text = "Начните любой тайтл — он окажется здесь вместе с серией, на которой вы остановились.",
        offersSearch = true,
    )
    ListStatus.PLANNED -> EmptyTabCopy(
        title = "В планах пока пусто",
        text = "Складывайте сюда всё, что хотите посмотреть потом. Kaeru покажет, когда выйдут новые серии.",
        offersSearch = true,
    )
    ListStatus.COMPLETED -> EmptyTabCopy(
        title = "Завершённых тайтлов пока нет",
        text = "Здесь соберётся всё, что вы досмотрели до конца.",
    )
    ListStatus.REWATCHING -> EmptyTabCopy(
        title = "Вы ничего не пересматриваете",
        text = "Отметьте тайтл как «Пересматриваю» — он появится здесь.",
    )
    ListStatus.ON_HOLD -> EmptyTabCopy(
        title = "Ничего не отложено",
        text = "Тайтлы на паузе ждут здесь. Вернуться к ним можно в любой вечер.",
    )
    ListStatus.DROPPED -> EmptyTabCopy(
        title = "Ничего не брошено",
        text = "Тайтлы, которые не пошли, собираются здесь, чтобы не мешать остальным.",
    )
}
