package app.kaeru.ui.tv

/** The four places the drawer leads to, in the order they sit in it. */
enum class TvDestination { HOME, LIBRARY, SEARCH, SETTINGS }

/**
 * Where the television is: one of the four destinations, and the title card open over it.
 *
 * Plain state rather than a navigation graph, because the television has exactly one thing that
 * stacks — a title card — and nothing that carries arguments anywhere else. What a back stack would
 * buy here is a library and a route table; what it costs is that every destination's scroll and
 * focus would have to be saved and restored through it anyway.
 *
 * A title card belongs to the destination it was opened from, which is why it travels with one
 * rather than being a fifth destination: coming back from a title opened in «Мой список» has to
 * land in «Мой список», on the same card.
 */
data class TvRoute(
    val destination: TvDestination = TvDestination.HOME,
    /** The anime whose title card is open over [destination], or null when none is. */
    val titleId: Int? = null,
) {
    /**
     * A destination chosen in the drawer.
     *
     * Choosing the one already open closes whatever is over it, because the drawer stays reachable
     * from a title card and «Главная» pressed there has to mean something. Moving elsewhere closes
     * it too: a card left open under another destination would reappear on the way back with no
     * press of back to explain it.
     */
    fun open(destination: TvDestination): TvRoute = TvRoute(destination)

    fun openTitle(animeId: Int): TvRoute = copy(titleId = animeId)

    /**
     * One step back, or null when there is nowhere left to go and the press belongs to the
     * launcher: the title card first, then the way home from anywhere else.
     */
    fun back(): TvRoute? = when {
        titleId != null -> copy(titleId = null)
        destination != TvDestination.HOME -> TvRoute(TvDestination.HOME)
        else -> null
    }
}
