package app.kaeru.ui.mobile

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    /** Reached from the settings row and from the downloads notification, never from a tab. */
    const val DOWNLOADS = "downloads"

    /** Reached from a `kaeru://pair` deep link, never from a tab. */
    const val PAIR = "pair"
    const val DETAILS = "details/{animeId}"
    fun details(animeId: Int) = "details/$animeId"
}
