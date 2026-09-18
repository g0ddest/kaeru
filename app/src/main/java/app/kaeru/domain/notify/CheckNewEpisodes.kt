package app.kaeru.domain.notify

import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.first
import java.time.Clock
import javax.inject.Inject

/** How a check ended, in the three ways the work around it has to treat differently. */
enum class NewEpisodeOutcome {
    /** Nobody is signed in: there is no list to check and nothing to retry. */
    NO_ACCOUNT,

    /** Shikimori could not be reached. Worth another go on the usual backoff. */
    UNREACHABLE,

    /** The list was read and compared; whether anything was said is the check's own business. */
    CHECKED,
}

/**
 * One run of the new-episode check: ask Shikimori what it holds, compare it with what was said
 * last time, write down the difference and publish it.
 *
 * Deliberately not a `Worker`. Everything here is about a list, a table and a clock, and the only
 * Android in the feature sits behind [NewEpisodeNotifier] — so the whole of this is testable
 * without WorkManager, and the worker is left as the six lines that map an outcome onto a retry.
 *
 * The same [LibraryRepository.refresh] the home screen pulls to. There is no cheaper request that
 * would do: what is new is the difference between two counts, and the counts arrive with the list.
 */
class CheckNewEpisodes @Inject constructor(
    private val auth: AuthRepository,
    private val library: LibraryRepository,
    private val remembered: NotifiedEpisodes,
    private val notifier: NewEpisodeNotifier,
    private val clock: Clock,
) {
    suspend fun run(): NewEpisodeOutcome {
        if (!auth.isLoggedIn.first()) return NewEpisodeOutcome.NO_ACCOUNT
        if (library.refresh().isFailure) return NewEpisodeOutcome.UNREACHABLE

        val check = NewEpisodeRule.check(library.observeLibrary().first(), remembered.all())
        // Written down before anything is published, and that order is the safe one. A process
        // killed between the two costs one announcement; the other order would repeat every
        // announcement it had just made, six hours later, for as long as the phone kept dying.
        remembered.record(check.record, clock.instant())
        if (check.news.isNotEmpty()) notifier.post(check.news)
        return NewEpisodeOutcome.CHECKED
    }
}
