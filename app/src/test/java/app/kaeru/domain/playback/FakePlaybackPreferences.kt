package app.kaeru.domain.playback

import app.kaeru.domain.model.Quality
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Settings a test can set by hand. The flows are mutable, so a test changes a setting the way a
 * settings screen would: by writing to it.
 */
class FakePlaybackPreferences(
    threshold: Float = 0.9f,
    autoplay: Boolean = true,
    quality: Quality? = null,
    preferred: List<String> = emptyList(),
    pip: Boolean = true,
) : PlaybackPreferences {
    override val watchedThreshold = MutableStateFlow(threshold)
    override val autoplayNext = MutableStateFlow(autoplay)
    override val pipOnLeave = MutableStateFlow(pip)
    override val defaultQuality = MutableStateFlow(quality)
    override val preferredTranslations = MutableStateFlow(preferred)

    override suspend fun setDefaultQuality(quality: Quality?) {
        defaultQuality.value = quality
    }
}
