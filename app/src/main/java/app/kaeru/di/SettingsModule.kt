package app.kaeru.di

import app.kaeru.data.auth.PreferencesAccountRepository
import app.kaeru.data.library.AppPreferences
import app.kaeru.domain.repository.AccountRepository
import app.kaeru.domain.settings.SettingsStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * What the settings screen depends on.
 *
 * The store is the same `AppPreferences` the player reads through `PlaybackPreferences`, bound a
 * second time under the interface that can also write: one store, two views of it, and the player
 * still cannot change a setting it was only meant to obey.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    // Unscoped on purpose: both implementations are already @Singleton, so these hand out the
    // one instance each.
    @Binds
    abstract fun settingsStore(impl: AppPreferences): SettingsStore

    @Binds
    abstract fun accountRepository(impl: PreferencesAccountRepository): AccountRepository
}
