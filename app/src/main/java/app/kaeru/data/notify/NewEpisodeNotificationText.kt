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

    /**
     * «Вышла 7 серия».
     *
     * An episode number is an ordinal, not a count, so the noun never goes plural: «11 серия» is
     * «одиннадцатая серия», and a count rule would turn it into «11 серий» and say that eleven of
     * them came out at once.
     */
    fun episode(episode: Int): String = "Вышла $episode серия"
}
