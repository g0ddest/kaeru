package app.kaeru.ui.mobile

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    /** Reached from the settings row and from the downloads notification, never from a tab. */
    const val DOWNLOADS = "downloads"

    /**
     * How something outside the app asks for a screen inside it: the downloads notification, today.
     *
     * Here rather than beside the notification that sends it, so nothing in the data layer has to
     * know the name of a route — the `di` module that builds the intent is the one place that sees
     * both an activity and a screen.
     */
    const val EXTRA_ROUTE = "route"

    /** Reached from a `kaeru://pair` deep link, never from a tab. */
    const val PAIR = "pair"
    const val DETAILS = "details/{animeId}"
    fun details(animeId: Int) = "details/$animeId"
}
