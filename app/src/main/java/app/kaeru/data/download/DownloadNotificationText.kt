package app.kaeru.data.download

/**
 * Every line the download notification can show, with nothing Android in it.
 *
 * Split out so the copy can be read and tested as copy. The plural rule is repeated here rather
 * than taken from `ui.common.design.Format` on purpose: nothing in the data layer imports the UI
 * layer, and a notification built by a service is data-layer work. It is the same rule — Russian
 * counts three ways and `11..14` are the exception every naive version gets wrong.
 */
object DownloadNotificationText {

    /** The heading, which never changes: what the notification is about, not what it is doing. */
    const val TITLE = "Загрузка серий"

    /** One episode as the notification names it. [percent] is 0..100; anything outside is clamped. */
    data class Item(val title: String?, val episode: Int, val percent: Int)

    /**
     * The line under the heading.
     *
     * One download is named — a viewer who started one wants to know which one and how far it has
     * got. Several are counted instead: a notification cannot list them, and a line that names
     * only the first would look like the others were not running.
     */
    fun progress(items: List<Item>, waitingForNetwork: Boolean = false): String = when {
        waitingForNetwork -> "Ожидание сети"
        items.isEmpty() -> "Подготовка"
        items.size == 1 -> items.single().let { "${it.named()}, ${it.percent.clamped()} %" }
        else -> "Загружается ${pluralEpisodes(items.size)}"
    }

    fun completed(item: Item): String = "Скачано: ${item.named()}"

    /**
     * «Не удалось скачать Тайтл, 7 серию».
     *
     * The accusative, because «скачать» governs it — the same rule
     * `ui.common.design.pluralEpisodesAccusative` exists for, and «Не удалось скачать 7 серия» is
     * the same mistake it was written to prevent.
     */
    fun failed(item: Item): String = "Не удалось скачать ${item.named(EPISODE_ACCUSATIVE)}"

    /**
     * «Тайтл, 7 серия», or just «7 серия» for a download whose title this device never learned.
     *
     * An episode number is an ordinal, not a count, so the noun never goes plural: «11 серия» is
     * «одиннадцатая серия», not eleven of them. That is why this takes the word rather than
     * calling [pluralEpisodes] — the count rule would turn 11 into «11 серий» and say something
     * else entirely.
     */
    private fun Item.named(noun: String = EPISODE_NOMINATIVE): String =
        if (title.isNullOrBlank()) "$episode $noun" else "$title, $episode $noun"

    private fun Int.clamped(): Int = coerceIn(0, 100)

    private const val EPISODE_NOMINATIVE = "серия"
    private const val EPISODE_ACCUSATIVE = "серию"

    private fun pluralEpisodes(count: Int): String {
        val n = kotlin.math.abs(count)
        val noun = if (n % 100 in 11..14) {
            "серий"
        } else {
            when (n % 10) {
                1 -> "серия"
                2, 3, 4 -> "серии"
                else -> "серий"
            }
        }
        return "$count $noun"
    }
}
