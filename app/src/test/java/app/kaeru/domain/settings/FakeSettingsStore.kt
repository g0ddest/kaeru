package app.kaeru.domain.settings

import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.model.Quality
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The settings store as a test can hold it: six mutable flows and a record of every write.
 *
 * [echo] is what makes optimism testable. With it off a setter is recorded but the flow it belongs
 * to never changes, so anything the screen still shows afterwards is something it decided to show
 * on its own rather than something the store told it.
 */
class FakeSettingsStore(
    studios: List<String> = emptyList(),
    autoplay: Boolean = true,
    quality: Quality? = null,
    threshold: Float = 0.9f,
    token: String? = null,
    downloads: DownloadPolicy = DownloadPolicy.DEFAULT,
    var echo: Boolean = true,
) : SettingsStore {
    override val preferredTranslations = MutableStateFlow(studios)
    override val autoplayNext = MutableStateFlow(autoplay)
    override val defaultQuality = MutableStateFlow(quality)
    override val watchedThreshold = MutableStateFlow(threshold)
    override val kodikToken = MutableStateFlow(token)
    override val downloadPolicy = MutableStateFlow(downloads)

    val writes = mutableListOf<String>()

    override suspend fun setPreferredTranslations(studios: List<String>) {
        writes += "studios=$studios"
        if (echo) preferredTranslations.value = studios
    }

    override suspend fun setAutoplayNext(enabled: Boolean) {
        writes += "autoplay=$enabled"
        if (echo) autoplayNext.value = enabled
    }

    override suspend fun setDefaultQuality(quality: Quality?) {
        writes += "quality=$quality"
        if (echo) defaultQuality.value = quality
    }

    override suspend fun setWatchedThreshold(fraction: Float) {
        writes += "threshold=$fraction"
        if (echo) watchedThreshold.value = fraction
    }

    override suspend fun setKodikToken(token: String?) {
        writes += "token=$token"
        if (echo) kodikToken.value = token
    }

    override suspend fun setDownloadPolicy(policy: DownloadPolicy) {
        writes += "downloads=$policy"
        if (echo) downloadPolicy.value = policy
    }
}
