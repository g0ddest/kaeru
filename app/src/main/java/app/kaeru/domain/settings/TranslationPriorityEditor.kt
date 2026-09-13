package app.kaeru.domain.settings

/**
 * The edits a viewer can make to the dub priority list, as pure list arithmetic.
 *
 * The list has two lives. Until somebody touches it the store holds nothing and the ranker falls
 * back to the studios the app ships with; the first edit writes the whole shown order down, so
 * moving one row one place up turns the app's guess into the viewer's own list. [shown] is the
 * one place that seam is expressed, and an empty result from [remove] hands the list back to the
 * app — which is what a reset writes too.
 *
 * Every operation returns the list it was given, unchanged and by identity, when it has nothing
 * to do: pressing the up control on the top row must not rewrite the store with the same nine names.
 */
object TranslationPriorityEditor {

    /** The order to put on screen: the viewer's own if there is one, otherwise the app's. */
    fun shown(stored: List<String>, defaults: List<String>): List<String> = stored.ifEmpty { defaults }

    /** [index] and the row above it change places. The top row has nowhere to go. */
    fun moveUp(studios: List<String>, index: Int): List<String> =
        if (index <= 0 || index >= studios.size) studios else studios.swapped(index - 1, index)

    /** [index] and the row below it change places. The bottom row has nowhere to go. */
    fun moveDown(studios: List<String>, index: Int): List<String> =
        if (index < 0 || index >= studios.lastIndex) studios else studios.swapped(index, index + 1)

    /**
     * Drops one row. Removing the last remaining one empties the list, which the store reads back
     * as «never chosen» — the same state a reset leaves. The screen declines to offer that press,
     * because the reset control is the labelled way to get there.
     */
    fun remove(studios: List<String>, index: Int): List<String> =
        if (index < 0 || index >= studios.size) studios else studios.filterIndexed { i, _ -> i != index }

    /**
     * Appends a studio somebody typed. A new name goes last because it is the one claim on the
     * list with nothing behind it yet, and de-duplication ignores capitals: «anilibria» and
     * «AniLibria» match the same track titles, so two rows would rank the same studio twice.
     */
    fun add(studios: List<String>, name: String): List<String> {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return studios
        if (studios.any { it.equals(cleaned, ignoreCase = true) }) return studios
        return studios + cleaned
    }

    private fun List<String>.swapped(a: Int, b: Int): List<String> =
        toMutableList().also { it[a] = this[b]; it[b] = this[a] }
}
