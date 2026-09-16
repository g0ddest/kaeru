package app.kaeru.domain.settings

import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.model.Quality
import kotlinx.coroutines.flow.Flow

/**
 * The range a share of an episode can meaningfully take.
 *
 * The floor is half an episode: below that a title would be marked watched while the viewer is
 * still deciding whether to keep watching. It is named here rather than inside the store so that a
 * screen can offer the same range it will get back, and never show a value the store has quietly
 * pulled into shape.
 */
val WATCHED_THRESHOLD_RANGE: ClosedFloatingPointRange<Float> = 0.5f..1f

/**
 * Everything the settings screen can read and change, as the screen sees it: flows to show and
 * suspend setters that take effect the moment they return.
 *
 * It is deliberately a superset of `PlaybackPreferences` rather than an extension of it. Playback
 * reads these values and never writes them; settings does both, and keeping the write side out of
 * the interface the player depends on is what stops a controller from quietly changing a
 * preference somebody set by hand.
 *
 * Nothing here throws. A setter given a value outside the range its setting can mean pulls it back
 * into range rather than refusing, because the only caller is a control that cannot offer an
 * out-of-range value in the first place.
 */
interface SettingsStore {

    /** Dub studios in the order the viewer wants them offered; empty until somebody sets them. */
    val preferredTranslations: Flow<List<String>>

    /** Writing an empty list is how the viewer says «use the app's own order again». */
    suspend fun setPreferredTranslations(studios: List<String>)

    /** Whether finishing an episode starts the next one by itself. */
    val autoplayNext: Flow<Boolean>

    suspend fun setAutoplayNext(enabled: Boolean)

    /** Quality to start playback at, or null for the best the source offers. */
    val defaultQuality: Flow<Quality?>

    suspend fun setDefaultQuality(quality: Quality?)

    /** How much of an episode has to be behind the viewer for it to count as watched. */
    val watchedThreshold: Flow<Float>

    /** Coerced into [WATCHED_THRESHOLD_RANGE]; a value that is not a number is not written at all. */
    suspend fun setWatchedThreshold(fraction: Float)

    /**
     * A Kodik key typed in by hand, or null to go back to the public one.
     *
     * This belongs to the device rather than to the account: it is configuration of how this
     * phone reaches Kodik, so it survives a sign-out along with the rest of the device's own
     * settings.
     */
    val kodikToken: Flow<String?>

    suspend fun setKodikToken(token: String?)

    /**
     * The rules every download obeys: the storage limit, whether Wi-Fi is required, whether a
     * watched episode is deleted, and which height to download at.
     *
     * One value rather than four flows, because the four are read together — the limit check
     * needs the quality it is about to estimate for — and a screen that wrote them one at a time
     * would be observed halfway through its own change.
     *
     * Like the playback settings, these belong to the device: an episode already on the phone is
     * not a fact about who is signed in, and neither is the rule that put it there.
     */
    val downloadPolicy: Flow<DownloadPolicy>

    suspend fun setDownloadPolicy(policy: DownloadPolicy)
}
