package app.kaeru.domain.playback

import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A title somebody has started watching belongs in their list.
 *
 * Until the mark at nine tenths of the episode there was nothing anywhere to say a title reached
 * from search had ever been opened: no rate, so no library entry, so no card on the home screen
 * and nothing to find the half-watched episode by. A viewer who stopped ten minutes in had to
 * search for the title all over again. The list is where that memory belongs — this device already
 * keeps the position, and the list is what makes the title findable at all.
 *
 * «Смотрю» with the count left at zero, which is the truth: an episode is being watched and none
 * has been finished. Written through [LibraryRepository.setStatus], the same offline-safe path
 * every mark uses — with a network it creates the rate on Shikimori, and without one it writes the
 * local rate and queues the create for the outbox to send. Either way the entry exists before this
 * returns, so «Продолжить» has the title by the time the first position lands.
 *
 * Only a title that is in no list at all. A status the viewer chose — «Запланировано»,
 * «Пересматриваю», «Отложено» — is theirs, and starting an episode is not an argument with it;
 * picking a shelved title back up is the mark's job, at the point the episode is genuinely
 * watched. The feed no longer needs the status to be «Смотрю» to offer a position, so there is
 * nothing to gain by overwriting one.
 *
 * Idempotent, and it stays idempotent while a create is in flight: re-opening the episode for a
 * retry or a change of voice takes seconds, a create takes a round trip, and two creates are a
 * duplicate rate on Shikimori that nothing here would ever clean up. The second start of a title
 * already being added simply returns — the first one is doing the work.
 *
 * The guard is per title rather than one lock for all of them, and that is the whole reason it is a
 * set of ids and not a mutex: a create stuck on a slow socket must not hold up the *next* title a
 * viewer starts. The lock below is held only long enough to add or remove an id, never across the
 * write.
 */
class AddStartedTitleToList(private val library: LibraryRepository) {
    private val guard = Mutex()

    /** The titles a create is in flight for, so a second start of one of them is a no-op. */
    private val creating = mutableSetOf<Int>()

    /**
     * Never throws and never reports: playback is what the viewer asked for, and a list that could
     * not be written is worth nothing beside it. The next start tries again.
     *
     * The library read is the whole of the check, and it reads the account's whole list to answer
     * about one title (`observeAnime` is `observeLibrary().map { … }`). That is one query per
     * episode start, off the path a frame of video takes; a list long enough for it to matter wants
     * a dao read of its own.
     *
     * Offline it can also do nothing at all, silently: creating a rate needs a displayable anime,
     * and for a title whose card this device has never cached the repository has to fetch one —
     * which it cannot. In practice anything reachable offline has been through the title screen and
     * the card is in Room; when it is not, the next start with a network puts it right.
     */
    suspend operator fun invoke(animeId: Int) {
        if (!guard.withLock { creating.add(animeId) }) return
        try {
            if (library.observeAnime(animeId).first() != null) return
            library.setStatus(animeId, ListStatus.WATCHING)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Deliberately silent; see above.
        } finally {
            // Uncancellable, or a scope going away mid-create would leave the id behind and this
            // title could never be added again for the life of the process.
            withContext(NonCancellable) { guard.withLock { creating.remove(animeId) } }
        }
    }
}
