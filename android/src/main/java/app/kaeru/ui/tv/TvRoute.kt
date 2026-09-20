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
    /**
     * Whether «Обновления» is open over [destination].
     *
     * A flag rather than a fifth destination, for the reason a title card is not one either: it is
     * opened from a screen and closed back onto it. Two places lead here — the settings page and
     * the one-line row on the home screen — and back has to return to whichever it was.
     */
    val updates: Boolean = false,
) {
    /**
     * A destination chosen in the drawer.
     *
     * Choosing the one already open closes whatever is over it, because the drawer stays reachable
     * from a title card and «Главная» pressed there has to mean something. Moving elsewhere closes
     * it too: a card left open under another destination would reappear on the way back with no
     * press of back to explain it. The same goes for «Обновления».
     */
    fun open(destination: TvDestination): TvRoute = TvRoute(destination)

    fun openTitle(animeId: Int): TvRoute = copy(titleId = animeId)

    fun openUpdates(): TvRoute = copy(updates = true)

    /**
     * One step back, or null when there is nowhere left to go and the press belongs to the
     * launcher: the title card first, then the way home from anywhere else.
     */
    fun back(): TvRoute? = when {
        // Closed before a title card, because it is the thing on top: the two are never open
        // together in practice, and an order written down is one a test can read.
        updates -> copy(updates = false)
        titleId != null -> copy(titleId = null)
        destination != TvDestination.HOME -> TvRoute(TvDestination.HOME)
        else -> null
    }
}

/**
 * What one press of back does, given where the television is and whether the rail is open.
 *
 * Null means the press belongs to the launcher. Everything else is the shell's to carry out, and
 * the decision is here rather than in the composable so that «back never exits from an open menu»
 * is a line a test can read.
 */
sealed interface TvBack {

    /**
     * Hand the D-pad back to the content, which is what closes the rail.
     *
     * Closing it means moving the focus, not setting a value: `ModalNavigationDrawer` registers no
     * back handler of its own and re-derives open from whether anything inside it has focus, so a
     * value set behind a rail that still holds the D-pad renders closed and behaves open.
     *
     * [fallback] is where to go when the rail keeps the focus because the screen behind it has
     * nothing to take it — a first sync is skeletons and one sentence, and nothing on it is
     * focusable. The press then means what it would have meant with the rail closed. Null on the
     * home screen, where that would be leaving the app: a press that opened a menu must never be
     * the press that closes the app.
     */
    data class CloseRail(val fallback: TvRoute?) : TvBack

    /** One step back through the destinations. */
    data class Go(val route: TvRoute) : TvBack
}

/** @see TvBack */
fun tvBack(route: TvRoute, railOpen: Boolean): TvBack? =
    if (railOpen) TvBack.CloseRail(route.back()) else route.back()?.let(TvBack::Go)
