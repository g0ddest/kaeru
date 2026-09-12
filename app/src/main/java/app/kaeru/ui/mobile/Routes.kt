package app.kaeru.ui.mobile

object Routes {
    const val HOME = "home"
    const val DETAILS = "details/{animeId}"
    fun details(animeId: Int) = "details/$animeId"
}
