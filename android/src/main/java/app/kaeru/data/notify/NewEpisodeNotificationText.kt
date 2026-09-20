package app.kaeru.data.notify

/**
 * Every line the new-episode notification can show, with nothing Android in it.
 *
 * Split out so the copy can be read and tested as copy, the same way `DownloadNotificationText` is
 * — and here for the same reason it is there: nothing in the data layer imports the UI layer, and a
 * notification a background worker publishes is data-layer work. The one string that has to be a
 * resource is the channel's name, because it is the system that draws it.
 */
object NewEpisodeNotificationText {

    /** The heading over the group, and the name of the channel as this object knows it. */
    const val TITLE = "Новые серии"

    /**
     * «Вышла 7 серия».
     *
     * An episode number is an ordinal, not a count, so the noun never goes plural: «11 серия» is
     * «одиннадцатая серия», and a count rule would turn it into «11 серий» and say that eleven of
     * them came out at once.
     */
    fun episode(episode: Int): String = "Вышла $episode серия"

    /**
     * «смотреть с 6-й»: the second line, for a viewer who is not caught up.
     *
     * Only there when the two numbers differ. Saying «смотреть с 9-й» under «Вышла 9 серия» would
     * be the notification explaining itself to somebody who needs no explanation.
     */
    fun watchFrom(episode: Int): String = "смотреть с $episode-й"

    /** «Тайтл, 7 серия» — one line of the summary, where there is no room for a sentence. */
    fun line(title: String, episode: Int): String = "$title, $episode серия"

    /**
     * «3 тайтла»: what the summary over two or more notifications says.
     *
     * A count this time, not an ordinal, so it does go plural — and Russian counts three ways,
     * with `11..14` the exception every naive version gets wrong. The same rule as
     * `DownloadNotificationText`'s, and repeated here for the same reason: nothing in the data
     * layer imports the UI layer, where the app's other plural rules live.
     */
    fun titles(count: Int): String {
        val n = kotlin.math.abs(count)
        val noun = if (n % 100 in 11..14) {
            "тайтлов"
        } else {
            when (n % 10) {
                1 -> "тайтл"
                2, 3, 4 -> "тайтла"
                else -> "тайтлов"
            }
        }
        return "$count $noun"
    }
}
