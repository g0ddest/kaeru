package app.kaeru.domain.playback

import app.kaeru.domain.model.Quality
import kotlinx.coroutines.flow.Flow

/**
 * The settings playback obeys, as the playback code sees them: four flows and nothing about
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

    /** Quality to start playback at, or null for the best the source offers. */
    val defaultQuality: Flow<Quality?>

    /**
     * Dub studios in the order the viewer wants them offered, and only those: empty until
     * somebody sets them. What answers an anime when this is empty is
     * [TranslationRanker.DEFAULT_STUDIOS], behind the viewer's own watching history.
     */
    val preferredTranslations: Flow<List<String>>
}
