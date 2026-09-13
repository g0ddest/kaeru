package app.kaeru.ui.mobile

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val DETAILS = "details/{animeId}"
    fun details(animeId: Int) = "details/$animeId"
}
