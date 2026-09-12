package app.kaeru.di

import app.kaeru.BuildConfig
import app.kaeru.data.kodik.DefaultKodikTokenProvider
import app.kaeru.data.kodik.KodikApi
import app.kaeru.data.kodik.KodikConstants
import app.kaeru.data.kodik.KodikSourceProvider
import app.kaeru.data.kodik.KodikTokenProvider
import app.kaeru.data.kodik.kodikJson
import app.kaeru.data.shikimori.UserAgentInterceptor
import app.kaeru.domain.source.EpisodeSourceProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton

/** OkHttp client for `kodikplayer.com`, which serves its pages only to something that looks like a desktop browser. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class KodikPlayerClient

@Module
@InstallIn(SingletonComponent::class)
object KodikModule {

    /** Empty unless a private Kodik key was put in `local.properties`; the public token covers the empty case. */
    @Provides
    @Named("kodikConfiguredToken")
    fun configuredToken(): String = BuildConfig.KODIK_TOKEN

    @Provides
    @Named("kodikAddPlayersUrl")
    fun addPlayersUrl(): String = KodikConstants.ADD_PLAYERS_URL

    @Provides
    @Named("kodikPlayerHost")
    fun playerHost(): String = KodikConstants.PLAYER_HOST

    /**
     * Derived from the plain client so it keeps its connection pool and logging.
     * The browser `User-Agent` is forced here for every call, including redirects;
     * `Referer` is per-request, because Kodik wants the page the call came from.
     */
    @Provides
    @Singleton
    @KodikPlayerClient
    fun playerClient(@PlainClient plain: OkHttpClient): OkHttpClient = plain.newBuilder()
        .addInterceptor(UserAgentInterceptor(KodikConstants.BROWSER_UA))
        .build()

    /** The API host is a plain JSON endpoint and does not care who is calling. */
    @Provides
    @Singleton
    fun kodikApi(@PlainClient client: OkHttpClient): KodikApi = Retrofit.Builder()
        .baseUrl(KodikConstants.API_URL)
        .client(client)
        .addConverterFactory(kodikJson().asConverterFactory("application/json".toMediaType()))
        .build()
        .create(KodikApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class KodikBindings {
    @Binds
    @Singleton
    abstract fun tokenProvider(impl: DefaultKodikTokenProvider): KodikTokenProvider

    @Binds
    @Singleton
    abstract fun episodeSource(impl: KodikSourceProvider): EpisodeSourceProvider
}
