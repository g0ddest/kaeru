package app.kaeru.domain.playback

import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Idempotent, and serialized so that it stays idempotent while a create is in flight: re-opening
 * the episode for a retry or a change of voice takes seconds, a create takes a round trip, and two
 * creates are a duplicate rate on Shikimori that nothing here would ever clean up.
 */
class AddStartedTitleToList(private val library: LibraryRepository) {
    private val creating = Mutex()

    /**
     * Never throws and never reports: playback is what the viewer asked for, and a list that could
     * not be written is worth nothing beside it. The next start tries again.
     */
    suspend operator fun invoke(animeId: Int) {
        try {
            creating.withLock {
                if (library.observeAnime(animeId).first() != null) return
                library.setStatus(animeId, ListStatus.WATCHING)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Deliberately silent; see above.
        }
    }
}
