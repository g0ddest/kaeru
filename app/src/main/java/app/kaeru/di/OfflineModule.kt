package app.kaeru.di

import app.kaeru.data.connectivity.AndroidConnectivity
import app.kaeru.data.library.RoomRateOutboxRepository
import app.kaeru.data.library.ShikimoriOutboxSyncer
import app.kaeru.domain.connectivity.Connectivity
import app.kaeru.domain.sync.OutboxSyncer
import app.kaeru.domain.sync.RateOutboxRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** What the app needs to keep working without a network: the queue of writes waiting for one. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineModule {
    @Binds
    abstract fun connectivity(impl: AndroidConnectivity): Connectivity

    @Binds
    abstract fun rateOutboxRepository(impl: RoomRateOutboxRepository): RateOutboxRepository

    @Binds
    abstract fun outboxSyncer(impl: ShikimoriOutboxSyncer): OutboxSyncer
}
