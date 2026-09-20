package app.kaeru.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.kaeru.BuildConfig
import app.kaeru.data.kodik.DataStoreKodikTokenCache
import app.kaeru.data.kodik.KodikSourceProvider
import app.kaeru.data.kodik.KodikTokenKeys
import app.kaeru.domain.source.EpisodeSourceProvider
import app.kaeru.shared.data.kodik.KodikClient
import app.kaeru.shared.data.network.HttpTransport
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.Clock
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object KodikModule {
    /**
     * The shared Kodik chain, with the two things only this app knows: where a key the viewer
     * typed in lives, and where the scraped token survives a restart.
     *
     * The key is read on every request rather than at construction, so a token saved in the
     * settings takes effect on the next resolve, not on the next launch. A settings key beats the
     * one baked in at build time; both beat the public token scraped off Kodik's embed script,
     * which is what an install with neither runs on.
     */
    @Provides
    @Singleton
    fun kodikClient(
        transport: HttpTransport,
        @Named("prefs") dataStore: DataStore<Preferences>,
        clock: Clock,
    ): KodikClient = KodikClient(
        transport,
        tokenCache = DataStoreKodikTokenCache(dataStore),
        configuredToken = {
            dataStore.data.first()[KodikTokenKeys.override]?.trim()?.takeIf { it.isNotEmpty() }
                ?: BuildConfig.KODIK_TOKEN
        },
        nowMillis = { clock.millis() },
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class KodikBindings {
    @Binds
    @Singleton
    abstract fun episodeSource(impl: KodikSourceProvider): EpisodeSourceProvider
}
