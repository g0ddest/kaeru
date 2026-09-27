package app.kaeru.data.playback

import app.kaeru.data.auth.AccountSession
import app.kaeru.data.local.WatchStateDao
import app.kaeru.data.local.toEntity
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.repository.WatchStateRepository
import app.kaeru.domain.viewsync.ViewingSyncEvents
import app.kaeru.shared.domain.sync.RememberedDub
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playback positions in Room, one row per anime.
 *
 * The rows belong to the signed-in account — logging out drops them with the rest of the
 * account's cache — so writes go through [AccountSession], which serializes them against
 * account transitions and refreshes. Reads deliberately do not: a library refresh can hold
 * that lock for seconds, and the player must never wait on it to learn where to resume.
 */
@Singleton
class RoomWatchStateRepository @Inject constructor(
    private val dao: WatchStateDao,
    private val session: AccountSession,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    /** Told when a title's dub changes, for viewing sync to send on; it never waits on sync. */
    private val sync: ViewingSyncEvents = ViewingSyncEvents(),
) : WatchStateRepository {

    override fun observe(animeId: Int): Flow<WatchState?> = dao.observeByAnimeId(animeId)
        .map { it?.toDomain() }
        // Room invalidates per table: without this, every other anime's progress sample
        // would wake up whoever is watching this one.
        .distinctUntilChanged()

    // Like [observe], a read, so it does not wait on the account lock either. The whole table is
    // a few dozen tiny rows — one per anime ever started — and the ranking asks for it once per
    // request rather than once per comparison.
    override fun observeAll(): Flow<List<WatchState>> = dao.observeAll()
        .map { rows -> rows.map { it.toDomain() } }
        .distinctUntilChanged()

    /**
     * A changed track is a dub chosen — from the title screen, the player's voice list, or the first
     * resolve of a title — and viewing sync hears of it once it is written. The same track written
     * again, as every resolve does, is not news; nor is a track nobody has named yet, since the
     * other devices could not show it.
     */
    override suspend fun save(state: WatchState) {
        var previous: Int? = null
        accountWrite(session, io) {
            previous = dao.getByAnimeId(state.animeId)?.translationId
            dao.upsert(state.toEntity())
        }
        val id = state.translationId ?: return
        val title = state.translationTitle ?: return
        if (previous != id) sync.dubChosen(state.animeId, RememberedDub(id, title))
    }

    override suspend fun clear(animeId: Int) = accountWrite(session, io) { dao.deleteByAnimeId(animeId) }
}
