package app.kaeru.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.kaeru.BuildConfig
import app.kaeru.data.update.GITHUB_API_BASE_URL
import app.kaeru.data.update.GitHubReleasesApi
import app.kaeru.data.update.GitHubUpdateRepository
import app.kaeru.data.update.githubJson
import app.kaeru.domain.update.UpdatePolicy
import app.kaeru.domain.update.UpdateRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Named
import javax.inject.Singleton

/**
 * The «Обновления» screen and the check behind it.
 *
 * Its own preference file rather than a corner of `prefs`, for one reason: signing out wipes that
 * store, and what this remembers is about the device and the build on it rather than about
 * whoever is signed in. A viewer who signs out and back in should not have the app forget that it
 * checked this morning.
 */
@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {

    @Provides
    @Singleton
    @Named("updates")
    fun updatesDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { ctx.preferencesDataStoreFile("updates") }

    /** The version this build was compiled with — the one every comparison is made against. */
    @Provides
    @Named("versionName")
    fun versionName(): String = BuildConfig.VERSION_NAME

    @Provides
    @Singleton
    fun updatePolicy(): UpdatePolicy = UpdatePolicy()

    /**
     * On the plain client, which is the anonymous one: GitHub is asked without a token, so nothing
     * about Shikimori's rate limiter, its authenticator or its `Authorization` header belongs on
     * this call.
     */
    @Provides
    @Singleton
    fun gitHubReleasesApi(@PlainClient client: OkHttpClient): GitHubReleasesApi = Retrofit.Builder()
        .baseUrl(GITHUB_API_BASE_URL)
        .client(client)
        .addConverterFactory(githubJson().asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GitHubReleasesApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateBindings {
    @Binds
    abstract fun updateRepository(impl: GitHubUpdateRepository): UpdateRepository
}
