package app.kaeru.domain.playback

import app.kaeru.domain.model.Quality
import kotlinx.coroutines.flow.Flow

/**
 * The settings playback obeys, as the playback code sees them: five flows and nothing about
 * where they are stored.
 *
 * It exists so that the use-cases, the controller and the screens depend on a domain type
 * rather than on the DataStore class that happens to hold these values today.
 */
interface PlaybackPreferences {
    /** How much of an episode has to be behind the viewer for it to count as watched. */
    val watchedThreshold: Flow<Float>

    /** Whether finishing an episode starts the next one by itself. */
    val autoplayNext: Flow<Boolean>

    /**
     * Whether leaving the app with an episode playing folds it into a floating window.
     *
     * On unless the viewer turns it off. Off means the player never enters a window on its own —
     * neither through the system's automatic entry nor on the leave hint — while the button that
     * asks for one by hand keeps working.
     */
    val pipOnLeave: Flow<Boolean>

    /** Quality to start playback at, or null for the best the source offers. */
    val defaultQuality: Flow<Quality?>

    /**
     * Settles on a quality for every episode from now on, or hands the choice back to the source
     * with null. Written from the player's quality chooser, which is where the viewer is looking
     * when they decide that 480p is what this connection can carry.
     */
    suspend fun setDefaultQuality(quality: Quality?)

    /**
     * Dub studios in the order the viewer wants them offered, and only those: empty until
     * somebody sets them. What answers an anime when this is empty is
     * [TranslationRanker.DEFAULT_STUDIOS], behind the viewer's own watching history.
     */
    val preferredTranslations: Flow<List<String>>
}
