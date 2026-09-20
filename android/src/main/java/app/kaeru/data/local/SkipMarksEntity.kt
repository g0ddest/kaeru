package app.kaeru.data.local

import androidx.room.Entity
import app.kaeru.domain.playback.SkipInterval
import app.kaeru.domain.playback.SkipMarks
import java.time.Instant

/**
 * What is known about one episode's opening and ending, at one length.
 *
 * The length is part of the key rather than a column beside it, because marks belong to a file
 * and not to an episode: the same episode in another voice is another file, with another length
 * and its own marks, and one row per episode would show a viewer the other dub's timings.
 *
 * Every mark is nullable and a row with all four empty is the answer «nobody has marked this
 * one», which is worth keeping: it is what stops the app asking again tomorrow.
 *
 * The table belongs to the device rather than to the account — like the downloads it sits beside,
 * and for the same reason — so signing out leaves it alone.
 */
@Entity(tableName = "skip_marks", primaryKeys = ["animeId", "episode", "lengthSec"])
data class SkipMarksEntity(
    val animeId: Int,
    val episode: Int,
    /** The length the marks were asked for, in whole seconds, as the source speaks of it. */
    val lengthSec: Int,
    val opStart: Long?,
    val opEnd: Long?,
    val edStart: Long?,
    val edEnd: Long?,
    /** When this was last asked for, which is the whole of the once-a-week rule. */
    val fetchedAt: Instant,
) {
    fun toDomain() = SkipMarks(
        opening = interval(opStart, opEnd),
        ending = interval(edStart, edEnd),
    )

    private fun interval(startMs: Long?, endMs: Long?): SkipInterval? =
        if (startMs == null || endMs == null) null else SkipInterval(startMs, endMs)
}

fun SkipMarks.toEntity(animeId: Int, episode: Int, lengthSec: Int, at: Instant) = SkipMarksEntity(
    animeId = animeId,
    episode = episode,
    lengthSec = lengthSec,
    opStart = opening?.startMs,
    opEnd = opening?.endMs,
    edStart = ending?.startMs,
    edEnd = ending?.endMs,
    fetchedAt = at,
)
