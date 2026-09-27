package app.kaeru.di

import app.kaeru.BuildConfig
import app.kaeru.data.auth.AccountSession
import app.kaeru.data.shikimori.SessionShikimoriApi
import app.kaeru.data.viewsync.DataStoreSyncState
import app.kaeru.data.viewsync.RelaySyncApi
import app.kaeru.data.viewsync.RoomLocalViewing
import app.kaeru.data.viewsync.SecretCards
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.connectivity.Connectivity
import app.kaeru.domain.settings.SettingsStore
import app.kaeru.domain.viewsync.ViewingSync
import app.kaeru.domain.viewsync.ViewingSyncEvents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.time.Clock
import javax.inject.Singleton

/**
 * Viewing sync: positions and dubs through the worker's `/sync`, while the viewer has it on.
 *
 * Put together here rather than by injection annotations on the service, because it is a domain
 * class — as `watchProgress` and `togetherSession` are.
 */
@Module
@InstallIn(SingletonComponent::class)
object ViewingSyncModule {

    /** One for the process: the repositories and the player talk into it, the service listens. */
    @Provides
    @Singleton
    fun viewingSyncEvents(): ViewingSyncEvents = ViewingSyncEvents()

    /** A title another device made «украдкой» is drawn from its card, fetched once it arrives. */
    @Provides
    fun secretCards(library: LibraryRepository): SecretCards = SecretCards { animeId -> library.refreshAnime(animeId) }

    @Provides
    @Singleton
    fun viewingSync(
        @PlainClient client: OkHttpClient,
        shikimori: SessionShikimoriApi,
        local: RoomLocalViewing,
        state: DataStoreSyncState,
        session: AccountSession,
        settings: SettingsStore,
        connectivity: Connectivity,
        events: ViewingSyncEvents,
        clock: Clock,
        @IoDispatcher io: CoroutineDispatcher,
    ): ViewingSync = ViewingSync(
        api = RelaySyncApi(BuildConfig.TOGETHER_RELAY_URL, client, shikimori, io),
        local = local,
        store = state,
        accounts = session.userId,
        enabled = settings.viewingSync,
        online = connectivity.online,
        events = events,
        scope = syncScope(),
        now = clock::millis,
    )

    /**
     * One coroutine at a time, which is what the service's fields rely on, and off the main thread.
     * Nothing it throws is allowed out: sync is never worth a crash.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun syncScope() = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + CoroutineExceptionHandler { _, _ -> },
    )
}
