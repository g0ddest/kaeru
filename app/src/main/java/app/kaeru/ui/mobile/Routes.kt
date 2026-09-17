package app.kaeru.ui.mobile

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    /** Reached from the settings row and from the downloads notification, never from a tab. */
    const val DOWNLOADS = "downloads"

    /**
     * Reached from the settings page and from the one-line row on the home screen.
     *
     * Never pushed by the app itself. A screen about installing something is a screen somebody has
     * to have gone looking for, and an app that opened it unasked would be an app that interrupts.
     */
    const val UPDATES = "updates"

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

    /**
     * Reached from an invitation to watch together, never from a tab.
     *
     * The link itself is not in the route. It carries the room key in its fragment, and a key that
     * went into a back stack entry would be a secret written into somebody's saved state; the
     * session holds it instead, and this route is only the screen that asks about it.
     */
    const val WATCH = "watch"
    const val DETAILS = "details/{animeId}"
    fun details(animeId: Int) = "details/$animeId"
}
