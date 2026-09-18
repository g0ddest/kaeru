package app.kaeru.data.local

import androidx.room.Entity
import app.kaeru.domain.notify.NotifiedEpisode
import java.time.Instant

/**
 * One episode the new-episode check has already accounted for.
 *
 * The pair is the key, which is the dedup rule written as a constraint rather than as code: there
 * is no way to say the same thing twice about the same episode, whatever a caller does. [notifiedAt]
 * is kept for the sake of a person reading the database — nothing in the app reads it, and a second
 * write of the same pair deliberately leaves the first one's time alone.
 */
@Entity(tableName = "notified_episodes", primaryKeys = ["animeId", "episode"])
data class NotifiedEpisodeEntity(
    val animeId: Int,
    val episode: Int,
    val notifiedAt: Instant,
) {
    fun toDomain() = NotifiedEpisode(animeId = animeId, episode = episode)
}

fun NotifiedEpisode.toEntity(at: Instant) = NotifiedEpisodeEntity(animeId, episode, at)
