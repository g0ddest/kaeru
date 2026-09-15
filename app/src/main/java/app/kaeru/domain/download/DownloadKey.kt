package app.kaeru.domain.download

import app.kaeru.domain.model.Quality

/**
 * Which episode, in which track, at which height a download is.
 *
 * The download engine keys everything it holds by one string, so this is that string with the
 * four facts still readable inside it. It is the identity of a download: changing the track or
 * the height makes a different file, which is why both are in the id rather than beside it.
 * Whoever enqueues an episode a second time at another height is asking for a second download,
 * and the repository is what decides to drop the first.
 */
data class DownloadKey(
    val animeId: Int,
    val episode: Int,
    val translationId: Int,
    val quality: Quality,
) {
    val id: String get() = "$animeId:$episode:$translationId:${quality.height}"

    companion object {
        /**
         * The id back into its four facts, or null for anything this app did not write: a row
         * left over from an older scheme, or a height no build offers any more. Null rather than
         * a best guess — a guessed height would point the player at a file nobody downloaded.
         */
        fun parse(id: String): DownloadKey? {
            val parts = id.split(':')
            if (parts.size != 4) return null
            val animeId = parts[0].toIntOrNull() ?: return null
            val episode = parts[1].toIntOrNull() ?: return null
            val translationId = parts[2].toIntOrNull() ?: return null
            val height = parts[3].toIntOrNull() ?: return null
            val quality = Quality.ofHeight(height) ?: return null
            return DownloadKey(animeId, episode, translationId, quality)
        }
    }
}
