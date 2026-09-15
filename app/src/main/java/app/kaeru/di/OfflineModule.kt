package app.kaeru.di

import app.kaeru.data.connectivity.AndroidConnectivity
import app.kaeru.domain.connectivity.Connectivity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** What the app needs to keep working without a network. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineModule {
    @Binds
    abstract fun connectivity(impl: AndroidConnectivity): Connectivity
}
