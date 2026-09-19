package app.kaeru.data.skip

import app.kaeru.data.local.SkipMarksDao
import app.kaeru.data.local.toEntity
import app.kaeru.domain.playback.SkipInterval
import app.kaeru.domain.playback.SkipMarks
import app.kaeru.domain.playback.SkipMarksSource
import app.kaeru.domain.playback.SkipRules
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/** How long an answer about one episode stands before it is worth asking again. */
private val FRESH = Duration.ofDays(7)

/**
 * The community's marks, filtered by common sense and remembered in the database.
 *
 * Three rules live here and nowhere else:
 *
 * * **One question per episode per week.** The row is the note that the question was asked,
 *   including when the answer was «nobody has marked this». Without that, an unmarked episode
 *   would cost a request every time it was opened.
 * * **The length is the question.** The engine's own duration goes out with the request and
 *   every interval that comes back is measured against it, so a file this viewer's dub produced
 *   never inherits another dub's timings.
 * * **Nothing here can stop an episode.** A refusal, a timeout, no network at all: the answer is
 *   whatever the table holds, and otherwise nothing at all. Buttons are a convenience.
 */
@Singleton
class AniSkipMarks @Inject constructor(
    private val api: AniSkipApi,
    private val dao: SkipMarksDao,
    private val clock: Clock,
) : SkipMarksSource {

    override suspend fun marks(animeId: Int, episode: Int, durationMs: Long): SkipMarks {
        // Nothing to ask with. The engine has not read the manifest yet, and a question with no
        // length would be answered for some other file.
        val lengthSec = (durationMs / 1000).toInt()
        if (lengthSec <= 0) return SkipMarks.NONE

        val now = clock.instant()
        val cached = dao.find(animeId, episode, lengthSec)
        if (cached != null && Duration.between(cached.fetchedAt, now) < FRESH) return cached.toDomain()

        val answered = runCatching { api.skipTimes(animeId, episode, ANISKIP_TYPES, lengthSec) }
            // Whatever went wrong, it is not the viewer's problem: what is remembered is the best
            // answer left, and for an episode never asked about there is simply nothing to show.
            .getOrElse { return cached?.toDomain() ?: SkipMarks.NONE }
            .marksFor(durationMs)

        dao.upsert(answered.toEntity(animeId, episode, lengthSec, now))
        return answered
    }
}

/**
 * The answer as this app can use it: milliseconds, and only intervals that could belong to a file
 * of this length.
 *
 * The first plausible one of each kind wins. The service answers with a list because several
 * people may have marked the same episode, and an argument between them is not something to put
 * in front of a viewer.
 */
internal fun AniSkipResponse.marksFor(durationMs: Long): SkipMarks {
    if (!found) return SkipMarks.NONE
    val opening = results.asSequence()
        .filter { it.skipType == "op" || it.skipType == "mixed-op" }
        .mapNotNull { SkipRules.opening(it.interval.toDomain(), durationMs) }
        .firstOrNull()
    val ending = results.asSequence()
        .filter { it.skipType == "ed" || it.skipType == "mixed-ed" }
        .mapNotNull { SkipRules.ending(it.interval.toDomain(), durationMs) }
        .firstOrNull()
    return SkipMarks(opening, ending)
}

private fun AniSkipInterval.toDomain() =
    SkipInterval((startTime * 1000).toLong(), (endTime * 1000).toLong())
