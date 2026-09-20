package app.kaeru.domain.model

import java.time.Instant

/** A rung of the quality ladder. Kodik serves one manifest per height, not one adaptive master playlist. */
enum class Quality(val height: Int) {
    P360(360),
    P480(480),
    P720(720),
    P1080(1080),
    ;

    companion object {
        fun ofHeight(height: Int): Quality? = entries.firstOrNull { it.height == height }
    }
}

/**
 * Playable URLs for one episode in one translation.
 *
 * The URLs are signed and short-lived (hours) and appear to be bound to the IP
 * that asked for them, hence [resolvedAt]: whoever holds a stream has to treat
 * it as perishable and resolve again rather than store it.
 */
data class EpisodeStream(
    val animeId: Int,
    val episode: Int,
    val translation: Translation,
    val urls: Map<Quality, String>,
    val resolvedAt: Instant,
) {
    init {
        require(urls.isNotEmpty()) { "An EpisodeStream with no playable url is not a stream" }
    }

    val best: Quality get() = urls.keys.maxBy { it.height }
}
