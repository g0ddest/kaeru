package app.kaeru.di

import app.kaeru.data.skip.ANISKIP_BASE_URL
import app.kaeru.data.skip.AniSkipApi
import app.kaeru.data.skip.AniSkipMarks
import app.kaeru.data.skip.aniSkipJson
import app.kaeru.domain.playback.SkipMarksSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Duration
import javax.inject.Singleton

/** Long enough for a call that decides whether a button appears, and short enough to forget. */
private val SKIP_TIMEOUT = Duration.ofSeconds(5)

/**
 * The marks, and the one short call that fetches them.
 *
 * On the plain client — this is an anonymous public service and wants nothing of Shikimori's —
 * with a call timeout of its own. The rest of the app waits minutes for a stream if it has to;
 * an episode must never wait even five seconds on the question of whether it can offer a button,
 * which is why the whole of it is asked off the playing thread and its failure is dropped.
 */
@Module
@InstallIn(SingletonComponent::class)
object SkipModule {

    @Provides
    @Singleton
    fun aniSkipApi(@PlainClient client: OkHttpClient): AniSkipApi = Retrofit.Builder()
        .baseUrl(ANISKIP_BASE_URL)
        .client(client.newBuilder().callTimeout(SKIP_TIMEOUT).build())
        .addConverterFactory(aniSkipJson().asConverterFactory("application/json".toMediaType()))
        .build()
        .create(AniSkipApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SkipBindings {
    @Binds
    abstract fun skipMarks(impl: AniSkipMarks): SkipMarksSource
}
